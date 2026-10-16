# Decision Log

## D-000 — 已确定

- 项目：November-raining/psa-lyric。
- 只用于个人学习/车机侧载。
- 当前目标设备为 Android 9、ARM64 车机。
- CarLife 只负责导航；QQ 音乐/网易云等车机本地 App 负责音频播放。
- 主悬浮歌词以 Apple Music 风格为唯一核心视觉，不需要 md3Music 的 Material 歌词风格。
- GitHub 作为网页会话和 Codex 的状态桥；网页侧负责代码与 CI，项目所有者负责最终签名、安装及要求 Codex ADB 验证。

## D-001 — 实现技术栈（待确认）

推荐：**原生 Android Kotlin + 自定义 Canvas/View**。

原因：Android 9 overlay 生命周期、WindowManager、触摸穿透、MediaSession、UsageStats、MediaProjection 都是原生接口；相比把 Flutter Engine 嵌进常驻 overlay，原生方案更轻、更容易控制帧率与窗口行为。md3Music 的视觉与运动参数按行为重新实现，不移植 Dart 源码。

备选：Flutter 主渲染 + 原生 overlay 桥。优点是更接近 md3Music 的渲染模型；缺点是 APK/内存更大，后台悬浮生命周期复杂。

## D-002 — “回到车机首页自动切换为通知栏歌词”的含义（待确认）

Lyrics-Companion 中至少有两种不同形态：
1. 前台服务的系统 Notification 文本跟随歌词；
2. 贴在屏幕顶部/状态栏高度位置的 overlay 歌词条。

需要确认本项目在 Launcher 首页时切到哪一种。推荐 **顶部细歌词条 overlay**，因为车机首页无需下拉通知栏即可直接看到。

## D-003 — 屏幕背景高斯模糊（待确认）

Android 9 普通悬浮窗不能直接模糊其下方的其他 App。
- 方案 A：V1 只做专辑封面高斯模糊，屏幕背景模式先不开放。
- 方案 B：加入 MediaProjection 捕获屏幕，低频截图后模糊作为歌词窗背景；需要一次系统录屏授权，并增加常驻捕获开销。

推荐 B 作为可选高级模式、默认关闭；封面模糊仍是默认。

## D-004 — 拖歌词跳转播放进度（待确认）

推荐交互：自由拖动只用于浏览歌词；选中某行后“单击/双击”执行 MediaController.TransportControls.seekTo()，5 秒无操作自动弹回当前行。这样不会把普通滚动误判成 seek。

可选：
- 双击跳转（更安全，推荐）。
- 单击跳转。
- 完全关闭 seek，只浏览。

## D-005 — 在线歌词来源（待确认）

推荐优先级：
1. 播放器同目录本地 LRC/KRC（若路径可访问）；
2. 网易云逐字 YRC（可用时）；
3. QQ / 酷狗等源；
4. 普通 LRC 兜底。

播放器来源与歌词来源解耦：QQ 音乐正在播放时也允许用网易云的高质量逐字匹配结果。

## D-006 — 代码许可证（待确认）

两个参考项目分别为 GPL-3.0 与 AGPL-3.0。目标代码将独立实现且不复制参考源码，但为了公开仓库的边界清晰，建议你选：
- AGPL-3.0-only（最保守，和更强 copyleft 参考保持兼容边界）；
- GPL-3.0-only；
- 你自己的其他许可证（前提是继续保持 clean-room 独立实现）。
