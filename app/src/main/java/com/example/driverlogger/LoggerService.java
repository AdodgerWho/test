package com.example.driverlogger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Observe-only logger. Records the text/buttons on screen (plus a screenshot)
 * whenever a watched driver app shows a new screen or posts a notification.
 * It never taps, swipes or changes anything.
 */
public class LoggerService extends AccessibilityService {

    private static final long DEBOUNCE_MS = 400;
    private static final long MIN_SHOT_GAP_MS = 1100;
    private static final int MAX_NODES = 1500;
    private static final int MAX_CAPTURES = 1500;
    private static final int SHOT_WIDTH = 720;

    private static class Seen {
        long last;
        int count;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor io = Executors.newSingleThreadExecutor();
    private final Map<String, Seen> seen = new HashMap<>();
    private Set<String> targets;
    private long targetsLoaded = 0;
    private long lastShot = 0;
    private boolean scheduled = false;
    private int captureCount = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        captureCount = (int) Store.stats(this)[0];
        logLine("SERVICE CONNECTED, watching " + targets());
    }

    @Override
    public void onInterrupt() {
    }

    private Set<String> targets() {
        long now = System.currentTimeMillis();
        if (targets == null || now - targetsLoaded > 5000) {
            targets = Store.targets(this);
            targetsLoaded = now;
        }
        return targets;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        final String pkg = event.getPackageName().toString();
        if (!targets().contains(pkg)) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            final String note = "notification from " + pkg + ": " + event.getText();
            logLine("NOTIFICATION " + note);
            capture(note, true);
            handler.postDelayed(() -> capture("follow-up to " + note, true), 1500);
            return;
        }

        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(() -> {
                scheduled = false;
                capture("", false);
            }, DEBOUNCE_MS);
        }
    }

    private void capture(String note, boolean force) {
        final long now = System.currentTimeMillis();
        Set<String> t = targets();
        StringBuilder tree = new StringBuilder();
        StringBuilder sig = new StringBuilder();
        boolean found = false;

        List<AccessibilityWindowInfo> wins = getWindows();
        if (wins != null && !wins.isEmpty()) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                String p = root.getPackageName().toString();
                if (!t.contains(p)) continue;
                found = true;
                tree.append("=== WINDOW ").append(p).append(" layer=").append(w.getLayer()).append(" ===\n");
                dump(root, 0, tree, sig, new int[]{0});
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && t.contains(root.getPackageName().toString())) {
                found = true;
                tree.append("=== WINDOW ").append(root.getPackageName()).append(" ===\n");
                dump(root, 0, tree, sig, new int[]{0});
            }
        }

        if (!force) {
            if (!found) return;
            if (seen.size() > 5000) seen.clear();
            // Digits are ignored so countdown timers do not look like new screens.
            String key = Integer.toString(sig.toString().hashCode());
            Seen s = seen.get(key);
            if (s == null) {
                s = new Seen();
                seen.put(key, s);
            }
            long cooldown = s.count < 3 ? 15000L : 300000L;
            if (s.count > 0 && now - s.last < cooldown) return;
            s.last = now;
            s.count++;
        }

        if (captureCount >= MAX_CAPTURES) return;
        captureCount++;

        String base = "cap_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now));
        if (!found) tree.append("(no watched app window was visible)\n");
        final String text = "time=" + new Date(now) + "\nnote=" + note + "\n\n" + tree;
        final File txt = new File(Store.dir(this), base + ".txt");
        io.execute(() -> writeFile(txt, text, false));

        if (now - lastShot >= MIN_SHOT_GAP_MS) {
            lastShot = now;
            shoot(new File(Store.dir(this), base + ".jpg"));
        }
    }

    private void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb, StringBuilder sig, int[] count) {
        if (n == null || depth > 40 || count[0] > MAX_NODES) return;
        count[0]++;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String id = n.getViewIdResourceName();
        Rect r = new Rect();
        n.getBoundsInScreen(r);

        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(shortName(n.getClassName()));
        if (id != null) sb.append(" id=").append(id);
        if (text != null && text.length() > 0) sb.append(" text=\"").append(oneLine(text)).append('"');
        if (desc != null && desc.length() > 0) sb.append(" desc=\"").append(oneLine(desc)).append('"');
        sb.append(" bounds=").append(r.left).append(',').append(r.top).append(',')
                .append(r.right).append(',').append(r.bottom);
        if (n.isClickable()) sb.append(" CLICKABLE");
        if (!n.isEnabled()) sb.append(" disabled");
        if (!n.isVisibleToUser()) sb.append(" hidden");
        sb.append('\n');

        if (n.isVisibleToUser()) {
            if (text != null) sig.append(text.toString().replaceAll("[0-9]", "#")).append('|');
            if (desc != null) sig.append(desc.toString().replaceAll("[0-9]", "#")).append('|');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            dump(n.getChild(i), depth + 1, sb, sig, count);
        }
    }

    private void shoot(final File out) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, io, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            int w = sw.getWidth();
                            int h = sw.getHeight();
                            if (w > SHOT_WIDTH) {
                                Bitmap scaled = Bitmap.createScaledBitmap(sw, SHOT_WIDTH, h * SHOT_WIDTH / w, true);
                                sw.recycle();
                                sw = scaled;
                            }
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                sw.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            }
                            sw.recycle();
                            hw.recycle();
                        }
                        hb.close();
                    } catch (Exception e) {
                        logLine("screenshot save error: " + e);
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    logLine("screenshot failed, code " + errorCode);
                }
            });
        } catch (Exception e) {
            logLine("screenshot error: " + e);
        }
    }

    private void logLine(String line) {
        final String s = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + line + "\n";
        final File f = new File(Store.dir(this), "events.log");
        io.execute(() -> writeFile(f, s, true));
    }

    private static void writeFile(File f, String s, boolean append) {
        try (FileOutputStream fos = new FileOutputStream(f, append)) {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String shortName(CharSequence c) {
        if (c == null) return "?";
        String s = c.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String oneLine(CharSequence c) {
        return c.toString().replace('\n', ' ').replace('"', '\'');
    }
}
