package com.local.arwserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * v17: 完整移植 SonyFTP / 官方智能遥控 的热点启动状态机（RadioWrapperGb + MainActivity）。
 *
 * 时序（逐拍照抄，缺一拍就被固件拒）：
 *   1) setWifiEnabled(false)            先关 STA WiFi（STA 与 GO 共用一张网卡，互斥）
 *   2) 等 WIFI_STATE_DISABLED 广播
 *   3) 延迟 5s → setWifiEnabled(true)
 *   4) 等 WIFI_STATE_ENABLED 广播
 *   5) setDirectEnabled(true)            同步返回 false 则 5s 后重发一次
 *   6) 等 DIRECT_STATE_CHANGED 广播 native=4(ENABLED)  —— 必须等，不能抢跑
 *   7) getConfigurations() 末项取 networkId → startGo(netId)   失败重试一次
 *   8) 等 GROUP_CREATE_SUCCESS 广播 → 读 SSID/PreSharedKey
 *
 * v16 教训：STA 没关就 startGo、不等 ENABLED 广播就 startGo —— 两处都会让固件直接拒。
 */
public class HotspotController {

    public static volatile boolean apRequested = false;
    public static volatile String ssid = null;
    public static volatile String psk = null;
    /** 手机是否已接入本热点（STA_CONNECTED 广播）；用来在面板上给用户明确反馈 */
    public static volatile boolean staConnected = false;

    private static Context ctx;
    private static WifiManager wifiManager;
    private static Object dm;                       // Sony DirectManager（"wifi-direct"）
    private static BroadcastReceiver receiver;      // 保留引用：stop() 要能注销
    private static boolean receiverRegistered = false;
    private static Handler h = new Handler();

    private static volatile String phase = "空闲";
    private static volatile String fatalReason = null;
    private static int watchdogRecoveries = 0;

    // ===== 官方常量（SonyFTP MainActivity 同款） =====
    private static final long DELAY_WIFI_ENABLE_MS = 5000;    // WiFi 关净后再等 5s 重开
    private static final long DIRECT_ENABLE_RETRY_MS = 5000;  // Direct 使能未应答重发间隔
    private static final long WATCHDOG_MS = 30000;            // 整链看门狗
    private static final int NET_ID_PERSISTENT_GO = -2;

    // 原生 DirectManager 状态值（stub 与运行时一致）
    private static final int NATIVE_DIRECT_DISABLED = 1;
    private static final int NATIVE_DIRECT_ENABLED = 4;

    // 原生广播 action
    private static final String A_DIRECT_STATE_CHANGED = "com.sony.wifi.direct.DIRECT_STATE_CHANGED_ACTION";
    private static final String A_GROUP_CREATE_SUCCESS = "com.sony.wifi.direct.GROUP_CREATE_SUCCESS_ACTION";
    private static final String A_GROUP_CREATE_FAILURE = "com.sony.wifi.direct.GROUP_CREATE_FAILURE_ACTION";
    private static final String A_GROUP_TERMINATE = "com.sony.wifi.direct.GROUP_TERMINATE_ACTION";
    // SonyFTP RadioWrapperGb / DirectManager 桩里同款的 STA 事件（用来告诉用户"手机接上了"）
    private static final String A_STA_CONNECTED = "com.sony.wifi.direct.STA_CONNECTED_ACTION";
    private static final String A_STA_DISCONNECTED = "com.sony.wifi.direct.STA_DISCONNECTED_ACTION";

    // ===== 官方标志位 =====
    private static boolean isInitialDisabling;
    private static boolean isDisableActionFiltered;
    private static boolean isEnableActionFiltered;
    private static boolean isGroupCreateActionFiltered;
    private static boolean isRetrying;
    private static boolean isDirectEnableRetrying;

    public static String statusText() { return phase; }
    public static String fatalText() { return fatalReason; }
    public static boolean isUp() { return ssid != null; }

    // ================= 启动 =================

