# Reference Audit — 2026-10-16

## 上传需求素材

原 ZIP：
- 1 份 Markdown 需求说明
- 6 张图片
- 2 段 MP4 视频

项目所有者已确认 Word 内容与 Markdown 相同，因此 Word 不再作为独立需求来源。

第二段 CarLife 视频确认了此前静态截图的裁切误判：右侧音乐卡片向上滑动完成后为接近整屏高度的卡片，目标默认几何修正为 **1373,10 / 535×700（1920×720 设计坐标）**。

## 上传 APK 静态检查

### lyrics-companion-29ef400(2).apk

SHA-256:
`a34fed693d5dcdcbf4326eaf2327b21cf0d724a0bd743e80c6bc9674ef696253`

二进制 Manifest 已解析：
- package: `com.zuoqirun.lyricscompanion`
- versionName: `20261015-29ef400`
- minSdk 19 / targetSdk 36
- 包含 SYSTEM_ALERT_WINDOW、PACKAGE_USAGE_STATS、FOREGROUND_SERVICE、BIND_NOTIFICATION_LISTENER_SERVICE 链路
- `LyricsDisplayService` 为常驻歌词 Overlay 服务
- `MusicNotificationListener` 为 NotificationListenerService
- 顶部歌词条与 foreground-service Notification 是两个独立显示面
- 目标 APK 本身也验证了 TYPE_APPLICATION_OVERLAY + 前台 App 探测这一路线是实际产物路线，而不只是 README 描述

### MD3Music-6.0.5-apple-music-arm64-v8a(2).apk

SHA-256:
`5e686f4076e6d7d9a71dce58878d304f3d3a2c76b5a426535d1766de1c583895`

二进制 Manifest / ZIP 已解析：
- versionName 6.0.5
- minSdk 24 / targetSdk 35
- APK 只包含 `lib/arm64-v8a/` native 库
- Flutter 引擎 + `libapp.so`，Manifest 开启 Impeller
- 包含 `FloatingLyricService`
- 上传者已在 Android 9 模拟器验证该 Apple Music 风格版本运行正常

这说明 Android 9 本身不是目标动效的阻碍；PSA Lyric 仍按原生 Canvas clean-room 实现，以减少常驻 overlay 的体积与生命周期复杂度。

## zuo-qirun/Lyrics-Companion 源码审查

- GPL-3.0。
- 已确认：MediaSession、NotificationListenerService、TYPE_APPLICATION_OVERLAY、FLAG_NOT_TOUCHABLE、UsageStats 前台识别、歌词校准/缓存、多来源歌词、顶部状态区歌词条。
- 本项目学习边界和行为，不复制源码、类结构或资源。

## zzyoxml/md3Music 源码审查

- AGPL-3.0。
- Apple Lyrics 模块为 Flutter/Dart。
- 已确认并冻结的关键行为/参数：
  - 当前行锚位 0.35
  - 非当前行 scale 0.85
  - scale spring = mass 2 / stiffness 100 / damping 25
  - posY stiffness 110–140，按歌词间隔动态计算
  - posY damping = sqrt(stiffness)×2.2
  - 已播字上浮约 3px
  - 动态色、辉光、高斯模糊、对唱、平衡换行
  - 上传设置基准 29 / 600 / 0.9×

目标项目按视觉/运动结果重新表达，不搬 AGPL 源文件。
