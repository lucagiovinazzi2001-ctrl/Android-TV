package com.essenziale.tvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;

/**
 * Sfondi a rotazione con dissolvenza incrociata.
 * Priorità: 1) immagini inviate dall'utente in files/wallpapers/
 *           2) foto naturalistiche "in vetrina" di Wikimedia Commons (scaricate e messe in cache)
 *           3) gradiente di riserva.
 */
final class WallpaperRotator {

    private static final String UA = "EssenzialeTVLauncher/1.0 (Android TV home screen; personal use)";
    private static final long REFRESH_MS = 3L * 24 * 3600 * 1000;
    private static final int PER_FETCH = 8;

    private final Activity act;
    private final Config cfg;
    private final TextView credit;
    private ImageView front, back;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread worker = new HandlerThread("wallpapers");
    private final Handler bg;
    private final File localDir, cacheDir, creditsFile;
    private final SharedPreferences prefs;
    private final int targetW, targetH;
    private final Random rnd = new Random();

    private final List<File> queue = new ArrayList<>();
    private volatile File current;
    private volatile Bitmap currentBmp;
    private boolean running;
    private volatile boolean downloading;

    WallpaperRotator(Activity act, ImageView a, ImageView b, TextView credit, Config cfg) {
        this.act = act;
        this.front = a;
        this.back = b;
        this.credit = credit;
        this.cfg = cfg;
        worker.start();
        bg = new Handler(worker.getLooper());
        File ext = act.getExternalFilesDir("wallpapers");
        localDir = ext != null ? ext : new File(act.getFilesDir(), "wallpapers_local");
        cacheDir = new File(act.getFilesDir(), "wallpapers_cache");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        creditsFile = new File(cacheDir, "credits.properties");
        prefs = act.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        targetW = Math.min(w, 1920);
        targetH = Math.min(h, 1080);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            bg.post(loadNext);
            ui.postDelayed(this, cfg.rotationSeconds * 1000L);
        }
    };

    void start() {
        if (running) return;
        running = true;
        if (currentBmp == null) {
            ui.post(tick);
        } else {
            ui.postDelayed(tick, cfg.rotationSeconds * 1000L);
        }
        bg.post(maybeDownload);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    void destroy() {
        stop();
        worker.quitSafely();
    }

    /** Sfondo successivo subito (tasto dedicato). */
    void skip() {
        ui.removeCallbacks(tick);
        if (running) ui.post(tick);
    }

    // ---------------------------------------------------------------- selezione + decodifica

    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return f.isFile() && f.length() > 0
                && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"));
    }

    private List<File> listImages(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (isImage(f)) out.add(f);
        return out;
    }

    private List<File> availableImages() {
        List<File> local = listImages(localDir);
        return local.isEmpty() ? listImages(cacheDir) : local;
    }

    private File pickNext() {
        List<File> all = availableImages();
        if (all.isEmpty()) return null;
        queue.retainAll(all);
        if (queue.isEmpty()) {
            queue.addAll(all);
            Collections.shuffle(queue, rnd);
            if (queue.size() > 1 && queue.get(0).equals(current)) {
                Collections.swap(queue, 0, queue.size() - 1);
            }
        }
        return queue.remove(0);
    }

    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW && o.outHeight / (sample * 2) >= targetH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private final Runnable loadNext = new Runnable() {
        @Override public void run() {
            for (int attempt = 0; attempt < 3; attempt++) {
                final File f = pickNext();
                if (f == null) {
                    ui.post(new Runnable() { @Override public void run() { showFallback(); } });
                    return;
                }
                if (f.equals(current) && currentBmp != null) return;
                final Bitmap bmp = decode(f);
                if (bmp == null) {
                    if (f.getParentFile() != null && f.getParentFile().equals(cacheDir)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    continue;
                }
                final String text = creditFor(f);
                ui.post(new Runnable() { @Override public void run() { show(f, bmp, text); } });
                return;
            }
        }
    };

    private void showFallback() {
        if (currentBmp != null) return;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B3D2E, 0xFF14532D, 0xFF0F3A5F});
        front.setImageDrawable(g);
        front.setAlpha(1f);
        credit.setText("");
    }

    private void show(File f, Bitmap bmp, String creditText) {
        if (act.isFinishing()) { bmp.recycle(); return; }
        final Bitmap old = currentBmp;
        current = f;
        currentBmp = bmp;
        back.animate().cancel();
        back.setImageBitmap(bmp);
        back.setAlpha(0f);
        back.setVisibility(View.VISIBLE);
        back.bringToFront();
        // le due ImageView vivono in un contenitore dedicato: bringToFront non copre la UI
        final ImageView newFront = back;
        final ImageView oldFront = front;
        front = newFront;
        back = oldFront;
        credit.setText(creditText == null ? "" : creditText);
        newFront.animate().alpha(1f).setDuration(cfg.fadeMillis).withEndAction(new Runnable() {
            @Override public void run() {
                oldFront.setImageDrawable(null);
                oldFront.setAlpha(0f);
                if (old != null && old != currentBmp) old.recycle();
            }
        }).start();
    }

    // ---------------------------------------------------------------- crediti

    private Properties loadCredits() {
        Properties p = new Properties();
        if (!creditsFile.isFile()) return p;
        InputStream in = null;
        try {
            in = new FileInputStream(creditsFile);
            p.load(new InputStreamReader(in, "UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        return p;
    }

    private void saveCredits(Properties p) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(creditsFile);
            p.store(new OutputStreamWriter(out, "UTF-8"), null);
        } catch (Exception ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    private String creditFor(File f) {
        if (!f.getParentFile().equals(cacheDir)) return "";
        return loadCredits().getProperty(f.getName(), "Wikimedia Commons");
    }

    // ---------------------------------------------------------------- download

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final Runnable maybeDownload = new Runnable() {
        @Override public void run() {
            if (downloading || !cfg.onlineWallpapers) return;
            if (!listImages(localDir).isEmpty()) return;          // l'utente ha i suoi sfondi
            int cached = listImages(cacheDir).size();
            long last = prefs.getLong("last_fetch", 0);
            boolean due = cached < cfg.maxCachedWallpapers / 2
                    || System.currentTimeMillis() - last > REFRESH_MS;
            if (!due || !online()) return;
            downloading = true;
            try {
                int added = fetchFromCommons();
                if (added > 0) prefs.edit().putLong("last_fetch", System.currentTimeMillis()).apply();
                trimCache();
                if (cached == 0 && added > 0) {
                    ui.post(new Runnable() { @Override public void run() { if (currentBmp == null) skip(); } });
                }
            } catch (Exception ignored) {
            } finally {
                downloading = false;
            }
        }
    };

    private static final class Candidate {
        String title, url, credit;
    }

    private int fetchFromCommons() throws Exception {
        List<Candidate> cands = new ArrayList<>();
        List<String> cats = new ArrayList<>(cfg.categories);
        Collections.shuffle(cats, rnd);
        for (String cat : cats) {
            if (cands.size() >= 120) break;
            try {
                String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                        + "&generator=categorymembers&gcmtype=file&gcmlimit=100"
                        + "&gcmtitle=" + URLEncoder.encode("Category:" + cat, "UTF-8")
                        + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata"
                        + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName"
                        + "&iiurlwidth=" + targetW;
                parseCandidates(httpGetString(url), cands);
            } catch (Exception ignored) { }
        }
        if (cands.isEmpty()) {
            String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                    + "&generator=search&gsrnamespace=6&gsrlimit=50"
                    + "&gsrsearch=" + URLEncoder.encode("landscape nature filew:>1900 filetype:bitmap", "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata"
                    + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName"
                    + "&iiurlwidth=" + targetW;
            parseCandidates(httpGetString(url), cands);
        }
        Collections.shuffle(cands, rnd);
        Properties credits = loadCredits();
        int added = 0;
        for (Candidate c : cands) {
            if (added >= PER_FETCH) break;
            String name = "c_" + Integer.toHexString(c.title.hashCode()) + ".jpg";
            File dest = new File(cacheDir, name);
            if (dest.exists()) continue;
            File tmp = new File(cacheDir, name + ".part");
            try {
                httpDownload(c.url, tmp);
                if (tmp.length() < 20000 || !tmp.renameTo(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    continue;
                }
                credits.setProperty(name, c.credit);
                added++;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        saveCredits(credits);
        return added;
    }

    private void parseCandidates(String json, List<Candidate> out) throws Exception {
        JSONObject q = new JSONObject(json).optJSONObject("query");
        if (q == null) return;
        JSONObject pages = q.optJSONObject("pages");
        if (pages == null) return;
        Iterator<String> it = pages.keys();
        while (it.hasNext()) {
            JSONObject page = pages.optJSONObject(it.next());
            if (page == null) continue;
            JSONArray ii = page.optJSONArray("imageinfo");
            if (ii == null || ii.length() == 0) continue;
            JSONObject info = ii.getJSONObject(0);
            int w = info.optInt("width"), h = info.optInt("height");
            if (w < 1600 || h <= 0 || (float) w / h < 1.3f || (float) w / h > 2.4f) continue; // solo panorami orizzontali
            String thumb = info.optString("thumburl", "");
            if (!thumb.startsWith("https://")) continue;
            String lower = thumb.toLowerCase();
            if (!(lower.contains(".jpg") || lower.contains(".jpeg"))) continue;
            JSONObject meta = info.optJSONObject("extmetadata");
            String title = page.optString("title", thumb).replaceFirst("^File:", "");
            String nice = metaValue(meta, "ObjectName");
            if (nice.isEmpty()) nice = title.replaceAll("\\.[A-Za-z]+$", "").replace('_', ' ');
            String artist = metaValue(meta, "Artist");
            String lic = metaValue(meta, "LicenseShortName");
            StringBuilder sb = new StringBuilder(nice);
            if (!artist.isEmpty()) sb.append(" — ").append(artist);
            if (!lic.isEmpty()) sb.append(" · ").append(lic);
            sb.append(" · Wikimedia Commons");
            Candidate c = new Candidate();
            c.title = title;
            c.url = thumb;
            c.credit = sb.length() > 140 ? sb.substring(0, 137) + "…" : sb.toString();
            out.add(c);
        }
    }

    private static String metaValue(JSONObject meta, String key) {
        if (meta == null) return "";
        JSONObject o = meta.optJSONObject(key);
        if (o == null) return "";
        return o.optString("value", "").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }

    private void trimCache() {
        List<File> files = listImages(cacheDir);
        if (files.size() <= cfg.maxCachedWallpapers) return;
        File[] arr = files.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (int i = 0; i < arr.length - cfg.maxCachedWallpapers; i++) {
            if (!arr[i].equals(current)) {
                //noinspection ResultOfMethodCallIgnored
                arr[i].delete();
            }
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void httpDownload(String url, File dest) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
