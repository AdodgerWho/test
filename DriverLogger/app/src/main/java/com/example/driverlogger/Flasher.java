package com.example.driverlogger;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Good-offer alert: a see-through colour pulse over the whole screen (4 pulses in about 2 s) plus a
 * small price pill at the top. The overlay never takes touches, so Match / Accept stays tappable.
 * Must be used from the main thread.
 */
final class Flasher {
    static final int PULSES = 4;
    static final long PULSE_MS = 500;
    static final long TOTAL_MS = PULSES * PULSE_MS;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ObjectAnimator anim;
    private final Runnable removeRunnable = new Runnable() {
        @Override
        public void run() {
            remove();
        }
    };

    Flasher(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    /** @param opacityPct peak strength of the tint, 5 to 60 */
    void flash(String label, int color, int opacityPct, boolean vibrate, boolean sound) {
        remove();
        try {
            root = new FrameLayout(svc);

            View tint = new View(svc);
            tint.setBackgroundColor(color);
            tint.setAlpha(0f);
            root.addView(tint, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            if (label != null && !label.isEmpty()) {
                TextView pill = new TextView(svc);
                pill.setText(label);
                pill.setTextColor(Color.WHITE);
                pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
                int px = dp(18), py = dp(8);
                pill.setPadding(px, py, px, py);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xCC11161C);
                bg.setCornerRadius(dp(24));
                pill.setBackground(bg);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(72);
                root.addView(pill, lp);
            }

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, p);

            float peak = Math.max(5, Math.min(60, opacityPct)) / 100f;
            anim = ObjectAnimator.ofFloat(tint, View.ALPHA, 0f, peak, 0f);
            anim.setDuration(PULSE_MS);
            anim.setRepeatCount(PULSES - 1);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    handler.postDelayed(removeRunnable, 1200);
                }
            });
            anim.start();
            handler.postDelayed(removeRunnable, TOTAL_MS + 3000);
        } catch (Throwable t) {
            remove();
        }

        if (vibrate) buzz();
        if (sound) beep();
    }

    void remove() {
        handler.removeCallbacks(removeRunnable);
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
            anim = null;
        }
        if (root != null) {
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) svc.getSystemService(AccessibilityService.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 140, 90, 140, 90, 140}, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    private void beep() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tg.release();
                }
            }, 700);
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }
}
