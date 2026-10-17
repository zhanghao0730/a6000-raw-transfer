import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HTTP 分帧 + 下载链路验证器（桌面）。
 *
 * 目的：把 **真实的** com.local.arwserver.ServerThread / Thumbs 编出来、当 HTTP 服务跑，
 * 然后用原始 socket 逐字节检查响应，验证 v21/v22 的改动是真的对。
 *
 * 覆盖：
 *   T1  keep-alive：一条 TCP 连接连打 5 个请求（核心改动）
 *   T2  pipeline：两个请求写在同一个 write() 里（预读吞字节的最强检验）
 *   T3  客户端 Connection: close / T4 HTTP/1.0 默认短连接
 *   T5  HEAD 不发体、之后连接不串帧
 *   T6  POST（SOAP）体被精确消费
 *   T7  /z 必须收掉连接
 *   T8  单连接 200 请求上限
 *   T9  /f/ 25MB 原文件下载：字节级 sha256 + 下载后连接仍可复用
 *   T10 Range / 206 / 416
 *   T11 /p/ ARW 原生预览直出：真实抽取器的 sha256 与独立实现对照
 *   T12 /t/ 缩略图分支（桩不解码 → 404）不破连接
 *   T13 缓存键：同名同长同 mtime、不同目录的两个 ARW 必须产生 2 份缓存（v21 修的 bug）
 *   T14 /z 打包：zip 结构 + 条目内容 sha256 + Content-Length 精确性（v30 回退同步流）
 *   T15 相册首页 / 目录过滤 / 路径穿越
 *   T16 /x/ EXIF 小图直出（v26）：12054B、合法 JPEG、磁盘缓存命中、JPEG 源回落
 *
 * 夹具：ServerThread 的 root 硬编码 /sdcard/DCIM。Windows 上 "/sdcard" 是盘符相对路径，
 * 只会继承 user.dir 的盘符，所以 run.sh 用 subst 造了个虚拟盘，夹具落在 <工作区>/_drive/sdcard/DCIM。
 *
 * 运行：bash run.sh
 * 产出：httptest-report.txt（UTF-8 全文报告）
 */
public class HttpFramingTest {

    static final int PORT = 18080;

    // ===== 夹具的已知真值（由独立实现算出，见 run.sh / mkfixture.py）=====
    static final String F_LARGE = "100MSDCF/DSC00001.ARW";   // test.arw
    static final long   L_LARGE = 25657344L;
    static final String H_LARGE = "a30dd6dc628091936e755946b32132ef0567f8034e67b4186885dd16618e936e";
    static final String F_JPG   = "100MSDCF/DSC00002.JPG";   // 从 ARW 里抽出来的内嵌预览
    static final long   L_JPG   = 1162339L;
    static final String H_JPG   = "ebae1d7d6aba494fdd957fae134439af8d502c30dfcf108f88dd2a85a4dd8bee";
    static final String F_A     = "100MSDCF/DSC00009.ARW";   // test_sd.arw
    static final String F_B     = "101MSDCF/DSC00009.ARW";   // 同一份、同名同长同 mtime
    static final long   L_SMALL = 8781824L;

    static int pass = 0;
    static final List<String> failures = new ArrayList<String>();
    static PrintWriter REPORT;
    static volatile boolean srvQuiet = false;
    static int srvReqCount = 0;

    /** 报告写 UTF-8 文件；控制台只打 ASCII，免得 Windows 控制台编码把中文糊掉。 */
    static void say(String s) {
        REPORT.println(s);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        System.out.println(sb.toString());
    }

    interface Case { void run() throws Exception; }

