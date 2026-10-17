package android.graphics;

/**
 * 桌面测试桩：让 Thumbs 能编过。
 * decode* 一律返回 null ⇒ /t/ /p/ 走 404 分支 —— 本次测试的目标是 HTTP 分帧
 * （keep-alive / Content-Length / Connection 语义），不是图像解码。
 */
public class BitmapFactory {

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public int outWidth;
        public int outHeight;
        public String outMimeType;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        return null;
    }

    public static Bitmap decodeFile(String path, Options opts) {
        return null;
    }
}
