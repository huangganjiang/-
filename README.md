# 即视 · EasyNVR 监控客户端

一个轻量的 Android 客户端，用来**方便快捷地接入 EasyNVR 查看监控**，并把日常最常用的几个动作做得足够简单：打开就能看、想看录像能立刻找到、画面能放大、能全屏。

- **不绑定任何服务端**：首次启动填写你自己的 EasyNVR 地址、账号、密码即可使用
- **通道数量自动读取**：以服务端 `/channels` 返回为准，不写死路数
- **无广告、无埋点、无账号体系**：不采集任何个人数据，不上传任何信息

> 本项目是第三方客户端，与 EasyNVR 官方无关。

---

## 功能

| 模块 | 能力 |
|---|---|
| 首页 | 当前画面大卡 + 两列设备卡片；到点自动轮播快照，可手动滑动 |
| 直播 | 自研 FLV/HEVC 解码上屏（`MediaCodec` 直渲）+ VLC 纯音频出声；分辨率实时显示 |
| 回放 | HLS 连续流播放；「最近录像」列表点一条播一条；−10 秒 / +10 秒 / 上一段 / 下一段；0.5×~8× 倍速 |
| 画面 | 双指缩放、双击放大、全屏（横竖屏自适应） |
| 其它 | 全面屏 / 刘海 / 手势条适配；切后台自动停止拉流，省流量省电 |

**明确不做**：深色模式、语音对讲（不申请麦克风权限）、截图功能、首页监控墙。

## 界面

见 [`设计稿/`](设计稿/) 目录。

## 快速开始

### 环境要求

- Android 7.0（API 24）及以上，**arm64-v8a** 设备
- 服务端：EasyNVR（HTTP 访问即可）
- 构建：JDK 17 + Android SDK（compileSdk 34）

### 编译

```bash
# Debug 包（可直接安装体验）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 包（需要先配置签名，见下）
./gradlew assembleRelease
```

### 配置签名（打包 Release 必需）

出于安全考虑，**没有配置发布签名时 release 打包会被拒绝**（避免用 debug 签名产出可被调试的正式包）。
在项目根目录 `local.properties` 中加入：

```properties
sdk.dir=/path/to/Android/Sdk

jishi.keystore=/path/to/your.jks
jishi.keystorePassword=******
jishi.keyAlias=your-alias
jishi.keyPassword=******
```

> `local.properties` 已在 `.gitignore` 中，不会被提交。

### 使用

1. 安装启动后，在登录页填写：**服务端地址**（如 `192.168.1.10:10000`，可省略 `http://`）、**账号**、**密码**
2. 登录信息经系统密钥库（AndroidKeyStore）加密保存在本机，之后免输入
3. 卸载重装后需重新填写

## 依赖的 EasyNVR 接口

客户端只使用 EasyNVR 的公开 HTTP 接口：

| 用途 | 接口 |
|---|---|
| 登录 | `POST /login`（口令为 SHA-256 十六进制） |
| 通道列表 | `GET /channels` |
| 直播拉流 | `GET /live/{id}.flv`（HTTP-FLV） |
| 录像分段 | `GET /records` → `GET /records/hls/index.m3u8?…` |
| HLS 播放列表 | `GET /records/devices/{dev}/channels/{ch}/{start}/{end}/index.m3u8` |
| 录像时间轴 | `GET /records/timeline`、`GET /records/months` |

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- 播放：自研 FLV/HEVC 内核（`MediaCodec` + `Surface`）、Media3/ExoPlayer（HLS）、libVLC（音频）
- 网络：OkHttp + kotlinx.serialization
- 最低 SDK 24，目标 SDK 34

### 播放内核说明

直播画面由项目内置的 FLV/HEVC 解析内核直接解码上屏，声音交给 libVLC；回放画面用 Media3 播 HLS，声音同样交给 libVLC。
两个内核可通过构建参数切换，便于在不同设备上回退：

```bash
./gradlew assembleRelease -Pjishi.liveKernel=hybrid   # 默认：自研画面 + VLC 声音
# 也可用环境变量 JISHI_LIVE_KERNEL=vlc | self
```

## 安全设计

- 明文口令**不进产物**：口令仅保存在用户本机（AndroidKeyStore 加密），源码与仓库中不含任何服务端地址或账号
- 网络明文白名单在构建期按配置生成，默认不放行任何全局明文
- 日志统一打码（URL 主机、IP、异常只记类型）
- 仅申请 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES` 两项权限

## 已知限制

- 仅打包 arm64-v8a，32 位设备与 x86 模拟器需自行调整 `abiFilters`
- 直播为 HTTP-FLV，若服务端关闭该出口则只能回放
- 部分设备 H.265 硬解能力有限，1440p 可能出现帧率不足

## 许可

本项目源码采用 **MIT License**，见 [LICENSE](LICENSE)。

第三方组件：

- **libVLC**（`org.videolan.android:libvlc-all`）— LGPLv2.1+，本项目以动态链接方式使用其预编译库，未修改其源码；用户有权替换该库，本项目不阻止
- **AndroidX / Media3**、**OkHttp**、**kotlinx.serialization**、**Coil** — Apache-2.0

## 免责声明

本项目仅供接入**你自有或已获授权**的监控设备使用。使用者需自行遵守所在地法律法规，不得用于侵犯他人隐私等用途。作者不对任何滥用行为负责。
