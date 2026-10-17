package com.local.arwserver;

import com.local.arwserver.Thumbs;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * v23 IFD 定点提取的等价性测试（纯 JDK，真实 ARW）：
 *   1. scan 路径（v21 语义）与 ifd 路径抽出的字节必须完全一致
 *   2. 坏头文件：IFD 解析必须返回 null（回退扫描），不得抛异常
 *   3. 截断文件：同上
 */
public class IfdEquTest {
    public static void main(String[] args) throws Exception {
        int fails = 0;
        for (String fn : args) {
            File f = new File(fn);
            if (!f.isFile()) { System.out.println("SKIP(缺文件) " + fn); continue; }
            System.out.println("== " + f.getName() + " (" + f.length() + " B) ==");

            long t0 = System.nanoTime();
            long[] seg = Thumbs.locateEmbeddedJpegByIfd(f);
            long t1 = System.nanoTime();
            byte[] byScan = Thumbs.extractEmbeddedJpeg(f);
            long t2 = System.nanoTime();
            byte[] byIfd = Thumbs.extractEmbeddedJpegByIfd(f);
            long t3 = System.nanoTime();

            System.out.printf("  ifd 段定位: %s  解析耗时 %.2f ms%n",
                    seg == null ? "null" : ("off=0x" + Long.toHexString(seg[0]) + " len=" + seg[1]),
                    (t1 - t0) / 1e6);
            System.out.printf("  抽取耗时: scan %.2f ms / ifd %.2f ms%n",
                    (t2 - t1) / 1e6, (t3 - t2) / 1e6);
            System.out.println("  scan sha256 = " + sha(byScan));
            System.out.println("  ifd  sha256 = " + sha(byIfd));

            if (byScan == null) { System.out.println("  !! scan 基线为 null，无基线可比"); fails++; continue; }
            if (byIfd == null) { System.out.println("  !! ifd 提取为 null"); fails++; continue; }
            if (!Arrays.equals(byScan, byIfd)) { System.out.println("  !! 内容不一致"); fails++; continue; }
            System.out.println("  PASS: 逐字节一致 (" + byIfd.length + " B)");

            // 负面用例：坏头（TIFF 魔数清零）→ ifd 解析必须 null
            File corrupt = new File(f.getParent(), "_corrupt_head.tmp");
            copyWithZeroedHead(f, corrupt, 64);
            if (Thumbs.locateEmbeddedJpegByIfd(corrupt) != null) {
                System.out.println("  !! 坏头未返回 null"); fails++;
            } else System.out.println("  PASS: 坏头返回 null");
            // 坏头整体流程仍要能靠扫描兜底
            byte[] c = Thumbs.extractEmbeddedJpeg(corrupt);
            if (c == null || !Arrays.equals(c, byScan)) { System.out.println("  !! 坏头扫描兜底不一致"); fails++; }
            else System.out.println("  PASS: 坏头回退扫描结果一致");
            corrupt.delete();

            // 负面用例：截断到 100KB → ifd 不抛异常（可能 null 或有效段）
            File trunc = new File(f.getParent(), "_trunc.tmp");
            copyHead(f, trunc, 100 * 1024);
            try {
                long[] ts = Thumbs.locateEmbeddedJpegByIfd(trunc);
                byte[] tb = Thumbs.extractEmbeddedJpegByIfd(trunc);
                System.out.println("  PASS: 截断文件未抛异常 (seg=" + (ts == null ? "null" : ts[0] + "/" + ts[1])
                        + ", bytes=" + (tb == null ? "null" : "" + tb.length) + ")");
            } catch (Throwable t) {
                System.out.println("  !! 截断文件抛异常: " + t); fails++;
            }
            trunc.delete();
        }
        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static String sha(byte[] d) throws Exception {
        if (d == null) return "(null)";
        return hex(MessageDigest.getInstance("SHA-256").digest(d));
    }
    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        sb.append("...");
        for (int i = d.length - 8; i < d.length; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }
    static void copyWithZeroedHead(File src, File dst, int head) throws Exception {
        FileInputStream in = new FileInputStream(src);
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        byte[] b = new byte[64 * 1024];
        long total = 0; int r;
        while ((r = in.read(b)) > 0) { out.write(b, 0, r); total += r; }
        out.seek(0);
        out.write(new byte[head]);
        out.close(); in.close();
    }
    static void copyHead(File src, File dst, int head) throws Exception {
        FileInputStream in = new FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] b = new byte[64 * 1024];
        int left = head, r;
        while (left > 0 && (r = in.read(b, 0, Math.min(b.length, left))) > 0) { out.write(b, 0, r); left -= r; }
        out.close(); in.close();
    }
}
