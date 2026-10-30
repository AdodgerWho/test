package com.example.driverlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dark, quiet main screen. The service itself shows nothing on screen except the good-offer pulse. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF181D25;
    private static final int TEXT = 0xFFE8ECF1;
    private static final int MUTED = 0xFF8A94A3;
    private static final int GREEN = 0xFF2ECC71;
    private static final int AMBER = 0xFFF5A524;
    private static final int RED = 0xFFE5484D;
    private static final int LINE = 0xFF2A313C;

    private TextView statusChip;
    private Button modeLog;
    private Button modeAlert;
    private TextView modeHelp;
    private TextView statSeen;
    private TextView statGood;
    private TextView statBest;
    private LinearLayout feed;
    private LinearLayout setupBody;
    private TextView stats;
    private EditText minRate;
    private EditText hichMiles;
    private EditText hichSched;
    private SeekBar opacity;
    private TextView opacityLabel;
    private CheckBox vibrate;
    private CheckBox sound;
    private CheckBox alwaysBox;
    private CheckBox autoPriority;
    private CheckBox autoSched;
    private CheckBox autoOnline;
    private EditText appsBox;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(36), dp(16), dp(28));

        // ---- header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Driver Logger", 26, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusChip = text("", 14, TEXT, true);
        statusChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(statusChip);
        col.addView(head);
        col.addView(text("v3.3", 12, MUTED, false));

        // ---- mode
        LinearLayout modeCard = card();
        modeCard.addView(text("MODE", 12, MUTED, true));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        modeLog = segButton("Log only", Store.MODE_LOG);
        modeAlert = segButton("Alert only", Store.MODE_ALERT);
        seg.addView(modeLog, new LinearLayout.LayoutParams(0, dp(48), 1f));
        seg.addView(modeAlert, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = dp(8);
        modeCard.addView(seg, segLp);
        modeHelp = text("", 13, MUTED, false);
        modeHelp.setPadding(0, dp(10), 0, 0);
        modeCard.addView(modeHelp);
        col.addView(modeCard);

        // ---- today
        LinearLayout todayCard = card();
        todayCard.addView(text("TODAY", 12, MUTED, true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        statSeen = bigStat(row, "Offers seen");
        statGood = bigStat(row, "Good");
        statBest = bigStat(row, "Best $/hr");
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        todayCard.addView(row, rowLp);
        col.addView(todayCard);

        // ---- recent offers
        LinearLayout feedCard = card();
        feedCard.addView(text("RECENT OFFERS", 12, MUTED, true));
        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feedCard.addView(feed);
        col.addView(feedCard);

        // ---- settings
        LinearLayout setCard = card();
        setCard.addView(text("SETTINGS", 12, MUTED, true));
        minRate = field(setCard, "Good offer: at least $/hr");
        hichMiles = field(setCard, "hich live rides: pickup within (miles)");
        hichSched = field(setCard, "hich scheduled rides: at least $");
        opacityLabel = text("", 14, TEXT, false);
        opacityLabel.setPadding(0, dp(12), 0, 0);
        setCard.addView(opacityLabel);
        opacity = new SeekBar(this);
        opacity.setMax(55);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                opacityLabel.setText("Flash strength: " + (p + 5) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        setCard.addView(opacity);
        vibrate = check("Vibrate on good offers");
        sound = check("Short beep on good offers");
        setCard.addView(vibrate);
        setCard.addView(sound);
        autoPriority = check("Turn on Lyft Priority Mode automatically when it becomes available");
        autoPriority.setOnCheckedChangeListener((b, checked) -> Store.setAutoPriority(MainActivity.this, checked));
        setCard.addView(autoPriority);
        autoSched = check("Open hich scheduled rides automatically when a ride is waiting");
        autoSched.setOnCheckedChangeListener((b, checked) -> Store.setAutoSched(MainActivity.this, checked));
        setCard.addView(autoSched);
        autoOnline = check("Go online in hich automatically when it is open and offline");
        autoOnline.setOnCheckedChangeListener((b, checked) -> Store.setAutoOnline(MainActivity.this, checked));
        setCard.addView(autoOnline);
        setCard.addView(flatButton("Save settings", GREEN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        }));
        col.addView(setCard);

        // ---- setup & data (collapsed)
        LinearLayout setupCard = card();
        final Button toggle = flatButton("Setup & data  \u25be", LINE, null);
        toggle.setTextColor(TEXT);
        setupCard.addView(toggle);
        setupBody = new LinearLayout(this);
        setupBody.setOrientation(LinearLayout.VERTICAL);
        setupBody.setVisibility(View.GONE);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = setupBody.getVisibility() == View.VISIBLE;
                setupBody.setVisibility(open ? View.GONE : View.VISIBLE);
                toggle.setText(open ? "Setup & data  \u25be" : "Setup & data  \u25b4");
            }
        });
        setupBody.addView(text("Turn the service on under Accessibility > Installed apps > Driver Logger. "
                + "If the switch is greyed out: App info > three-dot menu > Allow restricted settings.", 13, MUTED, false));
        setupBody.addView(flatButton("Open Accessibility settings", LINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        setupBody.addView(flatButton("Open App info", LINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        }));
        alwaysBox = check("Keep a short rolling screenshot buffer even when no watched app is visible");
        alwaysBox.setOnCheckedChangeListener((b, checked) -> Store.setAlways(MainActivity.this, checked));
        setupBody.addView(alwaysBox);
        setupBody.addView(text("Apps being watched (one package per line):", 13, MUTED, false));
        appsBox = new EditText(this);
        appsBox.setMinLines(3);
        appsBox.setTextColor(TEXT);
        appsBox.setTextSize(13);
        setupBody.addView(appsBox);
        setupBody.addView(flatButton("Save app list", LINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setAppsText(MainActivity.this, appsBox.getText().toString());
                toast("Saved");
            }
        }));
        stats = text("", 13, MUTED, false);
        stats.setPadding(0, dp(10), 0, 0);
        setupBody.addView(stats);
        setupBody.addView(flatButton("Export to Downloads (with screenshots)", LINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true);
            }
        }));
        setupBody.addView(flatButton("Export to Downloads (text only, smaller)", LINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false);
            }
        }));
        setupBody.addView(flatButton("Delete all captures", RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Delete all saved captures and offer history from this phone?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Store.deleteAll(MainActivity.this);
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }));
        setupCard.addView(setupBody);
        col.addView(setupCard);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        refresh();
    }

    // ---------------------------------------------------------------- data -> views

    private void loadSettings() {
        minRate.setText(trim(Store.getMinRate(this)));
        hichMiles.setText(trim(Store.getHichMiles(this)));
        hichSched.setText(trim(Store.getHichScheduled(this)));
        opacity.setProgress(Store.getOpacity(this) - 5);
        opacityLabel.setText("Flash strength: " + Store.getOpacity(this) + "%");
        vibrate.setChecked(Store.getVibrate(this));
        sound.setChecked(Store.getSound(this));
        alwaysBox.setChecked(Store.isAlways(this));
        autoPriority.setChecked(Store.getAutoPriority(this));
        autoSched.setChecked(Store.getAutoSched(this));
        autoOnline.setChecked(Store.getAutoOnline(this));
        appsBox.setText(Store.getAppsText(this));
    }

    private void saveSettings() {
        Store.saveSettings(this,
                num(minRate, 32f), num(hichMiles, 5f), num(hichSched, 20f),
                opacity.getProgress() + 5, vibrate.isChecked(), sound.isChecked());
        toast("Saved");
    }

    private void refresh() {
        boolean on = isServiceOn();
        statusChip.setText(on ? "\u25cf ON" : "\u25cf OFF");
        statusChip.setBackground(round(on ? 0x332ECC71 : 0x33E5484D, 20));
        statusChip.setTextColor(on ? GREEN : RED);

        int mode = Store.getMode(this);
        styleSeg(modeLog, mode == Store.MODE_LOG);
        styleSeg(modeAlert, mode == Store.MODE_ALERT);
        modeHelp.setText(mode == Store.MODE_ALERT
                ? "Good offers pulse the screen (and vibrate). You still tap Accept / Match yourself."
                : "Offers are only recorded. Nothing appears on screen while you drive.");

        double mb = Store.bytes(this) / (1024.0 * 1024.0);
        stats.setText(String.format(Locale.US, "%d text captures, %d screenshots, %.1f MB",
                Store.count(this, ".txt"), Store.count(this, ".jpg"), mb));

        List<JSONObject> offers = Store.recentOffers(this, 200);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        int seen = 0, good = 0;
        double best = -1;
        for (JSONObject o : offers) {
            if (o.optLong("t") < dayStart) continue;
            seen++;
            if ("GOOD".equals(o.optString("verdict"))) good++;
            best = Math.max(best, o.optDouble("rate", -1));
        }
        statSeen.setText(String.valueOf(seen));
        statGood.setText(String.valueOf(good));
        statBest.setText(best >= 0 ? "$" + Math.round(best) : "-");

        feed.removeAllViews();
        if (offers.isEmpty()) {
            TextView none = text("No offers yet. They appear here as they are seen.", 14, MUTED, false);
            none.setPadding(0, dp(10), 0, 0);
            feed.addView(none);
        }
        SimpleDateFormat tf = new SimpleDateFormat("h:mm a", Locale.US);
        for (int i = 0; i < Math.min(12, offers.size()); i++) feed.addView(offerRow(offers.get(i), tf));
    }

    private View offerRow(final JSONObject o, SimpleDateFormat tf) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));

        String app = o.optString("app");
        int appColor = app.equals("uber") ? 0xFFB8C0CC : app.equals("lyft") ? 0xFFFF4FA3 : 0xFF4FC3F7;
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(app.toUpperCase(Locale.US) + (o.optString("kind").equals("live") ? "" : "  " + o.optString("kind")),
                12, appColor, true));
        left.addView(text(tf.format(new Date(o.optLong("t"))), 12, MUTED, false));
        r.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        double price = o.optDouble("price", -1);
        TextView p = text(price > 0 ? String.format(Locale.US, "$%.2f", price) : "?", 20, TEXT, true);
        p.setPadding(0, 0, dp(14), 0);
        r.addView(p);

        double rate = o.optDouble("rate", -1);
        String v = o.optString("verdict");
        int vc = v.equals("GOOD") ? GREEN : v.equals("SKIP") ? MUTED : AMBER;
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(text(rate >= 0 ? "$" + Math.round(rate) + "/hr" : "-", 16, vc, true));
        right.addView(text(v, 11, vc, true));
        r.addView(right);

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String msg = o.optString("detail") + "\nread by: " + o.optString("source")
                        + "\nmode: " + (o.optInt("mode") == Store.MODE_ALERT ? "alert" : "log");
                String raw = o.optString("raw");
                if (!raw.isEmpty()) msg += "\n\nOCR text:\n" + raw;
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setPositiveButton("Close", null).show();
            }
        });
        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        wrap.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private boolean isServiceOn() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void export(final boolean withShots) {
        toast("Exporting...");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    msg = "Saved to Downloads: " + Store.exportZip(MainActivity.this, withShots);
                } catch (Exception ex) {
                    msg = "Export failed: " + ex.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- view helpers

    private Button segButton(String label, final int mode) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setMode(MainActivity.this, mode);
                refresh();
            }
        });
        return b;
    }

    private void styleSeg(Button b, boolean selected) {
        b.setBackground(round(selected ? GREEN : LINE, 12));
        b.setTextColor(selected ? 0xFF06210F : TEXT);
        b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private TextView bigStat(LinearLayout row, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView n = text("-", 26, TEXT, true);
        box.addView(n);
        box.addView(text(label, 12, MUTED, false));
        row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return n;
    }

    private EditText field(LinearLayout parent, String label) {
        TextView l = text(label, 13, MUTED, false);
        l.setPadding(0, dp(12), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(TEXT);
        e.setTextSize(18);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private CheckBox check(String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(14);
        return c;
    }

    private Button flatButton(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color == GREEN ? 0xFF06210F : TEXT);
        b.setBackground(round(color, 12));
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(CARD, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static String trim(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    private static float num(EditText e, float fallback) {
        try {
            return Float.parseFloat(e.getText().toString().trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
