package com.local.arwserver;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Vector;

/**
 * 缩略图 / 预览生成，带磁盘缓存。
 *
 * JPEG  → 直接按需采样解码
 * ARW   → 先抽出文件里内嵌的那颗 JPEG，再采样解码
 *         （索尼 ARW 是 TIFF 容器，里面塞了整张 JPEG 预览，比写 TIFF 解析器省事得多）
 *
 * 缓存目录 /sdcard/.arwthumbs —— 第二次访问同尺寸直接命中，不再重新解码。
 *
 * ============================ v21 性能修复 ============================
 * 问题：v20 的 extractEmbeddedJpeg 用「一个字节一次 in.read()」扫文件，而
 *       MAX_SCAN = 48MB ⇒ 对一个 24.5MB 的 ARW 要发起 2500 万次 synchronized
 *       单字节 read()（内层还每字节再 write 一次 ByteArrayOutputStream）。
 *       而实测：A6000 的 ARW 里只有 2 段 JPEG，最大的预览段 1.11MB、
 *       结束在文件偏移 0x13FB05 ≈ 1.25MB —— 也就是说 95% 的扫描都在读
 *       根本不含 JPEG 的裸传感器数据。
 *
 * 修法（三项，互相独立）：
 *   ① 两遍法 + 256KB 分块扫描：pass1 只记账（起点/长度），pass2 才把命中段读进来。
 *      把 2500 万次虚调用换成 ~100 次 read(byte[]) + 纯数组比较。
 *   ② 提前退出：一旦拿到 >= EARLY_MIN(1MB) 的完整段就停（那是真预览，不是 160x120 小图）。
 *      实测扫描量 24.47MB → 1.25MB（5.1%），抽出字节与旧实现 sha256 完全一致。
 *   ③ 缩小全局锁：抽出内嵌 JPEG 挪到锁外（纯 I/O），锁内只剩解码/压缩（内存大户）。
 *
 * 基准（PC/JDK，cam-patch/tools/ThumbScanBench.java，test.arw 24.47MB）：
 *   旧实现 210ms 扫 100%  →  分块 82ms 扫 100%  →  分块+提前退出 4ms 扫 5.1%
 *
 * ============================ v23：IFD 定点提取（抄 Imaging Edge） ============================
 * 反编译 Imaging Edge（com.sony.playmemories.mobile，database/ImageLoader.java）发现：
 * 索尼自己处理本地 ARW 时不扫描 —— 用 commons-imaging 解析 TIFF 目录，
 * 读 TIFF_TAG_JPEG_INTERCHANGE_FORMAT(0x0201)/_LENGTH(0x0202) 拿到内嵌 JPEG 的
 * 精确偏移和长度，再 skip+offset 定点读取。
 *
 * 用独立 Python 解析器在 test.arw 上验证过（见 imagingedge-ref 分析报告）：
 *   IFD0  @0x8:     Compression=6(JPEG), 0x0201=0x23EA2, 0x0202=1,162,339  ← 大预览，与实测逐字节吻合
 *   IFD0.SubIFD0:   Compression=32767(raw 传感器数据)
 *   IFD0.next @0x9570: 0x0201=0x9682, 0x0202=12,054  ← EXIF 小缩略图
 * ⇒ IFD0 本身就是预览 JPEG 的描述，解析头部两个 tag 即可定点抽取，不用扫描。
 *
 * v23 策略：先解析 IFD 定点提取（读文件头 1MB 解析目录 + 预览体本身）；
 * 解析失败/结构异常时回退 v21 的分块扫描（语义不变）。
 * 内存 LRU 同步改成字节计权（同 Imaging Edge 的 LruCache 容量 = maxMemory/8）。
 */
public class Thumbs {

    private static final String TAG = "ARWSRV";
    private static final int MAX_EMBED = 8 * 1024 * 1024;      // 内嵌 JPEG 上限 8MB
    private static final long MAX_SCAN = 48L * 1024 * 1024;    // 最多扫文件前 48MB（安全网）
    private static final int EARLY_MIN = 1024 * 1024;          // 拿到 >=1MB 的完整段就收工
    private static final int BUF_SIZE = 256 * 1024;

