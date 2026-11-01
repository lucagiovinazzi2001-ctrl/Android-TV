package com.essenziale.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elenco delle app avviabili (leanback + classiche), con cache di icone/banner. */
final class AppRepository {

    static final class AppEntry {
        final String pkg;
        final String label;
        final ActivityInfo activity;
        final boolean leanback;
        Drawable banner;   // 16:9, può essere null
        Drawable icon;
        boolean visualsLoaded;

        AppEntry(String pkg, String label, ActivityInfo activity, boolean leanback) {
            this.pkg = pkg;
            this.label = label;
            this.activity = activity;
            this.leanback = leanback;
        }

        Intent launchIntent() {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
            i.setClassName(activity.packageName, activity.name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
    }

    private final Context ctx;
    private final PackageManager pm;
    private volatile Map<String, AppEntry> apps = new LinkedHashMap<>();

    AppRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = ctx.getPackageManager();
    }

    /** Ricarica l'elenco. Può essere chiamato da un thread in background. */
    synchronized void reload() {
        Map<String, AppEntry> out = new HashMap<>();
        collect(out, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(out, Intent.CATEGORY_LAUNCHER, false);
        out.remove(ctx.getPackageName());

        List<AppEntry> sorted = new ArrayList<>(out.values());
        final Collator coll = Collator.getInstance();
        Collections.sort(sorted, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return coll.compare(a.label, b.label);
            }
        });
        Map<String, AppEntry> ordered = new LinkedHashMap<>();
        Map<String, AppEntry> old = apps;
        for (AppEntry e : sorted) {
            AppEntry prev = old.get(e.pkg);
            if (prev != null && prev.visualsLoaded && prev.activity.name.equals(e.activity.name)) {
                e.banner = prev.banner;
                e.icon = prev.icon;
                e.visualsLoaded = true;
            }
            ordered.put(e.pkg, e);
        }
        apps = ordered;
    }

    private void collect(Map<String, AppEntry> out, String category, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || out.containsKey(ai.packageName)) continue;
            CharSequence l = ri.loadLabel(pm);
            out.put(ai.packageName, new AppEntry(ai.packageName,
                    l == null ? ai.packageName : l.toString(), ai, leanback));
        }
    }

    List<AppEntry> all() {
        return new ArrayList<>(apps.values());
    }

    AppEntry get(String pkg) {
        return apps.get(pkg);
    }

    void loadVisuals(AppEntry e) {
        if (e.visualsLoaded) return;
        try {
            Drawable b = e.activity.loadBanner(pm);
            if (b == null) b = e.activity.applicationInfo.loadBanner(pm);
            e.banner = b;
        } catch (RuntimeException ignored) { }
        try {
            e.icon = e.activity.loadIcon(pm);
        } catch (RuntimeException ignored) { }
        e.visualsLoaded = true;
    }
}
