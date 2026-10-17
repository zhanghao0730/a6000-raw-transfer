package android.graphics;

import java.io.OutputStream;

/** 桌面测试桩：只满足编译与"不崩"的需求，不做真实解码。 */
public class Bitmap {

    public enum Config { ALPHA_8, ARGB_4444, ARGB_8888, RGB_565 }

    public enum CompressFormat { JPEG, PNG, WEBP }

    private final int w, h;

    private Bitmap(int w, int h) { this.w = w; this.h = h; }

    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public void recycle() { }
    public boolean isRecycled() { return false; }

    /** 桩：写一个最小的合法 JPEG 头，让 Content-Length 有值可算 */
    public boolean compress(CompressFormat fmt, int quality, OutputStream out) {
        try {
            out.write(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Bitmap createBitmap(int[] colors, int w, int h, Config cfg) {
        return new Bitmap(w, h);
    }

    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        return new Bitmap(w, h);
    }
}
