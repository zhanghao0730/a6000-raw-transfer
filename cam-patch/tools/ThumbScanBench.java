import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 基准：Thumbs.extractEmbeddedJpeg 的「逐字节」实现 vs 「缓冲区」实现。
 *
 * 纯 JDK，不依赖 Android —— 直接在本机跑，用真实 ARW 抽样比对
 *   (a) 抽出的字节是否完全一致（sha256）
 *   (b) 扫描量 / 耗时差多少
 *
 * 用法: java ThumbScanBench <arw> [<arw> ...]
 */
public class ThumbScanBench {

    static final int MAX_EMBED = 8 * 1024 * 1024;
    static final long MAX_SCAN = 48L * 1024 * 1024;

    // ======================= 现有实现（照抄 Thumbs.extractEmbeddedJpeg）=======================

    static byte[] slowExtract(File f) throws Exception {
        InputStream raw = null;
        BufferedInputStream in = null;
        try {
            raw = new FileInputStream(f);
            in = new BufferedInputStream(raw, 256 * 1024);
            byte[] best = null;
            long scanned = 0;
            int b0 = in.read();
            int b1 = in.read();
            scanned += 2;
            while (b1 >= 0 && scanned < MAX_SCAN) {
                int b2 = in.read();
                if (b2 < 0) break;
                scanned++;
                if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                    ByteArrayOutputStream seg = new ByteArrayOutputStream(512 * 1024);
                    seg.write(0xFF);
                    seg.write(0xD8);
                    seg.write(0xFF);
                    int p0 = 0xD8, p1 = 0xFF;
                    boolean closed = false;
                    while (scanned < MAX_SCAN && seg.size() < MAX_EMBED) {
                        int c = in.read();
                        if (c < 0) break;
                        scanned++;
                        seg.write(c);
                        p0 = p1;
                        p1 = c;
                        if (p0 == 0xFF && p1 == 0xD9) { closed = true; break; }
                    }
                    if (closed) {
                        byte[] cand = seg.toByteArray();
                        if (best == null || cand.length > best.length) best = cand;
                    }
                    b0 = in.read();
                    b1 = in.read();
                    scanned += 2;
                    continue;
                }
                b0 = b1;
                b1 = b2;
            }
            return best;
        } finally {
            if (in != null) in.close();
        }
    }

    // ======================= 新实现：两遍法 + 分块扫描 =======================

    /**
     * @param prefixCap 最多扫描多少字节（<=0 表示不设上限，只看 MAX_SCAN）
     * @param earlyMin  一旦找到 >= 该长度的完整段就停止扫描（<=0 表示不提前退出）
     * @return 抽出的 JPEG；统计信息写进 stat
     */
    static byte[] fastExtract(File f, long prefixCap, int earlyMin, long[] stat) throws Exception {
        long scanLimit = MAX_SCAN;
        if (prefixCap > 0 && prefixCap < scanLimit) scanLimit = prefixCap;

        long bestStart = -1;
        int bestLen = 0;
        long scanned;          // 实际扫过的字节数
        int segCount = 0;      // 发现过多少段（含被丢弃的）

        // ---------- pass 1: 只在内存里记账，不搬数据 ----------
        FileInputStream fin = new FileInputStream(f);
        try {
            byte[] b = new byte[1 << 18];              // 256 KB
            int h1 = -1, h0 = -1;                      // 处理到第 k 字节时：h1=b[k-2], h0=b[k-1]
            boolean inSeg = false;
            long segStart = -1;
            int segLen = 0;
            int segPrev = -1;
            long pos = 0;                              // 下一个字节的文件偏移
            scanned = 0;
            int n;
            outer:
            while ((n = fin.read(b)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (scanned >= scanLimit) break outer;
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
                        if (segPrev == 0xFF && c == 0xD9) {
                            // 完整段
                            if (segLen <= MAX_EMBED && segLen > bestLen) {
                                bestLen = segLen;
                                bestStart = segStart;
                            }
                            segCount++;
                            inSeg = false;
                            if (earlyMin > 0 && bestLen >= earlyMin) break outer;
                        } else if (segLen >= MAX_EMBED) {
                            segCount++;
                            inSeg = false;                 // 超长 -> 丢弃，从当前位置继续
                        }
                        segPrev = c;
                    }
                    h1 = h0;
                    h0 = c;
                }
                pos += n;
            }
        } finally {
            fin.close();
        }

        if (bestStart < 0) { stat[0] = scanned; stat[1] = 0; return null; }

        // ---------- pass 2: 只把命中的那一段读进来 ----------
        byte[] out = new byte[bestLen];
        FileInputStream in2 = new FileInputStream(f);
        try {
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
            if (got != bestLen) { stat[0] = scanned; stat[1] = segCount; return null; }
        } finally {
            in2.close();
        }
        stat[0] = scanned;
        stat[1] = segCount;
        return out;
    }

    // ======================= 工具 =======================

    static String sha256(byte[] d) throws Exception {
        if (d == null) return "(null)";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(d);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) sb.append(String.format("%02x", h[i]));
        return sb.toString();
    }

    static String mb(long v) { return String.format("%.2f MB", v / 1048576.0); }

    public static void main(String[] args) throws Exception {
        for (int a = 0; a < args.length; a++) {
            File f = new File(args[a]);
            if (!f.isFile()) { System.out.println("skip " + f); continue; }
            long flen = f.length();
            System.out.println("==================================================");
            System.out.println("file  : " + f.getName() + "   (" + flen + " B = " + mb(flen) + ")");

            long t0 = System.nanoTime();
            byte[] slow = slowExtract(f);
            long slowMs = (System.nanoTime() - t0) / 1000000L;
            System.out.println("slow  : " + slowMs + " ms   读入 " + flen + " 次单字节 read()"
                    + "   命中 " + (slow == null ? 0 : slow.length) + " B"
                    + "   sha256 " + sha256(slow).substring(0, 24));

            long[] st = new long[2];
            // 最后一组 = Thumbs.java v21 实际采用的配置（只有 EARLY_MIN=1MB，无前缀上限）
            int[] caps = { 0, 4 * 1024 * 1024, 2 * 1024 * 1024, 0 };
            int[] early = { 0, 1024 * 1024, 1024 * 1024, 1024 * 1024 };
            String[] names = { "fast-exact", "fast-cap4M+early1M", "fast-cap2M+early1M",
                    "fast-EARLY1M(=Thumbs v21)" };
            for (int k = 0; k < caps.length; k++) {
                t0 = System.nanoTime();
                byte[] fast = fastExtract(f, caps[k], early[k], st);
                long ms = (System.nanoTime() - t0) / 1000000L;
                boolean same = (fast == null && slow == null)
                        || (fast != null && slow != null && java.util.Arrays.equals(fast, slow));
                System.out.println(String.format("%-20s: %4d ms   扫描 %s (%.1f%% of file)  段数=%d  %s",
                        names[k], ms, mb(st[0]), 100.0 * st[0] / flen, st[1],
                        same ? "IDENTICAL ✓" : "*** DIFFERS ***"));
            }
        }
    }
}
