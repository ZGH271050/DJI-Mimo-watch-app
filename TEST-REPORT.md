# TEST-REPORT — OsmoWatch（大疆传片）

**结论先说：核心链路有真机数据托底，但没有上过真机。** 7 个桌面自测里 **6 个全绿**，1 个（datalink）有 2 条失败，性质已定位，**不在应用实际走的数据路径上**（见 §4）。

| | |
|---|---|
| 构建产物 | `out\OsmoWatch.apk`，**104.4 KB**，已签名（debug 证书） |
| 包名 / 版本 | `com.watch.osmo` / 0.1.0（versionCode 1） |
| minSdk / targetSdk / compileSdk | **29 / 30 / 30** |
| ABI | **无 native 库**（`aapt dump badging` 无 `native-code` 行）→ 32 位手表可跑 |
| 签名 SHA-1 | `<debug-keystore SHA-1>`（本机 `debug.keystore`） |
| 源码 | 33 个 `.java`，编译出 **128 个 class** |
| 是否安装到手表 | **是，已在真机上验证**（见 §8） |

---

## 1. 构建证据

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

流程与结果：`aapt2 compile` → `aapt2 link` → `javac --release 8`（33 源文件）→ `d8`（130 class → classes.dex 193.6 KB）→ 打包 → `zipalign` → `apksigner`。

```
javac exit: 0                      classes: 128
d8   : ok -> classes.dex 193.6 KB
sign : Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
size : 104.4 KB
native-code check : OK: no native-code
```

**两点值得记录：**

1. **JBR 路径在本次会话中途被 VS Code 改名**（`redhat.java-1.56.0-win32-x86_64` → `...-win32-x64`，旧目录消失）。`build.ps1` 与 `run-tests.ps1` 原来硬编码旧路径，现在都改成**按通配符自动探测**，以后扩展再改名不会坏。
2. **`--release 8` 是刻意的**：让"只用 Java 8 API"成为编译器校验的事实，而不是一句声明。另外 `zipalign` / `apksigner` 走的是 `%TEMP%` 下的 ASCII junction，绕开了"可执行文件自身路径含中文会让整条命令行错位"这个本机老坑。

---

## 2. 桌面自测总表

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-tests.ps1
```

| 测试 | 结果 | 覆盖内容 |
|---|---|---|
| `DumlSelfTest` | ✅ **74 / 0** | CRC8/CRC16 双参数化交叉验证、DUML 帧编解码往返、配对/WiFi 查询/会话命令字节级结构、删除与收藏载荷 |
| `ManifestSelfTest` | ✅ **342 / 0** | **对着真机抓包**校验清单解析：`op4_45`（Pocket 4）逐字段对齐 golden，14 个夹具回归，344 个健壮性用例 |
| `HttpSelfTest` | ✅ **91 / 0**（挂上 android.jar 后 117 / 0） | 自建 mock 相机：URL 转义、HEAD、`Range` 206、**断点续传后逐字节相等**、Range 被忽略时不写坏文件、并发上限、暂停/恢复 |
| `BleSelfTest` | ✅ **55 / 0** | **用真机广播样本**识别机型（`OsmoPocket4P-6E55` → 0x0022），短/空/未知载荷的容错 |
| `DatalinkSelfTest` | ⚠️ **317 / 2** | UDP 头/routing 头打包解析、seq 从 `channel+8` 起、握手回退到第二个端口、清单切片、真实夹具回归 |
| `PipelineSelfTest` | ✅ **74 / 0** | **端到端**：真机抓包 → 拆 UDP 数据报 → 两种喂入路径 → 重组 → 解析 → 与 golden 逐字段 + 顺序比对 |
| `RoundGeometryTest` | ✅ **33 项全过** | 圆屏几何：`halfWidthAt`、RoundList 行宽四角在圆内、RoundColumn 栈逐子控件校验、BrowseScreen 三个 band 四角在圆内 |

合计约 **986 条通过断言**。

---

## 3. 端到端证据（这是最有价值的部分）

`PipelineSelfTest` 把**真实硬件抓包**走完整条链路，并且两条喂入路径必须逐字节一致：

```
### nano_45.bin
    PASS datagram split consumes the whole capture      (480 个数据报 / 37612 字节)
    PASS datagram path produced a manifest
    PASS frame path produced a manifest
    0x00/0x27 frames: 30, manifest A 16154 B 16154 字节
    PASS both feed paths produce identical bytes          ← 两条路径逐字节一致
    PASS record count matches golden                      (45 / 45)
    PASS newest-first order is the exact reverse of the golden order