    public static synchronized String start(Context c) {
        ctx = c.getApplicationContext();
        try {
            wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            wifiManager = null;
        }
        try {
            dm = ctx.getSystemService("wifi-direct");
        } catch (Throwable t) {
            dm = null;
        }
        if (dm == null) {
            phase = "失败";
            fatalReason = "wifi-direct 服务不存在";
            return fatalReason;
        }

        registerReceiver();
        initFlags();
        apRequested = true;
        fatalReason = null;
        watchdogRecoveries = 0;
        phase = "启动中";

        int ws = WifiManager.WIFI_STATE_UNKNOWN;
        try {
            ws = wifiManager.getWifiState();
        } catch (Throwable ignored) {
        }

        if (ws != WifiManager.WIFI_STATE_DISABLED) {
            // 官方路径：先关 STA
            isDisableActionFiltered = false;
            isInitialDisabling = true;
            phase = "关闭 STA WiFi";
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
                addLog("setWifiEnabled(false) 异常: " + t);
            }
        } else {
            isEnableActionFiltered = false;
            phase = "开启 WiFi";
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Throwable t) {
                addLog("setWifiEnabled(true) 异常: " + t);
            }
        }

        h.removeCallbacks(watchdogRun);
        h.postDelayed(watchdogRun, WATCHDOG_MS);
        addLog("热点流程启动（wifiState=" + ws + "）");
        return null;
    }

    private static void initFlags() {
        isInitialDisabling = false;
        isDisableActionFiltered = true;
        isEnableActionFiltered = true;
        isGroupCreateActionFiltered = true;
        isRetrying = false;
        isDirectEnableRetrying = false;
    }

    // ================= 相位动作 =================

    private static final Runnable delayedWifiEnabler = new Runnable() {
        public void run() {
            if (!apRequested) return;
            phase = "开启 WiFi";
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Throwable t) {
                addLog("重开 WiFi 异常: " + t);
            }
        }
    };

    private static final Runnable directEnableRetryRun = new Runnable() {
        public void run() {
            if (!apRequested || ssid != null) return;
            if (isDirectStateEnabled()) return;   // 慢应答已到
            Boolean acked = invokeBool("setDirectEnabled", new Class[]{boolean.class}, Boolean.TRUE);
            addLog("重发 setDirectEnabled=" + acked);
            if (acked != null && acked.booleanValue()) {
                phase = "Direct 使能重发已受理";
            } else {
                goFatal("无法启用 Direct 模式");
            }
        }
    };

    private static final Runnable watchdogRun = new Runnable() {
        public void run() {
            if (!apRequested || ssid != null) return;
            if (watchdogRecoveries < 1) {
                watchdogRecoveries++;
                addLog("看门狗：启动卡住，重跑一次");
                restartCycle();
            } else {
                goFatal("启动超时");
            }
        }
    };

    private static final Runnable fatalCheckRun = new Runnable() {
        public void run() {
            if (!apRequested || ssid != null) return;
            if (!isDirectStateEnabled()) {
                addLog("Direct 未能进入 ENABLED，当前 phase=" + phase);
            }
        }
    };

    /** 一次完整重启：清标志 → 关 STA → 等广播（给看门狗用） */
    private static synchronized void restartCycle() {
        initFlags();
        isDisableActionFiltered = false;
        isInitialDisabling = true;
        phase = "重启无线（关 STA）";
        try {
            wifiManager.setWifiEnabled(false);
        } catch (Throwable ignored) {
        }
        h.removeCallbacks(watchdogRun);
        h.postDelayed(watchdogRun, WATCHDOG_MS);
    }

    private static void issueDirectEnable() {
        Boolean acked = invokeBool("setDirectEnabled", new Class[]{boolean.class}, Boolean.TRUE);
        addLog("setDirectEnabled=" + acked);
        Log.i("ARWSRV", "setDirectEnabled=" + acked);
        if (acked != null && acked.booleanValue()) {
            phase = "Direct 使能已受理，等待 ENABLED 广播";
            return;
        }
        if (!isDirectEnableRetrying) {
            isDirectEnableRetrying = true;
            phase = "Direct 未应答，5s 后重发";
            h.postDelayed(directEnableRetryRun, DIRECT_ENABLE_RETRY_MS);
        } else {
            goFatal("无法启用 Direct 模式");
        }
    }

    /** 第 7 步：取持久组 networkId 后 startGo */
    private static void startGroupOwner() {
        isGroupCreateActionFiltered = false;
        int netId = NET_ID_PERSISTENT_GO;
        try {
            List list = (List) invoke("getConfigurations", new Class[]{});
            if (list != null && !list.isEmpty()) {
                Object cfg = list.get(list.size() - 1);
                Object id = cfg.getClass().getMethod("getNetworkId").invoke(cfg);
                if (id instanceof Integer) {
                    netId = ((Integer) id).intValue();
                }
            }
        } catch (Throwable t) {
            addLog("getConfigurations 异常: " + t);
        }
        phase = "创建热点（netId=" + netId + "）";
        Boolean go = invokeBool("startGo", new Class[]{int.class}, Integer.valueOf(netId));
        addLog("startGo(" + netId + ")=" + go);
        Log.i("ARWSRV", "startGo(" + netId + ")=" + go);
        if (go == null || !go.booleanValue()) {
            handleGroupCreateFailure(-3);
        }
    }

    private static void handleGroupCreateFailure(int err) {
        if (isGroupCreateActionFiltered) {
            // 非本次流程的失败广播，忽略
        } else if (isRetrying) {
            goFatal("热点创建失败(" + err + ")");
        } else {
            isRetrying = true;
            addLog("热点创建失败(" + err + ")，重试一次");
            startGroupOwner();
        }
    }

    private static void goFatal(String reason) {
        phase = "失败";
        fatalReason = reason;
        addLog("热点致命: " + reason);
        Log.w("ARWSRV", "hotspot fatal: " + reason);
        h.removeCallbacks(watchdogRun);
        h.removeCallbacks(directEnableRetryRun);
        // 安静回滚，不留半开状态
        new Thread(new Runnable() {
            public void run() {
                try {
                    invokeBool("setDirectEnabled", new Class[]{boolean.class}, Boolean.FALSE);
                } catch (Throwable ignored) {
                }
                try {
                    wifiManager.setWifiEnabled(false);
                } catch (Throwable ignored) {
                }
            }
        }, "HotspotRollback").start();
    }

    // ================= 停止 =================

    /**
     * 关无线 + 注销接收器。**非阻塞**：只下发命令，不等无线落下。
     * 需要"确认关干净再退出"的调用方，请在后台线程里跟一次 {@link #awaitDown(long)}。
     */
    public static synchronized String stop() {
        apRequested = false;
        h.removeCallbacks(watchdogRun);
        h.removeCallbacks(directEnableRetryRun);
        h.removeCallbacks(fatalCheckRun);
        h.removeCallbacks(delayedWifiEnabler);
        // 顺序与 SonyFTP runShutdownSequence 一致：先关 Direct，再关 WiFi 总开关
        try {
            if (dm != null) {
                invoke("setDirectEnabled", new Class[]{boolean.class}, Boolean.FALSE);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (wifiManager != null) {
                wifiManager.setWifiEnabled(false);
            }
        } catch (Throwable ignored) {
        }
        unregisterReceiver();
        initFlags();                 // 置回"过滤一切广播"，避免停机期的迟到广播被当成新流程
        ssid = null;
        psk = null;
        staConnected = false;
        phase = "空闲";
        return null;
    }

    // ================= 广播 =================

    private static void registerReceiver() {
        if (receiverRegistered || ctx == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(A_DIRECT_STATE_CHANGED);
        f.addAction(A_GROUP_CREATE_SUCCESS);
        f.addAction(A_GROUP_CREATE_FAILURE);
        f.addAction(A_GROUP_TERMINATE);
        f.addAction(A_STA_CONNECTED);
        f.addAction(A_STA_DISCONNECTED);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                try {
                    handleEvent(intent);
                } catch (Throwable t) {
                    Log.w("ARWSRV", "receiver err: " + t);
                }
            }
        };
        ctx.registerReceiver(receiver, f);
        receiverRegistered = true;
    }

    /** 注销广播接收器。stop() 调用；再 start() 会重新注册。 */
    private static synchronized void unregisterReceiver() {
        if (!receiverRegistered || ctx == null || receiver == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
        receiverRegistered = false;
    }

    private static void handleEvent(Intent intent) {
        String a = intent.getAction();
        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(a)) {
            int st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
            onWifiStateChanged(st);
        } else if (A_DIRECT_STATE_CHANGED.equals(a)) {
            int st = intent.getIntExtra("direct_state", -1);
            onDirectStateChanged(st);
        } else if (A_GROUP_CREATE_SUCCESS.equals(a)) {
            onGroupCreateSuccess(intent);
        } else if (A_GROUP_CREATE_FAILURE.equals(a)) {
            int err = intent.getIntExtra("direct_state", -1);
            addLog("收到组创建失败广播");
            handleGroupCreateFailure(err);
        } else if (A_GROUP_TERMINATE.equals(a)) {
            addLog("热点已被系统关闭");
            ssid = null;
            psk = null;
            staConnected = false;
        } else if (A_STA_CONNECTED.equals(a)) {
            staConnected = true;
            addLog("手机已接入热点");
            Log.i("ARWSRV", "STA connected");
        } else if (A_STA_DISCONNECTED.equals(a)) {
            staConnected = false;
            addLog("手机已断开热点");
            Log.i("ARWSRV", "STA disconnected");
        }
    }

    private static void onWifiStateChanged(int st) {
        switch (st) {
            case WifiManager.WIFI_STATE_DISABLED:
                if (isDisableActionFiltered) {
                    return;
                }
                if (isInitialDisabling) {
                    isInitialDisabling = false;
                    isEnableActionFiltered = false;
                    phase = "WiFi 已关，5s 后重开";
                    addLog("STA WiFi 已关闭，5s 后重开");
                    h.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                }
                return;
            case WifiManager.WIFI_STATE_ENABLING:
                isDisableActionFiltered = false;
                return;
            case WifiManager.WIFI_STATE_ENABLED:
                if (isEnableActionFiltered) {
                    return;
                }
                isRetrying = false;
                phase = "使能 Direct";
                addLog("WiFi 已开启，使能 Direct");
                issueDirectEnable();
                return;
            case WifiManager.WIFI_STATE_UNKNOWN:
                if (isDisableActionFiltered) {
                    return;
                }
                if (isRetrying) {
                    h.postDelayed(fatalCheckRun, DELAY_WIFI_ENABLE_MS);
                    return;
                }
                isRetrying = true;
                h.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                return;
            default:
                return;
        }
    }

    private static void onDirectStateChanged(int st) {
        if (st == NATIVE_DIRECT_ENABLED) {
            isRetrying = false;
            isDirectEnableRetrying = false;
            h.removeCallbacks(directEnableRetryRun);
            if (isEnableActionFiltered) {
                return;
            }
            phase = "Direct 已启用";
            addLog("Direct = ENABLED，开始创建组");
            startGroupOwner();
            return;
        }
        if (st == NATIVE_DIRECT_DISABLED) {
            if (isInitialDisabling) {
                return;   // 初次复位阶段的关闭属正常
            }
            addLog("Direct 处于 DISABLED（phase=" + phase + "）");
        }
    }

    private static void onGroupCreateSuccess(Intent intent) {
        isRetrying = false;
        if (isGroupCreateActionFiltered) {
            return;
        }
        h.removeCallbacks(watchdogRun);
        h.removeCallbacks(directEnableRetryRun);

        // ① 首选：原生广播自带的 direct_config（DirectConfiguration Parcelable，
        //    已由 framework.odex 里的 DirectManager 证实会带；Sony 官方 WifiP2pManagerGb
        //    也是 getParcelableExtra("direct_config") 直接强转用它）
        Object cfg = null;
        try {
            cfg = intent.getParcelableExtra("direct_config");
        } catch (Throwable t) {
            // 跨进程 Parcelable 反序列化失败必须能降级，不能把整段 try 一起带走
            addLog("direct_config 读取失败: " + t);
        }
        // ② 回落：getConfigurations() 末项（= SonyFTP RadioWrapperGb.getLiveGroup()）
        if (cfg == null) {
            try {
                List list = (List) invoke("getConfigurations", new Class[]{});
                if (list != null && !list.isEmpty()) {
                    cfg = list.get(list.size() - 1);
                }
            } catch (Throwable t) {
                addLog("getConfigurations 回落失败: " + t);
            }
        }
        if (cfg != null) {
            try {
                Object s = cfg.getClass().getMethod("getSsid").invoke(cfg);
                Object p = cfg.getClass().getMethod("getPreSharedKey").invoke(cfg);
                if (s instanceof String) ssid = (String) s;
                if (p instanceof String) psk = (String) p;
            } catch (Throwable t) {
                addLog("读热点配置异常: " + t);
            }
        }

        phase = ssid != null ? ("热点已开: " + ssid) : "热点已开";
        addLog(ssid != null ? ("热点已开 " + ssid + " / " + psk) : "热点已开（无配置信息）");
        Log.i("ARWSRV", "AP up: " + ssid + " psk=" + psk);
    }

    /**
     * 有界等待无线真正落下（SonyFTP {@code radiosConfirmedDown()} 的等价物）。
     * 判定：WiFi 总开关 = DISABLED 且 Direct 不再是 ENABLED(4)。
     * ⚠ 会 sleep —— 只能在后台线程调用，绝不能在主线程/UI 线程调。
     */
    public static boolean awaitDown(long maxMs) {
        if (wifiManager == null) return true;   // 从没走过热点流程：没东西可等，别白等 6 秒
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            boolean wifiDown;
            try {
                wifiDown = wifiManager != null
                        && wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED;
            } catch (Throwable t) {
                wifiDown = false;
            }
            if (wifiDown && !isDirectStateEnabled()) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    // ================= 反射工具 =================

    private static Object invoke(String name, Class[] sig, Object... args) throws Exception {
        Method m = dm.getClass().getMethod(name, sig);
        return m.invoke(dm, args);
    }

    private static Boolean invokeBool(String name, Class[] sig, Object... args) {
        try {
            Object r = invoke(name, sig, args);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable t) {
            Log.w("ARWSRV", name + " failed: " + t);
            addLog(name + " 异常: " + t);
            return null;
        }
    }

    private static boolean isDirectStateEnabled() {
        try {
            Object st = invoke("getDirectState", new Class[]{});
            return (st instanceof Integer) && ((Integer) st).intValue() == NATIVE_DIRECT_ENABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void addLog(String s) {
        ArwService.addLog(s);
    }
}
