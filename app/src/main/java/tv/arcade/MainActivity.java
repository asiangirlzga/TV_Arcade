package tv.arcade;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * TV Arcade: a single full-screen WebView that plays the bundled games from assets/www (no server, no network needed),
 * with a built-in TV Pad client so the phone app works as a game controller. Kept deliberately small for 512 MB TVs.
 */
public class MainActivity extends Activity {
    private static final String LOCAL = "file:///android_asset/www/index.html";

    private WebView web;
    private PadClient pad;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastBack;
    private boolean everLoaded;
    private WebView syncWeb;
    private long lastSync, syncBytes;
    private volatile String syncStatus = "Not checked yet";
    private final java.util.Map<String, String> pending = new java.util.HashMap<String, String>();   // slug -> files written this run
    private final Object fileLock = new Object();

    /* Android key code -> {DOM key, DOM code, keyCode}. Matches the TV Pad phone app's buttons. */
    private static String[] map(int a) {
        switch (a) {
            case 19: return new String[]{"ArrowUp", "ArrowUp", "38"};
            case 20: return new String[]{"ArrowDown", "ArrowDown", "40"};
            case 21: return new String[]{"ArrowLeft", "ArrowLeft", "37"};
            case 22: return new String[]{"ArrowRight", "ArrowRight", "39"};
            case 23: case 96: case 66: return new String[]{"Enter", "Enter", "13"};      // OK, A
            case 4: case 97: return new String[]{"Escape", "Escape", "27"};              // Back, B
            case 8: return new String[]{"1", "Digit1", "49"};                            // L1
            case 99: return new String[]{"2", "Digit2", "50"};                           // X
            case 10: return new String[]{"3", "Digit3", "51"};                           // R1
            case 100: return new String[]{"4", "Digit4", "52"};                          // Y
            case 104: return new String[]{"ArrowDown", "ArrowDown", "40"};               // L2 = brake
            case 105: return new String[]{"ArrowUp", "ArrowUp", "38"};                   // R2 = gas
            case 108: return new String[]{"p", "KeyP", "80"};                            // Start
            case 109: return new String[]{"v", "KeyV", "86"};                            // Select = 2D/3D
            default: return null;
        }
    }

    private void sendKey(boolean down, int android) {
        String[] m = map(android);
        if (m == null || web == null) return;
        final String js = "(function(){var e=new KeyboardEvent('" + (down ? "keydown" : "keyup") + "',{key:'" + m[0] + "',code:'" + m[1]
                + "',bubbles:true,cancelable:true});try{Object.defineProperty(e,'keyCode',{get:function(){return " + m[2]
                + "}});Object.defineProperty(e,'which',{get:function(){return " + m[2]
                + "}});}catch(x){}(document.body||document.documentElement).dispatchEvent(e);})()";
        ui.post(new Runnable() { public void run() { if (web != null) web.evaluateJavascript(js, null); } });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("arcade", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b1030"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // best scores and saved builds use localStorage
        s.setMediaPlaybackRequiresUserGesture(false);    // game sounds
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !allowed(r.getUrl().toString()); }
            @Override public void onPageFinished(WebView v, String url) { everLoaded = true; v.requestFocus(); if (isAsset(url) && url.endsWith("/index.html")) startSync(false); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && !isLocal(r.getUrl().toString())) {
                    Toast.makeText(MainActivity.this, "Could not reach the online arcade", Toast.LENGTH_LONG).show();
                    v.loadUrl(LOCAL);
                }
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl(LOCAL);

        pad = new PadClient(this, new PadClient.Listener() {
            public void onKey(boolean down, int code) { sendKey(down, code); }
            public void onState(final boolean connected) {
                ui.post(new Runnable() { public void run() {
                    Toast.makeText(MainActivity.this, connected ? "Phone controller connected" : "Phone controller disconnected", Toast.LENGTH_SHORT).show();
                } });
            }
        });
        pad.setManualHost(prefs.getString("pad_host", ""));
        pad.start();
        startSync(false);
    }

    private boolean isAsset(String u) { return u != null && u.startsWith("file:///android_asset/www/"); }
    private boolean isLocal(String u) { return isAsset(u) || (u != null && u.startsWith("file://" + gamesDir().getAbsolutePath() + "/")); }
    private File gamesDir() { return new File(getFilesDir(), "games"); }
    private File metaFile() { return new File(getFilesDir(), "games.json"); }
    private static final String DEFAULT_ONLINE = "https://berry.gt.tc/";
    private String online() { return prefs.getString("online", DEFAULT_ONLINE); }
    private boolean allowed(String u) {
        if (isLocal(u)) return true;
        String o = online();
        if (o.length() == 0) return false;
        try {
            java.net.URI a = new java.net.URI(u), c = new java.net.URI(o);
            return a.getHost() != null && a.getHost().equalsIgnoreCase(c.getHost());
        } catch (Exception e) { return false; }
    }

    /** The menu is index.html (offline) or the online index.php / folder. */
    private boolean onMenu(String u) {
        if (u == null) return true;
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        return u.endsWith("/index.html") || u.endsWith("/index.php") || u.endsWith("/");
    }