    // v27：解码并发 2（原为全局锁串行——弱 SoC 上一张 0.5~1.5s，首屏 12 张排队 6~20s）。
    // 只在缓存 miss 的兜底路径（/t/、/x/ 回落）才会走到；2 是 A6000 单核~双核的甜点值，
    // 开 4 并发只会互相抢 CPU。同 key 偶发重复计算可接受（写盘是原子 rename）。
    private static final java.util.concurrent.Semaphore DECODE =
            new java.util.concurrent.Semaphore(2, true);
    private static int counter = 0;
    private static boolean toldAboutCacheFail = false;         // 缓存写失败只报一次面板

    /** 原生 ARW 预览图直出的上限：超过这个大小就退回"重编码"路线，别把 3MB+ 的图直接灌给手机 */
    private static final int MAX_RAW_PREVIEW = 3 * 1024 * 1024;

    // ===== 内存 LRU：命中就完全不走磁盘/CPU（v23：字节计权，同 Imaging Edge）=====
    // Imaging Edge 用 LruCache(maxMemory/8)，sizeOf=getByteCount()。我们缓存的是
    // 压缩后的 JPEG 字节数组（384px q78 约 8~15KB），同样按字节计权更准：
    // 一批 1600px 大预览不该把几百张 384px 缩略图全部挤出缓存。
    private static final long MEM_MAX_BYTES =
            Math.min(Runtime.getRuntime().maxMemory() / 8, 8L * 1024 * 1024);
    private static final int MEM_ITEM_MAX = 64 * 1024;
    private static long memBytes = 0;
    private static final java.util.LinkedHashMap MEM =
            new java.util.LinkedHashMap(64, 0.75f, true) {   // accessOrder=true
                protected boolean removeEldestEntry(java.util.Map.Entry e) {
                    // v41：条数超限时同步递减 memBytes——旧实现只在这里删、
                    // memBytes 却只在 memPut 的 while 里修正，长期运行计数虚高，
                    // 导致每次 put 都过度驱逐（有效缓存容量减半）
                    if (size() > 512) {
                        byte[] ev = (byte[]) e.getValue();
                        if (ev != null) memBytes -= ev.length;
                        return true;
                    }
                    return false;
                }
            };

    private static byte[] memGet(String key) {
        synchronized (MEM) {
            return (byte[]) MEM.get(key);
        }
    }

    private static void memPut(String key, byte[] v) {
        if (v == null || v.length > MEM_ITEM_MAX) return;
        synchronized (MEM) {
            byte[] prev = (byte[]) MEM.put(key, v);
            memBytes += v.length - (prev != null ? prev.length : 0);
            while (memBytes > MEM_MAX_BYTES && MEM.size() > 1) {
                java.util.Iterator it = MEM.entrySet().iterator();
                if (!it.hasNext()) break;
                byte[] ev = (byte[]) ((java.util.Map.Entry) it.next()).getValue();
                it.remove();
                memBytes -= ev.length;
            }
        }
    }

    public static byte[] thumbnail(File f) {
        return render(f, 384, 78);
    }

    public static byte[] preview(File f) {
        return render(f, 1600, 86);
    }

    public static byte[] render(File f, int maxDim, int quality) {
        if (f == null || !f.isFile() || f.length() <= 0) return null;

        String key = cacheName(f, maxDim, quality);

        // ① 内存 LRU —— 命中就什么都不做
        byte[] m = memGet(key);
        if (m != null) return m;

        File cache = new File(cacheDir(), key);
        byte[] hit = readIfFresh(cache, f.lastModified());
        if (hit != null) {
            memPut(key, hit);
            return hit;
        }

        // ② 锁外：抽内嵌 JPEG（只做 I/O + 数组扫描，很快）
        byte[] src = isJpeg(f.getName()) ? null : embeddedJpegCached(f);

        byte[] out = null;
        DECODE.acquireUninterruptibly();
        try {
            // 进信号量后再查一次：可能已被别的线程算好
            hit = readIfFresh(cache, f.lastModified());
            if (hit != null) {
                memPut(key, hit);
                return hit;
            }
            try {
                Bitmap bm = decodeScaled(f, src, maxDim);
                if (bm != null) {
                    out = compress(bm, quality);
                    bm.recycle();
                }
            } catch (Throwable t) {
                Log.w(TAG, "render " + f.getName() + " failed: " + t);
            }
        } finally {
            DECODE.release();
        }
        if (out != null) {
            memPut(key, out);
            writeFile(cache, out);
        }
        if ((++counter % 100) == 0) cleanup(cacheDir());
        return out;
    }

