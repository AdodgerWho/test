package com.example.driverlogger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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

    static final long PRE_ROLL_MS = 10000;
    static final long POST_ROLL_MS = 15000;
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
            "GO OFFLINE", "Go Offline"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

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
        io.execute(new Runnable() {
            @Override
            public void run() {
                Store.clearPending(LoggerService.this);
            }
        });
        logLine("SERVICE CONNECTED, watching " + Store.getApps(this)
                + ", rolling screenshot buffer " + (Store.isAlways(this) ? "ALWAYS" : "only with watched app visible"));
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TIMED_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
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
                    dumpNode(root, 0, sb, count);
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
