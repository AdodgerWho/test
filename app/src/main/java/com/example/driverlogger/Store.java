package com.example.driverlogger;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Store {
    static final String PREFS = "logger";
    static final String KEY_APPS = "apps";

    private Store() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "captures");
        d.mkdirs();
        return d;
    }

    /** Package names of the apps being watched. */
    static Set<String> targets(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY_APPS, null);
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            out.add("com.ubercab.driver");
            out.add("com.lyft.android.driver");
            return out;
        }
        for (String line : s.split("[\\s,]+")) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    static void saveTargets(Context c, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String s : pkgs) {
            if (s != null && !s.trim().isEmpty()) sb.append(s.trim()).append('\n');
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_APPS, sb.toString()).apply();
    }

    /** Known driver apps plus any installed app whose name looks like one of them. */
    static List<String> detect(Context c) {
        List<String> out = new ArrayList<>(Arrays.asList("com.ubercab.driver", "com.lyft.android.driver"));
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
            String pkg = ri.activityInfo.packageName;
            boolean brand = label.contains("uber") || label.contains("lyft") || label.contains("hich");
            if (brand && label.contains("driver") && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    /** {number of captures (.txt), total size in bytes} */
    static long[] stats(Context c) {
        File[] fs = dir(c).listFiles();
        long n = 0, bytes = 0;
        if (fs != null) {
            for (File f : fs) {
                bytes += f.length();
                if (f.getName().startsWith("cap_") && f.getName().endsWith(".txt")) n++;
            }
        }
        return new long[]{n, bytes};
    }

    static void clear(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) f.delete();
        }
    }

    static String exportZip(Context c, boolean withShots) throws IOException {
        File[] files = dir(c).listFiles();
        if (files == null || files.length == 0) throw new IOException("nothing to export yet");
        Arrays.sort(files);
        String name = "DriverLog_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date())
                + (withShots ? "" : "_text") + ".zip";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("could not create the file in Downloads");
        try (OutputStream os = c.getContentResolver().openOutputStream(uri);
             ZipOutputStream z = new ZipOutputStream(os)) {
            byte[] buf = new byte[65536];
            for (File f : files) {
                if (!withShots && f.getName().endsWith(".jpg")) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
        return name;
    }
}