    /**
     * ARW 的「原生预览」：把索尼塞在文件里的那颗完整 JPEG 直接交出去，**不解码、不重编码**。
     *
     * 为什么划算：那颗 JPEG 本身就有 ~1616x1080（1.1MB 量级），和 preview() 想要的
     * 1600px 基本等价 —— 但 /p/ 走这条路只是「读一个缓存文件」，CPU 成本≈0；
     * 走 render() 则要 decode 1.1MB JPEG + createScaledBitmap + 再编码一次。
     *
     * JPEG 源返回 null（原图可能 10MB+，必须走重编码），调用方自动回落到 render()。
     */
    public static byte[] embeddedFor(File f) {
        if (f == null || !f.isFile() || f.length() <= 0) return null;
        if (isJpeg(f.getName())) return null;
        byte[] j = embeddedJpegCached(f);
        if (j == null || j.length <= 0 || j.length > MAX_RAW_PREVIEW) return null;
        return j;
    }

    /**
     * ★ v26：EXIF 缩略图直出 —— 网格端 /x/ 的后端。
     *
     * Imaging Edge 的 getThumbnailBitmap() 路线：每张照片（ARW 和 JPEG 都一样）
     * 里都埋着一张**拍摄瞬间生成的现成小图**（A6000 ≈ 12KB / 160x120）。
     * 把它原样字节吐出去，全程零解码、零缩放、零重编码 —— 相机 CPU 只做"读 12KB"。
     * 浏览器拿到的是标准 JPEG，用手机自己的硬件解码器显示。
     *
     *  - ARW：IFD next-IFD 的 (0x0201,0x0202)，取 2KB~64KB 里最短的段；
     *  - JPEG：android.media.ExifInterface.getThumbnail()（只读 APP1，不解码）。
     *  拿不到（非常规文件）返回 null，调用方回落 /t/ 现算。
     */
    public static byte[] exifThumb(File f) {
        if (f == null || !f.isFile() || f.length() <= 0) return null;
        File cache = new File(cacheDir(), cacheName(f, 0, 0) + ".arwth");
        byte[] hit = readIfFresh(cache, f.lastModified());
        if (hit != null) return hit;
        byte[] out = null;
        try {
            if (isJpeg(f.getName())) {
                byte[] t = new ExifInterface(f.getAbsolutePath()).getThumbnail();
                if (t != null && t.length > 512
                        && (t[0] & 0xFF) == 0xFF && (t[1] & 0xFF) == 0xD8) {
                    out = t;
                }
            } else {
                long[] seg = locateThumbJpegByIfd(f);
                if (seg != null) out = readSegment(f, seg[0], (int) seg[1]);
            }
        } catch (Throwable t) {
            Log.w(TAG, "exifThumb " + f.getName() + ": " + t);
            out = null;
        }
        if (out != null) writeFile(cache, out);
        return out;
    }

