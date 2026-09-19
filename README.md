# LxMusicAndroid（精简版）

按 `lx-music-search-and-url-analysis.md` 的架构做成的单模块 Android 工程，目标是“功能保留、代码尽量少”。

## 已实现

- 5 平台搜索：酷我 `kw`、酷狗 `kg`、QQ 音乐 `tx`、网易云 `wy`、咪咕 `mg`
- 统一歌曲模型和结果展示
- 同曲跨平台匹配：歌名/歌手/专辑/时长，时长容差 ±5 秒
- 5 平台并发搜索；单个平台失败不影响其它平台
- JS 音源从系统文件管理器导入，复制到应用私有目录
- JS 元信息：`@name/@description/@author/@version/@homepage`
- `lx.send('inited', ...)`、`lx.on('request', ...)`
- `lx.request()`：GET/POST、headers、body、form、formData、60 秒网络超时
- `lx.utils.crypto`：MD5、SHA1、AES、RSA、randomBytes
- `lx.utils.buffer`：from / toString
- `lx.utils.zlib`：inflate / deflate
- 返回播放地址严格校验：仅允许 HTTP/HTTPS、长度 < 2048
- 播放地址本地缓存
- 音质回退：`flac24bit → flac → 320k → 128k`
- 当前平台失败后自动搜索其它平台并依次尝试
- Media3 ExoPlayer 播放网络音频
- WebView 导航/文件访问/内容访问限制，JS 只能通过受限桥接 API 工作

## 项目结构

```text
LxMusicAndroid/
├─ app/src/main/java/com/lxmusic/android/
│  ├─ MainActivity.kt       # UI、搜索、导入、播放、回退
│  ├─ SearchRepository.kt   # 5 平台搜索/签名/解析/匹配
│  ├─ UserApiEngine.kt      # JS 沙箱 + lx API
│  ├─ SourceStore.kt        # JS 音源持久化
│  ├─ UrlCache.kt            # 播放 URL 缓存
│  ├─ PlayerController.kt    # Media3 播放
│  ├─ Models.kt
│  └─ HttpUtil.kt
├─ app/src/main/assets/
│  ├─ user_api.html         # JS 沙箱运行时
│  └─ example-source.js     # 仅测试接口，不含真实播放逻辑
└─ SOURCE_API.md
```

## 运行

用 Android Studio 打开 `LxMusicAndroid`，同步 Gradle 后运行 `app`。

本环境没有 Android SDK/Gradle，因此这里没有声称已经构建并真机验证 APK。当前工程使用 AGP 9.4.0、Kotlin Gradle Plugin 2.4.10、Activity 1.13.0 和 Media3 ExoPlayer 1.9.4。

## 与原文档的一个重要边界

这个工程**不内置真实播放链接提取脚本**。播放 URL 仍由用户自己导入的第三方 JS 音源实现，和分析文档所述模式一致；工程只提供执行环境、安全约束、搜索、缓存、回退和播放能力。

## 自定义 JS 音源

- 首页点击「导入 JS」即可从 Android 系统文件管理器选择 `.js` 文件。
- 导入后会复制到 App 私有目录，原文件删除或移动不会影响已导入音源。
- App 重启后自动恢复已导入的音源，不需要重复选择文件。
- 点击「音源」可以切换已导入音源，也可以删除不再需要的音源。
- 导入时读取脚本头部 `/* ... */` 中的 `@name`、`@description`、`@author`、`@version`、`@homepage`。
- 单个 JS 文件限制为 2 MB。
- JS 运行在 App 内部的 WebView 沙箱，不直接获得 Android 文件系统访问能力。