    @Override public void onBackPressed() {
        String u = web == null ? null : web.getUrl();
        if (u != null && !onMenu(u)) { sendKey(true, 4); sendKey(false, 4); return; }     // games decide what Back does
        if (u != null && !isLocal(u)) { web.loadUrl(LOCAL); return; }                      // online menu -> offline menu
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { finish(); return; }
        lastBack = now;
        Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() { super.onResume(); if (web != null) { web.onResume(); web.resumeTimers(); } getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override protected void onPause() { if (web != null) { web.onPause(); web.pauseTimers(); } super.onPause(); }
    @Override protected void onDestroy() {
        if (pad != null) pad.stop();
        if (syncWeb != null) { syncWeb.destroy(); syncWeb = null; }
        if (web != null) { web.removeJavascriptInterface("Android"); web.destroy(); web = null; }
        super.onDestroy();
    }


    /* ------------------------------------------------------------------ downloading games uploaded at the online arcade */
    private static boolean slugOk(String s) { return s != null && s.matches("[a-z0-9][a-z0-9-]{0,40}"); }
    private static boolean relOk(String r) { return r != null && r.matches("[A-Za-z0-9_./-]{1,100}") && !r.contains("..") && !r.startsWith("/"); }

    private JSONArray readMeta() {
        synchronized (fileLock) {
            try {
                File f = metaFile();
                if (!f.exists()) return new JSONArray();
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
                in.close();
                return new JSONArray(new String(b, "UTF-8"));
            } catch (Exception e) { return new JSONArray(); }
        }
    }
    private void writeMeta(JSONArray a) throws IOException {
        synchronized (fileLock) {
            FileOutputStream o = new FileOutputStream(metaFile());
            o.write(a.toString().getBytes("UTF-8")); o.close();
        }
    }
    private static void deleteTree(File f) {
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); }
        f.delete();
    }

