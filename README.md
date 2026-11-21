# OsmoWatch（大疆传片）

把大疆 Osmo 相机里的照片和视频下载到 Wear OS 手表的工具：**连相机 → 浏览媒体 → 下载到手表相册**。

- 纯 Java，**无 native 库**，32 位手表可跑
- 圆屏界面，5 列满铺相册
- 支持断点续传、断线重连、失败自动重试

## 支持设备

| | |
|---|---|
| 手表 | Galaxy Watch7（SM-L3150）/ Wear OS / Android 16 / armeabi-v7a / 480×480 圆屏 |
| 相机 | 大疆 Osmo 系列（Pocket 3 / 4 / 4P、Action 2–6、Nano、360、Mavic 3、Neo 2） |

## 安装

1. 下载 [Releases](https://github.com/ZGH271050/DJI-Mimo-watch-app/releases) 里的 `OsmoWatch-*.apk`
2. `adb install -r OsmoWatch-*.apk`，或在手表上直接安装
3. 首次打开授予蓝牙 / 定位 / 通知权限

## 使用

1. 相机开机
2. 打开「大疆传片」→ 点「扫描相机」→ 点相机条目
3. 首次连接需要在相机屏幕上确认配对
4. 之后自动完成：取热点凭据 → 加入相机 Wi-Fi → 建立数据链 → 读取清单
5. 选文件 → 点下载，文件保存在 `DCIM/OsmoWatch/`
6. 相册页**长按标题**可主动断开相机

## 架构

```
ble/          BLE 扫描、配对、取热点 SSID/密码、唤醒热点
net/ApJoiner  WifiNetworkSpecifier 加入相机 AP + bindProcessToNetwork
net/Datalink  UDP 9004/10004 握手、保活、DUML 帧收发
proto/        清单重组（ManifestAssembler）与解析（ManifestParser）
net/HttpFile  HTTP 下载、Range 续传
store/        写入 MediaStore（DCIM/OsmoWatch），失败退回 App 目录
core/         OsmoClient：连接状态机（重试、看门狗、BLE→Wi-Fi 交接）
ui/           圆屏界面：连接页 / 相册页 / 传输页 / 日志页
```

数据流：

```
BLE 扫描 → 配对 → 取热点凭据 → 加入 AP → UDP 握手 → 媒体清单 → HTTP 下载 → 相册
```

## 构建

需要 **JDK 17+** 和 **Android SDK**（build-tools 30.0.3 + platforms/android-30），可选 `r8.jar`。

```powershell
# 复制模板并填上本机路径（build.local.ps1 已被 .gitignore 忽略）
copy build.local.example.ps1 build.local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

产物：`out\OsmoWatch.apk`。

## 测试

```powershell
# 测试夹具来自上游 osmosis 仓库（MIT，许可证副本见 THIRD_PARTY_LICENSES/）
git clone --depth 1 https://github.com/KonradIT/osmosis reference/osmosis
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-tests.ps1
```

7 个桌面自测：DUML、清单解析、HTTP、BLE、数据链、端到端、圆屏几何。
真机验证记录见 [TEST-REPORT.md](TEST-REPORT.md)。

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)（最新版本在最上面）。

## 许可

- 本项目以 **MIT** 许可发布，Copyright (c) 2026 ZGH271050，见 [LICENSE](LICENSE)。
- 协议实现移植自 [KonradIT/osmosis](https://github.com/KonradIT/osmosis)（MIT，Copyright (c) 2026 Konrad Iturbe），
  许可证副本见 `THIRD_PARTY_LICENSES/osmosis-LICENSE.txt`；CRC/DUML 帧实现源自 [dimadesu/dji-remote](https://github.com/dimadesu/dji-remote)（同样 MIT）。
