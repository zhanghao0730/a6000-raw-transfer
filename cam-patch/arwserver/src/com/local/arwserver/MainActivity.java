package com.local.arwserver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Vector;

/**
 * v18：面板（Activity）存活 = 服务存活，任何退出路径都停掉一切。
 *
 * 本版新增（基于固件逆向结论）：
 *  - 双二维码：① WiFi 连接码（手机扫码自动连热点，免输密码）② 网址码（扫码直开下载页）
 *    QR 生成器 QrCode 为纯 Java 零依赖实现，同平台编译。
 *  - 干净退出：向 DACM 服务上报 AppInfo（官方 BaseApp 同款广播 + DAConnectionManager.finish），
 *    退出后回到取景界面而不是应用菜单。
 *
 * v25 操作逻辑（真机反馈：热点码出来后没法切到网址码；v24 的自动切换按用户要求取消）：
 *  1) ★ 左/右方向键 = 直接切换二维码（竖排布局里这两个键本来没有用）。
 *  2) ★ 屏上按钮文案动态化：「切换 → 网址码 / 切换 → 连接码」，按下前就知道后果。
 *  3) 不做自动切换：显示哪种码完全由用户决定，服务运行期间保持不变。
 */
public class MainActivity extends Activity {

    private static final long REFRESH_MS = 1000;

    private TextView statusLine;
    private TextView logView;
    private TextView qrCaption;
    private ImageView qrView;
    private Button mainBtn;
    private Button qrBtn;
    private ScrollView scroller;
    private String lastQrPayload;   // v34：payload 没变就不重画二维码（省掉每秒一次 QR 编码+Bitmap 分配）
    // v43：软键盘密码编辑（文本框 + 确定/取消按钮）
    private boolean pwMode = false;
    private android.widget.EditText pwEdit;
    private LinearLayout pwBox;
    private Handler ui;
    private Runnable refresher;

    /** 二维码模式：0 = 连热点，1 = 开网页。完全由用户手动切换，不做自动翻转 */
    private int qrMode = 0;

    /** 正在有序退出：抑制刷新、屏蔽重复触发 */
    private volatile boolean exiting = false;

    // ===== 官方 AppInfo 键表（BaseApp 原文）=====
    private static final String[] PULLING_BACK_KEYS_FOR_PLAYBACK = {
            "KEY_S2", "KEY_S1_1", "KEY_S1_2", "KEY_MOVREC", "KEY_MODE_DIAL", "KEY_USB_CONNECT"};
    private static final String[] RESUME_KEYS_FOR_SHOOTING = {
            "KEY_POWER_SLIDE_PON", "KEY_RELEASE_APO", "KEY_PLAY_APO", "KEY_MEDIA_INOUT_APO",
            "KEY_LENS_APO", "KEY_ACCESSORY_APO", "KEY_DEDICATED_APO", "KEY_POWER_APO", "KEY_PLAY_PON"};

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Handler();
        Log.i("ARWSRV", "activity create, running=" + ArwService.running);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(10, 6, 10, 10);
        panel.setBackgroundColor(Color.argb(190, 0, 0, 0));

        TextView title = new TextView(this);
        title.setText("ARW Server v43");
        title.setTextColor(Color.rgb(120, 220, 120));
        title.setTextSize(17);
        panel.addView(title);

        // v31：二维码紧跟标题放最顶 —— 上/下键滚动面板时按钮可以滚出视野，码吃满可用高度
        LinearLayout qrWrap = new LinearLayout(this);
        qrWrap.setOrientation(LinearLayout.VERTICAL);
        qrWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        qrWrap.addView(qrView);
        qrCaption = new TextView(this);
        qrCaption.setTextColor(Color.rgb(255, 240, 150));
        qrCaption.setTextSize(12);
        qrCaption.setGravity(Gravity.CENTER_HORIZONTAL);
        qrWrap.addView(qrCaption);
        panel.addView(qrWrap);

        statusLine = new TextView(this);
        statusLine.setTextColor(Color.WHITE);
        statusLine.setTextSize(13);
        panel.addView(statusLine);

