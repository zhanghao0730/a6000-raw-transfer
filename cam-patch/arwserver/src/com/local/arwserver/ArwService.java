package com.local.arwserver;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.util.Vector;

public class ArwService extends Service implements ServerThread.Listener {

    public static volatile boolean running = false;
    private static ArwService instance;
    private static final Object LOG_LOCK = new Object();
    private static Vector logTail = new Vector();
    private ServerThread server;
    private UpnpServer upnp;
    private PowerManager.WakeLock wl;

    public static void addLog(String s) {
        synchronized (LOG_LOCK) {
            logTail.addElement(s);
            while (logTail.size() > 6) {
                logTail.removeElementAt(0);
            }
        }
    }

    public static String logText() {
        StringBuffer sb = new StringBuffer();
        synchronized (LOG_LOCK) {
            for (int i = 0; i < logTail.size(); i++) {
                sb.append(logTail.elementAt(i)).append('\n');
            }
        }
        return sb.toString();
    }

    public static String ipsText() {
        Vector ips = UpnpServer.localIps();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(ips.elementAt(i)).append(":8080");
        }
        return sb.toString();
    }

    public static void stopNow() {
        ArwService self = instance;
        if (self != null) {
            self.stopSelf();
        }
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        Log.i("ARWSRV", "service create");
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            // 仅保 CPU：不强制亮屏（强亮会让原生层滞留应用模式，黑屏）
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arwserver");
            wl.acquire();
        } catch (Throwable ignored) {
        }
        server = new ServerThread(8080, this);
        server.start();
        upnp = new UpnpServer();
        upnp.start();
        // 预热最新的 24 张缩略图（低优先级、延迟 8s 起跑，别和热点拉起抢 CPU）。
        // 这样手机连上热点打开网页时，/t/ 大多直接命中磁盘缓存，页面立刻出图。
        Thumbs.warmup(new java.io.File("/sdcard/DCIM"), 24, 8000);
        String hsErr = HotspotController.start(this);
        if (hsErr != null) {
            Log.w("ARWSRV", "hotspot start failed: " + hsErr);
            addLog("热点启动失败: " + hsErr);
        } else {
            addLog("热点请求已发出");
        }
    }

    public void onStart(Intent intent, int startId) {
        try {
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, new Intent(this, MainActivity.class), 0);
            Notification n = new Notification(
                    getApplicationInfo().icon, "ARW Server", System.currentTimeMillis());
            n.setLatestEventInfo(this, "ARW Server", "serving DCIM on :8080", pi);
            n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
            startForeground(1, n);
        } catch (Throwable t) {
            Log.w("ARWSRV", "startForeground failed: " + t);
        }
    }

    public void onDestroy() {
        Log.i("ARWSRV", "service destroy");
        running = false;
        instance = null;
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (upnp != null) {
            upnp.shutdown();
            upnp = null;
        }
        HotspotController.stop();
        if (wl != null) {
            try {
                wl.release();
            } catch (Throwable ignored) {
            }
            wl = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    public void onStatus(final String s) {
        Log.i("ARWSRV", s);
        addLog(s);
    }
}
