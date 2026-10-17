#!/usr/bin/env python3
"""
生成 HTTP 测试夹具。

ServerThread 的相册根目录是硬编码的 /sdcard/DCIM，所以夹具就得摆成那个形状。
本脚本负责其中一步：从真实 ARW 里把内嵌预览 JPEG 抠出来，存成一个 .JPG，
让测试能同时覆盖"RAW 源"和"JPEG 源"两条分支。

注意：这里的抽取是**独立实现**（直接 find 字节），故意不调用 Thumbs。
这样 Java 侧走真实代码路径算出的 sha256 与这里算出的对照，才算真正的交叉验证。

用法：mkfixture.py <input.arw> <output.jpg>
"""
import hashlib
import sys

MAX_EMBED = 8 * 1024 * 1024


def extract_largest_jpeg(data):
    best = None
    i = 0
    while True:
        s = data.find(b"\xff\xd8\xff", i)
        if s < 0:
            break
        e = data.find(b"\xff\xd9", s + 3)
        if e < 0:
            break
        seg_len = e + 2 - s
        if seg_len <= MAX_EMBED and (best is None or seg_len > best[1]):
            best = (s, seg_len)
        i = s + 1
    return best


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        data = f.read()
    hit = extract_largest_jpeg(data)
    if hit is None:
        print("!! %s 里没找到完整 JPEG" % src)
        return 1
    off, ln = hit
    seg = data[off:off + ln]
    with open(dst, "wb") as f:
        f.write(seg)
    print("    %s -> %s  offset=0x%X  len=%d  sha256=%s"
          % (src, dst, off, ln, hashlib.sha256(seg).hexdigest()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
