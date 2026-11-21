# TEST-REPORT — OsmoWatch（大疆传片）

## 结论

- **已在真机跑通完整链路**（Galaxy Watch7 + Osmo Pocket 4P）：BLE 扫描 → 配对 → 取热点凭据 →
  加入相机 Wi-Fi → UDP 数据链 → 读取媒体清单 → HTTP 下载到相册。
- 2026-11-21 针对相机固件更新后的连接不稳定做了修复，真机复验**点一次连上 2/2**。
- 7 个桌面自测中 6 个全绿；`DatalinkSelfTest` 有 2 条断言失败，均在 UDP 语义之外，不在应用数据路径上（见「已知问题」）。

| | |
|---|---|
| 构建产物 | `out/OsmoWatch.apk`，约 120 KB，debug 证书签名 |
| 包名 / 版本 | `com.watch.osmo` / 0.2.7（versionCode 9） |
| minSdk / targetSdk | 29 / 30 |
| ABI | 无 native 库，32 位手表可运行 |
| 真机 | Galaxy Watch7（SM-L3150），Android 16，armeabi-v7a |
| 相机 | 大疆 Osmo Pocket 4P（协议同时兼容同系列其他机型） |

---

## 1. 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

`aapt2 compile` → `aapt2 link` → `javac --release 8` → `d8` → 打包 → `zipalign` → `apksigner`。

- 37 个源文件编译出 143 个 class，`classes.dex` 约 226 KB，APK 约 120 KB；
- `aapt dump badging` 无 `native-code` 行，确认 32 位可跑；
- 构建前需要 JDK 17+ 与 Android SDK（build-tools 30.0.3 + platforms/android-30），路径写在
  `build.local.ps1`（已 gitignore）；`aapt2` / `zipalign` 对中文路径敏感，脚本会自动建 ASCII junction 绕开。

---

## 2. 桌面自测

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-tests.ps1
```

| 测试 | 结果 | 覆盖 |
|---|---|---|
| `DumlSelfTest` | ✅ 74 / 0 | CRC8/CRC16、DUML 帧编解码、配对/Wi-Fi/会话命令字节结构 |
| `ManifestSelfTest` | ✅ 342 / 0 | 真机抓包校验清单解析，含 14 个夹具回归与健壮性用例 |
| `HttpSelfTest` | ✅ 91 / 0（挂 android.jar 后 117 / 0） | mock 相机：HEAD、Range 206、断点续传逐字节一致、并发上限 |
| `BleSelfTest` | ✅ 55 / 0 | 真机广播样本识别机型（Pocket 4P = 0x22），空/短载荷容错 |
| `DatalinkSelfTest` | ⚠️ 317 / 2 | UDP/routing 头、seq 规则、握手端口回退、清单切片（2 条见下） |
| `PipelineSelfTest` | ✅ 74 / 0 | 真机抓包端到端：拆包 → 两种喂入路径 → 重组 → 解析 → 与 golden 逐字段比对 |
| `RoundGeometryTest` | ✅ 全过 | 圆屏几何：`halfWidthAt`、各界面 band 四角在圆内、栅格首末行可见性 |

---

## 3. 真机验证

### 3.1 完整链路（2026-10-18 首次跑通，2026-11-21 复验）

| 步骤 | 结果 |
|---|---|
| BLE 扫描 | 识别到相机，名称与机型正确 |
| 配对 | 首次需在相机屏幕确认，之后走「已配对」快速路径 |
| 取热点凭据 | 通过 BLE 拿到 SSID 与密码 |
| 加入相机 AP | 成功，拿到 `192.168.2.x/24`，网关 `192.168.2.1` |
| UDP 数据链 | 9004 端口握手成功，进入播放模式确认 |
| 媒体清单 | 45 个文件（照片 + 视频） |
| HTTP 下载 | 下载并写入 `DCIM/OsmoWatch/`，相册可见 |

### 3.2 相机固件更新后的连接修复（2026-11-21）

固件更新后，相机在 `07/47 ConnectToWiFi` 之后 5–6 秒必定断开 BLE（GATT status=8）——
它要把射频切给热点。旧代码把它当致命错误，顺手撤销了**还在关联中**的 Wi-Fi 请求，
于是「几十次才能连上一次」。

修复后真机复验（点一次，客户端自己完成连接）：

| 次 | 耗时 | 过程 | 结果 |
|---|---|---|---|
| 1 | 37.6 s | BLE 断开被正确忽略，Wi-Fi 首轮 20 s 超时后自动重试，4 s 连上 | 45 个文件 |
| 2 | 15.0 s | 无重试，一次成功 | 45 个文件 |

两次会话均无错误、无空指针。

### 3.3 界面相关修复

- 相册首行不再被顶部页签遮挡（顶部留白 122px），末行可滚动到按钮上方；
- 照片铺满整屏（`ScrollView.setClipToPadding(false)`），按钮悬浮在照片上并带渐变；
- 滚动停止后重新请求可见格子的缩略图，不再出现「只加载一部分」；
- 「取消连接」按钮在连接期间生效；
- 连接页副标题与相册页头显示实际机型（来自 BLE 广播）。

截图：[grid-rest.png](docs/images/grid-rest.png)、[grid-bottom.png](docs/images/grid-bottom.png)（预览模式）。

---

## 4. 已知问题

| 项 | 说明 |
|---|---|
| `DatalinkSelfTest` 2 条失败 | 一条把单个 UDP 数据报拆成两半分次喂入（UDP 语义上不会发生），一条是遥测字段 `subSeqFirst()` 差 1；均不影响应用实际数据路径 |
| 缩略图续载 | 仅在预览模式验证过「滚动后重新发起请求」，真实相机下的完整加载待复测 |
| 其他相机型号 | 协议与机型表支持 Pocket 3 / 4、Action 系列等，但只在 Pocket 4P 上做过真机验证 |
| 签名 | 使用本机 debug 证书；与商店版签名不同，覆盖安装其他签名的旧版需先卸载 |

---

## 5. 复现

```powershell
# 构建
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

# 桌面自测
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-tests.ps1

# 装到手表并抓日志/截图（会安装 APK）
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-on-watch.ps1 -Serial <IP:端口>
```

> 注意：手表加入相机热点后无线 adb 会断开（`offline`）。在相册页长按标题断开相机，
> 约 3–5 秒后 adb 会以新端口回来。
