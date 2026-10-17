package com.local.arwserver;

/** 桌面测试桩：Thumbs 只用到 ArwService.addLog 这一个静态方法。 */
public class ArwService {
    public static void addLog(String s) {
        System.out.println("[panel] " + s);
    }
}
