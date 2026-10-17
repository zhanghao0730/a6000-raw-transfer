#!/usr/bin/env bash
# ============================================================================
# HTTP 分帧 + 下载链路验证
#
# 把 **真实的** ServerThread.java / Thumbs.java / UpnpServer.java 编到桌面来，
# 用手写桩顶掉 android.graphics / android.util，然后拿原始 socket 逐字节查响应。
#
# 用法：
#   bash run.sh           编译 + 造夹具 + 跑全部 15 组测试
#   bash run.sh --clean   清掉产物（out / _drive / report）
#
# 夹具为什么要 subst + 为什么要 cd 到 X:：
#   ServerThread 里 root 是硬编码的 "/sdcard/DCIM"。Windows 上以 "\" 开头的路径是
#   "盘符相对"路径 —— 只继承**当前盘符**。于是 subst 一个临时盘，夹具落在
#   <工作区>/_drive/sdcard/... 里，跑完自动卸掉，不往 C:\ 根目录写测试数据。
#
#   ⚠ 两个坑，都实测过：
#   ① Git Bash 会把 "subst X: /D" 里的 /D 当路径转换 → 卸载静默失败、留下僵尸映射。
#      所有 subst 调用都必须带 MSYS2_ARG_CONV_EXCL='*'。
#   ② **-Duser.dir 不能用来改盘符相对路径的落点**。JDK 里
#        File.getAbsolutePath()  → 读 user.dir 属性
#        File.exists()/listFiles()/FileInputStream → 走原生进程 CWD（不看 user.dir）
#      所以 -Duser.dir=X:/ 会让 getAbsolutePath() 印出 X:\sdcard\DCIM，
#      而 exists() 依然去 C:\sdcard\DCIM 找，得到 exists=false。
#      正确做法是把 **JVM 的工作目录** cd 到 X:\ 上。
# ============================================================================
set -e

ROOT=/c/Users/22206/Desktop/sony
HERE="$ROOT/cam-patch/tools/httptest"
SRC="$ROOT/cam-patch/arwserver/src/com/local/arwserver"
OUT="$HERE/out"
PY=/c/Users/22206/.workbuddy/binaries/python/versions/3.13.12/python.exe
DRIVE=X:

subst_off() { cd "$HERE" 2>/dev/null || true; MSYS2_ARG_CONV_EXCL='*' subst "$DRIVE" /D >/dev/null 2>&1 || true; }
subst_on()  { MSYS2_ARG_CONV_EXCL='*' subst "$DRIVE" "$(cygpath -w "$HERE/_drive")"; }

if [ "$1" = "--clean" ]; then
    subst_off
    rm -rf "$OUT" "$HERE/_drive" "$HERE/httptest-report.txt"
    echo "已清理: out / _drive / httptest-report.txt 以及 subst $DRIVE"
    exit 0
fi

# ---------------- 夹具 ----------------
subst_off                              # 先卸掉可能残留的僵尸映射
mkdir -p "$HERE/_drive"
if ! subst_on; then
    echo "!! subst $DRIVE 失败。手动执行 'subst $DRIVE /D' 后再试，或改本脚本的 DRIVE 变量。"
    exit 1
fi
trap subst_off EXIT

FIX="$HERE/_drive/sdcard/DCIM"
echo "[1/4] 生成夹具（真实 ARW / 真实内嵌预览）"
rm -rf "$HERE/_drive/sdcard"
mkdir -p "$FIX/100MSDCF" "$FIX/101MSDCF"
cp "$ROOT/cam-patch/test.arw"    "$FIX/100MSDCF/DSC00001.ARW"   # 24.47MB：大文件下载 + 原生预览
cp "$ROOT/cam-patch/test_sd.arw" "$FIX/100MSDCF/DSC00009.ARW"   # 8.38MB：缓存键对照 A
cp "$ROOT/cam-patch/test_sd.arw" "$FIX/101MSDCF/DSC00009.ARW"   # 8.38MB：缓存键对照 B
# 同名 + 同长 + 同 mtime —— 只有这样才测得出"缓存键只用了文件名"那个 bug
touch -r "$FIX/100MSDCF/DSC00009.ARW" "$FIX/101MSDCF/DSC00009.ARW"
"$PY" "$HERE/mkfixture.py" "$ROOT/cam-patch/test.arw" "$FIX/100MSDCF/DSC00002.JPG"
ls -l "$FIX/100MSDCF" "$FIX/101MSDCF" | sed 's/^/       /'
echo "       $DRIVE -> $(subst | head -1)"

# ---------------- 编译 ----------------
# 只编 ServerThread 真正依赖的 3 个真实文件：刻意不含 HotspotController / MainActivity /
# QrCode（要 android.app 全套），Thumbs 用到的 ArwService.addLog 由桩提供。
rm -rf "$OUT"
mkdir -p "$OUT"
echo "[2/4] javac  真实源码(ServerThread/Thumbs/UpnpServer) + 桌面桩 + 测试"
javac -nowarn -encoding UTF-8 -d "$OUT" \
    "$SRC/ServerThread.java" \
    "$SRC/Thumbs.java" \
    "$SRC/UpnpServer.java" \
    $(find "$HERE/stubs" -name '*.java' | sort) \
    "$HERE/HttpFramingTest.java"

echo "[3/4] 跑测试   http://127.0.0.1:18080   /sdcard/DCIM -> $DRIVE/sdcard/DCIM"
echo "      （JVM 工作目录必须是 $DRIVE:\\ —— 见文件头坑②）"
cd "$DRIVE/"
java -Duser.dir="$DRIVE/" \
     -Dhttptest.report="$(cygpath -m "$HERE/httptest-report.txt")" \
     -cp "$OUT" HttpFramingTest
rc=$?
cd "$HERE"

echo "[4/4] 完成（退出码 $rc）。完整报告（UTF-8）：$HERE/httptest-report.txt"
echo "     夹具占 $(du -sh "$HERE/_drive" 2>/dev/null | cut -f1) 磁盘；清理用: bash run.sh --clean"
exit $rc