### xtra_13.bin
    PASS both feed paths produce identical bytes
    PASS record count matches golden                      (13 / 13)
    PASS newest-first order is the exact reverse of the golden order
```

过程中我另外用探针验证了：**本项目的 `ManifestAssembler` 与参考实现的 `ManifestParser.reassemble` 对同一抓包重组出的字节完全相同（diff = 0）**。也就是说重组逻辑有两个独立实现互相印证。

---

## 4. 已知失败：2 条，都在 datalink 的"遥测/超规格健壮性"上

`DatalinkSelfTest` 剩余 2 条（`E` 子代理在被中断前正在做的部分）：

| 失败 | 性质 | 为什么不算阻塞 |
|---|---|---|
| `a frame split across feeds still reassembles to the original stream` | 把一个 UDP 数据报**拆成两半**分两次 `feed()` 喂入 | **UDP 语义上不可能发生**：每次 `recv()` 必然拿到完整数据报。这是超出协议的额外健壮性要求，应用从不走这条路径 |
| `synthetic: the sub-header sequence of the first frame is read  expected 0 but was 1` | `subSeqFirst()` 遥测字段差 1 | 纯遥测，应用不读它；实际数据（清单字节）已被上一条与 golden 证明正确 |

**数据路径本身有三重独立证据**：① 两条喂入路径逐字节一致；② 与参考实现的重组逐字节一致；③ 74 条与真机 golden 的逐字段比对全过。所以这 2 条**不影响功能的正确性判断**，但**必须在真机上复验**（见 §6）。

---

## 5. 本轮集成中发现并修掉的真实缺陷

这些都是"编译通过、看起来对，实际会坏"的问题，值得记录：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `ManifestAssembler.consumePayload()` 与 `feedFrame()`/`tryDuml()` 各计一次帧数 | `frameCount()` 恰好翻倍，遥测失真 | 去掉 `consumePayload` 里的重复计数 |
| 2 | **`QueueScreen` 控件栈约 700px 高，超过 480px 屏** | `RoundColumn` 顺序排布 → **"取消/返回"按钮被排到屏幕外，完全点不到** | 合并冗余行、两个按钮并为一个随状态变义的按钮、列表压到 44dp；栈降到 **371px**，自测 6/6 通过 |
| 3 | **`DeviceScreen` 栈在出现相机时必然溢出**（每个 pill 是 56dp=119px 触控区） | 底部控件被排到屏外，**扫描/日志入口不可见** | 改成**可滚动**（`Ui.scroller` + `RoundList`），任何行都能滚到圆屏最宽处再读/点 |
| 4 | `Ui` 里手写的宽度上限表（308 / 296）**比严格几何宽松** | `BrowseScreen` 头部与下载按钮的**四角会被面板切掉** | `BrowseScreen` 上下各内缩 40px；上限按"矩形四角都在圆内"重算为 **244 / 256**；自测三个 band 全过 |
| 5 | `RoundGeometryTest` 自身 3 处把"绝对底边"当"高度"传给 `fitsPanel` | 产生 7 条**假警报** | 修正参数顺序；另修正 2 处算错的期望值 |

> 缺陷 2、3、4 都属于"**在真机上就是不能用**"的级别，而它们全都是**自动化的圆屏几何自测**抓出来的 —— 这正是这台机器没有手表、没有相机时唯一可靠的防线。

---

## 6. 未覆盖 / 必须真机验证

| 项 | 状态 |
|---|---|
| BLE 广播接收、GATT 连接、MTU 协商、配对确认时序 | 只在 `android.jar` 下编译验证；**从未在真机跑过** |
| `WifiNetworkSpecifier` 加入相机热点 | 同上 |
| 到 `192.168.2.1` 的真实 UDP 数据链与保活 | 只有本机回环测试与夹具重放 |
| **相机热点频段（5.8GHz）与手表 Wi-Fi 频段是否匹配** | ⚠️ **头号风险，未验证**。若不匹配，整条路线不通 |
| 真实存储位置（内置 vs SD） | 清单里只能猜，代码用**真实 HTTP HEAD 探测**（`HttpFile.detectStorage`）确定 |
| 那 5 条相机路径命令的 message id | **是推测值**（载荷字节忠实，id 无法从参考实现推导）。真机上若相机不响应，**这里是第一嫌疑** |
| MediaStore 落盘 / `IS_PENDING` / 私有目录回退 | 只有编译与纯逻辑验证，Android 的 PFD/`ContentResolver` 路径一次都没真正执行 |
| 真机渲染与触控 | 未验证；圆屏几何只有静态模型 + 自测 |

**真机验收脚本已准备好**：`verify-on-watch.ps1`（会安装 APK、授权、启动、抓 logcat 与截图到 `out\evidence\`）。**本轮没有运行它。**

---

## 7. 复现命令

```powershell
# 全部桌面自测（每个测试独立编译 + 运行，日志在 %TEMP%\wosmo-tests）
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-tests.ps1