    /**
     * 预热：服务起来后把最新的几张先算好，等手机连上热点打开网页时直接命中磁盘缓存。
     * 低优先级 + 每个文件之间让出 150ms，避免拖慢相机本身。
     *
     * @param startDelayMs 先等一会儿再开工（别和热点拉起的时序抢 CPU）
     */
    public static void warmup(final File root, final int max, final long startDelayMs) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(startDelayMs);
                    // v34：预热第一步 = 扫描（复用服务端 DIR_MEMO 缓存，消灭排序 stat 黑洞）
                    Vector recs = ServerThread.scanAll();
                    int n = Math.min(max, recs.size());
                    Log.i(TAG, "warmup: " + n + " of " + recs.size() + " files");
                    for (int i = 0; i < n; i++) {
                        ServerThread.Rec r = (ServerThread.Rec) recs.get(i);
                        exifThumb(new File(root, r.dir + "/" + r.name));
                        try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                    }
                    Log.i(TAG, "warmup done");
                } catch (Throwable t2) {
                    Log.w(TAG, "warmup: " + t2);
                }
            }
        }, "ArwWarmup");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /** 抽出的内嵌 JPEG 单独缓存一份，避免缩略图/预览各扫一遍整个文件 */
    private static byte[] embeddedJpegCached(File f) {
        File cache = new File(cacheDir(), cacheName(f, 0, 0) + ".arwjpg");
        byte[] hit = readIfFresh(cache, f.lastModified());
        if (hit != null) return hit;
        // v23：先走 IFD 定点提取（精确、I/O 最少），失败再回退分块扫描
        byte[] jpeg = extractEmbeddedJpegByIfd(f);
        if (jpeg == null) jpeg = extractEmbeddedJpeg(f);
        if (jpeg != null) writeFile(cache, jpeg);
        return jpeg;
    }

    // ---------------- v23：TIFF IFD 定点提取（Imaging Edge 的做法）----------------

    /**
     * 解析 ARW（TIFF 容器）的 IFD 目录，找到内嵌 JPEG 的精确 (offset, length)，
     * 然后只把那一段读出来。不做任何内容扫描。
     *
     * 遍历 IFD0 → next-IFD 链 → SubIFD(0x014A)，收集所有 (0x0201, 0x0202) 对，
     * 取长度最大且 ≤ MAX_EMBED 的一段（A6000: IFD0 = 1.16MB 预览，
     * next-IFD = 12KB EXIF 小图，最大者即真预览）。
     *
    /**
     * 取长度最大且 ≤ MAX_EMBED 的一段（A6000: IFD0 = 1.16MB 预览，
     * next-IFD = 12KB EXIF 小图，最大者即真预览）。
     *
     * 任何结构异常都返回 null，由调用方回退到扫描路径 —— 本函数绝不抛异常。
     */
    static byte[] extractEmbeddedJpegByIfd(File f) {
        long[] seg = locateEmbeddedJpegByIfd(f);
        if (seg == null) return null;
        return readSegment(f, seg[0], (int) seg[1]);
    }

    /** 从文件指定偏移精确读 len 字节，并校验 FFD8 铁律。失败返回 null，绝不抛异常。 */
    private static byte[] readSegment(File f, long skip, int len) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            long done = 0;
            while (done < skip) {
                long s = in.skip(skip - done);
                if (s <= 0) break;
                done += s;
            }
            if (done != skip) return null;
            byte[] out = new byte[len];
            int got = 0;
            while (got < len) {
                int r = in.read(out, got, len - got);
                if (r < 0) break;
                got += r;
            }
            if (got != len) return null;
            // 铁律校验：必须是 JPEG（FFD8 开头）。不满足说明解析有错，交回调用方回退。
            if ((out[0] & 0xFF) != 0xFF || (out[1] & 0xFF) != 0xD8) return null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "seg read " + f.getName() + ": " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 读文件头解析 IFD；返回 {offset, length}，失败返回 null。
     *  两步头：先 64KB（IFD0 的 0x0201/0x0202 值内联，test.arw 里 IFD0 只到 0xE6），
     *  不够再读 1MB（覆盖 next-IFD / SubIFD 在深处的机型）。 */
    static long[] locateEmbeddedJpegByIfd(File f) {
        return locateByIfd(f, false);
    }

    /** v26：定位 next-IFD 里的 EXIF 小图（(0x0201,0x0202) 段中 ≤64KB 的最小者）。 */
    static long[] locateThumbJpegByIfd(File f) {
        return locateByIfd(f, true);
    }

    private static long[] locateByIfd(File f, boolean smallest) {
        FileInputStream in = null;
        try {
            long flen = f.length();
            if (flen < 16) return null;
            int[] caps = {64 * 1024, 1024 * 1024};
            for (int ci = 0; ci < caps.length; ci++) {
                int cap = (int) Math.min(flen, caps[ci]);
                if (ci > 0 && cap <= caps[ci - 1]) continue;   // 文件本来就不大，不用扩
                byte[] b = new byte[cap];
                in = new FileInputStream(f);
                int got = 0;
                while (got < cap) {
                    int r = in.read(b, got, cap - got);
                    if (r < 0) break;
                    got += r;
                }
                in.close();
                in = null;
                if (got < 16) return null;
                long[] seg = findJpegSegment(b, got, flen, smallest);
                if (seg != null) return seg;
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 供测试注入的纯函数：在 buffer 里解析 TIFF IFD 树（取最长段，v23 语义） */
    static long[] findLargestJpegSegment(byte[] b, int n, long fileLen) {
        return findJpegSegment(b, n, fileLen, false);
    }

    /**
     * 在 buffer 里解析 TIFF IFD 树，收集全部合法 (0x0201,0x0202) 段：
     *  smallest=false → 取最长且 ≤ MAX_EMBED（真预览，v23 语义）；
     *  smallest=true  → 取 2KB~64KB 里最短的（EXIF 缩略图，v26 语义）。
     */
    static long[] findJpegSegment(byte[] b, int n, long fileLen, boolean smallest) {
        if (n < 8) return null;
        final boolean le;
        if (b[0] == 'I' && b[1] == 'I' && b[2] == 0x2A && b[3] == 0) le = true;
        else if (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 0x2A) le = false;
        else return null;

        long ifd0 = u32(b, n, 4, le);
        if (ifd0 <= 0) return null;

        long bestOff = -1, bestLen = 0;
        long[] queue = new long[16];         // IFD 偏移队列（最多 16 项，防环用 visited）
        int qHead = 0, qTail = 0;
        queue[qTail++] = ifd0;
        long[] visited = new long[16];
        int visitedN = 0;

        while (qHead < qTail && qHead < 8) {
            long off = queue[qHead++];
            boolean seen = false;
            for (int i = 0; i < visitedN; i++) if (visited[i] == off) { seen = true; break; }
            if (seen || off + 2 > n) continue;
            visited[visitedN++] = off;

            int entries = u16(b, n, (int) off, le);
            if (entries <= 0 || entries > 512) continue;
            long jpegOff = -1, jpegLen = 0;
            long nextIfd = 0;
            int base = (int) off + 2;

            for (int i = 0; i < entries; i++) {
                int e = base + 12 * i;
                if (e + 12 > n) break;
                int tag = u16(b, n, e, le);
                int type = u16(b, n, e + 2, le);
                if (tag == 0x014A && (type == 4 || type == 13)) {
                    // SubIFDs：count==1 时值内联；否则值是 LONG 数组的偏移
                    if (countAt(b, n, e, le) == 1) {
                        long sub = u32(b, n, e + 8, le);
                        if (sub > 0 && qTail < 16) queue[qTail++] = sub;
                    } else {
                        long arrOff = u32(b, n, e + 8, le);
                        int cnt = (int) Math.min(countAt(b, n, e, le), 4);
                        for (int k = 0; k < cnt; k++) {
                            long sub = u32(b, n, (int) arrOff + 4 * k, le);
                            if (sub > 0 && qTail < 16) queue[qTail++] = sub;
                        }
                    }
                } else if (type == 4 && countAt(b, n, e, le) == 1) {
                    long v = u32(b, n, e + 8, le);   // LONG count=1 → 值内联
                    if (tag == 0x0201) jpegOff = v;
                    else if (tag == 0x0202) jpegLen = v;
                }
            }
            int last = base + 12 * entries;
            if (last + 4 <= n) nextIfd = u32(b, n, last, le);
            if (nextIfd > 0 && qTail < 16) queue[qTail++] = nextIfd;

            if (jpegOff > 0 && jpegLen > 0
                    && jpegOff < fileLen && jpegOff + jpegLen <= fileLen) {
                if (smallest) {
                    // EXIF 缩略图语义：2KB~64KB 里挑最短的（A6000 = next-IFD 的 12KB 小图）
                    if (jpegLen >= 2048 && jpegLen <= 65536
                            && (bestLen == 0 || jpegLen < bestLen)) {
                        bestLen = jpegLen;
                        bestOff = jpegOff;
                    }
                } else if (jpegLen <= MAX_EMBED && jpegLen > bestLen) {
                    bestLen = jpegLen;
                    bestOff = jpegOff;
                }
            }
        }
        if (bestOff < 0) return null;
        return new long[]{bestOff, bestLen};
    }

    private static int u16(byte[] b, int n, int off, boolean le) {
        if (off < 0 || off + 2 > n) return -1;
        return le ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                  : ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int n, int off, boolean le) {
        if (off < 0 || off + 4 > n) return -1;
        int r = le ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                   | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24)
                   : ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                   | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        return r & 0xFFFFFFFFL;
    }

    private static long countAt(byte[] b, int n, int e, boolean le) {
        return u32(b, n, e + 4, le);
    }

    // ---------------- 内嵌 JPEG 抽取（两遍法 + 分块扫描）----------------

    /**
     * 找出 ARW 里最大的一段完整 JPEG（FFD8FF ... FFD9）。
     *
     * pass 1：256KB 分块扫，只用 (h1,h0,c) 三字节窗口记账，不搬任何数据；
     *         拿到 >= EARLY_MIN 的完整段就停（真预览），否则扫到 MAX_SCAN。
     * pass 2：只把命中的那一段读进来。
     *
     * 语义与旧的逐字节实现严格等价（含"超长段丢弃后从当前位置续扫"的行为），
     * 已在 cam-patch/tools/ThumbScanBench.java 上用真实 ARW 比对过 sha256。
     */
    static byte[] extractEmbeddedJpeg(File f) {
        long bestStart = -1;
        int bestLen = 0;
        FileInputStream fin = null;
        try {
            // ---------- pass 1 ----------
            fin = new FileInputStream(f);
            byte[] b = new byte[BUF_SIZE];
            int h1 = -1, h0 = -1;          // 处理第 k 字节时 h1=b[k-2], h0=b[k-1]
            boolean inSeg = false;
            long segStart = -1;
            int segLen = 0;
            int segPrev = -1;
            long pos = 0, scanned = 0;
            int n;
            scan:
            while ((n = fin.read(b)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (scanned >= MAX_SCAN) break scan;
                    int c = b[i] & 0xFF;
                    long p = pos + i;
                    scanned++;

                    if (!inSeg) {
                        if (h1 == 0xFF && h0 == 0xD8 && c == 0xFF) {
                            inSeg = true;
                            segStart = p - 2;
                            segLen = 3;
                            segPrev = 0xFF;
                        }
                    } else {
                        segLen++;
                        if (segPrev == 0xFF && c == 0xD9) {        // EOI：完整段
                            if (segLen <= MAX_EMBED && segLen > bestLen) {
                                bestLen = segLen;
                                bestStart = segStart;
                            }
                            inSeg = false;
                            if (bestLen >= EARLY_MIN) break scan;   // ★ 提前退出
                        } else if (segLen >= MAX_EMBED) {           // 超长 -> 丢弃，从当前位置续扫
                            inSeg = false;
                        }
                        segPrev = c;
                    }
                    h1 = h0;
                    h0 = c;
                }
                pos += n;
            }
        } catch (Throwable t) {
            Log.w(TAG, "extractEmbeddedJpeg scan " + f.getName() + ": " + t);
            return null;
        } finally {
            try { if (fin != null) fin.close(); } catch (Throwable ignored) { }
        }

        if (bestStart < 0) return null;

        // ---------- pass 2：只读命中段 ----------
        FileInputStream in2 = null;
        try {
            byte[] out = new byte[bestLen];
            in2 = new FileInputStream(f);
            long skip = bestStart, done = 0;
            while (done < skip) {
                long s = in2.skip(skip - done);
                if (s <= 0) break;
                done += s;
            }
            int got = 0;
            while (got < bestLen) {
                int r = in2.read(out, got, bestLen - got);
                if (r < 0) break;
                got += r;
            }
            if (got != bestLen) return null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "extractEmbeddedJpeg read " + f.getName() + ": " + t);
            return null;
        } finally {
            try { if (in2 != null) in2.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------- 解码 / 缩放 ----------------

    private static Bitmap decodeScaled(File f, byte[] src, int maxDim) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        if (src != null) BitmapFactory.decodeByteArray(src, 0, src.length, probe);
        else BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

        int sample = 1;
        while (Math.max(probe.outWidth, probe.outHeight) / (sample * 2) >= maxDim) sample *= 2;

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.RGB_565;   // API 10 就有，省内存
        Bitmap bm = (src != null)
                ? BitmapFactory.decodeByteArray(src, 0, src.length, opt)
                : BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        if (bm == null) return null;

        int w = bm.getWidth(), h = bm.getHeight();
        float scale = Math.min(1f, (float) maxDim / (float) Math.max(w, h));
        if (scale < 1f) {
            int nw = Math.max(1, (int) (w * scale));
            int nh = Math.max(1, (int) (h * scale));
            Bitmap smaller = Bitmap.createScaledBitmap(bm, nw, nh, true);
            if (smaller != bm) bm.recycle();
            bm = smaller;
        }
        return bm;
    }

    private static byte[] compress(Bitmap bm, int quality) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        if (!bm.compress(Bitmap.CompressFormat.JPEG, quality, bo)) return null;
        return bo.toByteArray();
    }

    // ---------------- 缓存 ----------------

    private static File cacheDir() {
        File d = new File("/sdcard/.arwthumbs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * ★ v21 修了个真 bug：旧版 key 只用了 f.getName()，所以 100MSDCF/DSC00001.ARW 与
     * 101MSDCF/DSC00001.ARW 会撞同一个缓存文件 → 显示错误的缩略图。改用绝对路径。
     */
    private static String cacheName(File f, int maxDim, int quality) {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        return Integer.toHexString(key.hashCode()) + "-" + maxDim + "q" + quality + ".jpg";
    }

    private static byte[] readIfFresh(File cache, long sourceModified) {
        try {
            if (!cache.isFile()) return null;
            if (cache.lastModified() < sourceModified) return null;
            long len = cache.length();
            if (len <= 0 || len > 32L * 1024 * 1024) return null;
            byte[] buf = new byte[(int) len];
            InputStream in = new FileInputStream(cache);
            try {
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n < 0) break;
                    got += n;
                }
                if (got != buf.length) return null;
            } finally {
                in.close();
            }
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 先写临时文件再 rename 覆盖：避免两个线程同时写同一个缓存文件名写出半截文件 */
    private static void writeFile(File out, byte[] data) {
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
            try {
                bo.write(data);
            } finally {
                bo.close();
            }
            if (!tmp.renameTo(out)) {
                out.delete();
                tmp.renameTo(out);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache write " + out.getName() + ": " + t);
            // 缓存写不进去 = 每次访问都要重算，这是"一直很慢"的头号嫌疑，报到面板上
            if (!toldAboutCacheFail) {
                toldAboutCacheFail = true;
                ArwService.addLog("! 缩略图缓存写不进 /sdcard/.arwthumbs: " + t);
            }
            tmp.delete();
        }
    }

    /** 缓存超过 600 个文件时，删掉最旧的一批 */
    private static void cleanup(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= 600) return;
            java.util.Arrays.sort(fs, new java.util.Comparator() {
                public int compare(Object a, Object b) {
                    long x = ((File) a).lastModified(), y = ((File) b).lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
            for (int i = 0; i < 200 && i < fs.length; i++) fs[i].delete();
        } catch (Throwable ignored) {
        }
    }

    static boolean isJpeg(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    static boolean isImage(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".arw");
    }
}
