package com.essenziale.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Schermata iniziale essenziale in stile "Apple TV":
 * sfondo naturalistico a tutto schermo, orologio, una sola fila di app preferite,
 * griglia "Tutte le app" a comparsa. Nessun contenuto promosso, nessun tracciamento.
 */
public class HomeActivity extends Activity {

    private static final String PREFS = "home";
    private static final String KEY_FAVS = "favorites";
    private static final String KEY_FAVS_IMPORTED_AT = "favorites_imported_at";

    /** Preferite proposte al primo avvio se l'utente non ha inviato favorites.txt. */
    private static final String[] DEFAULT_FAVS = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "it.rainet.androidtv", "it.mediaset.infinitytv", "it.mediaset.mediasetplay",
            "com.dazn", "com.spotify.tv.android", "com.plexapp.android", "org.xbmc.kodi",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppRepository repo;
    private SharedPreferences prefs;
    private Config cfg;
    private WallpaperRotator rotator;

    private LinearLayout favRow;
    private HorizontalScrollView favScroll;
    private TextView focusLabel;
    private FrameLayout overlay;
    private GridView grid;
    private AllAppsAdapter gridAdapter;
    private final List<String> favorites = new ArrayList<>();
    private String lastFocusedPkg;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadAppsAsync(); }
    };

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        repo = new AppRepository(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cfg = Config.load(this);
        buildUi();
        repo.reload();          // veloce: solo metadati, le icone si caricano al bisogno
        loadFavorites();
        renderFavorites();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (importFavoritesFileIfNewer()) renderFavorites();
        rotator.start();
        if (overlay.getVisibility() != View.VISIBLE) focusFirstFavoriteLater();
    }

    @Override
    protected void onPause() {
        rotator.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        rotator.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Premuto HOME mentre siamo già qui: chiudi la griglia e torna alla prima preferita.
        if (overlay.getVisibility() == View.VISIBLE) hideAllApps();
        favScroll.smoothScrollTo(0, 0);
        focusFirstFavoriteLater();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) hideAllApps();
        // altrimenti: siamo la home, BACK non fa nulla
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            rotator.skip();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------------ interfaccia

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean light) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(light ? "sans-serif-light" : "sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Sfondi (contenitore dedicato, così la dissolvenza non copre il resto)
        FrameLayout wallpaper = new FrameLayout(this);
        ImageView a = new ImageView(this), b = new ImageView(this);
        for (ImageView iv : new ImageView[]{a, b}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            wallpaper.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        // Velature per la leggibilità
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(170), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x66000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        // Orologio + data (in alto a destra)
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setTextColor(0xE6FFFFFF);
        date.setAllCaps(false);
        date.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        clockBox.addView(clock);
        clockBox.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(48), 0);
        root.addView(clockBox, clp);

        // Fila delle preferite (in basso)
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        favScroll.setFillViewport(false);
        favScroll.setPadding(dp(48), dp(18), dp(48), dp(14));
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favRow.setClipToPadding(false);
        favScroll.addView(favRow, new FrameLayout.LayoutParams(-2, -2));
        bottom.addView(favScroll, new LinearLayout.LayoutParams(-1, -2));

        focusLabel = text(17, Color.WHITE, false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(dp(48), 0, dp(48), 0);
        bottom.addView(focusLabel, flp);

        TextView credit = text(10, 0x99FFFFFF, true);
        credit.setGravity(Gravity.END);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.setMargins(dp(48), dp(6), dp(48), dp(14));
        bottom.addView(credit, crp);

        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Griglia "Tutte le app" (a comparsa)
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6101214);
        overlay.setVisibility(View.GONE);
        LinearLayout ov = new LinearLayout(this);
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setPadding(dp(48), dp(32), dp(48), 0);
        TextView title = text(26, Color.WHITE, true);
        title.setText("Tutte le app");
        ov.addView(title);
        TextView hint = text(12, 0xB3FFFFFF, true);
        hint.setText("OK: apri   ·   Tieni premuto OK o MENU: aggiungi/togli dalle preferite   ·   INDIETRO: chiudi");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMargins(0, dp(4), 0, dp(16));
        ov.addView(hint, hp);
        grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing(dp(18));
        grid.setHorizontalSpacing(dp(18));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(dp(8), dp(12), dp(8), dp(32));
        grid.setVerticalScrollBarEnabled(false);
        gridAdapter = new AllAppsAdapter();
        grid.setAdapter(gridAdapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                launch(gridAdapter.getItem(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                toggleFavorite(gridAdapter.getItem(pos));
                return true;
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                for (int i = 0; i < p.getChildCount(); i++) setTileFocused(p.getChildAt(i), p.getChildAt(i) == v);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent e) {
                if (keyCode == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                    Object sel = grid.getSelectedItem();
                    if (sel instanceof AppRepository.AppEntry) toggleFavorite((AppRepository.AppEntry) sel);
                    return true;
                }
                return false;
            }
        });
        ov.addView(grid, new LinearLayout.LayoutParams(-1, -1));
        overlay.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        rotator = new WallpaperRotator(this, a, b, credit, cfg);
    }

    // ------------------------------------------------------------------ tessere

    private static final float TILE_W = 196, TILE_H = 110;

    private FrameLayout makeCard(int wDp, int hDp) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x40FFFFFF);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        card.setClipToOutline(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return card;
    }

    /** Riempie una tessera con banner 16:9 se c'è, altrimenti icona + nome. */
    private void fillCard(FrameLayout card, AppRepository.AppEntry e, boolean showNameIfIcon) {
        card.removeAllViews();
        repo.loadVisuals(e);
        if (e.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(e.banner);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        ((GradientDrawable) card.getBackground()).setColor(0x59FFFFFF);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = e.icon;
        if (d != null) iv.setImageDrawable(d);
        box.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
        if (showNameIfIcon) {
            TextView t = text(12, Color.WHITE, false);
            t.setText(e.label);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(6), dp(8), 0);
            box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private void setTileFocused(View v, boolean focused) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f)
                .translationZ(focused ? dp(14) : 0).setDuration(140)
                .setInterpolator(new DecelerateInterpolator()).start();
        v.setAlpha(focused ? 1f : 0.88f);
    }

    private View.OnFocusChangeListener tileFocus(final String label, final String pkg) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                setTileFocused(v, has);
                if (has) {
                    focusLabel.setText(label);
                    lastFocusedPkg = pkg;
                }
            }
        };
    }

    private void addRowTile(View tile) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
        lp.setMargins(0, 0, dp(22), 0);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setAlpha(0.88f);
        favRow.addView(tile, lp);
    }

    private void renderFavorites() {
        favRow.removeAllViews();
        for (final String pkg : new ArrayList<>(favorites)) {
            final AppRepository.AppEntry e = repo.get(pkg);
            if (e == null) continue; // non installata o disabilitata: resta salvata ma non si vede
            FrameLayout card = makeCard((int) TILE_W, (int) TILE_H);
            fillCard(card, e, true);
            card.setOnFocusChangeListener(tileFocus(e.label, pkg));
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { launch(e); }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { favoriteMenu(pkg); return true; }
            });
            card.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int k, KeyEvent ev) {
                    if (k == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                        favoriteMenu(pkg);
                        return true;
                    }
                    return false;
                }
            });
            card.setTag(pkg);
            addRowTile(card);
        }

        // "Tutte le app"
        FrameLayout all = makeCard((int) TILE_W, (int) TILE_H);
        TextView plus = text(17, Color.WHITE, false);
        plus.setText("⊞  Tutte le app");
        plus.setGravity(Gravity.CENTER);
        all.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        all.setOnFocusChangeListener(tileFocus("Tutte le app — tieni premuto OK su un'app per aggiungerla qui", "#all"));
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAllApps(); }
        });
        all.setTag("#all");
        addRowTile(all);

        // Impostazioni
        FrameLayout set = makeCard((int) TILE_W, (int) TILE_H);
        TextView st = text(17, Color.WHITE, false);
        st.setText("⚙  Impostazioni");
        st.setGravity(Gravity.CENTER);
        set.addView(st, new FrameLayout.LayoutParams(-1, -1));
        set.setOnFocusChangeListener(tileFocus("Impostazioni di sistema", "#settings"));
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        set.setTag("#settings");
        addRowTile(set);
    }

    private void focusFirstFavoriteLater() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (favRow.getChildCount() == 0) return;
                View target = favRow.getChildAt(0);
                if (lastFocusedPkg != null) {
                    View v = favRow.findViewWithTag(lastFocusedPkg);
                    if (v != null) target = v;
                }
                target.requestFocus();
            }
        });
    }

    // ------------------------------------------------------------------ preferite

    private void loadFavorites() {
        favorites.clear();
        String s = prefs.getString(KEY_FAVS, null);
        if (s != null) {
            for (String p : s.split(",")) if (p.trim().length() > 0) favorites.add(p.trim());
        }
        if (importFavoritesFileIfNewer()) return;
        if (s == null) {
            for (String p : DEFAULT_FAVS) if (repo.get(p) != null && favorites.size() < 8) favorites.add(p);
            saveFavorites();
        }
    }

    /** favorites.txt (un package per riga) inviato via adb sostituisce le preferite se è più recente. */
    private boolean importFavoritesFileIfNewer() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return false;
        File f = new File(dir, "favorites.txt");
        if (!f.isFile()) return false;
        long imported = prefs.getLong(KEY_FAVS_IMPORTED_AT, 0);
        if (f.lastModified() <= imported) return false;
        List<String> list = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!list.contains(line)) list.add(line);
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
        }
        favorites.clear();
        favorites.addAll(list);
        saveFavorites();
        prefs.edit().putLong(KEY_FAVS_IMPORTED_AT, f.lastModified()).apply();
        return true;
    }

    private void saveFavorites() {
        prefs.edit().putString(KEY_FAVS, TextUtils.join(",", favorites)).apply();
    }

    private void toggleFavorite(AppRepository.AppEntry e) {
        if (e == null) return;
        if (favorites.contains(e.pkg)) {
            favorites.remove(e.pkg);
            toast(e.label + " tolta dalle preferite");
        } else {
            favorites.add(e.pkg);
            toast(e.label + " aggiunta alle preferite");
        }
        saveFavorites();
        renderFavorites();
        gridAdapter.notifyDataSetChanged();
    }

    private void favoriteMenu(final String pkg) {
        AppRepository.AppEntry e = repo.get(pkg);
        String[] items = {"Sposta a sinistra", "Sposta a destra", "Togli dalle preferite"};
        new AlertDialog.Builder(this)
                .setTitle(e != null ? e.label : pkg)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int i = favorites.indexOf(pkg);
                        if (i < 0) return;
                        if (which == 0 && i > 0) {
                            favorites.remove(i);
                            favorites.add(i - 1, pkg);
                        } else if (which == 1 && i < favorites.size() - 1) {
                            favorites.remove(i);
                            favorites.add(i + 1, pkg);
                        } else if (which == 2) {
                            favorites.remove(i);
                        }
                        saveFavorites();
                        lastFocusedPkg = which == 2 ? null : pkg;
                        renderFavorites();
                        focusFirstFavoriteLater();
                    }
                }).show();
    }

    // ------------------------------------------------------------------ azioni

    private void launch(AppRepository.AppEntry e) {
        if (e == null) return;
        try {
            startActivity(e.launchIntent());
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (i != null) {
                try { startActivity(i); return; } catch (Exception ignored) { }
            }
            toast("Impossibile aprire " + e.label);
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Impostazioni non disponibili");
        }
    }

    private void showAllApps() {
        gridAdapter.setItems(repo.all());
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(160).start();
        grid.setSelection(0);
        grid.requestFocus();
    }

    private void hideAllApps() {
        overlay.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override public void run() {
                overlay.setVisibility(View.GONE);
                focusFirstFavoriteLater();
            }
        }).start();
    }

    private void reloadAppsAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                repo.reload();
                ui.post(new Runnable() {
                    @Override public void run() {
                        boolean hadFocus = favRow.hasFocus();
                        renderFavorites();
                        if (overlay.getVisibility() == View.VISIBLE) gridAdapter.setItems(repo.all());
                        else if (hadFocus) focusFirstFavoriteLater();
                    }
                });
            }
        }, "apps-reload").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ griglia

    private final class AllAppsAdapter extends BaseAdapter {
        private List<AppRepository.AppEntry> items = new ArrayList<>();

        void setItems(List<AppRepository.AppEntry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public AppRepository.AppEntry getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setClipChildren(false);
                FrameLayout card = makeCard(10, 10);
                cell.addView(card, new LinearLayout.LayoutParams(-1, dp(92)));
                TextView name = text(12, Color.WHITE, false);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, dp(6), 0, 0);
                cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
                cell.setAlpha(0.88f);
            }
            AppRepository.AppEntry e = items.get(pos);
            FrameLayout card = (FrameLayout) cell.getChildAt(0);
            ((GradientDrawable) card.getBackground()).setColor(0x40FFFFFF);
            fillCard(card, e, false);
            TextView name = (TextView) cell.getChildAt(1);
            name.setText(favorites.contains(e.pkg) ? "★ " + e.label : e.label);
            boolean sel = grid.getSelectedItemPosition() == pos && grid.hasFocus();
            cell.setScaleX(sel ? 1.12f : 1f);
            cell.setScaleY(sel ? 1.12f : 1f);
            cell.setAlpha(sel ? 1f : 0.88f);
            return cell;
        }
    }
}
