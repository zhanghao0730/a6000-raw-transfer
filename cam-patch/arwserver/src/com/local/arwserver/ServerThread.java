package com.local.arwserver;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Vector;

public class ServerThread extends Thread {

    // ===== v42：日期锁定 =====
    // 最新日期随便传；更早日期必须输密码解锁（按日期粒度，内存记忆，App 重启清零）。
    // 默认密码 1234，相机面板「设密码」可改（存 SharedPreferences）。
    static volatile String UNLOCK_PASSWORD = "1234";
    static final java.util.HashSet UNLOCKED_DAYS = new java.util.HashSet();
    private static volatile String LATEST_DAY = null;   // 最新日期 yyyy-MM-dd
    private static final java.util.HashMap DAY_MEMO = new java.util.HashMap(); // mtime/60000 → 日期串

    /** mtime → "yyyy-MM-dd"（手拼不走 Locale；分钟桶缓存；与锁检查/分组共用，key 恒一致） */
    static String dayOf(long mtime) {
        Long bucket = Long.valueOf(mtime / 60000L);
        synchronized (DAY_MEMO) {
            String d = (String) DAY_MEMO.get(bucket);
            if (d != null) return d;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(mtime);
            int y = c.get(java.util.Calendar.YEAR), mo = c.get(java.util.Calendar.MONTH) + 1, dd = c.get(java.util.Calendar.DAY_OF_MONTH);
            String ys = y < 10 ? "000" + y : y < 100 ? "00" + y : y < 1000 ? "0" + y : "" + y;
            d = ys + "-" + (mo < 10 ? "0" : "") + mo + "-" + (dd < 10 ? "0" : "") + dd;
            DAY_MEMO.put(bucket, d);
            return d;
        }
    }

    /** 最新日期（惰性，走扫描缓存） */
    static synchronized String latestDay() {
        if (LATEST_DAY == null) {
            Vector all = scanAll();
            if (all.size() > 0) LATEST_DAY = dayOf(((Rec) all.firstElement()).mtime);
        }
        return LATEST_DAY;
    }

    /** 文件是否锁定：非最新日期且未解锁 */
    static boolean locked(File f) {
        String ld = latestDay();
        if (ld == null) return false;                 // 卡里没照片
        String d = dayOf(f.lastModified());
        if (d.equals(ld)) return false;               // 最新日期永远开放
        synchronized (UNLOCKED_DAYS) { return !UNLOCKED_DAYS.contains(d); }
    }
    // ===== v33：单张照片元数据快照 =====
    // 扫描时把 len/mtime 读成纯内存值，之后日期分组/排序/统计全部不再碰 FUSE。
    // 旧实现的黑洞：排序比较器里每次比较调 2 次 lastModified()（FUSE stat），
    // 500 张 ≈ 9000 次 stat，一次几毫秒就是好几秒——这就是进日期页要等的元凶。
    public static final class Rec {
        public final String dir, name;
        public final long len, mtime;
        public final boolean jpg;
        Rec(String dir, String name, long len, long mtime, boolean jpg) {
            this.dir = dir; this.name = name; this.len = len; this.mtime = mtime; this.jpg = jpg;
        }
    }

