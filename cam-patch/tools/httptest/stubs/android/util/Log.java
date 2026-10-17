package android.util;

/** 桌面测试桩：把 android.util.Log 变成 stdout，好让真实 ServerThread 能在 PC 上跑。 */
public class Log {
    private static String tag(Object t) { return String.valueOf(t); }

    public static int i(String tag, String msg) { System.out.println("[I/" + tag + "] " + msg); return 0; }
    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { System.out.println("[W/" + tag + "] " + msg); return 0; }
    public static int e(String tag, String msg) { System.out.println("[E/" + tag + "] " + msg); return 0; }
}
