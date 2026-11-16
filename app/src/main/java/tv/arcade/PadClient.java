package tv.arcade;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Connects to the TV Pad phone app (asiangirlzga/tvpad). The phone runs a TCP server on port 8765 and sends one line per
 * key event: "D96" = Android key 96 down, "U96" = key 96 up. When the TV is joined to the phone's hotspot the phone is the
 * TV's gateway, so we try the gateway automatically. A typed IP address overrides that (use it on a normal router).
 */
public class PadClient {
    public static final int PORT = 8765;

    public interface Listener { void onKey(boolean down, int code); void onState(boolean connected); }

    private final Context ctx;
    private final Listener listener;
    private volatile String manualHost = "";
    private volatile boolean running;
    private volatile boolean connected;
    private volatile String status = "Starting...";
    private volatile Socket current;
    private Thread thread;

    public PadClient(Context c, Listener l) { ctx = c.getApplicationContext(); listener = l; }

    public void setManualHost(String h) {
        manualHost = h == null ? "" : h.trim();
        Socket s = current;                       // drop the current link so the loop reconnects to the new address
        if (s != null) { try { s.close(); } catch (IOException ignored) { } }
    }
    public String getManualHost() { return manualHost; }
    public String getStatus() { return status; }
    public boolean isConnected() { return connected; }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "tvpad-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Socket s = current;
        if (s != null) { try { s.close(); } catch (IOException ignored) { } }
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            String host = manualHost.length() > 0 ? manualHost : gateway();
            if (host == null) {
                status = "No network. Join the phone's hotspot or Wi-Fi.";
                sleep(4000);
                continue;
            }
            status = "Looking for the phone at " + host + " ...";
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, PORT), 1500);
                s.setTcpNoDelay(true);
                current = s;
                connected = true;
                status = "Connected to " + host;
                listener.onState(true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"), 256);
                String line;
                while (running && (line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.length() < 2) continue;
                    char t = line.charAt(0);
                    if (t != 'D' && t != 'U') continue;
                    try { listener.onKey(t == 'D', Integer.parseInt(line.substring(1))); } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) {
                // phone not there yet: retry below
            } finally {
                boolean was = connected;
                connected = false;
                current = null;
                try { s.close(); } catch (IOException ignored) { }
                if (was) listener.onState(false);
            }
            if (running) sleep(was2(host) ? 1000 : 3000);
        }
    }

    private boolean was2(String host) { return status.startsWith("Connected"); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    /** Default gateway of the active network, or null. */
    private String gateway() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm == null ? null : cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (RouteInfo r : lp.getRoutes()) {
                        if (r.isDefaultRoute() && r.getGateway() != null && r.getGateway() instanceof java.net.Inet4Address) {
                            return r.getGateway().getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int g = wm == null ? 0 : wm.getDhcpInfo().gateway;
            if (g != 0) {
                return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
            }
        } catch (Exception ignored) { }
        return null;
    }
}