    /**
     * 扫描 DCIM 全部子目录（静态，供预热线程复用）。缓存按"目录 mtime 验证"：
     * 每个目录每次请求只打 1 发 dir-stat（有变化才 listFiles + 每文件 1 次 stat），
     * 新增/删除照片自动失效。返回 Vector<Rec>，已按 mtime 降序。
     */
    public static Vector scanAll() {
        File[] dirs = root.listFiles();
        Vector all = new Vector();
        if (dirs != null) {
            for (int i = 0; i < dirs.length; i++) {
                if (!dirs[i].isDirectory()) continue;
                String dp = dirs[i].getAbsolutePath();
                long dm = dirs[i].lastModified();
                Vector recs = null;
                synchronized (DIR_MEMO) {
                    Object[] e = (Object[]) DIR_MEMO.get(dp);
                    if (e != null && ((Long) e[0]).longValue() == dm) {
                        recs = (Vector) e[1];
                    }
                }
                if (recs == null) {
                    recs = new Vector();
                    File[] fs = dirs[i].listFiles();
                    if (fs != null) {
                        for (int j = 0; j < fs.length; j++) {
                            String nm = fs[j].getName();
                            if (!Thumbs.isImage(nm) || !fs[j].isFile()) continue;
                            recs.add(new Rec(dirs[i].getName(), nm,
                                    fs[j].length(), fs[j].lastModified(), Thumbs.isJpeg(nm)));
                        }
                    }
                    synchronized (DIR_MEMO) {
                        DIR_MEMO.put(dp, new Object[]{ Long.valueOf(dm), recs });
                    }
                }
                for (int k = 0; k < recs.size(); k++) all.add(recs.elementAt(k));
            }
        }
        java.util.Collections.sort(all, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                long x = ((Rec) a).mtime, y = ((Rec) b).mtime;
                if (x != y) return x > y ? -1 : 1;
                return ((Rec) a).name.compareTo(((Rec) b).name);
            }
        });
        return all;
    }


    public interface Listener {
        void onStatus(final String s);
    }

    private final int port;
    private final Listener listener;
    private volatile ServerSocket ss;
    private volatile boolean done = false;
    private static final File root = new File("/sdcard/DCIM");

    // ===== 相册目录扫描的短期记忆 =====
    // 每次渲染页面都要 listFiles() + 对每个文件 stat(lastModified)；上千个文件走 SD 很贵。
    // 但目录内容短时间内不会变，缓存 5 秒足够 —— 刷新/重开页面就能立刻出图。
    private static final java.util.HashMap DIR_MEMO = new java.util.HashMap(); // v33: dirPath → {dirMtime, Vector<Rec>}

    public ServerThread(int port, Listener l) {
        this.port = port;
        this.listener = l;
    }

    public void run() {
        android.util.Log.i("ARWSRV", "server thread run");
        try {
            ss = new ServerSocket(port, 16, (InetAddress) null);
            android.util.Log.i("ARWSRV", "bound :" + port);
            log("HTTP server on :" + port);
            while (!done) {
                Socket s = ss.accept();
                // 30s：空闲连接及时还给系统（响应里广告的是 Keep-Alive: timeout=20）。
                // SoTimeout 只管"读请求"，不影响长下载（那是往 socket 写 + 读文件）。
                s.setSoTimeout(30000);
                new Worker(s).start();
            }
        } catch (Throwable t) {
            if (!done) log("server stopped: " + t);
        }
        log("server thread exit");
    }

    public void shutdown() {
        done = true;
        try {
            if (ss != null) ss.close();
        } catch (Throwable ignored) {
        }
    }

    private void log(String s) {
        if (listener != null) listener.onStatus(s);
    }

    private class Worker extends Thread {
        private final Socket sock;
        private boolean closeAfter = false;   // 本次响应后必须断开
        private boolean clientClose = false;  // 客户端要求断开
        private int served = 0;

        Worker(Socket s) {
            this.sock = s;
        }

        public void run() {
            BufferedReader r = null;
            try {
                // ★ 一个 BufferedReader 必须贯穿整条连接。它内部会预读，
                //   每个请求重建一个会把"下一个请求的开头字节"吞掉（keep-alive 的经典坑）。
                r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ISO-8859-1"));
                while (!done) {
                    closeAfter = false;
                    clientClose = false;
                    handle(r);
                    if (closeAfter || clientClose) break;
                    if (++served >= 200) break;   // 单连接最多 200 个请求，防赖着不走
                }
                log("conn close after " + served + " req");
            } catch (Throwable t) {
                closeAfter = true;
                try {
                    writeRaw("500 Internal Error", "text/plain", ("err: " + t).getBytes("UTF-8"), null, sock);
                } catch (Throwable ignored) {
                }
            } finally {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }
        }

        /** 连接头：本响应之后要不要复用同一条 TCP 连接 */
        private String connHeader() {
            return (closeAfter || clientClose)
                    ? "Connection: close\r\n"
                    : "Connection: keep-alive\r\nKeep-Alive: timeout=20\r\n";
        }

        private void handle(BufferedReader r) throws Exception {
            String line = r.readLine();
            if (line == null) { closeAfter = true; return; }   // EOF，别死循环
            String[] p = line.split(" ");
            if (p.length < 2) { closeAfter = true; return; }
            String method = p[0];
            String rawPath = p[1];
            // HTTP/1.0 默认短连接，除非它显式要 keep-alive
            if (p.length > 2 && p[2].startsWith("HTTP/1.0")) clientClose = true;
            boolean headOnly = "HEAD".equals(method);
            boolean isPost = "POST".equals(method);
            if (!"GET".equals(method) && !"HEAD".equals(method) && !isPost) {
                closeAfter = true;
                writeRaw("405 Method Not Allowed", "text/plain", "no".getBytes(), null, sock);
                return;
            }
            long rangeStart = -1, rangeEnd = -1;
            int contentLength = 0;
            String soapAction = null;
            boolean wantsHtml = false;
            String h;
            while ((h = r.readLine()) != null && h.length() > 0) {
                String hl = h.toLowerCase();
                if (hl.startsWith("range:")) {
                    long[] rg = parseRange(h.substring(6).trim());
                    if (rg != null) {
                        rangeStart = rg[0];
                        rangeEnd = rg[1];
                    }
                } else if (hl.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(h.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (hl.startsWith("soapaction:")) {
                    soapAction = h.substring(11).trim();
                } else if (hl.startsWith("connection:")) {
                    String v = h.substring(11).toLowerCase();
                    if (v.indexOf("close") >= 0) clientClose = true;
                    else if (v.indexOf("keep-alive") >= 0) clientClose = false;
                } else if (hl.startsWith("accept:") && hl.contains("text/html")) {
                    wantsHtml = true;
                }
            }
            String body = null;
            if (isPost && contentLength > 0) {
                char[] cb = new char[contentLength];
                int got = 0;
                while (got < contentLength) {
                    int n = r.read(cb, got, contentLength - got);
                    if (n < 0) break;
                    got += n;
                }
                byte[] bb = new byte[got];
                for (int i = 0; i < got; i++) bb[i] = (byte) (cb[i] & 0xFF);
                body = new String(bb, 0, got, "UTF-8");
            }

            String query = null;
            int qm = rawPath.indexOf('?');
            if (qm >= 0) {
                query = rawPath.substring(qm + 1);
                rawPath = rawPath.substring(0, qm);
            }
            String path = urlDecode(rawPath);
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            log("req " + method + " " + path + (rangeStart >= 0 ? " range" : ""));

            if ("/desc.xml".equals(path)) {
                writeRaw("200 OK", "text/xml; charset=\"utf-8\"", deviceDesc(sock).getBytes("UTF-8"), null, sock, headOnly);
                return;
            }
            if ("/scpd.xml".equals(path)) {
                writeRaw("200 OK", "text/xml; charset=\"utf-8\"", scpd().getBytes("UTF-8"), null, sock, headOnly);
                return;
            }
            if ("/evt/CD".equals(path)) {
                writeRaw("200 OK", "text/plain", "ok".getBytes(), null, sock);
                return;
            }
            if ("/ctl/CD".equals(path) && body != null) {
                handleSoap(soapAction, body, sock, headOnly);
                return;
            }
            if ("/favicon.ico".equals(path)) {
                writeRaw("204 No Content", "text/plain", new byte[0], null, sock, headOnly);
                return;
            }

            if ("/".equals(path) || "/index".equals(path)) {
                sendGallery(null, wantsHtml, headOnly, query, sock);
                return;
            }
            if (path.startsWith("/d/")) {
                String name = cleanSeg(path.substring(3));
                if (name == null || !new File(root, name).isDirectory()) {
                    writeRaw("404 Not Found", "text/plain", "no dir".getBytes(), null, sock);
                    return;
                }
                sendGallery(name, wantsHtml, headOnly, query, sock);
                return;
            }
            if (path.startsWith("/f/") || path.startsWith("/p/")
                    || path.startsWith("/t/") || path.startsWith("/x/")) {
                char kind = path.charAt(1);
                String rest = path.substring(3);
                int slash = rest.indexOf('/');
                if (slash <= 0) {
                    writeRaw("404 Not Found", "text/plain", "bad path".getBytes(), null, sock);
                    return;
                }
                String dir = cleanSeg(rest.substring(0, slash));
                String name = cleanSeg(rest.substring(slash + 1));
                if (dir == null || name == null) {
                    writeRaw("403 Forbidden", "text/plain", "bad name".getBytes(), null, sock);
                    return;
                }
                File f = new File(new File(root, dir), name);
                if (!f.isFile()) {
                    writeRaw("404 Not Found", "text/plain", "no file".getBytes(), null, sock);
                    return;
                }
                if (locked(f)) {   // v42：最新日期之外的文件先解锁
                    writeRaw("403 Forbidden", "text/plain; charset=utf-8",
                            "该日期未解锁".getBytes("UTF-8"), null, sock);
                    return;
                }
                if (kind == 'f') {
                    sendFile(f, headOnly, rangeStart, rangeEnd, sock);
                } else {
                    // ★ v27：JPEG 的"预览"就是原文件本身——直出字节，零解码零编码。
                    // 旧路径 preview() 要全量解码 10MP+ 再重编码（弱 SoC 上秒级），
                    // 现在浏览器拿原 JPEG 自己硬解，相机只做文件服务器。
                    if (kind == 'p' && Thumbs.isJpeg(name)) {
                        sendJpegDirect(f, headOnly, sock);
                        return;
                    }
                    byte[] img;
                    if (kind == 'x') {
                        // ★ v26：EXIF 小图直出（Imaging Edge 的 getThumbnailBitmap 路线）。
                        // 每张 ~12KB 现成 JPEG，零解码零编码 —— 相机只读字节，浏览器硬解显示。
                        // 拿不到（非常规文件）回落 /t/ 现算，语义兜底。
                        img = Thumbs.exifThumb(f);
                        if (img == null) img = Thumbs.thumbnail(f);
                    } else if (kind == 't') {
                        img = Thumbs.thumbnail(f);
                    } else {
                        // /p/ 优先直出 ARW 里那颗原生预览 JPEG（零解码、零重编码 —
                        // 它本身就有 ~1616x1080，和 preview() 要的 1600px 基本等价）。
                        // 拿不到（JPEG 源 / 图太大）才回落到重编码。
                        img = Thumbs.embeddedFor(f);
                        if (img == null) img = Thumbs.preview(f);
                    }
                    if (img == null) {
                        writeRaw("404 Not Found", "text/plain", "no preview".getBytes(), null, sock);
                        return;
                    }
                    writeRaw("200 OK", "image/jpeg",
                            img, "Cache-Control: max-age=86400\r\n", sock, headOnly);
                }
                return;
            }
            if ("/unlock".equals(path)) {
                // v42：密码解锁某日期（GET 明文，局域网直连可接受）
                String d = null, pw = null;
                if (query != null) {
                    String[] kv = query.split("&");
                    for (int i = 0; i < kv.length; i++) {
                        if (kv[i].startsWith("d=")) d = urlDecode(kv[i].substring(2));
                        else if (kv[i].startsWith("pw=")) pw = urlDecode(kv[i].substring(3));
                    }
                }
                if (d == null || d.length() == 0) {
                    writeRaw("400 Bad Request", "text/plain", "no date".getBytes(), null, sock);
                    return;
                }
                if (UNLOCK_PASSWORD.equals(pw)) {
                    synchronized (UNLOCKED_DAYS) { UNLOCKED_DAYS.add(d); }
                    writeRaw("302 Found", "text/html", new byte[0],
                            "Location: /?date=" + encPath(d) + "\r\n", sock, headOnly);
                } else {
                    sendLockPage(d, true, headOnly, sock);
                }
                return;
            }
            if ("/z".equals(path)) {
                // v30：回退到同步流打包（v29 预打包真机失败），行为与 v28 完全一致
                sendZip(query, sock, headOnly);
                return;
            }
            writeRaw("404 Not Found", "text/plain", "unknown".getBytes(), null, sock);
        }

        // ==================== 相册页面 ====================

        /** 扫描 DCIM 目录（带 5 秒记忆）。listFiles + 每文件 lastModified 在 SD 上很贵。 */

        /**
         * ★ v28：日期分组页（根路径第一呈现）。按拍摄日期（mtime）跨子文件夹聚合，
         * 每个日期一张卡片：日期 / 星期 / 张数 / 总大小 / 最晚一张的时间。
         * 点卡片 → /?date=… → 该日照片缩略图网格。全程零额外 I/O（复用已扫描的列表）。
         */
        private void sendDayIndex(Vector files, boolean headOnly, Socket sock) throws Exception {
            // v42：日期串统一走 dayOf()（与锁检查共用，key 恒一致）
            // v35：相机 Locale 缺中文星期数据时 SimpleDateFormat("E") 会退化成数字（如"6"），
            // 改为 Calendar.DAY_OF_WEEK 自查中文星期表，不依赖设备格式化
            final String[] WEEK_CN = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm");
            java.util.LinkedHashMap days = new java.util.LinkedHashMap();
            for (int i = 0; i < files.size(); i++) {
                Rec f = (Rec) files.get(i);
                String d = dayOf(f.mtime);   // v42：与锁检查共用同一日期函数
                Object[] rec = (Object[]) days.get(d);
                if (rec == null) {
                    rec = new Object[]{ Integer.valueOf(0), Long.valueOf(0), Long.valueOf(f.mtime) };
                    days.put(d, rec);
                }
                rec[0] = Integer.valueOf(((Integer) rec[0]).intValue() + 1);
                rec[1] = Long.valueOf(((Long) rec[1]).longValue() + f.len);
            }

            if (files.size() > 0) LATEST_DAY = dayOf(((Rec) files.firstElement()).mtime);   // v42
            StringBuffer h = new StringBuffer(8192);
            h.append("<!doctype html><html lang='zh'><head><meta charset='utf-8'>")
             .append("<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>")
             .append("<meta name='theme-color' content='#0d1117'>")
             .append("<title>相机相册 · 日期</title><style>").append(CSS)
             .append(".days{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;padding:4px 12px 60px}")
             .append(".daycard{display:block;background:#161b22;border:1px solid #30363d;border-radius:12px;padding:16px 18px;color:#e6edf3;text-decoration:none}")
             .append(".daycard:active{border-color:#19a974}")
             .append(".daycard .dd{font-size:19px;font-weight:700;display:block}")
             .append(".daycard .dw{font-size:12px;color:#19a974;margin-left:8px;font-weight:400}")
             .append(".daycard .ds{font-size:12px;color:#8b949e;display:block;margin-top:8px}")
             .append("</style></head><body>");

            h.append("<header><h1>")
             .append("<svg width='20' height='20' viewBox='0 0 24 24' fill='none' stroke='#19a974' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z'/><circle cx='12' cy='13' r='4'/></svg>")
             .append("相机相册</h1>")
             .append("</header>");

            h.append("<main class='days'>");
            java.util.Iterator it = days.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry en = (java.util.Map.Entry) it.next();
                String d = (String) en.getKey();
                Object[] rec = (Object[]) en.getValue();
                long latestMs = ((Long) rec[2]).longValue();
                java.util.Calendar cc = java.util.Calendar.getInstance();
                cc.setTimeInMillis(latestMs);
                String wk = WEEK_CN[cc.get(java.util.Calendar.DAY_OF_WEEK) - 1];
                java.util.Date latest = new java.util.Date(latestMs);
                h.append("<a class='daycard' href='/?date=").append(encPath(d)).append("'>")
                 .append("<span class='dd'>").append(xmlEsc(d))
                 .append("<span class='dw'>").append(xmlEsc(wk)).append("</span></span>")
                 .append("<span class='ds'>").append(rec[0]).append(" 张 · ")
                 .append(human(((Long) rec[1]).longValue()))
                 .append(" · 最新 ").append(tf.format(latest))
                 .append(d.equals(LATEST_DAY) || UNLOCKED_DAYS.contains(d) ? "" : " · 🔒").append("</span>")
                 .append("</a>");
            }
            h.append("</main>");
            h.append("</body></html>");
            writeRaw("200 OK", "text/html; charset=utf-8",
                    h.toString().getBytes("UTF-8"), "Cache-Control: no-cache\r\n", sock, headOnly);
        }

        /** v42：锁定日期的密码页 */
        private void sendLockPage(String day, boolean wrongPw, boolean headOnly, Socket sock) throws Exception {
            StringBuffer h = new StringBuffer(2048);
            h.append("<!doctype html><html lang='zh'><head><meta charset='utf-8'>")
             .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
             .append("<title>已锁定</title><style>").append(CSS)
             .append(".lockbox{max-width:320px;margin:60px auto;background:#161b22;border:1px solid #30363d;border-radius:12px;padding:24px}")
             .append(".lockbox h2{margin:0 0 6px;font-size:17px}.lockbox .dy{color:#7ee2b8;font-size:13px;margin-bottom:16px}")
             .append(".lockbox input[type=password]{width:100%;padding:10px;font-size:16px;background:#0d1117;border:1px solid #30363d;border-radius:8px;color:#e6edf3}")
             .append(".lockbox input[type=submit]{width:100%;padding:10px;margin-top:10px;font-size:15px;background:#19a974;border:0;border-radius:8px;color:#fff}")
             .append(".err{color:#e5484d;font-size:12px;margin:8px 0}.bk{display:block;margin-top:14px;font-size:12px;color:#8b949e}")
             .append("</style></head><body><div class='lockbox'>")
             .append("<h2>已锁定</h2><div class='dy'>").append(xmlEsc(day)).append("</div>")
             .append(wrongPw ? "<div class='err'>密码错误，请重试</div>" : "")
             .append("<form action='/unlock' method='get'>")
             .append("<input type='hidden' name='d' value='").append(xmlEsc(day)).append("'>")
             .append("<input type='password' name='pw' placeholder='密码' autofocus>")
             .append("<input type='submit' value='解锁'></form>")
             .append("<a class='bk' href='/'>← 返回日期列表</a></div></body></html>");
            writeRaw("200 OK", "text/html; charset=utf-8",
                    h.toString().getBytes("UTF-8"), "Cache-Control: no-cache\r\n", sock, headOnly);
        }

        private void sendGallery(String folderFilter, boolean html, boolean headOnly, String query, Socket sock) throws Exception {
            Vector files = scanAll();          // v33: Vector<Rec>，已按 mtime 降序
            if (folderFilter != null) {
                Vector sub = new Vector();
                for (int i = 0; i < files.size(); i++) {
                    Rec r = (Rec) files.get(i);
                    if (r.dir.equals(folderFilter)) sub.add(r);
                }
                files = sub;
            }

            if (!html) {
                sendPlainListing(folderFilter, files, headOnly, sock);
                return;
            }

            // ★ v28：根路径第一呈现 = 日期分组页；带 date= 参数 → 该日的照片网格。
            // 按拍摄日期（文件 mtime）跨 100MSDCF/101MSDCF 聚合，点进去才是缩略图。
            String day = null;
            if (query != null) {
                String[] kv = query.split("&");
                for (int i = 0; i < kv.length; i++) {
                    if (kv[i].startsWith("date=")) day = urlDecode(kv[i].substring(5));
                }
            }
            if (day == null && folderFilter == null) {
                sendDayIndex(files, headOnly, sock);
                return;
            }
            if (day != null && !day.equals(latestDay()) && !UNLOCKED_DAYS.contains(day)) {
                sendLockPage(day, false, headOnly, sock);   // v42：未解锁 → 密码页
                return;
            }
            if (day != null) {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                Vector dayFiles = new Vector();
                for (int i = 0; i < files.size(); i++) {
                    Rec r = (Rec) files.get(i);
                    if (df.format(new java.util.Date(r.mtime)).equals(day)) dayFiles.add(r);
                }
                files = dayFiles;
            }

            boolean showAll = query != null && query.indexOf("all=1") >= 0;
            int limit = showAll ? files.size() : 80;
            int shown = Math.min(files.size(), limit);

            int nRaw = 0, nJpg = 0;
            long totalBytes = 0;
            for (int i = 0; i < files.size(); i++) {
                Rec r = (Rec) files.get(i);
                totalBytes += r.len;
                if (r.jpg) nJpg++; else nRaw++;
            }

            StringBuffer h = new StringBuffer(16384);   // v41：页面实际 ~9KB，原 196KB 分配白费
            h.append("<!doctype html><html lang='zh'><head><meta charset='utf-8'>")
             .append("<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>")
             .append("<meta name='theme-color' content='#0d1117'>")
             .append("<title>")
             .append(folderFilter == null ? "相机相册" : xmlEsc(folderFilter))
             .append("</title><style>").append(CSS).append("</style></head><body>");

            h.append("<header><h1>")
             .append("<svg width='20' height='20' viewBox='0 0 24 24' fill='none' stroke='#19a974' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z'/><circle cx='12' cy='13' r='4'/></svg>")
             .append("相机相册 <span class='pill'>")
             .append(day != null ? xmlEsc(day) : (folderFilter == null ? "DCIM" : xmlEsc(folderFilter)))
             .append("</span></h1>")
             .append("<div class='stat'>共 ").append(files.size()).append(" 张 · RAW ").append(nRaw)
             .append(" · JPEG ").append(nJpg).append(" · ").append(human(totalBytes))
             .append("</div></header>");

            h.append("<div class='tabs'>")
             .append(day != null ? "<a class='tab' href='/'>← 日期列表</a>" : "")
             .append("<button class='tab on' data-f='all'>全部</button>")
             .append("<button class='tab' data-f='raw'>RAW</button>")
             .append("<button class='tab' data-f='jpg'>JPG</button>")
             .append("<a class='tab' href='/'>刷新</a></div>");

            if (files.isEmpty()) {
                h.append("<div class='empty'>没有照片</div>");
            } else {
                h.append("<main class='grid'>");
                for (int i = 0; i < shown; i++) appendCard(h, (Rec) files.get(i));
                h.append("</main>");
                if (shown < files.size()) {
                    h.append("<div class='more'><a href='")
                     .append(day != null ? "/?date=" + encPath(day) + "&all=1" : "/?all=1")
                     .append("'>显示全部 ")
                     .append(files.size() - shown).append(" 张</a></div>");
                }
            }

            h.append("<div class='dock'>")
             .append("<button class='btn' id='selall'>全选</button>")
             .append("<button class='btn' id='selnone'>清空</button>")
             .append("<span class='cnt' id='cnt'>未选择</span>")
             .append("<button class='btn p' id='dl' disabled>打包下载</button>")
             .append("</div>")
             .append("<script>").append(JS).append("</script></body></html>");

            writeRaw("200 OK", "text/html; charset=utf-8",
                    h.toString().getBytes("UTF-8"), "Cache-Control: no-cache\r\n", sock, headOnly);
        }

        private void appendCard(StringBuffer h, Rec f) {
            String dir = f.dir;
            String name = f.name;
            String rel = dir + "/" + name;
            String encrel = encPath(rel);
            boolean jpg = f.jpg;
            String type = jpg ? "jpg" : "raw";
            h.append("<figure class='card' data-t='").append(type)
             .append("' data-len='").append(f.len).append("' data-mt='").append(f.mtime).append("'>")
             .append("<span class='bdg ").append(type).append("'>").append(jpg ? "JPEG" : "RAW").append("</span>")
             .append("<label class='pick'><input type='checkbox' value='").append(xmlEsc(rel)).append("'></label>")
             .append("<a class='thumb' href='/p/").append(encrel).append("' target='_blank'>")
             .append("<img loading='lazy' src='/x/").append(encrel).append("' alt=''>")
             .append("</a>")
             .append("<figcaption class='cap'>")
             .append("<span class='nm'>").append(xmlEsc(name)).append("</span>")
             .append("<span class='mt'>").append(human(f.len)).append(" · ")
             .append(new SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(f.mtime)))
             .append(" · ").append(xmlEsc(dir)).append("</span>")
             .append("<a class='dl' href='/f/").append(encrel).append("' download>下载</a>")
             .append("</figcaption></figure>");
        }

        private void sendPlainListing(String folderFilter, Vector files, boolean headOnly, Socket sock) throws Exception {
            StringBuffer sb = new StringBuffer();
            sb.append("# ").append(folderFilter == null ? "DCIM root" : folderFilter).append("\n");
            for (int i = 0; i < files.size(); i++) {
                Rec f = (Rec) files.get(i);
                String rel = f.dir + "/" + f.name;
                sb.append("f\t").append(f.name).append("\t").append(f.len)
                  .append("\t/f/").append(rel).append("\n");
            }
            if (folderFilter == null) {
                File[] dirs = root.listFiles();
                if (dirs != null) {
                    java.util.Arrays.sort(dirs);
                    for (int i = 0; i < dirs.length; i++) {
                        if (dirs[i].isDirectory()) {
                            sb.append("d\t").append(dirs[i].getName()).append("\t/d/").append(dirs[i].getName()).append("\n");
                        }
                    }
                }
            }
            writeRaw("200 OK", "text/plain; charset=utf-8", sb.toString().getBytes("UTF-8"), null, sock, headOnly);
        }

        // ==================== 打包下载（v30：回退同步流，v29 预打包真机失败） ====================

        private void sendZip(String query, Socket sock, boolean headOnly) throws Exception {
            closeAfter = true;   // 大文件下载 + HEAD 时会只发头不发体，必须断开
            Vector chosen = new Vector();
            if (query != null) {
                String[] kv = query.split("&");
                for (int i = 0; i < kv.length; i++) {
                    if (!kv[i].startsWith("p=")) continue;
                    String rel = urlDecode(kv[i].substring(2));
                    int slash = rel.indexOf('/');
                    if (slash <= 0) continue;
                    String dir = cleanSeg(rel.substring(0, slash));
                    String name = cleanSeg(rel.substring(slash + 1));
                    if (dir == null || name == null) continue;
                    File f = new File(new File(root, dir), name);
                    if (f.isFile()) chosen.add(f);
                }
            }
            if (chosen.isEmpty()) {
                writeRaw("400 Bad Request", "text/plain; charset=utf-8", "没有选择文件".getBytes("UTF-8"), null, sock);
                return;
            }
            for (int i = 0; i < chosen.size(); i++) {   // v42：含锁定日期的照片整体拒绝
                if (locked((File) chosen.get(i))) {
                    writeRaw("403 Forbidden", "text/plain; charset=utf-8",
                            "所选包含未解锁日期的照片".getBytes("UTF-8"), null, sock);
                    return;
                }
            }

            // 先算好长度和 CRC（STORED 方式，相机端几乎不耗 CPU）
            int n = chosen.size();
            long[] crc = new long[n];
            String[] entryNames = new String[n];
            long total = 22;
            for (int i = 0; i < n; i++) {
                File f = (File) chosen.get(i);
                crc[i] = crcOf(f);
                entryNames[i] = f.getParentFile().getName() + "_" + f.getName();
                int nb = entryNames[i].getBytes("UTF-8").length;
                total += 30 + nb + f.length() + 46 + nb;
            }

            String zipName = "DCIM-" + new SimpleDateFormat("yyyyMMdd-HHmm").format(new java.util.Date()) + ".zip";
            OutputStream out = sock.getOutputStream();
            StringBuffer hdr = new StringBuffer();
            hdr.append("HTTP/1.1 200 OK\r\n")
               .append("Content-Type: application/zip\r\n")
               .append("Content-Disposition: attachment; filename=\"").append(zipName).append("\"\r\n")
               .append("Content-Length: ").append(total).append("\r\n")
               .append("Connection: close\r\n\r\n");
            out.write(hdr.toString().getBytes("ISO-8859-1"));
            out.flush();
            if (headOnly) return;

            java.util.zip.ZipOutputStream zos =
                    new java.util.zip.ZipOutputStream(new BufferedOutputStream(out, 64 * 1024));
            try {
                byte[] buf = new byte[64 * 1024];
                for (int i = 0; i < n; i++) {
                    File f = (File) chosen.get(i);
                    java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(entryNames[i]);
                    e.setMethod(java.util.zip.ZipEntry.STORED);
                    e.setSize(f.length());
                    e.setCompressedSize(f.length());
                    e.setCrc(crc[i]);
                    zos.putNextEntry(e);
                    FileInputStream in = new FileInputStream(f);
                    try {
                        int rd;
                        while ((rd = in.read(buf)) > 0) zos.write(buf, 0, rd);
                    } finally {
                        in.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                zos.finish();
                zos.flush();
            }
        }

        private long crcOf(File f) throws Exception {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int rd;
                while ((rd = in.read(buf)) > 0) c.update(buf, 0, rd);
            } finally {
                in.close();
            }
            return c.getValue();
        }
        // ==================== 下载 / 工具 ====================

        /** ★ v27：JPEG 大图直出 —— 原文件本身就是 JPEG，流式吐出，浏览器硬解显示。
         *  零解码零编码（旧路径 preview() 要全量解码 10MP+ 再重编码）。 */
        private void sendJpegDirect(File f, boolean headOnly, Socket sock) throws Exception {
            OutputStream out = sock.getOutputStream();
            BufferedOutputStream bo = new BufferedOutputStream(out, 32 * 1024);
            StringBuffer hdr = new StringBuffer();
            hdr.append("HTTP/1.1 200 OK\r\n");
            hdr.append("Content-Type: image/jpeg\r\n");
            hdr.append("Content-Length: ").append(f.length()).append("\r\n");
            hdr.append("Cache-Control: max-age=86400\r\n");
            hdr.append("Accept-Ranges: bytes\r\n");
            hdr.append(connHeader()).append("\r\n");
            bo.write(hdr.toString().getBytes("ISO-8859-1"));
            bo.flush();
            if (headOnly) return;
            FileInputStream fin = new FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fin.read(buf)) > 0) bo.write(buf, 0, n);
                bo.flush();
            } finally {
                try { fin.close(); } catch (Throwable ignored) { }
            }
        }

        private void sendFile(File f, boolean headOnly, long rs, long re, Socket sock) throws Exception {
            long len = f.length();
            long start = 0, end = len - 1;
            String status = "200 OK";
            boolean partial = false;
            if (rs >= 0) {
                if (rs >= len) {
                    writeRaw("416 Range Not Satisfiable", "text/plain", "range".getBytes(), null, sock);
                    return;
                }
                start = rs;
                if (re >= 0 && re < len - 1) end = re;
                partial = true;
                status = "206 Partial Content";
            }
            long count = end - start + 1;
            OutputStream out = sock.getOutputStream();
            BufferedOutputStream bo = new BufferedOutputStream(out, 32 * 1024);
            StringBuffer hdr = new StringBuffer();
            hdr.append("HTTP/1.1 ").append(status).append("\r\n");
            hdr.append("Content-Type: application/octet-stream\r\n");
            hdr.append("Content-Disposition: attachment; filename=\"").append(f.getName()).append("\"\r\n");
            hdr.append("Content-Length: ").append(count).append("\r\n");
            hdr.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                hdr.append("Content-Range: bytes ").append(start).append("-").append(end)
                   .append("/").append(len).append("\r\n");
            }
            hdr.append(connHeader()).append("\r\n");
            bo.write(hdr.toString().getBytes("ISO-8859-1"));
            bo.flush();
            if (headOnly) return;
            FileInputStream fin = new FileInputStream(f);
            try {
                long skipped = 0;
                while (skipped < start) {
                    long n = fin.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                while (sent < count) {
                    int n = fin.read(buf, 0, (int) Math.min(buf.length, count - sent));
                    if (n < 0) break;
                    bo.write(buf, 0, n);
                    sent += n;
                }
            } finally {
                fin.close();
            }
            bo.flush();
        }

        private String urlDecode(String s) {
            try {
                return URLDecoder.decode(s, "UTF-8");
            } catch (Throwable t) {
                return s;
            }
        }

        private String encPath(String rel) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < rel.length(); i++) {
                char c = rel.charAt(i);
                if (c == '/') { sb.append('/'); continue; }
                try {
                    sb.append(URLEncoder.encode(String.valueOf(c), "UTF-8"));
                } catch (Throwable t) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private String human(long b) {
            if (b >= 1048576L) return String.format("%.1f MB", b / 1048576.0);
            if (b >= 1024L) return String.format("%.0f KB", b / 1024.0);
            return b + " B";
        }

        private String cleanSeg(String s) {
            if (s == null) return null;
            while (s.startsWith("/")) s = s.substring(1);
            if (s.length() == 0) return null;
            if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) return null;
            if (s.equals(".") || s.equals("..")) return null;
            return s;
        }

        private long[] parseRange(String spec) {
            if (spec == null) return null;
            if (!spec.startsWith("bytes=")) return null;
            String v = spec.substring(6);
            int dash = v.indexOf('-');
            if (dash < 0) return null;
            try {
                long a = Long.parseLong(v.substring(0, dash).trim());
                String b = v.substring(dash + 1).trim();
                if (b.length() == 0) return new long[]{a, -1};
                return new long[]{a, Long.parseLong(b)};
            } catch (NumberFormatException nfe) {
                return null;
            }
        }

        private void writeRaw(String status, String type, byte[] body, String extra, Socket sock) throws Exception {
            writeRaw(status, type, body, extra, sock, false);
        }

        private void writeRaw(String status, String type, byte[] body, String extra, Socket sock, boolean headOnly) throws Exception {
            OutputStream out = sock.getOutputStream();
            StringBuffer hdr = new StringBuffer();
            hdr.append("HTTP/1.1 ").append(status).append("\r\n");
            hdr.append("Content-Type: ").append(type).append("\r\n");
            hdr.append("Content-Length: ").append(body.length).append("\r\n");
            hdr.append(connHeader());
            if (extra != null) hdr.append(extra);
            hdr.append("\r\n");
            out.write(hdr.toString().getBytes("ISO-8859-1"));
            if (!headOnly) out.write(body);
            out.flush();
        }

        // ---------------- UPnP MediaServer ----------------

        private String localIpOf(Socket s) {
            try {
                InetAddress a = s.getLocalAddress();
                if (a != null && !a.isAnyLocalAddress() && !a.isLoopbackAddress()) {
                    return a.getHostAddress();
                }
            } catch (Throwable ignored) {
            }
            Vector ips = UpnpServer.localIps();
            if (!ips.isEmpty()) return (String) ips.firstElement();
            return "10.0.0.1";
        }

        private String deviceDesc(Socket s) {
            String ip = localIpOf(s);
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
                    + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                    + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                    + "<URLBase>http://" + ip + ":8080</URLBase>"
                    + "<device>"
                    + "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>"
                    + "<friendlyName>ILCE-6000 (ARW)</friendlyName>"
                    + "<manufacturer>Sony / ArwServer</manufacturer>"
                    + "<modelName>ILCE-6000</modelName>"
                    + "<UDN>" + UpnpServer.UUID + "</UDN>"
                    + "<serviceList><service>"
                    + "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>"
                    + "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>"
                    + "<SCPDURL>/scpd.xml</SCPDURL>"
                    + "<controlURL>/ctl/CD</controlURL>"
                    + "<eventSubURL>/evt/CD</eventSubURL>"
                    + "</service></serviceList>"
                    + "</device></root>";
        }

        private String scpd() {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                    + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                    + "<actionList>"
                    + "<action><name>Browse</name><argumentList>"
                    + "<argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>"
                    + "<argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>"
                    + "<argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>"
                    + "<argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>"
                    + "<argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>"
                    + "<argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>"
                    + "<argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>"
                    + "<argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>"
                    + "<argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>"
                    + "<argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>"
                    + "</argumentList></action>"
                    + "<action><name>GetSearchCapabilities</name><argumentList>"
                    + "<argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>"
                    + "</argumentList></action>"
                    + "<action><name>GetSortCapabilities</name><argumentList>"
                    + "<argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>"
                    + "</argumentList></action>"
                    + "<action><name>GetSystemUpdateID</name><argumentList>"
                    + "<argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>"
                    + "</argumentList></action>"
                    + "</actionList>"
                    + "<serviceStateTable>"
                    + "<stateVariable sendEvents=\"yes\"><name>SystemUpdateID</name><dataType>ui4</dataType><defaultValue>0</defaultValue></stateVariable>"
                    + "<stateVariable><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable><name>SortCapabilities</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable sendEvents=\"yes\"><name>ContainerUpdateIDs</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>"
                    + "<allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>"
                    + "<stateVariable><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>"
                    + "</serviceStateTable></scpd>";
        }

        private String xmlEsc(String s) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '&') sb.append("&amp;");
                else if (c == '<') sb.append("&lt;");
                else if (c == '>') sb.append("&gt;");
                else if (c == '"') sb.append("&quot;");
                else if (c == '\'') sb.append("&#39;");
                else sb.append(c);
            }
            return sb.toString();
        }

        private String didlItem(String ip, String dir, String name, long size) {
            boolean jpg = Thumbs.isJpeg(name);
            String mime = jpg ? "image/jpeg" : "application/octet-stream";
            String cls = jpg ? "object.item.imageItem.photo" : "object.item.imageItem";
            return "<item id=\"F" + xmlEsc(dir + "/" + name) + "\" parentID=\"D" + xmlEsc(dir) + "\" restricted=\"1\">"
                    + "<dc:title>" + xmlEsc(name) + "</dc:title>"
                    + "<upnp:class>" + cls + "</upnp:class>"
                    + "<res size=\"" + size + "\" protocolInfo=\"http-get:*:" + mime + ":*\">"
                    + "http://" + ip + ":8080/f/" + encPath(dir + "/" + name) + "</res></item>";
        }

        private String didlContainer(String dir, int childCount) {
            return "<container id=\"D" + xmlEsc(dir) + "\" parentID=\"0\" restricted=\"1\" childCount=\"" + childCount + "\">"
                    + "<dc:title>" + xmlEsc(dir) + "</dc:title>"
                    + "<upnp:class>object.container.storageFolder</upnp:class></container>";
        }

        private String browseResult(String objId, boolean metadata, Socket s) {
            String ip = localIpOf(s);
            StringBuffer didl = new StringBuffer();
            didl.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
                .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">");
            if (objId == null || objId.length() == 0 || "0".equals(objId)) {
                File[] fs = root.listFiles();
                Vector dirs = new Vector();
                if (fs != null) {
                    for (int i = 0; i < fs.length; i++) {
                        if (fs[i].isDirectory()) dirs.add(fs[i].getName());
                    }
                }
                java.util.Collections.sort(dirs);
                for (int i = 0; i < dirs.size(); i++) {
                    if (metadata) continue;
                    String dn = (String) dirs.get(i);
                    File d = new File(root, dn);
                    int cc = 0;
                    File[] inner = d.listFiles();
                    if (inner != null) cc = inner.length;
                    didl.append(didlContainer(dn, cc));
                }
            } else if (objId.startsWith("D")) {
                String dir = objId.substring(1);
                File d = new File(root, dir);
                File[] fs = d.listFiles();
                if (fs != null && d.isDirectory()) {
                    Vector names = new Vector();
                    for (int i = 0; i < fs.length; i++) {
                        if (fs[i].isFile()) names.add(fs[i].getName());
                    }
                    java.util.Collections.sort(names);
                    for (int i = 0; i < names.size(); i++) {
                        String n = (String) names.get(i);
                        if (metadata) continue;
                        didl.append(didlItem(ip, dir, n, new File(d, n).length()));
                    }
                }
            } else if (objId.startsWith("F")) {
                String rest = objId.substring(1);
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String dir = rest.substring(0, slash);
                    String name = rest.substring(slash + 1);
                    File f = new File(new File(root, dir), name);
                    if (f.isFile()) didl.append(didlItem(ip, dir, name, f.length()));
                }
            }
            didl.append("</DIDL-Lite>");
            return didl.toString();
        }

        private String soapField(String body, String field) {
            int a = body.indexOf("<" + field + ">");
            if (a < 0) a = body.indexOf("<" + field + " ");
            if (a < 0) return null;
            int gt = body.indexOf('>', a);
            int b = body.indexOf("</" + field + ">", gt);
            if (gt < 0 || b < 0) return "";
            String v = body.substring(gt + 1, b);
            v = v.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                 .replace("&quot;", "\"").replace("&apos;", "'");
            return v.trim();
        }

        private void handleSoap(String soapAction, String body, Socket sock, boolean headOnly) throws Exception {
            String action = "Browse";
            if (soapAction != null) {
                int hash = soapAction.indexOf('#');
                if (hash >= 0) action = soapAction.substring(hash + 1).replace("\"", "").trim();
            }
            log("soap " + action);
            String resp;
            try {
                if ("Browse".equals(action)) {
                    String objId = soapField(body, "ObjectID");
                    if (objId == null) objId = "0";
                    String flag = soapField(body, "BrowseFlag");
                    boolean metadata = "BrowseMetadata".equals(flag);
                    String didl = browseResult(objId, metadata, sock);
                    resp = browseResponseEnvelope(action, xmlEsc(didl), countOf(didl));
                } else if ("Search".equals(action)) {
                    String cont = soapField(body, "ContainerID");
                    if (cont == null) cont = "0";
                    String didl = browseResult(cont, false, sock);
                    resp = browseResponseEnvelope(action, xmlEsc(didl), countOf(didl));
                } else if ("GetSearchCapabilities".equals(action)) {
                    resp = envelope(action, "<SearchCaps></SearchCaps>");
                } else if ("GetSortCapabilities".equals(action)) {
                    resp = envelope(action, "<SortCaps>+dc:title</SortCaps>");
                } else if ("GetSystemUpdateID".equals(action)) {
                    resp = envelope(action, "<Id>1</Id>");
                } else {
                    writeRaw("404 Not Found", "text/plain", "no action".getBytes(), null, sock);
                    return;
                }
            } catch (Throwable t) {
                Log.w("ARWSRV", "soap fail: " + t);
                writeRaw("500 Internal Error", "text/plain", ("err: " + t).getBytes(), null, sock);
                return;
            }
            try {
                writeRaw("200 OK", "text/xml; charset=\"utf-8\"", resp.getBytes("UTF-8"), null, sock, headOnly);
            } catch (Throwable t) {
                Log.w("ARWSRV", "soap write fail: " + t);
            }
        }

        private int countOf(String didl) {
            int n = 0;
            for (int i = didl.indexOf("<item "); i >= 0; i = didl.indexOf("<item ", i + 1)) n++;
            for (int i = didl.indexOf("<container "); i >= 0; i = didl.indexOf("<container ", i + 1)) n++;
            return n;
        }

        private String browseResponseEnvelope(String action, String escapedResult, int count) {
            return envelope(action,
                    "<Result>" + escapedResult + "</Result>"
                    + "<NumberReturned>" + count + "</NumberReturned>"
                    + "<TotalMatches>" + count + "</TotalMatches>"
                    + "<UpdateID>1</UpdateID>");
        }

        private String envelope(String action, String inner) {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:" + action + "Response "
                    + "xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                    + inner + "</u:" + action + "Response></s:Body></s:Envelope>";
        }
    }

    // ==================== 页面样式 / 脚本（内联，手机在相机热点里没有外网） ====================

    private static final String CSS =
        "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
        + "body{margin:0;background:#0d1117;color:#e6edf3;"
        + "font:15px/1.45 -apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif}"
        + "header{padding:14px 14px 4px}"
        + "h1{margin:0;font-size:17px;font-weight:600;display:flex;align-items:center;gap:7px}"
        + "h1 svg{flex:0 0 auto}"
        + ".pill{font-size:11px;font-weight:600;color:#7ee2b8;background:#12321f;border:1px solid #1d4a30;padding:2px 8px;border-radius:999px}"
        + ".stat{margin-top:6px;font-size:12px;color:#7d8b99}"
        + ".tabs{display:flex;gap:8px;padding:11px 14px 9px;overflow-x:auto}"
        + ".tab{flex:0 0 auto;padding:6px 14px;border-radius:999px;background:#161e26;color:#93a4b4;"
        + "font-size:13px;border:1px solid #212c36;text-decoration:none}"
        + ".tab.on{background:#1f6feb;border-color:#1f6feb;color:#fff}"
        + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(146px,1fr));gap:10px;padding:4px 12px 118px}"
        + ".card{position:relative;margin:0;background:#131a21;border:1px solid #1d262f;border-radius:12px;overflow:hidden;padding:0}"
        + ".card.sel{border-color:#19a974;box-shadow:inset 0 0 0 1px #19a974}"
        + ".thumb{display:block;position:relative;padding-top:75%;background:#0a0e12;overflow:hidden}"
        + ".thumb img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover;display:block}"
        + ".bdg{position:absolute;top:6px;left:6px;font-size:10px;font-weight:700;letter-spacing:.5px;"
        + "padding:2px 6px;border-radius:5px;z-index:3}"
        + ".bdg.raw{background:#e5484d;color:#fff}.bdg.jpg{background:#1f6feb;color:#fff}"
        + ".pick{position:absolute;top:2px;right:2px;width:36px;height:36px;display:flex;"
        + "align-items:center;justify-content:center;z-index:3}"
        + ".pick input{width:20px;height:20px;margin:0}"
        + ".cap{padding:7px 8px 9px}"
        + ".nm{display:block;font-size:11.5px;color:#c9d6e2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
        + ".mt{display:block;font-size:10.5px;color:#71818f;margin-top:3px}"
        + ".dl{display:block;margin-top:7px;padding:5px 0;text-align:center;border-radius:7px;"
        + "background:#1b2531;color:#79b8ff;font-size:11.5px;text-decoration:none}"
        + ".empty{padding:60px 20px;text-align:center;color:#5b6b7a;font-size:14px}"
        + ".empty span{font-size:12px;color:#44525f}"
        + ".more{padding:4px 14px 120px;text-align:center}"
        + ".more a{display:inline-block;color:#79b8ff;font-size:13px;text-decoration:none;"
        + "background:#131a21;border:1px solid #1d262f;padding:10px 16px;border-radius:10px}"
        + ".dock{position:fixed;left:0;right:0;bottom:0;display:flex;align-items:center;gap:8px;"
        + "padding:10px 12px;background:rgba(15,20,26,.95);border-top:1px solid #1d262f;z-index:20}"
        + ".btn{flex:0 0 auto;padding:9px 13px;border-radius:9px;border:1px solid #232f3b;"
        + "background:#151d25;color:#c6d4e0;font:inherit;font-size:13px}"
        + ".btn.p{background:#19a974;border-color:#19a974;color:#fff;font-weight:600}"
        + ".btn[disabled]{opacity:.4}"
        + ".dock .cnt{flex:1;text-align:center;font-size:12.5px;color:#93a4b4}"
        + "footer{padding:0 14px 130px;text-align:center;color:#4f5b67;font-size:11px}"
        + "@keyframes fadeUp{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}"
        + "@keyframes rise{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}"
        + "@keyframes pop{0%{transform:scale(.4)}70%{transform:scale(1.15)}100%{transform:scale(1)}}"
        + ".daycard,.card{animation:fadeUp .35s ease both}"
        + ".daycard:active{transform:scale(.97);border-color:#19a974}"
        + ".card:active{transform:scale(.98)}"
        + "header{animation:fadeUp .3s ease both}"
        + ".tabs{animation:fadeUp .35s ease both;animation-delay:.05s}"
        + ".dock{animation:rise .4s ease both}"
        + ".card .pick input{appearance:none;-webkit-appearance:none;border:1.5px solid #4a5a68;border-radius:6px;background:#0d1117;transition:background .15s,border-color .15s}"
        + ".card.sel .pick input{background:#19a974;border-color:#19a974;animation:pop .25s ease}"
        + ".card.sel{background:#122b1d}"
        + ".thumb img{opacity:0;transition:opacity .35s ease}"
        + ".thumb img.ld{opacity:1}"
        + "@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}";

    private static final String JS =
        "(function(){"
        + "function $(i){return document.getElementById(i)}"
        + "var cards=[].slice.call(document.querySelectorAll('.card'));"
        + "var tabs=[].slice.call(document.querySelectorAll('.tab[data-f]'));"
        + "var filter='all';"
        + "function checked(){var r=[];for(var i=0;i<cards.length;i++){var b=cards[i].querySelector('input');"
        + "if(b&&b.checked&&cards[i].style.display!=='none')r.push(b.value)}return r}"
        + "function refresh(){for(var i=0;i<cards.length;i++){var b=cards[i].querySelector('input');"
        + "if(!b)continue;cards[i].className=b.checked?'card sel':'card';}"
        + "var n=checked().length,c=$('cnt');if(c)c.textContent=n?('已选 '+n+' 张'):'未选择';"
        + "var d=$('dl');if(d)d.disabled=(n===0);}"
        + "function applyFilter(){var k=0;for(var i=0;i<cards.length;i++){var t=cards[i].getAttribute('data-t');"
        + "var vis=(filter==='all'||filter===t);cards[i].style.display=vis?'':'none';"
        + "if(vis){cards[i].style.animation='none';void cards[i].offsetWidth;cards[i].style.animation='';"
        + "cards[i].style.animationDelay=Math.min(k*25,350)+'ms';k++;}}refresh();}"
        + "for(var i=0;i<tabs.length;i++)(function(t){t.onclick=function(){filter=t.getAttribute('data-f');"
        + "for(var j=0;j<tabs.length;j++)tabs[j].className=(tabs[j]===t)?'tab on':'tab';"
        + "applyFilter();return false;};})(tabs[i]);"
        + "for(var i=0;i<cards.length;i++)(function(c){var b=c.querySelector('input');if(!b)return;"
        + "b.onchange=function(){refresh();};})(cards[i]);"
        + "var a=$('selall'),n=$('selnone'),d=$('dl');"
        + "if(a)a.onclick=function(){for(var i=0;i<cards.length;i++){if(cards[i].style.display==='none')continue;"
        + "var b=cards[i].querySelector('input');if(b)b.checked=true;}refresh();};"
        + "if(n)n.onclick=function(){for(var i=0;i<cards.length;i++){var b=cards[i].querySelector('input');"
        + "if(b)b.checked=false;}refresh();};"
        + "var CRC_T=null;"
        + "function crcT(){if(CRC_T)return CRC_T;CRC_T=new Int32Array(256);"
        + "for(var n=0;n<256;n++){var c=n;for(var k=0;k<8;k++)c=(c&1)?(0xEDB88320^(c>>>1)):(c>>>1);CRC_T[n]=c;}return CRC_T;}"
        + "function crc32(u8){var t=crcT(),c=-1;for(var i=0;i<u8.length;i++)c=(c>>>8)^t[(c^u8[i])&0xFF];return (c^-1)>>>0;}"
        + "function u16c(c2,v){c2.enqueue(new Uint8Array([v&255,(v>>>8)&255]));}"
        + "function u32c(c2,v){c2.enqueue(new Uint8Array([v&255,(v>>>8)&255,(v>>>16)&255,(v>>>24)&255]));}"
        + "function dosDT(ms){var dt=new Date(ms);return [((dt.getHours()<<11)|(dt.getMinutes()<<5)|(dt.getSeconds()>>1))&65535,"
        + "(((dt.getFullYear()-1980)<<9)|((dt.getMonth()+1)<<5)|dt.getDate())&65535];}"
        + "function utf8(str){try{return new TextEncoder().encode(str)}catch(e){var u=unescape(encodeURIComponent(str)),a=new Uint8Array(u.length);for(var i=0;i<u.length;i++)a[i]=u.charCodeAt(i);return a;}}"
        + "if(d)d.onclick=function(){var sel=[];for(var i=0;i<cards.length;i++){var b=cards[i].querySelector('input');if(b&&b.checked&&cards[i].style.display!=='none')sel.push({rel:b.value,len:+cards[i].getAttribute('data-len'),mt:+cards[i].getAttribute('data-mt')});}if(!sel.length){alert('先勾选要下载的照片');return;}var tot=0;for(var i=0;i<sel.length;i++)tot+=sel[i].len;if(tot>600*1024*1024&&!confirm('所选共 '+Math.round(tot/1048576)+'MB，超过 600MB，建议分批。仍要继续？'))return;d.disabled=true;d.textContent='取回 0/'+sel.length;var zipName='DCIM-'+new Date().toISOString().slice(0,10).replace(/-/g,'')+'.zip';(async function(){var pos=0,entries=[];var stream=new ReadableStream({async pull(ctrl){for(var i=0;i<sel.length;i++){var m=sel[i],seg=m.rel.split('/');for(var j=0;j<seg.length;j++)seg[j]=encodeURIComponent(seg[j]);var url='/f/'+seg.join('/'),resp=null;for(var r=0;r<3;r++){try{resp=await fetch(url);if(resp.ok)break;}catch(e){}if(r<2)await new Promise(function(res){setTimeout(res,800);});}if(!resp||!resp.ok){ctrl.error(new Error('取回失败：'+m.rel));return;}var buf=new Uint8Array(await resp.arrayBuffer());var crc=crc32(buf),nm=utf8(m.rel.split('/').pop()),dd=dosDT(m.mt);entries.push({off:pos,crc:crc,size:buf.length,nm:nm,t:dd[0],d:dd[1]});u32c(ctrl,0x04034b50);u16c(ctrl,20);u16c(ctrl,0x0800);u16c(ctrl,0);u16c(ctrl,dd[0]);u16c(ctrl,dd[1]);u32c(ctrl,crc);u32c(ctrl,buf.length);u32c(ctrl,buf.length);u16c(ctrl,nm.length);u16c(ctrl,0);ctrl.enqueue(nm);ctrl.enqueue(buf);pos+=30+nm.length+buf.length;d.textContent='取回 '+(i+1)+'/'+sel.length+' · '+Math.round(pos/1048576)+'MB';}var cdOff=pos;for(var i=0;i<entries.length;i++){var e2=entries[i];u32c(ctrl,0x02014b50);u16c(ctrl,20);u16c(ctrl,20);u16c(ctrl,0x0800);u16c(ctrl,0);u16c(ctrl,e2.t);u16c(ctrl,e2.d);u32c(ctrl,e2.crc);u32c(ctrl,e2.size);u32c(ctrl,e2.size);u16c(ctrl,e2.nm.length);u16c(ctrl,0);u16c(ctrl,0);u16c(ctrl,0);u32c(ctrl,0);u32c(ctrl,e2.off);ctrl.enqueue(e2.nm);pos+=46+e2.nm.length;}u32c(ctrl,0x06054b50);u16c(ctrl,0);u16c(ctrl,0);u16c(ctrl,entries.length);u16c(ctrl,entries.length);u32c(ctrl,pos-cdOff);u32c(ctrl,cdOff);u16c(ctrl,0);ctrl.close();}});try{var blob=await new Response(stream).blob();var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=zipName;document.body.appendChild(a);a.click();a.remove();d.textContent='完成';setTimeout(function(){d.disabled=false;d.textContent='打包下载';},3000);}catch(e){d.disabled=false;d.textContent='打包下载';alert('打包失败：'+(e.message||e));}})();};"
        + "var an=document.querySelectorAll('.daycard,.card');"
        + "for(var i=0;i<an.length;i++)an[i].style.animationDelay=Math.min(i*30,420)+'ms';"
        + "var ims=document.querySelectorAll('.thumb img');"
        + "for(var i=0;i<ims.length;i++)(function(im){if(im.complete)im.className='ld';else im.onload=function(){im.className='ld';};})(ims[i]);"
        + "refresh();})();";
}