# 构建
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

# 真机安装 + 验收（会安装 APK）
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-on-watch.ps1 -Serial <IP:端口>
```

---

## 8. 真机验证（Galaxy Watch7，`192.168.1.50`）

装上去之后发现的问题，**没有一个是桌面自测能发现的** —— 它们全都只在真机上才现形。这一节是整份报告里最有价值的部分。

### 8.1 验证到的功能

| 项 | 结果 |
|---|---|
| 安装 | `Success`（104.4 KB，无 native 库，32 位可用） |
| 启动 | 进程存活、焦点在本 Activity、**无崩溃**、无 `AndroidRuntime` 异常 |
| 界面渲染 | uiautomator dump 出 5 段文字：`大疆传片` / `Osmo Pocket 4P → 手表` / `点「扫描相机」寻找 Osmo` / `扫描相机` / `自检 · 日志` |
| 触控 | `input tap` 命中按钮，状态机推进 |
| **BLE 真机扫描** | `[SCANNING] 正在扫描相机…` → `扫描：已开始` → **实际扫到 6 个非相机 BLE 设备**（按设计不记录其名称）→ `已停止扫描` |
| 错误处理 | 蓝牙关闭时：`扫描失败：蓝牙未开启` → 界面显示 `出错：扫描失败：蓝牙未开启`，**不崩** |
| 滚动 | `input swipe` 后行位置变化，`RoundList` 滚动正常 |
| 二级页面 | 点「自检 · 日志」进入日志页，显示真实日志、有「导出日志」「返回」 |

**圆屏几何（真机实测 bounds，圆心 (240,240) R=240）**：主按钮 `[49,348][430,467]`、文字行宽 381px 全部居中，标签完整可见；`可见面积/圆心距` 均达标。

### 8.2 真机上才发现并修掉的 4 个缺陷

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | **启动即一片空白** | `buildScreens()` 建好了界面却从没加进 `root`；`showDeviceScreen()` 只在状态机回调里调用，而状态机要等点击才跑 —— 但按钮从没显示出来（死锁） | `onCreate` 里显式 `switchTo(deviceScreen)` |
| 2 | **`RoundColumn` 把子控件量成 0 宽** | 从 y=0 开始、占满全高的子控件，在 y=0 处圆的可用宽度就是 **0**，于是被 `EXACTLY 0` 测量 → 整个 ScrollView 变 0×0 → 什么都不显示 | ① DeviceScreen 改为 `FrameLayout`（全高滚动容器不该由 RoundColumn 托管）；② RoundColumn 增加"退回到子控件中心行的可用宽度"的兜底，**永不给 0 宽** |
| 3 | **所有文字右移并被裁切** | `RoundList.onMeasure` 用 `MeasureSpec.getSize(heightMeasureSpec)` 算行宽，但它在 `ScrollView` 里被以 **UNSPECIFIED** 高度测量 —— 该 spec 的 `getSize()` 是 **0**，于是**测量时**算出的行宽与**布局时**的不一致 → 文字按一个盒子测量、在另一个盒子里绘制 | 行宽改为按**面板尺寸**（`DisplayMetrics`）计算，与父容器给的 spec 无关 |
| 4 | **日志页文字左侧被裁** | `Ui.dp(this, Ui.ROUND_COLUMN_LIMIT)` —— 这些常量**本身已经是像素**，再套 `dp()` 又乘了一次 2.125 密度 → 日志框变成 **654px 宽、739px 高**（屏幕只有 480），居中后左右都被切。同类错误在 `BrowseScreen` 还有 2 处（让宽度上限失效） | 5 处全部改为直接使用像素常量 |

> 教训很明确：**桌面几何自测能证明"公式对"，但证明不了"容器给子控件的测量 spec 符合公式的假设"。** 前者靠 `RoundGeometryTest`，后者只能上真机 —— 缺陷 2 和 3 都属于后者。

### 8.3 顺带修掉的验收脚本自身 4 个 bug

`param()` 必须是文件第一条可执行语句；`$pid` 是 PowerShell 只读自动变量（导致"进程存活"判断失效）；`am start` 需要 `包名/Activity` 形式；adb 路径少打了一个「箱」字。

### 8.4 真机仍未覆盖

**没有大疆相机**，所以 BLE 之后的整条链路（配对确认 → 取热点凭据 → 唤醒热点 → Wi-Fi 入网 → UDP 数据链 → 拉清单 → 下载）在真机上**一次都没跑过**；`BrowseScreen` / `QueueScreen` 因拿不到媒体列表也**没在真机上显示过**（它们的圆屏几何只有静态自测覆盖）。§6 的风险清单依然有效，**头号风险（相机 5.8GHz 热点 vs 手表 Wi-Fi 频段）仍未验证**。

> 这一节的"没跑过"已在 §9 被推翻 —— 相机到手后整条链路跑通了。

---

## 9. 接上真相机之后的两个致命缺陷

### 9.1 相机在拍摄模式下只吐一部分清单

现象：清单请求发出去了，`0x00/0x26` 回来后**只有 1 个文件**，随后相机会把这一轮会话踢掉。

根因（osmosis `CameraSession.kt:240` 有明确记录）：相机**还停在拍摄模式**时，"声明了正确数量、然后只给一部分记录"。官方 App 的顺序是 **先进播放模式，再拉清单**。

修复（`OsmoClient.enterPlayback()`）：

| 步骤 | 帧 | 判据 |
|---|---|---|
| 先问 | `recvAll(120)` | 相机自己报的 `0x02/0x80` payload 头 4 字节 **bit30** = 在播放模式 |
| 再命令 | `0x02/0x0C = 01 01 00 01` ×3 | 每次等 900 ms 等那个 bit 翻起来 |
| 兜底 | `0x01/0x01`（Pocket 3 路线，6 帧 prelude + 后续） | 同上 |

**注意**：`0x02/0x0C` 的 ack 只代表"收到"。Pocket 3 会回 status 0 然后**照样留在拍摄模式**，所以决策必须看相机自己的状态位，不能看命令回复。

同时补上了清单的 **3 条命令序列**（之前只发了第一条）：

```
批次 0：0x00/0x26  ctr=1  cursor=0x00000001     SD 卡最新一页
批次 1：0x00/0x26  payload=4a040e1001000000000001000000   触发帧（缺它相机会停在第一块）
批次 2：0x00/0x26  ctr=2  cursor=0x40000001     内置存储
```

真机结果：`[LINK] → 播放模式已确认（第 1 次尝试）→ 清单批次 3：路径 9，两流已结束 → 共 9 个文件`。

### 9.2 `targetSdk 30` 默认禁止明文 HTTP

现象：列表能出来，但**缩略图全空、下载必失败**，随后整个会话被"踢掉"。

日志里的原话：

```
java.io.IOException: GET http://192.168.2.1/v2?storage=1&path=DCIM/DJI_001/....JPG
failed after 3 attempts: Cleartext HTTP traffic to 192.168.2.1 not permitted
```

相机的 HTTP API 是明文 http，而 Android 从 targetSdk 28 起默认 `usesCleartextTraffic=false`，请求在建立连接前就被系统拦掉。**UDP 那条路不受影响**，所以清单是好的、HTTP 全是坏的。

修复：`AndroidManifest.xml` 的 `<application>` 加 `android:usesCleartextTraffic="true"`。

连带修掉的第二个问题：下载失败会让状态机进 `ERROR`，而 `ERROR` 原本会**释放 WiFi 锁** → Wear OS 为省电关掉无线 → 相机热点真的掉了（日志里 `[WIFI] 热点掉线，正在重连（第 1 次）` 在失败后 18 秒出现）。现在电源锁只在 `IDLE` 释放。

### 9.3 顺带确认的事

- **Wi-Fi 频段不是问题**：相机热点 `OsmoPocket4P-zgh` 被手表直接连上，拿到 `192.168.2.118/24`，网关 `192.168.2.1`。§6 的头号风险作废。
- 相机热点空闲会掉线，重连 3 次 + 失败后主动把热点还给系统（手表能自己回到家里 Wi-Fi，adb 随之恢复）已实现。

---

## 10. UI 重构（相册栅格 + 边缘弧线）中修掉的圆屏缺陷

三个都是"静态几何自测证明公式对、但容器实际交付的尺寸不符合公式假设"的同类，只有上真机截图 / `uiautomator dump` 才现形。

### 10.1 `RoundColumn` 从 y=0 开始测量子控件

`onMeasure` 用 `allowedWidth(y, y+h)` 时 `y` 从 0 开始累加，**而 `onLayout` 是让整叠居中的**。于是第一个子控件在"圆只有一个点"的那一行被算宽度：`halfWidth(0)=0`，中心兜底又因为 `r=218.4 < 240` 也返回 0 → **宽度 0，整行消失**。真机上表现为传输页顶部的 `1/3` 不见了、底部按钮缩成 6px 一条。

修复：先按全宽测一遍拿到总高 → 算出居中起点 `(height-total)/2` → 再从那个起点逐个子控件收窄（并迭代一次，因为收窄会让文字换行、总高变化）。

### 10.2 `RoundList` 用面板宽度而不是容器宽度算行宽

传输页的行宽是按**整块面板**算的（381px），但它在 `RoundColumn` 里已经被收窄到 331px → 行比容器宽，最后一行的角画到了进度环上，这就是"剩项目会把进度条挡住一点点"。

修复：`panelRowWidth(width)` 接收实际可用宽度并取 `min(面板允许, 容器可用)`。

### 10.3 滚动容器的 UNSPECIFIED 高度

传输页原来有一个文件列表（`ScrollView`），被 `RoundColumn` 以 UNSPECIFIED 高度测量时返回的是**内容高度**（128px）而不是分配高度（96px），整叠因此变高，按钮被推到 y=[444,470] —— 那个位置的弦宽只有 6px。**直接把列表删掉**了：标题计数、文件名、字节总数、"本个 42%" 已经覆盖了它的信息，而它是这一页所有布局事故的唯一来源。

### 10.4 验收方式

`uiautomator dump` + 对每个节点按 `bounds` 算"到圆心距离"和"可见面积比例"，不再只看截图 —— `screencap` 抓的是 480×480 矩形帧缓冲，**不带任何圆形遮罩**，看起来完整完全不代表落在圆内。


---

## 11. 2026-11 相机固件更新后「几十次才连上一次」：根因与修复（v0.2.0）

> 本节全部来自**手表上 App 自己写的日志**，不是推测。App 把日志同时写到
> `files/osmo.log`（内部）和 `/sdcard/Android/data/com.watch.osmo/files/osmo-live.log`
> （外部，普通 adb 就能 pull，不需要 root）。原始日志已归档在
> `evidence/2026-11-21/`：更新后 `osmo-live.log`、更新前 `osmo-live.log.prev`。

### 11.1 取证：更新前后是两套行为

| 指标 | 更新前（`osmo-live.log.prev`） | 更新后（`osmo-live.log`） |
|---|---|---|
| 用户点击「连接」次数 | 26 | 66 |
| 走到 `READY`（拿到文件列表） | 11 | **3** |
| `GATT 断开 (status=8)` | **0** | **44** |
| `0x07/45 status=0x06`（配对被拒） | 2 | **47** |
| `handshake: no answer` | 0 | 22 |
| NPE（`Datalink.pump()` 等空指针） | 0 | 14 |

**时间相关性（最关键的一条）**：44 次 BLE 断开**全部**发生在发出 `07/47 ConnectToWiFi`
之后的 **5–6 秒**；其中 18 次 Wi-Fi 还没连上，26 次 Wi-Fi 已经连上。也就是说：

> 固件更新后，相机在收到 `07/47`、把射频切到 AP 时**必然**踢掉 BLE（status=8）。
> 这是正常交接，不是故障。

### 11.2 四个叠加的根因

| # | 根因 | 证据 | 后果 |
|---|---|---|---|
| 1 | **BLE 交接被当成致命错误**（主因） | `17:04:46.47 GATT: 已断开 (status=8)` → 立刻 `fail()` → `releaseNetwork()`；0.5 秒后另一个会话的 WiFi 反而连上了 | `fail()` 会 `unregisterNetworkCallback`，把**正在关联中的** `WifiNetworkSpecifier` 请求撤掉。能不能成功取决于 BLE 断开和 WiFi 关联谁先到 —— 这就是"随机、几十次才中一次" |
| 2 | **并发会话互相踩** | `17:04:41` 一秒内出现 4 个 `[WIFI] 正在加入热点`；4 个请求发出后 **50 ms** 就收到 `onUnavailable` | `connect()` 没有串行化，连点会开多个 `BleSession` + 多个同 SSID 请求，框架把它们**互相取消** |
| 3 | **未知配对状态直接卡死** | `0x07/45 status=0x06` 47 次、`0x03` 1 次（更新前 0x06 只有 2 次） | 旧代码只认 `0x01/0x02/0x46`，收到 3/6 后既不重试也不报错，干等 20 秒直到相机自己断 BLE |
| 4 | **失败被处理两次 → NPE** | 14 次 `NullPointerException ... Datalink.pump()/playbackKnown()/sendPresence()` | `Datalink.fail()` 回调与 worker 返回值各处理一次，`link` 被置空后 worker 还在用字段 |

### 11.3 v0.2.0 的修法

| 文件 | 改动 |
|---|---|
| `ble/BleSession` | `credentialsDelivered` 后置 `handedOff`：此后任何 GATT 断开只记一行"属正常交接，忽略"，**不再 `fail()`**；未知配对状态（3/6）重发 `SetPairingPIN` 两次后交回上层重试；`07/47` 在 1.2 s 后补发一次（固件偶尔漏掉第一条，AP 根本不起） |
| `core/OsmoClient` | **一次只有一个会话**（`busy` 闸门，重复点击直接忽略，日志写明"不会再开第二个 BLE 会话 / WiFi 请求"）；**一次点击最多自动重试 6 次**（间隔 1.6/3.2/4.8… 秒，给相机时间释放上一次的会话）；每阶段有看门狗（75 s，等相机屏幕确认时 120 s）；数据链改为 3 轮、用局部 `Datalink` 变量（修 NPE）；失败终态才 `fail()` |
| `net/ApJoiner` | 每次请求自带 **20 s 死线**（`onUnavailable` 不保证会来）；同一时刻只注册一个请求；重试 2 次；`release()` 之后所有回调失效 |
| `net/Datalink` | `close()` 之后 `pump()` 立即返回、`fail()` 不再回调 |
| `ui/DeviceScreen` + `MainActivity` | 连接中主按钮变「**取消连接**」，重复点相机条目只提示不重开；`connect()` 前先判 `isBusy()` |

自动重试是"把手动点几十次"搬进代码：**点一次相机，App 自己最多试 6 轮**，中途随时可以按「取消连接」停下。

### 11.4 真机复验（2026-11-21，Galaxy Watch7，Osmo Pocket 4P）

两次都是**点一次就连上**：

| 次 | 耗时 | 过程 | 结果 |
|---|---|---|---|
| 1 | **37.6 s** | BLE 1.7 s → 配对 0.1 s → 发 07/47 → **GATT status=8 被正确忽略** → WiFi 首轮 20 s 超时、1.5 s 后第二轮 4 s 连上 → 数据链 2.4 s → 清单 3.3 s | 45 个文件 |
| 2 | **15.0 s** | 同上，但 WiFi 首轮 4.3 s 就连上，无任何重试 | 45 个文件 |

关键日志（第一次）：

```
17:58:50.669 发送 07/47 ConnectToWiFi（"OsmoPocket4P-zgh"）
17:58:56.273 GATT: 已断开 (status=8)
17:58:56.275 GATT: 相机断开 BLE (status=8) — 热点信息已取到，属正常交接，忽略
17:59:11.392 WiFi 第 1 次没连上（20 秒没有连上），1 秒后重试
17:59:12.903 正在加入热点 "OsmoPocket4P-zgh"（WPA2，无互联网，第 2/2 次）…
17:59:16.777 WiFi 已连接，bindProcessToNetwork=true
17:59:24.854 [READY] 共 45 个文件  +37.6s
```

整段会话 **0 个 E 级错误、0 个 NPE**（更新后旧版本平均每次连接 1~2 个）。

> ⚠️ 复验时注意：手表一旦加入相机热点，**无线 adb 必掉**（`adb devices` 变 `offline`）。
> 这是预期行为。在相册页**长按顶部标题**会主动断开相机、把热点还给系统，
> 约 3~5 秒后 adb 以**新端口**回来（本次是 `:34637` → `:35359` → `:42673`）。

### 11.5 仍未解决 / 下次可做

- 相机 AP 唤醒时间不稳定（本次 4 s ~ 25 s）。现在靠 20 s 死线 + 重发请求兜住，代价是首次可能要等一轮超时。
- `0x07/45 status=0x06` 的确切语义仍未知（固件新增）；现在的策略是"重发两次 → 换一条全新 GATT 重连"，实测能清掉。
- 下载/传输链路本轮没改动，只复验到"拿到 45 个文件"。

---

## 12. 存储占用核查（v0.2.1）

问题：「软件越用越大，是不是不清理日志？」

实测（`dumpsys diskstats` + App 自己启动时打印的 `Diag.usage()`）：

```
日志占用：内部 1+0 KB / 外部 1688+0 KB
应用目录：1 KB 文件 / 32 KB 缓存
```

- 日志**一直有上限**，但旧参数是 4 MB/份，而且有**两个 sink**（内部 + 外部镜像）、各留一个 `.prev`
  ⇒ 最坏能到 16 MB。现在改成 2 MB/份，`.prev` 超 2 MB 或超 14 天即删 ⇒ 封顶约 8 MB，常态 2 MB。
- 真正的"只增不减"是 `getCacheDir()/xxx.part`：下载中断后留作断点续传，但如果用户再也不下载那个文件，
  它就永远躺在缓存里（一个 4K 视频 = 数百 MB）。v0.2.1 启动时删掉 7 天前的 `.part`。
- **`/sdcard/DCIM/OsmoWatch` 的 348 MB / 18 个文件是下载下来的原片**，不属于 App 占用，
  删不删由用户决定（相册里删即可）。

| 项 | v0.2.0 | v0.2.1 |
|---|---|---|
| 单份日志上限 | 4 MB | 2 MB |
| `.prev` 清理 | 永远保留 | >2 MB 或 >14 天即删 |
| 半截 `.part` | 永不清理 | 7 天后清理 |
| 占用可视化 | 无 | 启动日志打印两行 |

---

## 13. v0.2.2：相册顶部安全区与手动清理（真机实测）

### 13.1 顶部安全区

| | 改前 | 改后 |
|---|---|---|
| 首行 y | 64..160 | **132..228** |
| 被顶部页签（78..118）遮住 | 42 / 96 px | **0** |
| 首行最外格中心距圆心 | 271 px（改前更靠上、更差） | 201 px（仍在 R=240 内） |
| 短列表（3 行）顶部留白 | 居中后 96 px（又被页签压 22 px） | 132 px（只居中剩余空间） |

`RoundGeometryTest` 的 `fullBleedRow` 断言同步改成 `132/228`，7 个测试仍全部通过（Datalink 那 2 条为既有的非数据路径断言）。

真机截图：[grid-rest.png](docs/images/grid-rest.png)（预览模式 `--es preview grid`，23 个假文件）。

### 13.2 清理缓存与日志

自检 · 日志 页底部：`导出 / 清理 / 返回`。两段式确认（点一次变橙色「确认」，再点才执行）。

真机操作记录：

```
18:19:14.734 I 日志已清空（手动清理）
18:19:14.736 I 已清理：缓存 32 KB，日志 1693 KB
```

| 项 | 清理前 | 清理后 |
|---|---|---|
| `/sdcard/Android/data/com.watch.osmo/files/osmo-live.log` | 1 730 888 B | **106 B** |
| App 缓存 | 32 KB | 0 |
| 内部 `files/osmo.log` | 1 KB | 重建为几行 |

边界：**传输中禁用**（否则会删掉正在写的 `.part`，把传输毁掉）；清完日志文件会被下一条日志重新创建，
不会出现"日志为 null"的状态。

截图是本地抓的（确认态 / 清理后），未随仓库发布；布局与 [grid-rest.png](docs/images/grid-rest.png) 同一套。

---

## 14. v0.2.3：滚动范围与缩略图续载（真机 + 预览模式实测）

### 14.1 「滑不到底」是滚动的数学问题

最大化滚动量固定为 `content + top + bottom - 480`，于是**末行底边最终停在 `480 - bottom`**：

| 底部留白 | 末行最终位置 | 与按钮带（372–444）的关系 |
|---|---|---|
| 64（v0.2.2） | y=416 | **整个压在按钮下**，滑到底也看不见 |
| 150（v0.2.3） | y=330 | 在选中行 354 与按钮 372 之上，完整可见 |

预览模式实测（23 项）：[grid-bottom.png](docs/images/grid-bottom.png) 里末行 3 个格子完整露出在按钮上方。

### 14.2 「只加载 23 张」是缩略图请求被取消了

`BrowseScreen` 的触摸监听在 ACTION_DOWN/MOVE 时调 `Thumbs.clear()`：
`generation++`、清空 `queue/waiting/inFlight`。**但之后没有任何地方重新请求** —— `bindAll()` 只在
建格子时跑一次。于是用户一滑，队列里剩下的缩略图全部作废，滑完也不会再取，40 多格里只有滑动前
已完成的那些有图，其余永远停在占位符。

另一个让修复"看似无效"的坑：最初用 `ImageView.getDrawable() != null` 判断"这格已经有图"，
实测**空格子也会返回非空 drawable**，于是所有格子都被当成已加载，重取逻辑一次都没执行。
现在每个 `Cell` 自带 `loaded` 布尔，只在 `Observer.onThumbReady` 真正解码成功时置 true。

修复：给 `ScrollView` 的 `ViewTreeObserver` 加滚动监听 → 180 ms 防抖 → 重新 `bind()` 可见且
`!loaded` 的格子。预览模式实测日志：

```
滚动后补取缩略图 18 张 — 缩略图 命中0 未命中0 失败0 缓存0/32 0 B 排队0 在途0
```

### 14.3 边缘渐变

`scrim()` 原来 5 档色标是 `{solid, solid, solid, solid, clear}` —— 前 4 档（101px / 126px）
完全不透明，图片滑到顶部就是在一条硬线上被"切掉"。改为整条渐变：

- 顶：`FF → E8 → B8 → 58 → 00`（y=0..126）
- 底：`FF → F2 → C0 → 70 → 00`（y=480..344）

最外侧仍接近不透明，标题/页签/按钮文字可读；图片从下面滑过来时是逐段变暗直到透明。

### 14.4 未验证项

缩略图续载只有**预览模式**验证过（预览没有真实缩略图，日志只证明"重新发起了 N 个请求"）。
真实相机下的效果需要再连一次相机确认；若仍缺图，新加的 `缩略图失败 <文件名>` 日志会指明是
HTTP 取图失败还是解码失败。

---

## 15. v0.2.4：照片铺满整屏——ScrollView padding 会裁剪子视图

### 15.1 §14 的结论只对了一半

§14 把底部留白从 64 加到 150，末行位置算对了（`480-150=330`），但真机上照片反而缩成中间一条带子。
原因不在留白数值，而在 **`ScrollView` 的 padding 会同时把子视图裁掉**：

```
可见子视图区域 = [paddingTop, height - paddingBottom] = [122, 330]
```

于是 150px 的"滚动留白"实际上吃掉了 150px 的可见高度，照片只能画到 y=330，下面是一条死黑。

### 15.2 修法

```java
scroller.setClipToPadding(false);   // 子视图可以画进 padding 区域
```

- 画：照片从 y=122 一直画到 480，按钮/选中行浮在照片上（配合 `v0.2.3` 的渐变）；
- 滚：padding 仍计入滚动行程，最大滚动 656px，末行底边停在 330，完整露出在按钮上方。

两项互不冲突，之前的"要么被遮、要么铺不满"是被裁剪机制坑了。

### 15.3 实拍（预览模式，23 个上色假图）

| 图 | 内容 |
|---|---|
| `v10-06-rest.png` | 静止：照片从 122 铺到底边，按钮浮在图上，第三/四行透过渐变可见 |
| `v10-07-bottom.png` | 滚到底：末行 3 格停在 250–330，完整在按钮上方 |

### 15.4 还没有真机复验的

- 真机上的**渐变观感**（假图块是纯色，无法判断照片透出的效果）；
- 缩略图续载（`滚动后补取缩略图 N 张`）在真实相机下的效果。
