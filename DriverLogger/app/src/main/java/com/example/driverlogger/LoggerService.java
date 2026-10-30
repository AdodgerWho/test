package com.example.driverlogger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Logs the accessibility tree of the watched apps (including overlay windows) plus screenshots.
 *
 * v2.1: screenshots are taken continuously into a short-lived "pending" folder and are only kept
 * permanently when something interesting happens (offer-like text, a notification, a change of
 * screen state, a new window, or a big visual change while a watched app is present). When that
 * happens the previous PRE_ROLL_MS of shots are kept too, and new shots are kept for POST_ROLL_MS.
 * Near-identical shots are never stored. Everything else is deleted automatically.
 */
public class LoggerService extends AccessibilityService {
    static final int DEBOUNCE_MS = 400;
    static final int TIMED_MS = 1200;
    static final int MIN_SHOT_GAP_MS = 1100;
    static final int SHOT_WIDTH = 720;
    static final int JPEG_QUALITY = 55;
    static final int MAX_NODES = 1500;

    static final long PRE_ROLL_MS = 8000;
    static final long POST_ROLL_MS = 10000;
    static final double DUPLICATE_DIFF = 2.0;
    static final double BIG_CHANGE_DIFF = 22.0;
    static final long VISUAL_COOLDOWN_MS = 8000;

    static volatile boolean running = false;

