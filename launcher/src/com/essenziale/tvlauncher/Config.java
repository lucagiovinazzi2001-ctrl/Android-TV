package com.essenziale.tvlauncher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Configurazione opzionale letta da
 * /sdcard/Android/data/com.essenziale.tvlauncher/files/config.properties
 * (lo script toolkit la invia via adb). Tutti i valori hanno un default.
 */
final class Config {

    static final String[] DEFAULT_CATEGORIES = {
            "Featured pictures of landscapes",
            "Featured pictures of mountains",
            "Featured pictures of lakes",
            "Featured pictures of forests",
            "Featured pictures of waterfalls",
            "Featured pictures of coasts",
    };

    int rotationSeconds = 90;
    int fadeMillis = 1500;
    boolean onlineWallpapers = true;
    int maxCachedWallpapers = 40;
    List<String> categories = new ArrayList<>();

    static Config load(Context ctx) {
        Config c = new Config();
        for (String s : DEFAULT_CATEGORIES) c.categories.add(s);
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return c;
        File f = new File(dir, "config.properties");
        if (!f.isFile()) return c;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
        } catch (Exception e) {
            return c;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        c.rotationSeconds = clamp(intOf(p, "rotation_seconds", c.rotationSeconds), 15, 3600);
        c.fadeMillis = clamp(intOf(p, "fade_millis", c.fadeMillis), 0, 5000);
        c.maxCachedWallpapers = clamp(intOf(p, "max_cached_wallpapers", c.maxCachedWallpapers), 5, 200);
        c.onlineWallpapers = !"false".equalsIgnoreCase(p.getProperty("online_wallpapers", "true").trim());
        String cats = p.getProperty("wallpaper_categories");
        if (cats != null && cats.trim().length() > 0) {
            c.categories.clear();
            for (String s : cats.split("\\|")) {
                if (s.trim().length() > 0) c.categories.add(s.trim());
            }
        }
        return c;
    }

    private static int intOf(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, "").trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
