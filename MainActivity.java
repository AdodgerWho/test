package com.example.driverlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status;
    private TextView stats;
    private EditText appsBox;
    private CheckBox alwaysBox;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(32), dp(16), dp(24));

        col.addView(label("Driver Logger v2.1", 22));
        status = label("", 18);
        col.addView(status);
        stats = label("", 14);
        col.addView(stats);

        col.addView(label("1. Open Accessibility settings\n"
                + "2. Turn it on under Installed apps / Downloaded apps > Driver Logger.\n"
                + "If the switch is greyed out: open App info, tap the three-dot menu, "
                + "choose Allow restricted settings, then try again.", 14));

        col.addView(button("Open Accessibility settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        col.addView(button("Open App info", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        }));

        alwaysBox = new CheckBox(this);
        alwaysBox.setText("Keep a short rolling screenshot buffer even when no watched app is visible. "
                + "Screenshots are only kept permanently around offers and screen changes in the "
                + "watched apps; everything else is deleted within about 20 seconds.");
        alwaysBox.setChecked(Store.isAlways(this));
        alwaysBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Store.setAlways(MainActivity.this, checked);
            }
        });
        col.addView(alwaysBox);

        col.addView(label("\nApps being watched (one package name per line):", 14));
        appsBox = new EditText(this);
        appsBox.setMinLines(3);
        appsBox.setText(Store.getAppsText(this));
        col.addView(appsBox);
        col.addView(button("Save app list", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setAppsText(MainActivity.this, appsBox.getText().toString());
                Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
            }
        }));

        col.addView(label("\n", 8));
        col.addView(button("Export to Downloads (zip with screenshots)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true);
            }
        }));
        col.addView(button("Export to Downloads (text only, smaller)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false);
            }
        }));
        col.addView(button("Delete all captures", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Delete all saved captures from this phone?")
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

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        status.setText(isServiceOn() ? "Logger is ON" : "Logger is OFF");
        double mb = Store.bytes(this) / (1024.0 * 1024.0);
        stats.setText(String.format(Locale.US, "%d text captures, %d screenshots, %.1f MB",
                Store.count(this, ".txt"), Store.count(this, ".jpg"), mb));
    }

    private boolean isServiceOn() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void export(final boolean withShots) {
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show();
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

    private TextView label(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