    /** Matches Accept buttons such as text="Accept" or text="Accept • $20.01". */
    private static final Pattern ACCEPT = Pattern.compile("(text|desc)=\"Accept( \u2022[^\"]*)?\"");
    private static final String[] MARKERS = {
            "text=\"Decline\"", "Reject ride", "est. rate for this ride", "eservation request",
            "request matching", "Matched to trip", "xclusive", "Scheduled ride",
            "Picking up", "Waiting for rider", "Dropping off", "Finding trips",
            "Waiting for requests", "You're offline", "You're online", "You're Online",
            "GO OFFLINE", "Go Offline",
            "desc=\"ACCEPT\"", "Schedule Bookings", "SUGGESTED SCHEDULED RIDE"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static final String UBER = "com.ubercab.driver";
    static final String LYFT = "com.lyft.android.driver";
    static final String HICH = "us.mi.hich.driver";
    private static final long HICH_TAP_GAP_MS = 8000;
    private static final long HICH_REPEAT_MS = 600000;
    private static final long HICH_ONLINE_SETTLE_MS = 1500;
    private static final long HICH_ONLINE_REPEAT_MS = 600000;
    private static final Pattern OFFLINE = Pattern.compile("You.re offline", Pattern.CASE_INSENSITIVE);
    private static final long PRIORITY_GAP_MS = 6000;
    private static final long PRIORITY_NOTE_GAP_MS = 30000;
    private static final long OFFER_REPEAT_MS = 90000;
    private static final long OCR_GAP_MS = 1500;

    private Flasher flasher;
    private TextRecognizer recognizer;
    private final Map<String, Long> offerSeen = new HashMap<>();
    private Rect blankUber = null;
    private long lastOcr = 0;
    private long flashUntil = 0;
    private long lastPriorityTap = 0;
    private long lastHichTap = 0;
    private long hichOfflineSince = 0;
    private long lastHichOnlineTap = 0;
    private int lastHichCount = 0;
    private long lastPriorityNote = 0;
    private volatile boolean ocrBusy = false;

    // main thread only
    private boolean scheduled = false;
    private String lastBody = "";
    private long lastShot = 0;
    private List<String> watched = null;
    private long watchedLoadedAt = 0;
    private String curWinSig = "";
    private String prevWinSig = null;
    private int lastState = -1;
    private volatile boolean watchedPresent = false;

    // io thread only
    private int writes = 0;
    private int[] lastSig = null;
    private int[] prevSig = null;
    private long keepUntil = 0;
    private long lastVisualTrigger = 0;
    private long lastKeepLog = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isInteractive()) capture("", false, true);
            } catch (Throwable t) {
                logLine("tick error: " + t);
            }
            handler.postDelayed(this, TIMED_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
        running = true;
        flasher = new Flasher(this);
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Throwable t) {
            recognizer = null;
            logLine("OCR unavailable: " + t);
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                Store.clearPending(LoggerService.this);
            }
        });
        logLine("SERVICE CONNECTED, watching " + Store.getApps(this)
                + ", mode " + (Store.getMode(this) == Store.MODE_ALERT ? "ALERT" : "LOG")
                + ", rolling screenshot buffer " + (Store.isAlways(this) ? "ALWAYS" : "only with watched app visible"));
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TIMED_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        if (flasher != null) flasher.remove();
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (flasher != null) flasher.remove();
        if (recognizer != null) recognizer.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int type = e.getEventType();
        CharSequence p = e.getPackageName();
        String pkg = p == null ? "" : p.toString();

        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (isWatched(pkg)) {
                final String note = "notification from " + pkg + ": " + e.getText();
                logLine(note);
                capture(note, true, false);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        capture("follow-up to " + note, true, false);
                    }
                }, 1500);
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || isWatched(pkg)) {
            scheduleCapture();
        }
    }

    private boolean isWatched(String pkg) {
        long n = SystemClock.elapsedRealtime();
        if (watched == null || n - watchedLoadedAt > 3000) {
            watched = Store.getApps(this);
            watchedLoadedAt = n;
        }
        return watched.contains(pkg);
    }

    private void scheduleCapture() {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                scheduled = false;
                capture("", false, false);
            }
        }, DEBOUNCE_MS);
    }

    /**
     * @param note  text written to the note= line (notifications etc.)
     * @param force always write the text file and keep the screenshot
     * @param timed true when triggered by the timer rather than an event
     */
    private void capture(String note, boolean force, boolean timed) {
        try {
            long now = System.currentTimeMillis();
            final String base = "cap_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now));

            StringBuilder body = new StringBuilder();
            boolean any = dumpWindows(body);
            watchedPresent = any;
            String b = body.toString();
            if (any) {
                scanOffers(b);
                tryPriorityMode(b);
                tryHichScheduled(b);
                tryHichOnline(b);
                if (blankUber != null) {
                    trigger("uber offer card with no readable text");
                    ocrUber(new Rect(blankUber));
                }
            }
            boolean changed = !b.equals(lastBody);

            // ---- decide whether this moment is worth keeping screenshots around ----
            if (force) trigger("notification");
            if (any) {
                if (prevWinSig != null && !curWinSig.equals(prevWinSig)) {
                    trigger("window set changed: " + curWinSig.trim());
                }
                prevWinSig = curWinSig;
                int st = stateOf(b);
                if (lastState != -1 && st != lastState) trigger("screen state changed");
                lastState = st;
            }

            if (force || (any && changed)) {
                lastBody = b;
                final String text = "time="
                        + new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(new Date(now))
                        + "\nnote=" + note + "\n\n" + b;
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        writeFile(base + ".txt", text.getBytes(StandardCharsets.UTF_8));
                    }
                });
            }

            boolean shot = force
                    || (timed && (Store.isAlways(this) || any))
                    || (!timed && any && changed);
            if (shot) shoot(base, force);
        } catch (Throwable t) {
            logLine("capture error: " + t);
        }
    }

    /** Bitmask of which offer / ride-flow markers are currently on screen. */
    private static int stateOf(String body) {
        int st = ACCEPT.matcher(body).find() ? 1 : 0;
        for (int i = 0; i < MARKERS.length; i++) {
            if (body.contains(MARKERS[i])) st |= 1 << (i + 1);
        }
        return st;
    }

    private boolean dumpWindows(StringBuilder sb) {
        boolean any = false;
        StringBuilder others = new StringBuilder();
        StringBuilder wsig = new StringBuilder();
        int noroot = 0;
        blankUber = null;

        List<AccessibilityWindowInfo> wins = null;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            logLine("getWindows error: " + t);
        }

        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Throwable ignored) {
                }
                if (root == null) {
                    noroot++;
                    others.append("noroot:L").append(w.getLayer())
                            .append(":t").append(w.getType()).append("  ");
                    continue;
                }
                CharSequence cs = root.getPackageName();
                String pkg = cs == null ? "?" : cs.toString();
                if (isWatched(pkg)) {
                    any = true;
                    sb.append("=== WINDOW ").append(pkg)
                            .append(" layer=").append(w.getLayer()).append(" ===\n");
                    int[] count = new int[]{0};
                    StringBuilder seg = new StringBuilder();
                    dumpNode(root, 0, seg, count);
                    sb.append(seg);
                    if (pkg.equals(UBER) && w.getLayer() >= 1 && count[0] > 1
                            && seg.indexOf("text=\"") < 0 && seg.indexOf("desc=\"") < 0
                            && seg.indexOf("RecyclerView") >= 0) {
                        Rect rb = new Rect();
                        root.getBoundsInScreen(rb);
                        if (rb.height() > 400) blankUber = rb;
                    }
                    // tiny windows (the floating bubble) don't count as a real window
                    if (count[0] > 3) wsig.append(pkg).append(":L").append(w.getLayer()).append(' ');
                } else {
                    others.append(pkg).append(":L").append(w.getLayer())
                            .append(":t").append(w.getType()).append("  ");
                }
            }
        }

        if (!any) {
            try {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) {
                    CharSequence cs = r.getPackageName();
                    String pkg = cs == null ? "?" : cs.toString();
                    if (isWatched(pkg)) {
                        any = true;
                        sb.append("=== WINDOW ").append(pkg).append(" layer=-1 ===\n");
                        int[] count = new int[]{0};
                        dumpNode(r, 0, sb, count);
                        if (count[0] > 3) wsig.append(pkg).append(":L-1 ");
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (noroot > 0) wsig.append("noroot=").append(noroot);
        curWinSig = wsig.toString();

        if (!any) sb.append("(no watched app window was visible)\n");
        if (others.length() > 0) sb.append("others=").append(others.toString().trim()).append('\n');
        return any;
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] >= MAX_NODES) return;
        count[0]++;
        for (int i = 0; i < depth; i++) sb.append("  ");

        CharSequence cn = n.getClassName();
        String cls = cn == null ? "?" : cn.toString();
        int dot = cls.lastIndexOf('.');
        if (dot >= 0) cls = cls.substring(dot + 1);
        sb.append(cls);

        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" id=").append(id);
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(" desc=\"").append(oneLine(d)).append('"');
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(" text=\"").append(oneLine(t)).append('"');

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(" bounds=").append(r.left).append(',').append(r.top)
                .append(',').append(r.right).append(',').append(r.bottom);

        if (n.isClickable()) sb.append(" CLICKABLE");
        if (!n.isEnabled()) sb.append(" disabled");
        if (!n.isVisibleToUser()) sb.append(" hidden");
        if (!n.isImportantForAccessibility()) sb.append(" notimportant");
        sb.append('\n');

        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) dumpNode(ch, depth + 1, sb, count);
        }
    }

    private static String oneLine(CharSequence s) {
        return s.toString().replace('\n', ' ').replace('\r', ' ');
    }

    // ---------------------------------------------------------------- v3: offers

    /** Offers whose text is readable in the accessibility tree (Lyft, hich, Uber reservations). */
    private void scanOffers(String body) {
        if (!body.contains("est. rate for this ride") && !body.contains("desc=\"ACCEPT\"")
                && !body.contains("Accept \u2022")) return;
        List<Offers.Offer> list = Offers.fromTree(body, Store.getMinRate(this),
                Store.getHichMiles(this), Store.getHichScheduled(this));
        for (Offers.Offer o : list) handleOffer(o);
    }

    /** Uber live offers have no readable text, so read the offer card from a screenshot instead. */
    private void ocrUber(final Rect bounds) {
        long now = SystemClock.elapsedRealtime();
        if (recognizer == null || ocrBusy || now - lastOcr < OCR_GAP_MS || now < flashUntil) return;
        ocrBusy = true;
        lastOcr = now;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, io,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                Bitmap crop = cropShot(result, bounds);
                                if (crop == null) {
                                    ocrBusy = false;
                                    return;
                                }
                                readCrop(crop);
                            } catch (Throwable t) {
                                ocrBusy = false;
                                logLine("ocr error: " + t);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            ocrBusy = false;
                            logLine("ocr screenshot failed, code " + errorCode);
                        }
                    });
        } catch (Throwable t) {
            ocrBusy = false;
            logLine("ocr error: " + t);
        }
    }

    private Bitmap cropShot(AccessibilityService.ScreenshotResult result, Rect bounds) {
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        if (hw == null) {
            hb.close();
            return null;
        }
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();
        if (sw == null) return null;
        int top = Math.max(0, bounds.top - 80);
        int bottom = Math.min(sw.getHeight(), bounds.bottom + 40);
        if (bottom - top < 200) {
            sw.recycle();
            return null;
        }
        Bitmap crop = Bitmap.createBitmap(sw, 0, top, sw.getWidth(), bottom - top);
        if (crop != sw) sw.recycle();
        return crop;
    }

    private void readCrop(final Bitmap crop) {
        recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(text -> {
                    crop.recycle();
                    ocrBusy = false;
                    handleOffer(Offers.fromUberOcr(text.getText(), Store.getMinRate(LoggerService.this)));
                })
                .addOnFailureListener(e -> {
                    crop.recycle();
                    ocrBusy = false;
                    logLine("ocr failed: " + e);
                });
    }

    /** Main thread: log an offer once, and in Alert mode pulse the screen when it is good. */
    private void handleOffer(Offers.Offer o) {
        long now = SystemClock.elapsedRealtime();
        String key = o.key();
        Long last = offerSeen.get(key);
        if (last != null && now - last < OFFER_REPEAT_MS) return;
        offerSeen.put(key, now);
        if (offerSeen.size() > 200) offerSeen.clear();

        final int mode = Store.getMode(this);
        String label = labelFor(o);
        logLine("OFFER " + label + " " + o.kind + " " + o.verdict + " (" + o.source + ") " + o.detail);

        try {
            final JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis());
            j.put("app", o.app);
            j.put("kind", o.kind);
            j.put("price", o.price);
            j.put("rate", o.rate);
            j.put("verdict", o.verdict);
            j.put("detail", o.detail);
            j.put("source", o.source);
            j.put("raw", o.raw);
            j.put("mode", mode);
            io.execute(new Runnable() {
                @Override
                public void run() {
                    Store.addOffer(LoggerService.this, j);
                }
            });
        } catch (Throwable t) {
            logLine("offer save error: " + t);
        }

        if (mode == Store.MODE_ALERT && "GOOD".equals(o.verdict) && flasher != null) {
            flashUntil = now + Flasher.TOTAL_MS + 1500;
            flasher.flash(label, Color.rgb(0, 200, 83), Store.getOpacity(this),
                    Store.getVibrate(this), Store.getSound(this));
        }
    }

    static String labelFor(Offers.Offer o) {
        String app = o.app.equals("uber") ? "Uber" : o.app.equals("lyft") ? "Lyft" : "hich";
        StringBuilder sb = new StringBuilder(app);
        if (o.price > 0) sb.append(" $").append(String.format(Locale.US, "%.2f", o.price));
        if (o.rate >= 0) sb.append(" \u00b7 $").append(Math.round(o.rate)).append("/hr");
        return sb.toString();
    }

    // ---------------------------------------------------------------- v3.1: Lyft Priority Mode

    /**
     * Lyft shows a "Turn on" pill next to "Priority Mode" only while Priority Mode is available and
     * off. Once it is on, the pill becomes the plain, untappable text "On". So the rule is: find a
     * visible, enabled node whose text is exactly "Turn on" inside the Priority Mode row of the Lyft
     * driver app, and click it. Nothing else is ever tapped. Never while an offer card is showing.
     */
    private void tryPriorityMode(String body) {
        if (!Store.getAutoPriority(this)) return;
        if (!body.contains("Turn on")) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPriorityTap < PRIORITY_GAP_MS) return;
        if (ACCEPT.matcher(body).find() || body.contains("est. rate for this ride")
                || body.contains("Reject ride")) return;

        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            return;
        }
        if (wins == null) return;

        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Throwable ignored) {
            }
            if (root == null) continue;
            CharSequence cs = root.getPackageName();
            if (cs == null || !LYFT.equals(cs.toString())) continue;

            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Turn on");
            if (hits == null) continue;
            for (AccessibilityNodeInfo n : hits) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                boolean exact = (t != null && "Turn on".equals(t.toString().trim()))
                        || (d != null && "Turn on".equals(d.toString().trim()));
                if (!exact) continue;
                if (!n.isEnabled() || !n.isVisibleToUser()) continue;
                if (!inPriorityRow(n)) {
                    priorityNote(now, "found \"Turn on\" but not next to Priority Mode, left alone");
                    continue;
                }
                AccessibilityNodeInfo target = clickableOf(n);
                if (target == null) {
                    priorityNote(now, "found the Priority Mode pill but nothing clickable around it");
                    continue;
                }
                lastPriorityTap = now;
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                logLine("PRIORITY MODE: tapped \"Turn on\", click result=" + ok);
                trigger("priority mode tap");
                return;
            }
        }
    }

    /** True when "Priority Mode" text sits in the same small group as the button (up to 3 levels up). */
    private static boolean inPriorityRow(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; i < 3 && p != null; i++) {
            List<AccessibilityNodeInfo> l = p.findAccessibilityNodeInfosByText("Priority Mode");
            if (l != null && !l.isEmpty()) return true;
            p = p.getParent();
        }
        return false;
    }

    /** The node itself or the nearest clickable parent (up to 4 levels), or null. */
    private static AccessibilityNodeInfo clickableOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 5 && c != null; i++) {
            if (c.isClickable() && c.isEnabled()) return c;
            c = c.getParent();
        }
        return null;
    }

    private void priorityNote(long now, String msg) {
        if (now - lastPriorityNote < PRIORITY_NOTE_GAP_MS) return;
        lastPriorityNote = now;
        logLine("PRIORITY MODE: " + msg);
    }

    // ---------------------------------------------------------------- v3.2: hich scheduled rides

    /**
     * On the hich home screen a car-and-clock icon at the top right carries a red badge with the
     * number of waiting scheduled rides (0 when none). When that badge shows 1 or more, tap the
     * icon once so the Schedule Bookings screen (OFFERS tab) opens. The badge is found by its
     * number and position, so no button id is needed. It is not tapped again for the same number
     * within 10 minutes, so backing out of the screen does not reopen it. Never while a live offer
     * card is on screen.
     */
    private void tryHichScheduled(String body) {
        if (!Store.getAutoSched(this)) return;
        if (!body.contains(HICH)) return;
        if (body.contains("Schedule Bookings")) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHichTap < HICH_TAP_GAP_MS) return;
        if (ACCEPT.matcher(body).find() || body.contains("desc=\"ACCEPT\"")) return;

        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            return;
        }
        if (wins == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Throwable ignored) {
            }
            if (root == null) continue;
            CharSequence cs = root.getPackageName();
            if (cs == null || !HICH.equals(cs.toString())) continue;

            List<AccessibilityNodeInfo> badges = new ArrayList<>();
            collectBadges(root, dm.widthPixels, dm.heightPixels, badges, new int[]{0});
            for (AccessibilityNodeInfo n : badges) {
                int count;
                try {
                    count = Integer.parseInt(n.getText().toString().trim());
                } catch (Throwable t) {
                    continue;
                }
                if (count == lastHichCount && now - lastHichTap < HICH_REPEAT_MS) continue;
                AccessibilityNodeInfo target = clickableOf(n);
                if (target == null) {
                    priorityNote(now, "hich badge " + count + " found but nothing clickable around it");
                    continue;
                }
                lastHichTap = now;
                lastHichCount = count;
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                logLine("HICH SCHEDULED: badge shows " + count + ", tapped the icon, click result=" + ok);
                trigger("hich scheduled badge tap");
                return;
            }
        }
    }

    /** Collects visible 1 or 2 digit numbers in the top-right of the screen (the badge). */
    private static void collectBadges(AccessibilityNodeInfo n, int w, int h,
                                      List<AccessibilityNodeInfo> out, int[] budget) {
        if (n == null || budget[0]++ > 800) return;
        CharSequence t = n.getText();
        if (t != null && n.isVisibleToUser()) {
            String s = t.toString().trim();
            if (s.matches("[1-9][0-9]?")) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.centerY() < h * 0.12 && r.centerX() > w * 0.70) out.add(n);
            }
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) collectBadges(n.getChild(i), w, h, out, budget);
    }

    // ---------------------------------------------------------------- v3.3: hich go online

    /**
     * Scheduled rides can only be accepted while hich is online. When the hich home screen is open,
     * says "You're offline" and shows its big "GO" button, tap GO once. Only an enabled button whose
     * text is exactly "GO" is tapped. The offline screen has to stay put for 1.5 seconds first (so a
     * screen that is still loading is left alone), and it will not tap again for 10 minutes, so
     * going offline on purpose right afterwards sticks. Never while a live offer card is showing.
     */
    private void tryHichOnline(String body) {
        if (!Store.getAutoOnline(this) || !body.contains(HICH) || !OFFLINE.matcher(body).find()
                || ACCEPT.matcher(body).find() || body.contains("desc=\"ACCEPT\"")) {
            hichOfflineSince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (hichOfflineSince == 0) {
            hichOfflineSince = now;
            return;
        }
        if (now - hichOfflineSince < HICH_ONLINE_SETTLE_MS) return;
        if (lastHichOnlineTap != 0 && now - lastHichOnlineTap < HICH_ONLINE_REPEAT_MS) return;

        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            return;
        }
        if (wins == null) return;

        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Throwable ignored) {
            }
            if (root == null) continue;
            CharSequence cs = root.getPackageName();
            if (cs == null || !HICH.equals(cs.toString())) continue;

            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("GO");
            if (hits == null) continue;
            for (AccessibilityNodeInfo n : hits) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                boolean exact = (t != null && "GO".equals(t.toString().trim()))
                        || (d != null && "GO".equals(d.toString().trim()));
                if (!exact || !n.isEnabled() || !n.isVisibleToUser()) continue;
                AccessibilityNodeInfo target = clickableOf(n);
                if (target == null) {
                    priorityNote(now, "hich GO button found but nothing clickable around it");
                    continue;
                }
                lastHichOnlineTap = now;
                hichOfflineSince = 0;
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                logLine("HICH ONLINE: tapped GO, click result=" + ok);
                trigger("hich go online tap");
                return;
            }
        }
    }

    // ---------------------------------------------------------------- keep / discard logic

    /** Called from the main thread: marks "now" as interesting. */
    private void trigger(final String why) {
        final long t = System.currentTimeMillis();
        io.execute(new Runnable() {
            @Override
            public void run() {
                promote(t, why);
            }
        });
    }

    /** io thread: keep the recent past, and keep new shots for a while. */
    private void promote(long t, String why) {
        keepUntil = Math.max(keepUntil, t + POST_ROLL_MS);
        int moved = 0;
        File[] files = Store.pendingDir(this).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.lastModified() >= t - PRE_ROLL_MS) {
                    if (f.renameTo(new File(Store.dir(this), f.getName()))) moved++;
                }
            }
        }
        if (t - lastKeepLog > 2000) {
            lastKeepLog = t;
            logLine("KEEP (" + why + "): " + moved + " earlier shots kept");
        }
    }

    /** io thread: throw away pending shots that are too old to matter. */
    private void prunePending() {
        File[] files = Store.pendingDir(this).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - PRE_ROLL_MS - 10000;
        for (File f : files) {
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    // ---------------------------------------------------------------- screenshots

    private void shoot(final String base, final boolean force) {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastShot < MIN_SHOT_GAP_MS) return;
        lastShot = nowMs;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, io,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                saveShot(result, base, force);
                            } catch (Throwable t) {
                                logLine("screenshot save error: " + t);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            logLine("screenshot failed, code " + errorCode);
                        }
                    });
        } catch (Throwable t) {
            logLine("screenshot error: " + t.getMessage());
        }
    }

    // runs on the io thread
    private void saveShot(AccessibilityService.ScreenshotResult result, String base, boolean force) throws Exception {
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        if (hw == null) {
            hb.close();
            logLine("screenshot: could not read the screen buffer");
            return;
        }
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();
        if (sw == null) return;

        int h = Math.max(1, Math.round(sw.getHeight() * (SHOT_WIDTH / (float) sw.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(sw, SHOT_WIDTH, h, true);
        if (scaled != sw) sw.recycle();

        int[] sig = signature(scaled);
        long sum = 0;
        for (int v : sig) sum += v;
        if (sum / (double) sig.length < 3.0) {
            logLine("screenshot looks BLANK (secure screen?) at " + base);
        }

        long now = System.currentTimeMillis();

        // 1. duplicates are never stored
        boolean same = lastSig != null && meanDiff(sig, lastSig) < DUPLICATE_DIFF;
        if (same && !force) {
            prevSig = sig;
            scaled.recycle();
            return;
        }

        // 2. a big change on screen while a watched app is present counts as "something happened"
        if (prevSig != null && watchedPresent
                && meanDiff(sig, prevSig) >= BIG_CHANGE_DIFF
                && now - lastVisualTrigger > VISUAL_COOLDOWN_MS) {
            lastVisualTrigger = now;
            promote(now, "big screen change");
        }
        prevSig = sig;
        lastSig = sig;

        // 3. keep now, or park in the pending folder where it expires unless something happens
        boolean keepNow = force || now < keepUntil;
        File out = new File(keepNow ? Store.dir(this) : Store.pendingDir(this), base + ".jpg");
        FileOutputStream os = new FileOutputStream(out);
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os);
        } finally {
            os.close();
        }
        scaled.recycle();

        if (!keepNow) prunePending();
        if (++writes % 100 == 0) Store.prune(this);
    }

    private static int[] signature(Bitmap b) {
        final int w = 24, h = 48;
        int[] s = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = b.getPixel(x * b.getWidth() / w, y * b.getHeight() / h);
                int r = (px >> 16) & 0xff, g = (px >> 8) & 0xff, bl = px & 0xff;
                s[y * w + x] = (r * 3 + g * 6 + bl) / 10;
            }
        }
        return s;
    }

    private static double meanDiff(int[] a, int[] b) {
        long d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / (double) a.length;
    }

    // ---------------------------------------------------------------- files

    // runs on the io thread
    private void writeFile(String name, byte[] data) {
        try {
            FileOutputStream os = new FileOutputStream(new File(Store.dir(this), name));
            try {
                os.write(data);
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            logLine("write error: " + t);
        }
        if (++writes % 100 == 0) Store.prune(this);
    }

    void logLine(String msg) {
        final String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + msg + "\n";
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getFilesDir(), "events.log");
                    if (f.length() > 1000000) f.delete();
                    FileOutputStream os = new FileOutputStream(f, true);
                    try {
                        os.write(line.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        os.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
