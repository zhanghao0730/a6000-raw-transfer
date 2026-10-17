# Sony A6000 · RAW 无线传输（相机端 App + 浏览器直连）

> 让 A6000 通过自带 WiFi 传输 RAW：相机上装一个 53KB 的 App「发送到手机 RAW」，
> 手机连相机热点、浏览器打开网页即可浏览和下载 ARW/JPG，零安装、零外网依赖。

**当前版本：v42**（2026-10-17）· 完整工程记录见 `A6000-发送到手机-RAW-链路.md` 与 `A6000-WiFi-RAW-方案.md`

---

## 一、最终形态

相机端 App 同时提供四样东西：

| 能力 | 说明 |
|---|---|
| **HTTP 服务 :8080** | 浏览器打开即用：日期分组 → 当日缩略图网格 → 点开大图 → 逐张下载/勾选/打包 zip |
| **UPnP/DLNA MediaServer** | SSDP 宣告 + ContentDirectory SOAP，Windows 资源管理器/电视直接发现 |
| **相机自开热点** | 反射索尼私有 `wifi-direct` 服务，不依赖原生「发送到智能手机」入口 |
| **双二维码面板** | ① 连热点码 ② 开网页码；左/右方向键切换；上/下键滚轮；码随屏幅自动放大 |

### 性能设计（v23–v34 的主线：相机 SoC 上零计算）

- 网格缩略图 `/x/`：**EXIF 小图直出**——ARW/JPG 里现成的 ~12KB 小图字节直吐，零解码零编码
- ARW 大图 `/p/`：**1.1MB 内嵌预览直出**（TIFF IFD 定点提取，Imaging Edge 同款思路）
- JPEG 大图 `/p/`：**原文件直出**，浏览器硬解
- 扫描：`Rec` 元数据快照 + 目录 mtime 验证缓存（没有 5 秒 TTL，没有排序 stat 黑洞）
- 后台预热：App 打开 8 秒后空闲线程预扫全卡 + 预抽最新 24 张小图
- 批量下载：**逐张下载队列**（Chrome 站点级"允许多个下载"授权一次即可），`打包zip` 为归档副选项

---

## 二、快速上手

### 相机端（构建 + 安装）

```bash
# 工具链（Windows 本机，均已具备）
#   javac 21（--release 8 语法）、build-tools 36.0.0 的 d8/aapt/apksigner
#   平台包 android.jar：cam-patch/platform10/（来自 dl.google.com android-2.3.3_r02）
cd cam-patch/arwserver
bash build.sh          # → arwserver.apk（AOSP testkey 签名，与相机上已装版本同签名）

adb connect <相机IP>:5555
adb install -r arwserver.apk
```

> 签名约束：相机上已装的同包名 App 用 AOSP testkey（`fw/keys/`），换钥匙会签名冲突。

### 手机端

1. 相机应用列表 → 打开**「发送到手机 RAW」** → 面板出现二维码
2. 扫 ① 连相机热点 → 扫 ② 打开下载页（左右方向键手动切码）
3. 日期卡片点进去 → 勾选照片 → **逐张下载**（首次浏览器会问一次"允许多个下载"，选允许）
4. 或点 **打包zip** 一次拿一个归档文件

### 手机端备选（Termux 脚本，支持并行+断点续传）

`android-arw-pull/arw_pull_http.py`（HTTP 版）、`sony_arw_pull.py`（UPnP 版）

---

## 三、验证

桌面回归工装（真机不接也能跑，相机代码 + Android 桩编到 PC）：

```bash
cd cam-patch/tools/httptest && bash run.sh
# 16 组 / 92 断言：HTTP 分帧、Range、路径穿越、zip 结构、
# IFD 提取等价性（逐字节 sha256 对照独立实现）、日期分组、/x/ 直出
```

---

## 四、目录结构

| 路径 | 内容 |
|---|---|
| `cam-patch/arwserver/` | ★ 主产物：8 个 Java 源码、`build.sh`、`arw.keystore`、当前 APK |
| `cam-patch/tools/` | 桌面验证工装（httptest、ThumbScanBench） |
| `cam-patch/rawswitch/`、`cam-patch/rawbypass/` | 历史路线（RAW 开关 App / 原生 .so 补丁），证据保留 |
| `android-arw-pull/` | 手机端 Termux 拉取脚本（HTTP / UPnP 两版） |
| `dlna/cam_dlna_probe.py` | 相机 DLNA 目录枚举探测 |
| `fw/` | 固件离线逆向：反汇编/寻址脚本、`keys/`（testkey 签名，**构建必需**）、omp_* 源码 |
| `pmca/`、`pmca-exe/` | PMCA 工具与相机 USB 探测脚本 |
| `sonyftp-src/` | SonyFTP 参考实现（独立 git） |
| `A6000-发送到手机-RAW-链路.md` | 工程主文档：闸门清单、地址表、§9 全部版本演化 |
| `A6000-WiFi-RAW-方案.md` | 方案总纲 + 使用手册 + 固件解密/RAW 开关记录 |
| `项目文件地图.md` | 全项目文件索引 + 整理记录 |

**不入库**（见 `.gitignore`，本机保留）：固件 dmg/7z、测试 ARW/JPG 基准、平台包、反编译参考（`imagingedge-ref/` 等）。

---

## 五、版本史要点

