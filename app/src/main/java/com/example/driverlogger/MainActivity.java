package com.example.driverlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private TextView status;
    private TextView info;
    private EditText apps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!getSharedPreferences(Store.PREFS, MODE_PRIVATE).contains(Store.KEY_APPS)) {
            Store.saveTargets(this, Store.detect(this));
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        status = label(root, "");
        button(root, "1. Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(root, "If the switch is greyed out: open App info", v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));

        label(root, "\nApps being watched (one package name per line):");
        apps = new EditText(this);
        apps.setMinLines(4);
        apps.setGravity(Gravity.TOP | Gravity.START);
        apps.setText(TextUtils.join("\n", Store.targets(this)));
        root.addView(apps);
        button(root, "Save app list", v -> {
            List<String> list = new ArrayList<>(Arrays.asList(apps.getText().toString().split("[\\s,]+")));
            Store.saveTargets(this, list);
            toast("Saved");
        });

        info = label(root, "");
        button(root, "Export to Downloads (zip with screenshots)", v -> export(true));
        button(root, "Export to Downloads (text only, smaller)", v -> export(false));
        button(root, "Delete all captures", v ->
                new AlertDialog.Builder(this)
                        .setMessage("Delete all saved captures from this phone?")
                        .setPositiveButton("Delete", (d, w) -> {
                            Store.clear(this);
                            refresh();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        status.setText("Logger is " + (serviceOn() ? "ON" : "OFF")
                + (serviceOn() ? "" : "\nTurn it on under Installed apps / Downloaded apps > Driver Logger."));
        long[] s = Store.stats(this);
        info.setText("\nSaved: " + s[0] + " captures, " + String.format("%.1f", s[1] / 1048576.0) + " MB\n");
    }

    private boolean serviceOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void export(boolean withShots) {
        new Thread(() -> {
            String msg;
            try {
                msg = "Saved to Downloads: " + Store.exportZip(this, withShots);
            } catch (Exception e) {
                msg = "Export failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private TextView label(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        root.addView(t);
        return t;
    }

    private void button(LinearLayout root, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
    }
}