    static void run(String name, Case c) {
        try {
            c.run();
        } catch (Throwable t) {
            fail(name + "  -- 抛异常: " + t);
        }
    }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; say("  [PASS] " + name); }
        else { fail(name + "  -- " + detail); }
    }

    static void fail(String msg) {
        failures.add(msg);
        say("  [FAIL] " + msg);
    }

    // ==================== main ====================

    public static void main(String[] args) throws Exception {
        String rp = System.getProperty("httptest.report", "httptest-report.txt");
        REPORT = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(rp), "UTF-8"), true);

        say("HTTP 分帧 + 下载链路验证 —— 真实 ServerThread.java / Thumbs.java（v22）");
        say("时间: " + new java.util.Date());
        say("java: " + System.getProperty("java.version") + "   os: " + System.getProperty("os.name"));
        say("user.dir: " + System.getProperty("user.dir"));
        say("相册根: " + new File("/sdcard/DCIM").getAbsolutePath()
                + "   存在=" + new File("/sdcard/DCIM").isDirectory());
        say("缓存目录: " + new File("/sdcard/.arwthumbs").getAbsolutePath());
        say("");

        File root = new File("/sdcard/DCIM");
        if (!root.isDirectory()) {
            say("!! 夹具不存在: " + root.getAbsolutePath());
            say("   run.sh 会 subst 一个临时盘并把夹具生成到 <工作区>/_drive/sdcard/DCIM。");
            say("   如果这行显示的是 C:\\sdcard\\DCIM，说明 subst 没生效 / 传递丢盘符了。");
            REPORT.close();
            System.exit(2);
        }

        com.local.arwserver.ServerThread srv = new com.local.arwserver.ServerThread(PORT,
                new com.local.arwserver.ServerThread.Listener() {
                    public void onStatus(String s) {
                        srvReqCount++;
                        if (!srvQuiet) say("    [srv] " + s);
                    }
                });
        srv.start();
        Thread.sleep(400);

        try {
            run("T1",  new Case() { public void run() throws Exception { tKeepAlive(); } });
            run("T2",  new Case() { public void run() throws Exception { tPipelined(); } });
            run("T3",  new Case() { public void run() throws Exception { tClientClose(); } });
            run("T4",  new Case() { public void run() throws Exception { tHttp10(); } });
            run("T5",  new Case() { public void run() throws Exception { tHeadThenGet(); } });
            run("T6",  new Case() { public void run() throws Exception { tPostThenGet(); } });
            run("T7",  new Case() { public void run() throws Exception { tZipCloses(); } });
            run("T8",  new Case() { public void run() throws Exception { tRequestCap(); } });
            run("T9",  new Case() { public void run() throws Exception { tFileDownload(); } });
            run("T10", new Case() { public void run() throws Exception { tRange(); } });
            run("T11", new Case() { public void run() throws Exception { tRawPreview(); } });
            run("T12", new Case() { public void run() throws Exception { tThumbFallback(); } });
            run("T13", new Case() { public void run() throws Exception { tCacheKey(); } });
            run("T14", new Case() { public void run() throws Exception { tZipPackage(); } });
            run("T15", new Case() { public void run() throws Exception { tGallery(); } });
            run("T16", new Case() { public void run() throws Exception { tExifThumbX(); } });
            run("T17", new Case() { public void run() throws Exception { tDateLock(); } });
        } finally {
            srv.shutdown();
            Thread.sleep(300);
        }

        say("");
        say("================================");
        say("结果:  PASS " + pass + "    FAIL " + failures.size());
        for (int i = 0; i < failures.size(); i++) say("   x " + failures.get(i));
        say("================================");
        REPORT.close();

        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ==================== 原始 HTTP 客户端 ====================

    static class Resp {
        int code = -1;
        String status = "";
        Map<String, String> h = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];

        String hdr(String k) { String v = h.get(k); return v == null ? "" : v; }
        String conn() { return hdr("connection").toLowerCase(); }
        long clen() {
            String v = h.get("content-length");
            if (v == null) return -1;
            try { return Long.parseLong(v); } catch (Exception e) { return -1; }
        }
        String text() {
            try { return new String(body, "UTF-8"); } catch (Exception e) { return ""; }
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] a = b.toByteArray();
                int n = a.length;
                if (n > 0 && a[n - 1] == '\r') n--;
                return new String(a, 0, n, "ISO-8859-1");
            }
            b.write(c);
        }
        if (b.size() == 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    /** 读一个完整响应；返回 null = 对端已关闭（短连接被正确执行时的正常现象）。 */
    static Resp readResp(InputStream in, boolean headOnly) throws IOException {
        String st = readLine(in);
        if (st == null) return null;
        Resp r = new Resp();
        r.status = st;
        String[] parts = st.split(" ");
        if (parts.length > 1) {
            try { r.code = Integer.parseInt(parts[1]); } catch (Exception ignored) { }
        }
        String ln;
        while ((ln = readLine(in)) != null) {
            if (ln.length() == 0) break;
            int c = ln.indexOf(':');
            if (c > 0) r.h.put(ln.substring(0, c).trim().toLowerCase(), ln.substring(c + 1).trim());
        }
        long len = r.clen();
        if (headOnly || len <= 0) return r;
        if (len > 64L * 1024 * 1024) throw new IOException("可疑的 Content-Length: " + len);
        byte[] buf = new byte[(int) len];
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) throw new IOException("响应体被截断: " + got + "/" + len);
            got += n;
        }
        r.body = buf;
        return r;
    }

    static Socket conn() throws IOException {
        Socket s = new Socket(InetAddress.getByName("127.0.0.1"), PORT);
        s.setSoTimeout(20000);
        return s;
    }

    static void send(OutputStream out, String... lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) sb.append(lines[i]).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    static String head(Resp r) {
        if (r == null) return "(null)";
        String t = r.text().replace("\r", " ").replace("\n", " ");
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }

    static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "<sha-error " + e + ">";
        }
    }

    static int countBySuffix(File dir, String suffix) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (int i = 0; i < fs.length; i++) if (fs[i].getName().endsWith(suffix)) n++;
        return n;
    }

    static String shortSha(String s) { return s.length() > 16 ? s.substring(0, 16) + "..." : s; }

    // ==================== T1：连接复用（核心） ====================

    static void tKeepAlive() throws Exception {
        say("");
        say("--- T1  keep-alive：同一条 TCP 连接连打 5 个请求 ---");
        Socket s = conn();
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam", "User-Agent: framingtest/1.0");
            Resp r1 = readResp(in, false);
            say("       r1 = " + (r1 == null ? "EOF" : r1.status + "  conn=" + r1.conn() + "  clen=" + r1.clen()));
            ok("T1a /desc.xml 返回 200", r1 != null && r1.code == 200,
                    "got " + (r1 == null ? "EOF" : r1.status));
            ok("T1b 响应头广告 keep-alive", r1 != null && r1.conn().contains("keep-alive"),
                    "connection='" + (r1 == null ? "" : r1.conn()) + "'");
            ok("T1c Content-Length 与实际字节数严格一致",
                    r1 != null && r1.clen() == r1.body.length,
                    "clen=" + (r1 == null ? -1 : r1.clen()) + " body=" + (r1 == null ? -1 : r1.body.length));
            ok("T1d /desc.xml 是 MediaServer 设备描述",
                    r1 != null && r1.text().contains("urn:schemas-upnp-org:device:MediaServer:1"),
                    head(r1));

            send(out, "GET /scpd.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T1e 同连接第 2 个请求 /scpd.xml 拿到真实响应（BufferedReader 没吞掉下一请求）",
                    r2 != null && r2.code == 200 && r2.text().contains("<scpd"),
                    "got " + (r2 == null ? "EOF" : r2.status + " " + head(r2)));

            send(out, "GET / HTTP/1.1", "Host: cam", "Accept: text/html,application/xhtml+xml");
            Resp r3 = readResp(in, false);
            say("       r3 = " + (r3 == null ? "EOF" : r3.status + "  clen=" + r3.clen() + "  body=" + r3.body.length));
            ok("T1f 同连接第 3 个请求 / 出相册页",
                    r3 != null && r3.code == 200 && r3.text().contains("相机相册"),
                    "got " + (r3 == null ? "EOF" : r3.status + " " + head(r3)));
            ok("T1g 相册页 Content-Length 按字节算（页面含大量中文 UTF-8）",
                    r3 != null && r3.clen() == r3.body.length,
                    "clen=" + (r3 == null ? -1 : r3.clen()) + " body=" + (r3 == null ? -1 : r3.body.length));
            ok("T1h 页面为精简版相册（v37 起页面不带版本字样）",
                    r3 != null && r3.text().contains("相机相册") && !r3.text().contains("ARW Server"), head(r3));

            send(out, "GET /nope HTTP/1.1", "Host: cam");
            Resp r4 = readResp(in, false);
            ok("T1i 404 不破连接（仍 keep-alive）",
                    r4 != null && r4.code == 404 && r4.conn().contains("keep-alive"),
                    "got " + (r4 == null ? "EOF" : r4.status) + " conn=" + (r4 == null ? "" : r4.conn()));

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r5 = readResp(in, false);
            ok("T1j 404 之后的第 5 个请求照常服务", r5 != null && r5.code == 200,
                    "got " + (r5 == null ? "EOF" : r5.status));
        } finally {
            s.close();
        }
    }

    // ==================== T2：pipeline ====================

    static void tPipelined() throws Exception {
        say("");
        say("--- T2  pipeline：两个请求写在同一个 write() 里 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            String reqs = "GET /desc.xml HTTP/1.1\r\nHost: cam\r\n\r\n"
                        + "GET /scpd.xml HTTP/1.1\r\nHost: cam\r\n\r\n";
            out.write(reqs.getBytes("ISO-8859-1"));
            out.flush();

            Resp a = readResp(in, false);
            Resp b = readResp(in, false);
            ok("T2a 第 1 个响应 200", a != null && a.code == 200,
                    "got " + (a == null ? "EOF" : a.status));
            ok("T2b 第 2 个响应 200 且内容正确（第二个请求的字节没被预读吃掉）",
                    b != null && b.code == 200 && b.text().contains("<scpd"),
                    "got " + (b == null ? "EOF" : b.status + " " + head(b)));
        } finally {
            s.close();
        }
    }

    // ==================== T3 / T4：短连接语义 ====================

    static void tClientClose() throws Exception {
        say("");
        say("--- T3  客户端显式 Connection: close ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, "GET /desc.xml HTTP/1.1", "Host: cam", "Connection: close");
            Resp r = readResp(in, false);
            ok("T3a 200", r != null && r.code == 200, "got " + (r == null ? "EOF" : r.status));
            ok("T3b 响应头回 Connection: close（不是 keep-alive）",
                    r != null && r.conn().equals("close"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");
            int n = in.read();
            ok("T3c 服务端随后真的关闭了连接（读到 EOF）", n < 0, "read()=" + n);
        } finally {
            s.close();
        }
    }

    static void tHttp10() throws Exception {
        say("");
        say("--- T4  HTTP/1.0（没写 Connection，默认应短连接） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, "GET /desc.xml HTTP/1.0", "Host: cam");
            Resp r = readResp(in, false);
            ok("T4a 200", r != null && r.code == 200, "got " + (r == null ? "EOF" : r.status));
            ok("T4b 响应头是 close", r != null && r.conn().equals("close"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");
            int n = in.read();
            ok("T4c HTTP/1.0 之后服务端关闭", n < 0, "read()=" + n);
        } finally {
            s.close();
        }
    }

    // ==================== T5：HEAD ====================

    static void tHeadThenGet() throws Exception {
        say("");
        say("--- T5  HEAD 只发头，之后的请求不受影响 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, "HEAD /scpd.xml HTTP/1.1", "Host: cam");
            Resp r = readResp(in, true);
            ok("T5a HEAD 200 且声明了 Content-Length > 0",
                    r != null && r.code == 200 && r.clen() > 0,
                    "got " + (r == null ? "EOF" : r.status + " clen=" + r.clen()));
            ok("T5b HEAD 之后连接仍 keep-alive",
                    r != null && r.conn().contains("keep-alive"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            say("       r2 = " + (r2 == null ? "EOF" : r2.status + "  clen=" + r2.clen()));
            ok("T5c HEAD 之后 GET 仍能对上帧（服务端没发体、客户端没读体，两侧同步）",
                    r2 != null && r2.code == 200 && r2.text().startsWith("<?xml"),
                    "got " + (r2 == null ? "EOF" : head(r2)));
        } finally {
            s.close();
        }
    }

    // ==================== T6：POST 体精确消费 ====================

    static void tPostThenGet() throws Exception {
        say("");
        say("--- T6  POST（带 Content-Length 的 SOAP）之后复用同连接 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                    + "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                    + "<ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                    + "<Filter>*</Filter><StartingIndex>0</StartingIndex>"
                    + "<RequestedCount>100</RequestedCount><SortCriteria></SortCriteria>"
                    + "</u:Browse></s:Body></s:Envelope>";
            byte[] sb = soap.getBytes("UTF-8");
            String hdrs = "POST /ctl/CD HTTP/1.1\r\n"
                    + "Host: cam\r\n"
                    + "SOAPACTION: \"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"\r\n"
                    + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                    + "Content-Length: " + sb.length + "\r\n\r\n";
            out.write(hdrs.getBytes("ISO-8859-1"));
            out.write(sb);
            out.flush();

            Resp r = readResp(in, false);
            ok("T6a SOAP Browse 返回 200 且是 BrowseResponse",
                    r != null && r.code == 200 && r.text().contains("BrowseResponse"),
                    "got " + (r == null ? "EOF" : r.status + " " + head(r)));
            ok("T6b Browse 结果里包含夹具目录 100MSDCF",
                    r != null && r.text().contains("100MSDCF"), head(r));

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T6c POST 体被精确消费（后续请求没串帧）",
                    r2 != null && r2.code == 200 && r2.text().startsWith("<?xml"),
                    "got " + (r2 == null ? "EOF" : head(r2)));
        } finally {
            s.close();
        }
    }

    // ==================== T7：/z 收连接 ====================

    static void tZipCloses() throws Exception {
        say("");
        say("--- T7  /z 打包下载必须收掉连接（Content-Length 事先算好，不能复用） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, "GET /z HTTP/1.1", "Host: cam");
            Resp r = readResp(in, false);
            ok("T7a 没选文件时 400", r != null && r.code == 400,
                    "got " + (r == null ? "EOF" : r.status));
            ok("T7b 响应头是 close", r != null && r.conn().equals("close"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");
            int n = in.read();
            ok("T7c 服务端随后关闭", n < 0, "read()=" + n);
        } finally {
            s.close();
        }
    }

    // ==================== T8：请求上限 ====================

    static void tRequestCap() throws Exception {
        say("");
        say("--- T8  单连接请求上限（防客户端赖着不走） ---");
        srvQuiet = true;
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            int served = 0;
            for (int i = 0; i < 215; i++) {
                try {
                    send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
                    Resp r = readResp(in, false);
                    if (r == null) break;
                    if (r.code == 200) served++;
                } catch (IOException e) {
                    break;
                }
            }
            srvQuiet = false;
            say("       上限前实际服务了 " + served + " 个请求（服务端本次连接共回调 "
                    + srvReqCount + " 次）");
            ok("T8a 恰好 200 个请求后断开（代码里的 200 上限生效）", served == 200, "served=" + served);
        } finally {
            srvQuiet = false;
            s.close();
        }
    }

    // ==================== T9：/f/ 大文件下载 ====================

    static void tFileDownload() throws Exception {
        say("");
        say("--- T9  /f/ 原文件下载（" + F_LARGE + "，" + L_LARGE + " 字节） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            long t0 = System.currentTimeMillis();
            send(out, "GET /f/" + F_LARGE + " HTTP/1.1", "Host: cam",
                    "User-Agent: framingtest/1.0", "Accept: */*");
            Resp r = readResp(in, false);
            long ms = System.currentTimeMillis() - t0;
            String got = r == null ? "" : sha256(r.body);
            say("       " + (r == null ? "EOF" : r.status) + "  clen=" + (r == null ? -1 : r.clen())
                    + "  body=" + (r == null ? -1 : r.body.length)
                    + "  sha256=" + shortSha(got) + "  " + ms + " ms（回环）");

            ok("T9a 200", r != null && r.code == 200, "got " + (r == null ? "EOF" : r.status));
            ok("T9b Content-Length == 文件长度 " + L_LARGE,
                    r != null && r.clen() == L_LARGE, "clen=" + (r == null ? -1 : r.clen()));
            ok("T9c 收到的字节数 == 文件长度",
                    r != null && r.body.length == L_LARGE, "body=" + (r == null ? -1 : r.body.length));
            ok("T9d 下载内容的 sha256 与源文件一致",
                    H_LARGE.equals(got), "want " + shortSha(H_LARGE) + " got " + shortSha(got));
            ok("T9e Content-Type 是 application/octet-stream",
                    r != null && r.hdr("content-type").equals("application/octet-stream"),
                    "ct='" + (r == null ? "" : r.hdr("content-type")) + "'");
            ok("T9f 带 Content-Disposition 附件名",
                    r != null && r.hdr("content-disposition").contains("filename=\"DSC00001.ARW\""),
                    "cd='" + (r == null ? "" : r.hdr("content-disposition")) + "'");
            ok("T9g 声明 Accept-Ranges: bytes（下载器可续传）",
                    r != null && r.hdr("accept-ranges").equals("bytes"),
                    "ar='" + (r == null ? "" : r.hdr("accept-ranges")) + "'");
            ok("T9h 大文件下载后连接仍是 keep-alive",
                    r != null && r.conn().contains("keep-alive"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T9i 25MB 下载之后同连接的下一个请求仍能对上帧",
                    r2 != null && r2.code == 200 && r2.text().startsWith("<?xml"),
                    "got " + (r2 == null ? "EOF" : r2.status + " " + head(r2)));
        } finally {
            s.close();
        }
    }

    // ==================== T10：Range ====================

    static void tRange() throws Exception {
        say("");
        say("--- T10 Range 断点续传 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET /f/" + F_LARGE + " HTTP/1.1", "Host: cam", "Range: bytes=100-199");
            Resp r = readResp(in, false);
            say("       " + (r == null ? "EOF" : r.status) + "  clen=" + (r == null ? -1 : r.clen())
                    + "  cr='" + (r == null ? "" : r.hdr("content-range")) + "'");
            ok("T10a 206 Partial Content", r != null && r.code == 206,
                    "got " + (r == null ? "EOF" : r.status));
            ok("T10b Content-Length == 100", r != null && r.clen() == 100,
                    "clen=" + (r == null ? -1 : r.clen()));
            ok("T10c Content-Range == bytes 100-199/" + L_LARGE,
                    r != null && r.hdr("content-range").equals("bytes 100-199/" + L_LARGE),
                    "cr='" + (r == null ? "" : r.hdr("content-range")) + "'");

            boolean same = false;
            if (r != null && r.body.length == 100) {
                FileInputStream fin = new FileInputStream(new File("/sdcard/DCIM", F_LARGE));
                try {
                    long skip = 100;
                    while (skip > 0) { long k = fin.skip(skip); if (k <= 0) break; skip -= k; }
                    byte[] want = new byte[100];
                    int g = 0;
                    while (g < 100) { int n = fin.read(want, g, 100 - g); if (n < 0) break; g += n; }
                    same = java.util.Arrays.equals(want, r.body);
                } finally { fin.close(); }
            }
            ok("T10d 这 100 字节与源文件同偏移处的字节完全一致", same, "字节不符");

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T10e 206 之后连接复用正常", r2 != null && r2.code == 200,
                    "got " + (r2 == null ? "EOF" : r2.status));

            send(out, "GET /f/" + F_LARGE + " HTTP/1.1", "Host: cam", "Range: bytes=999999999-");
            Resp r3 = readResp(in, false);
            ok("T10f 起点越界 → 416 Range Not Satisfiable", r3 != null && r3.code == 416,
                    "got " + (r3 == null ? "EOF" : r3.status));
            ok("T10g 416 之后连接仍可复用",
                    r3 != null && r3.conn().contains("keep-alive"),
                    "connection='" + (r3 == null ? "" : r3.conn()) + "'");
        } finally {
            s.close();
        }
    }

    // ==================== T11：/p/ ARW 原生预览直出 ====================

    static void tRawPreview() throws Exception {
        say("");
        say("--- T11 /p/ RAW 原生预览直出（v21 抽取器走真实代码路径） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            long t0 = System.currentTimeMillis();
            send(out, "GET /p/" + F_LARGE + " HTTP/1.1", "Host: cam");
            Resp r = readResp(in, false);
            long ms = System.currentTimeMillis() - t0;
            String got = r == null ? "" : sha256(r.body);
            say("       " + (r == null ? "EOF" : r.status) + "  clen=" + (r == null ? -1 : r.clen())
                    + "  body=" + (r == null ? -1 : r.body.length)
                    + "  sha256=" + shortSha(got) + "  " + ms + " ms");

            ok("T11a 200 image/jpeg",
                    r != null && r.code == 200 && r.hdr("content-type").equals("image/jpeg"),
                    "got " + (r == null ? "EOF" : r.status + " ct=" + r.hdr("content-type")));
            ok("T11b 长度 == 内嵌预览长度 " + L_JPG,
                    r != null && r.clen() == L_JPG, "clen=" + (r == null ? -1 : r.clen()));
            ok("T11c sha256 与独立实现(Python)抽出的内嵌预览完全一致",
                    H_JPG.equals(got), "want " + shortSha(H_JPG) + " got " + shortSha(got));
            ok("T11d 内容是合法 JPEG（FFD8FF 开头 / FFD9 结尾）",
                    r != null && r.body.length > 4
                            && (r.body[0] & 0xFF) == 0xFF && (r.body[1] & 0xFF) == 0xD8
                            && (r.body[r.body.length - 2] & 0xFF) == 0xFF
                            && (r.body[r.body.length - 1] & 0xFF) == 0xD9,
                    "首尾标记不符");
            ok("T11e 带 Cache-Control: max-age=86400（手机端不用反复拉）",
                    r != null && r.hdr("cache-control").contains("max-age=86400"),
                    "cc='" + (r == null ? "" : r.hdr("cache-control")) + "'");
            ok("T11f /p/ 之后连接仍 keep-alive",
                    r != null && r.conn().contains("keep-alive"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");

            send(out, "GET /p/" + F_LARGE + " HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T11g 同一文件第二次请求（arwjpg 缓存命中）sha256 一致",
                    r2 != null && r2.code == 200 && H_JPG.equals(sha256(r2.body)),
                    "got " + (r2 == null ? "EOF" : r2.status + " " + shortSha(sha256(r2.body))));

            send(out, "GET /p/" + F_JPG + " HTTP/1.1", "Host: cam");
            Resp r3 = readResp(in, false);
            ok("T11h JPEG 源 → 原文件直出（v27 零计算）：200 + sha256 与原文件一致",
                    r3 != null && r3.code == 200 && H_JPG.equals(sha256(r3.body))
                            && r3.clen() == L_JPG
                            && r3.hdr("content-type").equals("image/jpeg"),
                    "got " + (r3 == null ? "EOF" : r3.status + " clen=" + (r3 == null ? -1 : r3.clen())
                            + " sha=" + (r3 == null ? "" : shortSha(sha256(r3.body)))));
            ok("T11i JPEG 源直出之后连接仍 keep-alive",
                    r3 != null && r3.conn().contains("keep-alive"),
                    "connection='" + (r3 == null ? "" : r3.conn()) + "'");
        } finally {
            s.close();
        }
    }

    // ==================== T12：/t/ 缩略图分支 ====================

    static void tThumbFallback() throws Exception {
        say("");
        say("--- T12 /t/ 缩略图分支（桌面桩不做真实解码，预期 404，只验不破连接） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, "GET /t/" + F_LARGE + " HTTP/1.1", "Host: cam");
            Resp r = readResp(in, false);
            ok("T12a 桩环境下 404（BitmapFactory 桩返回 null）", r != null && r.code == 404,
                    "got " + (r == null ? "EOF" : r.status));
            ok("T12b 404 之后连接仍 keep-alive",
                    r != null && r.conn().contains("keep-alive"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");
            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T12c 之后请求照常", r2 != null && r2.code == 200,
                    "got " + (r2 == null ? "EOF" : r2.status));
        } finally {
            s.close();
        }
    }

    // ==================== T16：/x/ EXIF 小图直出（v26） ====================

    static void tExifThumbX() throws Exception {
        say("");
        say("--- T16 /x/ EXIF 小图直出（零解码零编码，ARW = 12054B 现成小图） ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET /x/" + F_LARGE + " HTTP/1.1", "Host: cam");
            Resp r = readResp(in, false);
            String got = r == null ? "" : sha256(r.body);
            say("       " + (r == null ? "EOF" : r.status) + "  clen=" + (r == null ? -1 : r.clen())
                    + "  sha256=" + shortSha(got));

            ok("T16a 200 image/jpeg",
                    r != null && r.code == 200 && r.hdr("content-type").equals("image/jpeg"),
                    "got " + (r == null ? "EOF" : r.status + " ct=" + r.hdr("content-type")));
            ok("T16b 长度 == EXIF 小图 12054 字节（独立 Python 解析器实测值）",
                    r != null && r.clen() == 12054L, "clen=" + (r == null ? -1 : r.clen()));
            ok("T16c 内容是合法 JPEG（FFD8 开头 / FFD9 结尾）",
                    r != null && r.body.length > 4
                            && (r.body[0] & 0xFF) == 0xFF && (r.body[1] & 0xFF) == 0xD8
                            && (r.body[r.body.length - 2] & 0xFF) == 0xFF
                            && (r.body[r.body.length - 1] & 0xFF) == 0xD9,
                    "首尾标记不符");
            ok("T16d 带 Cache-Control: max-age=86400",
                    r != null && r.hdr("cache-control").contains("max-age=86400"),
                    "cc='" + (r == null ? "" : r.hdr("cache-control")) + "'");
            ok("T16e /x/ 之后连接仍 keep-alive",
                    r != null && r.conn().contains("keep-alive"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");

            send(out, "GET /x/" + F_LARGE + " HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            ok("T16f 第二次请求（.arwth 磁盘缓存命中）sha256 一致",
                    r2 != null && r2.code == 200 && got.equals(sha256(r2.body)),
                    "got " + (r2 == null ? "EOF" : r2.status));

            // JPEG 源：桩的 ExifInterface.getThumbnail() 恒 null → 回落 /t/ → 桩不解码 → 404
            send(out, "GET /x/" + F_JPG + " HTTP/1.1", "Host: cam");
            Resp r3 = readResp(in, false);
            ok("T16g JPEG 源（桩无 EXIF → 回落 /t/ → 桩不解码）404，连接不破",
                    r3 != null && r3.code == 404 && r3.conn().contains("keep-alive"),
                    "got " + (r3 == null ? "EOF" : r3.status + " " + r3.conn()));
        } finally {
            s.close();
        }
    }

    // ==================== T13：缓存键（v21 修的 bug） ====================

    static void tCacheKey() throws Exception {
        say("");
        say("--- T13 缓存键：同名同长同 mtime、不同目录的两个 ARW 必须各自成缓存 ---");
        File cache = new File("/sdcard/.arwthumbs");
        int n0 = countBySuffix(cache, ".arwjpg");
        File fa = new File("/sdcard/DCIM", F_A);
        File fb = new File("/sdcard/DCIM", F_B);
        say("       " + F_A + "  len=" + fa.length() + "  mtime=" + fa.lastModified());
        say("       " + F_B + "  len=" + fb.length() + "  mtime=" + fb.lastModified());
        say("       起始 arwjpg 缓存文件数 = " + n0);

        ok("T13a 两个夹具同名同长（否则测不出旧 bug）",
                fa.getName().equals(fb.getName()) && fa.length() == fb.length() && fa.length() == L_SMALL,
                "name/len 不一致");
        ok("T13b 两个夹具 mtime 也相同", fa.lastModified() == fb.lastModified(),
                "mtime 不同: " + fa.lastModified() + " vs " + fb.lastModified());

        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET /p/" + F_A + " HTTP/1.1", "Host: cam");
            Resp r1 = readResp(in, false);
            int n1 = countBySuffix(cache, ".arwjpg");
            ok("T13c 第一个文件抽出的预览 sha256 正确",
                    r1 != null && r1.code == 200 && H_JPG.equals(sha256(r1.body)),
                    "got " + (r1 == null ? "EOF" : r1.status));
            ok("T13d 新增 1 份 arwjpg 缓存", n1 == n0 + 1, "n0=" + n0 + " n1=" + n1);

            send(out, "GET /p/" + F_B + " HTTP/1.1", "Host: cam");
            Resp r2 = readResp(in, false);
            int n2 = countBySuffix(cache, ".arwjpg");
            ok("T13e 另一个目录的同名文件也能出预览",
                    r2 != null && r2.code == 200 && H_JPG.equals(sha256(r2.body)),
                    "got " + (r2 == null ? "EOF" : r2.status));
            ok("T13f ★ 缓存键含绝对路径：又新增 1 份（旧版按文件名做键时这里只会是 " + n1 + "）",
                    n2 == n1 + 1, "n1=" + n1 + " n2=" + n2);
        } finally {
            s.close();
        }
    }

    // ==================== T14：/z 打包（v30 回退同步流） ====================

    static void tZipPackage() throws Exception {
        say("");
        say("--- T14 /z 打包下载：zip 结构 + 内容 sha256 + Content-Length 精确性 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET /z?p=100MSDCF%2FDSC00002.JPG HTTP/1.1", "Host: cam",
                    "User-Agent: framingtest/1.0");
            Resp r = readResp(in, false);
            say("       " + (r == null ? "EOF" : r.status) + "  clen=" + (r == null ? -1 : r.clen())
                    + "  body=" + (r == null ? -1 : r.body.length)
                    + "  cd='" + (r == null ? "" : r.hdr("content-disposition")) + "'");

            ok("T14a 200 application/zip",
                    r != null && r.code == 200 && r.hdr("content-type").equals("application/zip"),
                    "got " + (r == null ? "EOF" : r.status + " ct=" + r.hdr("content-type")));
            ok("T14b 声明的 Content-Length == 实收字节数（少算会截断、多算会挂住浏览器）",
                    r != null && r.clen() == r.body.length,
                    "clen=" + (r == null ? -1 : r.clen()) + " body=" + (r == null ? -1 : r.body.length));
            ok("T14c 附件名形如 DCIM-yyyyMMdd-HHmm.zip",
                    r != null && r.hdr("content-disposition").matches(
                            "attachment; filename=\"DCIM-\\d{8}-\\d{4}\\.zip\""),
                    "cd='" + (r == null ? "" : r.hdr("content-disposition")) + "'");
            ok("T14d 响应头是 close", r != null && r.conn().equals("close"),
                    "connection='" + (r == null ? "" : r.conn()) + "'");
            int extra = in.read();
            ok("T14e 服务端没多发一个字节（读完 Content-Length 后立即 EOF）", extra < 0, "read()=" + extra);

            String entryName = null, entrySha = null;
            int entryMethod = -1;
            long entrySize = -1;
            int nEntries = 0;
            if (r != null && r.body.length > 0) {
                ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(r.body));
                try {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        nEntries++;
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                        entryName = e.getName();
                        entryMethod = e.getMethod();
                        entrySize = e.getSize();
                        entrySha = sha256(bo.toByteArray());
                    }
                } finally {
                    zin.close();
                }
            }
            say("       zip: entries=" + nEntries + "  name=" + entryName
                    + "  method=" + entryMethod + "  size=" + entrySize
                    + "  sha256=" + shortSha(String.valueOf(entrySha)));
            ok("T14f zip 里恰好 1 个条目", nEntries == 1, "n=" + nEntries);
            ok("T14g 条目名是 目录_文件名", "100MSDCF_DSC00002.JPG".equals(entryName),
                    "name=" + entryName);
            ok("T14h 条目是 STORED（相机端不压，省 CPU）", entryMethod == ZipEntry.STORED,
                    "method=" + entryMethod);
            ok("T14i 条目大小 == 源文件大小", entrySize == L_JPG, "size=" + entrySize);
            ok("T14j 条目内容 sha256 == 源文件 sha256",
                    H_JPG.equals(entrySha), "want " + shortSha(H_JPG) + " got " + shortSha(String.valueOf(entrySha)));
        } finally {
            s.close();
        }
    }

    // ==================== T17：日期锁定（v42） ====================

    static void tDateLock() throws Exception {
        say("");
        say("--- T17 日期锁定：非最新日期 403 → 密码解锁 → 放行 ---");
        final String OLD_FILE = "101MSDCF/DSC00009.ARW";
        // 把该文件 mtime 改到 10 天前（其余夹具是"今天" → 它成为非最新日期）
        java.io.File f = new java.io.File("/sdcard/DCIM/" + OLD_FILE);
        long oldMs = System.currentTimeMillis() - 10L * 86400 * 1000;
        if (!f.setLastModified(oldMs)) { ok("T17-pre setLastModified", false, "无法改 mtime"); return; }
        String oldDay = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(oldMs));
        say("       旧日期 = " + oldDay);

        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET /f/" + OLD_FILE + " HTTP/1.1", "Host: cam");
            Resp r = readResp(in, false);
            ok("T17a 非最新日期 /f/ → 403 该日期未解锁",
                    r != null && r.code == 403 && r.text().contains("该日期未解锁"),
                    "got " + (r == null ? "EOF" : r.status));

            send(out, "GET /?date=" + oldDay + " HTTP/1.1", "Host: cam", "Accept: text/html");
            Resp r2 = readResp(in, false);
            ok("T17b 未解锁日期页 → 密码页",
                    r2 != null && r2.code == 200 && r2.text().contains("已锁定"),
                    "got " + (r2 == null ? "EOF" : r2.status));

            send(out, "GET /unlock?d=" + oldDay + "&pw=9999 HTTP/1.1", "Host: cam");
            Resp r3 = readResp(in, false);
            ok("T17c 错误密码 → 密码页带错误提示",
                    r3 != null && r3.code == 200 && r3.text().contains("密码错误"),
                    "got " + (r3 == null ? "EOF" : r3.status));

            send(out, "GET /unlock?d=" + oldDay + "&pw=1234 HTTP/1.1", "Host: cam");
            Resp r4 = readResp(in, false);
            ok("T17d 正确密码 → 302 跳转日期页",
                    r4 != null && r4.code == 302 && r4.hdr("location").contains(oldDay),
                    "got " + (r4 == null ? "EOF" : r4.status + " loc=" + (r4 == null ? "" : r4.hdr("location"))));

            send(out, "GET /f/" + OLD_FILE + " HTTP/1.1", "Host: cam");
            Resp r5 = readResp(in, false);
            ok("T17e 解锁后 /f/ 放行（200 + 源文件大小）",
                    r5 != null && r5.code == 200 && r5.clen() == L_SMALL,
                    "got " + (r5 == null ? "EOF" : r5.status + " clen=" + (r5 == null ? -1 : r5.clen())));

            send(out, "GET /z?p=" + OLD_FILE + " HTTP/1.1", "Host: cam");
            Resp r6 = readResp(in, false);
            ok("T17f 解锁后 /z 打包放行",
                    r6 != null && r6.code == 200 && r6.hdr("content-type").equals("application/zip"),
                    "got " + (r6 == null ? "EOF" : r6.status));

        } finally {
            s.close();
        }
    }

    // ==================== T15：相册首页 / 目录过滤 / 穿越 ====================

    static void tGallery() throws Exception {
        say("");
        say("--- T15 相册首页 / 目录过滤 / 路径穿越 ---");
        Socket s = conn();
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, "GET / HTTP/1.1", "Host: cam", "Accept: text/html");
            Resp r = readResp(in, false);
            String t = r == null ? "" : r.text();
            ok("T15a 首页 200 text/html",
                    r != null && r.code == 200 && r.hdr("content-type").startsWith("text/html"),
                    "got " + (r == null ? "EOF" : r.status + " ct=" + r.hdr("content-type")));
            ok("T15b 首页是日期分组页（按日期 + /?date= 链接）",
                    t.contains("href='/?date="),
                    "缺日期分组");
            String dayUrl = null;
            int dp0 = t.indexOf("/?date=");
            if (dp0 >= 0) {
                int dp1 = t.indexOf('\'', dp0);
                if (dp1 > dp0) dayUrl = t.substring(dp0, dp1);
            }
            ok("T15c 日期卡片链接可解析", dayUrl != null, "没解析到 /?date= 链接");

            send(out, "GET " + dayUrl + " HTTP/1.1", "Host: cam", "Accept: text/html");
            Resp r1 = readResp(in, false);
            String t2 = r1 == null ? "" : r1.text();
            ok("T15d 日期页统计到 4 张 · RAW 3 · JPEG 1",
                    t2.contains("共 4 张") && t2.contains("RAW 3") && t2.contains("JPEG 1"),
                    "统计行不符");
            ok("T15e 卡片列出夹具文件名", t2.contains("DSC00001.ARW") && t2.contains("DSC00002.JPG"), "缺卡片");
            ok("T15f 卡片缩略图走 /x/、预览走 /p/、下载走 /f/",
                    t2.contains("src='/x/100MSDCF/DSC00001.ARW'")
                            && t2.contains("href='/p/100MSDCF/DSC00001.ARW'")
                            && t2.contains("href='/f/100MSDCF/DSC00001.ARW'"),
                    "卡片链接不符");
            ok("T15g 网格页含 RAW/JPG 统计与下载链接",
                    t2.contains("RAW 3") && t2.contains("JPEG 1") && t2.contains("href='/f/100MSDCF/DSC00002.JPG'"),
                    "统计/链接不符");

            send(out, "GET / HTTP/1.1", "Host: cam", "Accept: */*");
            Resp r2 = readResp(in, false);
            ok("T15h 非 HTML 请求走纯文本清单",
                    r2 != null && r2.hdr("content-type").startsWith("text/plain")
                            && r2.text().contains("f\tDSC00001.ARW"),
                    "got " + (r2 == null ? "EOF" : r2.hdr("content-type")));

            send(out, "GET /d/101MSDCF HTTP/1.1", "Host: cam", "Accept: text/html");
            Resp r3 = readResp(in, false);
            String t3 = r3 == null ? "" : r3.text();
            ok("T15i /d/101MSDCF 只列该目录（1 张）",
                    t3.contains("共 1 张") && t3.contains("DSC00009.ARW"),
                    "统计/内容不符");
            ok("T15j /d/101MSDCF 不混入 100MSDCF 的卡片",
                    !t3.contains("DSC00001.ARW") && !t3.contains("DSC00002.JPG"),
                    "目录过滤失效");

            send(out, "GET /d/.. HTTP/1.1", "Host: cam");
            Resp r4 = readResp(in, false);
            ok("T15k /d/.. 被拒（路径穿越）", r4 != null && r4.code == 404,
                    "got " + (r4 == null ? "EOF" : r4.status));

            send(out, "GET /f/100MSDCF/..%2F..%2Fetc%2Fpasswd HTTP/1.1", "Host: cam");
            Resp r5 = readResp(in, false);
            ok("T15l /f/ 里的穿越尝试被拒",
                    r5 != null && (r5.code == 404 || r5.code == 403),
                    "got " + (r5 == null ? "EOF" : r5.status));

            send(out, "GET /desc.xml HTTP/1.1", "Host: cam");
            Resp r6 = readResp(in, false);
            ok("T15m 一串异常路径之后连接仍可用", r6 != null && r6.code == 200,
                    "got " + (r6 == null ? "EOF" : r6.status));
        } finally {
            s.close();
        }
    }
}