    private void startSync(boolean manual) {
        if (syncWeb != null) return;
        long now = System.currentTimeMillis();
        if (!manual && now - lastSync < 10 * 60 * 1000L) return;
        String base = online();
        if (base.length() == 0) { syncStatus = "No online address set"; return; }
        if (!base.endsWith("/")) base = base + "/";
        lastSync = now; syncBytes = 0; pending.clear();
        deleteTree(new File(getFilesDir(), "games_new"));
        syncStatus = "Checking for new games...";
        final WebView w = new WebView(this);
        syncWeb = w;
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        w.addJavascriptInterface(new SyncBridge(), "ArcadeSync");
        final String host;
        String h = null;
        try { h = new java.net.URI(base).getHost(); } catch (Exception ignored) { }
        host = h;
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String rh = r.getUrl().getHost();
                return host == null || rh == null || !rh.equalsIgnoreCase(host);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) endSync("Could not reach the online arcade", false);
            }
        });
        w.loadUrl(base + "sync.php");
        ui.postDelayed(new Runnable() { public void run() { if (syncWeb == w) endSync("Update timed out", false); } }, 120000);
    }

    private void endSync(String status, boolean changed) {
        syncStatus = status;
        deleteTree(new File(getFilesDir(), "games_new"));
        final WebView w = syncWeb; syncWeb = null;
        if (w != null) { w.removeJavascriptInterface("ArcadeSync"); w.stopLoading(); w.destroy(); }
        String u = web == null ? null : web.getUrl();
        if (changed && u != null && isAsset(u) && u.endsWith("/index.html")) web.reload();
    }

    /** Runs on a background thread, called by the sync page. Only the hidden sync browser can reach it. */
    public class SyncBridge {
        @JavascriptInterface public String have() {
            JSONObject o = new JSONObject();
            JSONArray a = readMeta();
            try {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject g = a.getJSONObject(i);
                    if (new File(gamesDir(), g.getString("slug") + "/index.html").exists()) o.put(g.getString("slug"), g.getString("ver"));
                }
            } catch (Exception ignored) { }
            return o.toString();
        }
        @JavascriptInterface public void save(String slug, String rel, String mode, String data) {
            try {
                if (!slugOk(slug) || !relOk(rel) || data == null) return;
                byte[] bytes;
                if ("b64".equals(mode)) bytes = Base64.decode(data, Base64.DEFAULT);
                else {
                    String html = data;
                    if (rel.equals("index.html")) html = html.replace("index.php", "file:///android_asset/www/index.html");
                    bytes = html.getBytes("UTF-8");
                }
                synchronized (fileLock) {
                    syncBytes += bytes.length;
                    if (bytes.length > 4 * 1048576 || syncBytes > 24 * 1048576) return;
                    File f = new File(new File(getFilesDir(), "games_new/" + slug), rel);
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream o = new FileOutputStream(f);
                    o.write(bytes); o.close();
                    pending.put(slug, "1");
                }
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public void commit(final String manifestJson) {
            ui.post(new Runnable() { public void run() {
                try {
                    JSONArray list = new JSONArray(manifestJson), old = readMeta(), out = new JSONArray();
                    java.util.Set<String> keep = new java.util.HashSet<String>();
                    int added = 0; String addedName = "";
                    long nowMs = System.currentTimeMillis();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject g = list.getJSONObject(i);
                        String slug = g.getString("slug");
                        if (!slugOk(slug)) continue;
                        File fresh = new File(getFilesDir(), "games_new/" + slug), dest = new File(gamesDir(), slug);
                        long at = nowMs; boolean existed = false;
                        for (int k = 0; k < old.length(); k++) if (old.getJSONObject(k).getString("slug").equals(slug)) { at = old.getJSONObject(k).optLong("at", nowMs); existed = true; }
                        if (fresh.isDirectory()) {
                            gamesDir().mkdirs();
                            deleteTree(dest);
                            if (!fresh.renameTo(dest)) continue;
                        } else if (!new File(dest, "index.html").exists()) continue;
                        if (!existed) { added++; addedName = g.optString("name", slug); }
                        JSONObject m = new JSONObject();
                        m.put("slug", slug); m.put("name", g.optString("name", slug)); m.put("desc", g.optString("desc", ""));
                        m.put("hint", g.optString("hint", "")); m.put("color", g.optString("color", "#8a7dff")); m.put("ver", g.getString("ver")); m.put("at", at);
                        out.put(m); keep.add(slug);
                    }
                    File[] dirs = gamesDir().listFiles();                         // games removed or hidden online disappear here too
                    if (dirs != null) for (File d : dirs) if (!keep.contains(d.getName())) deleteTree(d);
                    writeMeta(out);
                    boolean changed = added > 0 || out.length() != old.length();
                    if (added == 1) Toast.makeText(MainActivity.this, "New game: " + addedName, Toast.LENGTH_LONG).show();
                    else if (added > 1) Toast.makeText(MainActivity.this, added + " new games added", Toast.LENGTH_LONG).show();
                    endSync(out.length() + " online game" + (out.length() == 1 ? "" : "s") + " on this TV. Updated just now.", changed || added > 0);
                } catch (Exception e) { endSync("Update failed", false); }
            } });
        }
        @JavascriptInterface public void fail(final String msg) {
            ui.post(new Runnable() { public void run() { endSync(msg == null ? "Update failed" : (msg.length() > 120 ? msg.substring(0, 120) : msg), false); } });
        }
    }

    /** Called from the bundled pad.html page. Ignored for any other page. */
    public class Bridge {
        private boolean trusted() {
            final String[] u = new String[1];
            // getUrl() must run on the UI thread; JS calls arrive on a background thread
            final Object lock = new Object();
            synchronized (lock) {
                ui.post(new Runnable() { public void run() { synchronized (lock) { u[0] = web == null ? null : web.getUrl(); lock.notifyAll(); } } });
                try { lock.wait(500); } catch (InterruptedException ignored) { }
            }
            return u[0] != null && isAsset(u[0]);
        }
        @JavascriptInterface public String customGames() {
            JSONArray a = readMeta(), out = new JSONArray();
            try {
                long now = System.currentTimeMillis();
                java.util.List<JSONObject> l = new java.util.ArrayList<JSONObject>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject g = a.getJSONObject(i);
                    File f = new File(gamesDir(), g.getString("slug") + "/index.html");
                    if (!f.exists()) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", g.optString("name")); o.put("desc", g.optString("desc")); o.put("hint", g.optString("hint")); o.put("color", g.optString("color"));
                    o.put("url", "file://" + f.getAbsolutePath()); o.put("at", g.optLong("at")); o.put("isNew", now - g.optLong("at") < 14L * 86400000L);
                    l.add(o);
                }
                java.util.Collections.sort(l, new java.util.Comparator<JSONObject>() { public int compare(JSONObject x, JSONObject y) { return Long.compare(y.optLong("at"), x.optLong("at")); } });
                for (JSONObject o : l) out.put(o);
            } catch (Exception ignored) { }
            return out.toString();
        }
        @JavascriptInterface public String syncStatus() { return syncStatus; }
        @JavascriptInterface public void syncNow() {
            if (!trusted()) return;
            ui.post(new Runnable() { public void run() { startSync(true); } });
        }
        @JavascriptInterface public String padStatus() { return pad.getStatus(); }
        @JavascriptInterface public String padHost() { return pad.getManualHost(); }
        @JavascriptInterface public void padConnect(String ip) {
            if (!trusted()) return;
            if (ip == null || !ip.matches("[0-9a-zA-Z.\\-]{0,60}")) return;
            prefs.edit().putString("pad_host", ip).apply(); pad.setManualHost(ip);
        }
        @JavascriptInterface public String getOnlineUrl() { return online(); }
        @JavascriptInterface public void setOnlineUrl(String u) {
            if (!trusted()) return;
            if (u == null || (u.length() > 0 && !u.matches("https?://[^\\s]{3,200}"))) return;
            prefs.edit().putString("online", u).apply(); lastSync = 0;
        }
        @JavascriptInterface public void openOnline() {
            if (!trusted()) return;
            final String o = online();
            if (o.length() == 0) return;
            ui.post(new Runnable() { public void run() { if (web != null) web.loadUrl(o); } });
        }
    }
}