| 版本 | 内容 |
|---|---|
| v13–v19 | 初版：HTTP+UPnP+热点三合一，Termux 路线 |
| v20–v22 | 热点 API 修正、缩略图分块扫描 50×、页面三处串行拆解 |
| v23 | **IFD 定点提取**（抄 Imaging Edge）+ 字节计权 LRU |
| v24–v25 | 二维码切换交互（自动切换被否，改左右键手动） |
| v26–v27 | **零计算路线**：/x/ EXIF 小图直出、JPEG 原文件直出、解码并发 2 |
| v28 | 日期分组首页 + 面板二维码放大 |
| v29→v30 | 预打包真机失败（FUSE `getUsableSpace()` 返 0）→ 回退同步流 |
| v31 | 二维码置顶再放大 + 上/下键滚轮 |
| v32 | **去打包化**：逐张下载队列（Chrome 多下载授权），zip 降为副选项 |
| v33 | 扫描缓存重构：Rec 快照 + 目录 mtime 验证（消灭 9000 次 FUSE stat） |
| v34 | 后台扫描预热 + QR 重绘跳过 + cleanup 降频 |
| v35 | 日期卡片星期显示修复（相机 Locale 缺中文数据） |
| v37 | 网页文案精简（删 footer、短文案省流量） |
| v38–v39 | 首页去「按日期」标签与统计行 |
| v40 | 网页动效（进场 stagger / 勾选 pop / 图片淡入，GPU 合成） |
| v41 | 全代码性能审查（LRU 计数漂移 / 面板 setText 空转 / 分配与 format 缓存） |
| v42 | 日期锁定（最新日期开放，其余密码解锁）+ 相机键盘密码设置 + ScalarInput 键码接入 |

详细演化与踩坑记录：`A6000-发送到手机-RAW-链路.md` §9.1–§9.21。

---

## 免责声明

- 本项目仅供**个人学习与自有设备**使用，与 Sony Corporation 无任何关联，也未获得索尼任何形式的认可或背书。Sony、A6000、PlayMemories 等名称与商标归其权利人所有。
- 相机端 App 借助索尼官方的 PlayMemories 相机应用安装机制部署；安装、签名、运行本软件所产生的任何风险（设备异常、数据丢失、保修状态变化等）**由使用者自行承担**。
- 本项目不修改相机固件，不绕过任何在线服务或付费机制；`fw/keys/` 使用的是 Android 开源项目（AOSP）公开的测试密钥，仅用于本地签名，不代表任何官方身份。
- 网页端的日期解锁密码是**访问控制而非安全边界**——请勿在不受信任的网络环境中使用。
- 仓库中的逆向分析仅针对公开分发的软件与固件包，用于理解设备行为；请遵守你所在司法辖区的法律。

## 借鉴与致谢

本项目的实现大量参考了以下开源工作（按重要性排序）：

| 来源 | 借鉴内容 |
|---|---|
| [Imaging Edge Mobile](https://www.sony.com/zh-cn/electronics/support/cameras-camcorders/articles/00020243)（索尼官方，逆向分析见 `imagingedge-ref/`，未随仓库分发） | 本地 ARW 处理走 TIFF IFD 定点提取而非全文件扫描——`/p/` 预览直出与缩略图管线的核心思路；字节计权 LruCache（maxMemory/8） |
| [ma1co/OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) | `com.sony.scalar.sysutil.ScalarInput` 完整键码表（120+ 键）——相机物理键盘接入的权威来源 |
| [SonyFTP](https://github.com/)（`sonyftp-src/` 参考实现，未随仓库分发） | 相机端 App 的按键处理写法（`event.getScanCode()` + ScalarInput 桩）、WiFi Direct 配置、项目结构 |
| [OpenMemories-Tweak](https://github.com/ma1co/OpenMemories-Tweak) | 相机应用部署机制（Backup_read/write）与 DACM AppInfo 上报的理解 |
| [client-zip](https://www.npmjs.com/package/client-zip) | 浏览器端流式 zip 组装的思路（v36 手机端打包：`ReadableStream` → `Response(stream).blob()`，STORED + CRC32 查表为自行实现） |
| [Nextcloud](https://github.com/nextcloud/server) | 批量下载的交互范式（逐张下载授权 vs 打包 zip 的双路径） |
| [AOSP](https://android.googlesource.com/) | `build-tools` d8/aapt/apksigner 工具链与 testkey 签名体系 |

------

## 六、已知坑（复现时先看这里）

1. **d8 必须 `--release`**：不带会带调试信息且与装机版本不一致（差 9.9%）
2. **FUSE `getUsableSpace()`**：相机 `/sdcard` 查不到路径时返回 0，不能当"没空间"（v29 教训）
3. **API 10 平台包没有 `View.canScroll()`**、Worker 内部类不许有 static 成员
4. **STORED zip 不能用 data-descriptor**：Java/Android `ZipInputStream` 拒绝（`only DEFLATED entries can have EXT descriptor`），要写 zip 须 CRC 先算或 RandomAccessFile 回填
5. **测试夹具路径**：`/sdcard` 是盘符相对路径，Windows 上用 subst 虚拟盘（见 httptest/run.sh 注释）

---

## 七、当前状态与断点

- **v20–v34 全部未上真机验证**（桌面 92 断言全绿，但真机断点：v29 预打包失败后已回退；v31 码放大/滚轮、v32 逐张队列、v33 扫描缓存、v34 预热均待实测）
- 真机装法：`adb connect <相机IP>:5555 && adb install -r arwserver.apk`
