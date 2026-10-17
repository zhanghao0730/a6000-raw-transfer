package com.sony.scalar.sysutil;

/**
 * 索尼相机私有 API 编译桩（v42：随 APK 打包，运行时由相机系统提供真实实现）。
 * 键码来源：ma1co/OpenMemories-Framework（GitHub，已与 SonyFTP 真机验证交叉核对）。
 * 用法：onKeyDown 里用 event.getScanCode() 匹配这些常量——相机物理键不走标准 KEYCODE。
 */
public class ScalarInput {

    public static final int ISV_KEY_UP = 103;
    public static final int ISV_KEY_LEFT = 105;
    public static final int ISV_KEY_RIGHT = 106;
    public static final int ISV_KEY_DOWN = 108;
    public static final int ISV_KEY_PLAY = 207;
    public static final int ISV_KEY_SK1 = 229;
    public static final int ISV_KEY_ENTER = 232;
    public static final int ISV_KEY_SK2 = 513;
    public static final int ISV_KEY_MENU = 514;
    public static final int ISV_KEY_STASTOP = 515;
    public static final int ISV_KEY_S1_1 = 516;
    public static final int ISV_KEY_S1_2 = 517;
    public static final int ISV_KEY_S2 = 518;
    public static final int ISV_KEY_FINDER = 519;
    public static final int ISV_KEY_FN = 520;
    public static final int ISV_KEY_AEL = 532;
    public static final int ISV_KEY_AFMF = 533;
    public static final int ISV_KEY_DELETE = 595;
    public static final int ISV_KEY_DISP = 608;
    public static final int ISV_KEY_IRIS_DIAL = 621;
    public static final int ISV_KEY_CUSTOM1 = 622;
    public static final int ISV_KEY_CUSTOM2 = 623;
    public static final int ISV_KEY_WIFI = 657;
    public static final int ISV_KEY_CUSTOM3 = 659;
}
