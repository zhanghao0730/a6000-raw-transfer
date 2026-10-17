package android.media;

import java.io.IOException;

/** 桌面测试桩：不做真实 EXIF 解析，getThumbnail 恒 null（验证 /x/ 的回落路径） */
public class ExifInterface {
    public ExifInterface(String file) throws IOException {
        if (file == null) throw new IOException("stub");
    }
    public byte[] getThumbnail() {
        return null;
    }
}