        // v43：软键盘密码编辑框（默认隐藏，「修改密码」按钮显示）
        pwBox = new LinearLayout(this);
        pwBox.setOrientation(LinearLayout.VERTICAL);
        pwBox.setVisibility(View.GONE);
        TextView pwHint = new TextView(this);
        pwHint.setText("输入新密码（数字）：");
        pwHint.setTextColor(Color.rgb(255, 240, 150));
        pwHint.setTextSize(13);
        pwBox.addView(pwHint);
        pwEdit = new android.widget.EditText(this);
        pwEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pwEdit.setTextColor(Color.WHITE);
        pwEdit.setTextSize(16);
        pwBox.addView(pwEdit);
        LinearLayout pwRow = new LinearLayout(this);
        pwRow.setOrientation(LinearLayout.HORIZONTAL);
        pwRow.setGravity(Gravity.CENTER_HORIZONTAL);
        Button pwOk = new Button(this);
        pwOk.setText("确定");
        pwOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { savePw(); }
        });
        Button pwCancel = new Button(this);
        pwCancel.setText("取消");
        pwCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitPwMode(); }
        });
        pwRow.addView(pwOk);
        pwRow.addView(pwCancel);
        pwBox.addView(pwRow);
        panel.addView(pwBox);

        // v28：面板不再放日志区（二维码下的 req 日志滚屏干扰扫码），腾出的空间给二维码。
        // logView 保留对象但不上屏，refresh/exitAll 的 setText 变成无操作。
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(160, 255, 160));
        logView.setTextSize(11);

        mainBtn = new Button(this);
        mainBtn.setTextColor(Color.WHITE);
        mainBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onMainButton();
            }
        });
        panel.addView(mainBtn);

        qrBtn = new Button(this);
        qrBtn.setText("切换二维码");
        qrBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleQr();
            }
        });
        panel.addView(qrBtn);

        // v43：修改密码按钮（点按进入密码设置；中央键长按的入口保留）
        Button pwBtn = new Button(this);
        pwBtn.setText("修改密码");
        pwBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!pwMode) enterPwMode();   // 设置中重复点击忽略（防重置已输入的数字）
            }
        });
        panel.addView(pwBtn);

        Button exitBtn = new Button(this);
        exitBtn.setText("停止并退出");
        exitBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                exitAll();
            }
        });
        panel.addView(exitBtn);

        // v31：面板套 ScrollView —— 上/下方向键 = 滚轮（内容超高时），滚到头/不够高时
        // 交回 super 继续走焦点导航
        scroller = new ScrollView(this);
        scroller.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(scroller, pl);
        setContentView(root);

        mainBtn.requestFocus();
        mainBtn.setFocusableInTouchMode(false);

        // v42：恢复相机端设置的解锁密码
        try {
            android.content.SharedPreferences sp = getSharedPreferences("arwcfg", MODE_PRIVATE);
            String saved = sp.getString("pw", null);
            if (saved != null && saved.length() > 0) ServerThread.UNLOCK_PASSWORD = saved;
        } catch (Throwable t) { Log.w("ARWSRV", "load pw: " + t); }

        if (!ArwService.running) {
            startService(new Intent(this, ArwService.class));
        }
        refresher = new Runnable() {
            public void run() {
                refresh();
            }
        };
        refresh();
    }

    public void onResume() {
        super.onResume();
        notifyAppInfo();   // 官方每次 onResume 重发
    }

    /**
     * 向 DACM 服务上报应用信息。官方每次 onResume 都发，且带 REC/STILL 分类与两组按键表；
     * 参数与官方逐项一致，app 退出后才会回到取景界面而不是应用菜单。
     */
    private void notifyAppInfo() {
        try {
            Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            intent.putExtra("package_name", getComponentName().getPackageName());
            intent.putExtra("class_name", getComponentName().getClassName());
            intent.putExtra("large_category", "CATEGORY_REC");
            intent.putExtra("small_category", "STILL");
            intent.putExtra("pullingback_key", PULLING_BACK_KEYS_FOR_PLAYBACK);
            intent.putExtra("resume_key", RESUME_KEYS_FOR_SHOOTING);
            sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w("ARWSRV", "notifyAppInfo failed: " + t);
        }
    }

    /** 结束本应用的 DA 会话（反射调用，类不存在时静默跳过） */
    private void daFinish() {
        try {
            Class<?> c = Class.forName("android.app.DAConnectionManager");
            Object mgr = c.getConstructor(android.content.Context.class).newInstance(this);
            c.getMethod("finish").invoke(mgr);
        } catch (Throwable t) {
            Log.w("ARWSRV", "daFinish skipped: " + t);
        }
    }

    // 方向键/OK 交给面板 UI；左/右 = 切换二维码；其余任意键 = 退出并停服务
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        int sc = event.getScanCode();
        if (pwMode) return true;   // v43：编辑中忽略物理键，走软键盘

        // 中央键长按 = 进入密码设置（在 onKeyLongPress 里触发）
        if (isCenter(keyCode, sc) && event.getRepeatCount() == 0) {
            event.startTracking();
            return true;
        }
        // MENU 长按 = 退出 App（在 onKeyLongPress 里触发）
        if (isMenu(keyCode, sc) && event.getRepeatCount() == 0) {
            event.startTracking();
            return true;
        }
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                return super.onKeyDown(keyCode, event);
            case android.view.KeyEvent.KEYCODE_DPAD_UP:     // v31：上/下 = 滚轮
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: {
                if (scroller != null && scroller.getChildCount() > 0
                        && scroller.getChildAt(0).getHeight() > scroller.getHeight()) {
                    scroller.scrollBy(0, keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ? 160 : -160);
                    return true;
                }
                return super.onKeyDown(keyCode, event);     // 内容不够滚 → 交回焦点导航
            }
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                toggleQr();
                return true;
            default:
                // v42：未知的索尼专用键（MENU/播放/Fn/AEL 等 scanCode 键）忽略，
                // 不再 exitAll——真机上按这些键误退 App
                return true;
        }
    }

    public boolean onKeyLongPress(int keyCode, android.view.KeyEvent event) {
        int sc = event.getScanCode();
        if (isCenter(keyCode, sc)) { enterPwMode(); return true; }
        if (isMenu(keyCode, sc)) { exitAll(); return true; }
        return super.onKeyLongPress(keyCode, event);
    }

    // ===== v42：按键识别（keyCode 与索尼 scanCode 双判定） =====
    private static boolean isUp(int kc, int sc)    { return kc == android.view.KeyEvent.KEYCODE_DPAD_UP || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_UP; }
    private static boolean isDown(int kc, int sc)  { return kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_DOWN; }
    private static boolean isLeft(int kc, int sc)  { return kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_LEFT; }
    private static boolean isRight(int kc, int sc) { return kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_RIGHT; }
    private static boolean isCenter(int kc, int sc){ return kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER || kc == android.view.KeyEvent.KEYCODE_ENTER || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_ENTER; }
    private static boolean isMenu(int kc, int sc)  { return kc == android.view.KeyEvent.KEYCODE_MENU || sc == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_MENU; }

    // ===== v43：软键盘密码编辑 =====

    private void enterPwMode() {
        pwMode = true;
        qrView.setVisibility(View.GONE);          // 编辑时收起二维码，腾出软键盘空间
        pwBox.setVisibility(View.VISIBLE);
        pwEdit.setText(ServerThread.UNLOCK_PASSWORD);
        statusLine.setText("输入新密码，按「确定」保存");
        pwEdit.requestFocus();
        pwEdit.setSelection(pwEdit.getText().length());
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(pwEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            imm.toggleSoftInput(0, 0);            // 相机 IME 不弹时强制唤起一次
        } catch (Throwable t) { Log.w("ARWSRV", "ime show: " + t); }
    }

    private void savePw() {
        String pw = pwEdit.getText().toString().trim();
        if (pw.length() == 0 || pw.length() > 8) {
            statusLine.setText("密码需 1-8 位数字");
            return;
        }
        for (int i = 0; i < pw.length(); i++) {
            char c = pw.charAt(i);
            if (c < '0' || c > '9') { statusLine.setText("密码只能是数字"); return; }
        }
        ServerThread.UNLOCK_PASSWORD = pw;
        try {
            getSharedPreferences("arwcfg", MODE_PRIVATE).edit().putString("pw", pw).commit();
        } catch (Throwable t) { Log.w("ARWSRV", "save pw: " + t); }
        exitPwMode();
        refresh();
    }

    private void exitPwMode() {
        pwMode = false;
        pwBox.setVisibility(View.GONE);
        qrView.setVisibility(View.VISIBLE);
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(pwEdit.getWindowToken(), 0);
        } catch (Throwable t) { }
        refresh();
    }

    /** 手动切换二维码（屏上按钮 / 左右方向键都会走这里） */
    private void toggleQr() {
        qrMode = (qrMode == 0) ? 1 : 0;
        refresh();
    }

    public void onBackPressed() {
        exitAll();
    }

    /**
     * 有序退出（对齐 SonyFTP {@code runShutdownSequence}）：
     *   ① 停 HTTP/UPnP + 下发关 Direct/WiFi（走 ArwService.onDestroy）
     *   ② 有界等待无线真的落下（awaitDown，最多 6s）
     *   ③ 回 UI 线程上报 DACM + finish
     * 等待必须放后台线程 —— 主线程 sleep 会 ANR。
     * v19 的缺陷：stopNow() 之后立刻 daFinish()，无线还在拆的过程中就告诉相机"应用结束了"。
     */
    private void exitAll() {
        if (exiting) return;
        exiting = true;
        Log.i("ARWSRV", "exitAll: stage1 - stop servers + radios");
        if (ui != null && refresher != null) ui.removeCallbacks(refresher);
        try {
            mainBtn.setEnabled(false);
        } catch (Throwable ignored) {
        }
        statusLine.setText("正在关闭无线…\n等 Direct / WiFi 落下后再退出");
        logView.setText(ArwService.logText());
        new Thread(new Runnable() {
            public void run() {
                boolean down = false;
                try {
                    ArwService.stopNow();     // → onDestroy：停服务 + HotspotController.stop()
                    down = HotspotController.awaitDown(6000);
                } catch (Throwable t) {
                    Log.w("ARWSRV", "exit stage1 failed: " + t);
                }
                Log.i("ARWSRV", "exitAll: stage2 radios_down=" + down);
                ui.post(new Runnable() {
                    public void run() {
                        daFinish();
                        finish();
                    }
                });
            }
        }, "ArwExit").start();
    }

    private void onMainButton() {
        if (ArwService.running) {
            statusLine.setText("正在停止…");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        ArwService.stopNow();
                        HotspotController.awaitDown(6000);
                    } catch (Throwable ignored) {
                    }
                }
            }, "ArwStop").start();
        } else {
            startService(new Intent(this, ArwService.class));
            refresh();
        }
    }

    public void onDestroy() {
        if (ui != null && refresher != null) {
            ui.removeCallbacks(refresher);
        }
        if (ArwService.running) {
            ArwService.stopNow();
        }
        Log.i("ARWSRV", "activity destroy");
        super.onDestroy();
    }

    // ===== 面板刷新 =====

    private void refresh() {
        if (exiting) return;   // 退出流程接管画面，停止每秒刷新链
        if (!ArwService.running) {
            statusLine.setText("STOPPED");
            mainBtn.setText("启动服务");
            qrView.setImageBitmap(null);
            qrCaption.setText("");
            logView.setText(ArwService.logText());
            ui.postDelayed(refresher, REFRESH_MS);
            return;
        }

        String s = "RUNNING :8080 · 解锁密码 " + ServerThread.UNLOCK_PASSWORD + "\n" + ArwService.ipsText();
        if (HotspotController.ssid != null) {
            s = "热点 " + HotspotController.ssid + "\n密码 " + HotspotController.psk + "\n" + s;
        } else {
            s = "热点：" + HotspotController.statusText() + "\n" + s;
        }
        if (HotspotController.staConnected) {
            s = "手机已接入 ✓\n" + s;
        }
        String fatal = HotspotController.fatalText();
        if (fatal != null && fatal.length() > 0) {
            s = "热点失败：" + fatal + "\n" + s;
        }
        // v41：每秒刷新只在文本变化时 setText——TextView.setText 即使文本相同
        // 也会触发 invalidate+relayout，整面板每秒重排是最大的面板侧空转开销
        if (!s.equals(statusLine.getText().toString())) statusLine.setText(s);

        if (!mainBtn.getText().toString().equals("停止服务")) mainBtn.setText("停止服务");
        logView.setText(ArwService.logText());
        String qb = qrMode == 0 ? "切换 → 网址码" : "切换 → 连接码";
        if (!qrBtn.getText().toString().equals(qb)) qrBtn.setText(qb);

        updateQr();
        ui.postDelayed(refresher, REFRESH_MS);
    }

    private String firstIp() {
        try {
            Vector ips = UpnpServer.localIps();
            for (int i = 0; i < ips.size(); i++) {
                Object o = ips.elementAt(i);
                if (o == null) continue;
                String v = o.toString();
                if (v.length() > 6 && !v.startsWith("127.")) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updateQr() {
        try {
            String ssid = HotspotController.ssid;
            String psk = HotspotController.psk;
            String ip = firstIp();

            String payload = null;
            String caption = null;

            if (qrMode == 0) {
                if (ssid != null && psk != null) {
                    payload = "WIFI:T:WPA;S:" + ssid + ";P:" + psk + ";;";
                    caption = "① 扫码连相机热点";
                } else if (ip != null) {
                    payload = "http://" + ip + ":8080/";
                    caption = "热点未就绪，先给网址码";
                } else {
                    caption = "等待网络就绪…";
                }
            } else {
                if (ip != null) {
                    payload = "http://" + ip + ":8080/";
                    caption = "② 扫码打开下载页";
                } else {
                    caption = "等待网络就绪…";
                }
            }

            if (payload == null) {
                qrView.setImageBitmap(null);
                qrCaption.setText(caption == null ? "" : caption);
                lastQrPayload = null;
            } else if (payload.equals(lastQrPayload)) {
                // v34：payload 没变 → 跳过重画，只刷说明文字（原来每秒重编码一次 QR + 分配 Bitmap）
                qrCaption.setText(caption);
            } else {
                // v28：二维码按屏幅放大——模块尺寸 = min(可用宽, 可用高 62%) / 总模块数，
                // 夹在 [4,24]。日志区撤掉后竖向空间全给码，手机更好对焦。
                boolean[][] mods = QrCode.encode(payload);
                int n = mods.length, quiet = 3;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int per = Math.min((dm.widthPixels - 48) / (n + 2 * quiet),
                        (int) (dm.heightPixels * 0.62) / (n + 2 * quiet));
                int scale = Math.max(4, Math.min(per, 24));
                Bitmap bm = renderQr(mods, scale, scale, quiet);
                qrView.setImageBitmap(bm);
                qrView.setMinimumWidth(bm.getWidth());
                qrView.setMinimumHeight(bm.getHeight());
                qrCaption.setText(caption);
                lastQrPayload = payload;
            }
        } catch (Throwable t) {
            Log.w("ARWSRV", "updateQr failed: " + t);
        }
    }

    /** 布尔矩阵 → Bitmap（白底黑块，quiet 为静默区模块数） */
    private Bitmap renderQr(boolean[][] modules, int scaleX, int scaleY, int quiet) {
        int n = modules.length;
        int width = (n + 2 * quiet) * scaleX;
        int height = (n + 2 * quiet) * scaleY;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFFFFFFFF;
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!modules[r][c]) continue;
                int x0 = (c + quiet) * scaleX;
                int y0 = (r + quiet) * scaleY;
                for (int dy = 0; dy < scaleY; dy++) {
                    int row = (y0 + dy) * width;
                    for (int dx = 0; dx < scaleX; dx++) {
                        pixels[row + x0 + dx] = 0xFF000000;
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
