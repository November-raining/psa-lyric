# Project Spec — 2026-10-16

## 场景

车机本地 QQ 音乐、网易云音乐等继续负责真正的音频播放；CarLife 只用于导航。PSA Lyric 读取系统当前活跃播放器状态，自行匹配歌词并覆盖在车机界面上。

参考屏幕为 1920×720。对上传截图和第二段 CarLife 视频重新核对后，右侧音乐卡片在完成向上滑动后不是截图中被截断的下半块，而是与左侧导航栏近似同高的**全高卡片**：
- 左侧系统导航栏约 x=0..112。
- CarLife 主区域右边界约 x=1361。
- 右侧音乐卡片约 x=1373..1908。
- 完整展开后 y≈10..710。
- 因此目标悬浮歌词默认矩形冻结为 **x=1373, y=10, w=535, h=700**。
- 该矩形是 1920×720 设计坐标，运行时按实际屏幕等比映射，并允许后续实车微调保存。

## 媒体链路

1. NotificationListenerService 获得 MediaSessionManager.getActiveSessions() 访问资格。
2. 从活跃会话选择正在播放、且标题/歌手信息有效的播放器。
3. 订阅 PlaybackState + MediaMetadata；用 position + lastPositionUpdateTime + speed 进行播放位置插值。
4. TransportControls 实现上一首 / 播放暂停 / 下一首。
5. 双击歌词行通过 seekTo() 跳转；自由拖动只浏览，5 秒无操作回弹。
6. 会话轮询作为车机 ROM 的兼容兜底。

## 歌词链路

优先级冻结为：
1. 用户指定本地歌词目录；
2. 网易云 YRC/LRC；
3. QQ；
4. 酷狗；
5. 无结果时只保留媒体信息，不伪造逐字时间。

播放器来源与歌词来源解耦。歌词 Provider 通过统一模型输出逐行、逐字、翻译/罗马音数据。全局偏移正值表示歌词提前。

## Apple 风格歌词视觉基线

目标是 md3Music Apple Music 风格的运动结果，而不是 Material 歌词风格：
- 当前行锚位 0.35。
- 当前行 scale=1.0，非当前行默认 0.85。
- 行缩放弹簧参数 mass=2 / stiffness=100 / damping=25。
- posY 普通滚动 stiffness 随相邻歌词间隔在 110–140 动态变化，damping=sqrt(stiffness)×2.2。
- 当前逐字进度按真实逐字时间推进；普通 LRC 不伪造逐字进度。
- 非当前行距离分级模糊；用户手动浏览时取消模糊。
- 动态歌词色由白色与封面提取色混合。
- 光辉、对唱左右/居中布局、平衡换行、翻译/罗马音副行、上下边缘渐隐。
- 设置基准冻结为字号 29、字重 600、行间距 0.9×。
- 播放/暂停、上一首、下一首带按压回弹动画。

## 悬浮窗行为

- 原生 Kotlin + 自定义 Canvas/View。
- WindowManager TYPE_APPLICATION_OVERLAY。
- 默认 1920×720 设计矩形：1373,10 / 535×700。
- FLAG_NOT_TOUCHABLE 触摸穿透可切换。
- CarLife/播放器等正常界面显示主 Apple 歌词卡片。
- 检测到车机 Launcher 前台时隐藏主卡片并切换为贴顶/状态栏高度的细歌词 Overlay。
- 顶部细歌词是悬浮条，不是系统 Notification 文本。
- Android 9 为唯一当前运行目标；minSdk/targetSdk 均围绕该环境设计。

## 背景

- 默认：专辑封面缩小后高斯模糊。
- 高级：MediaProjection 获取屏幕低频画面后高斯模糊；需要一次系统录屏授权，默认关闭。
- Android 9 无 RenderEffect，本项目使用 Android 9 可用的 RenderScript blur 路径做首版验证。
- 屏幕捕获模式需要模拟器/实车重点验证自捕获反馈、功耗和稳定性。

## CI / 交付

- GitHub Actions：JDK 17、Gradle 8.7、Android SDK，执行 unit tests 与 assembleRelease。
- CI 上传 app-release-unsigned.apk、SHA256SUMS.txt、BUILD_INFO.txt。
- 项目所有者负责最终签名、安装；Codex 负责后续 ADB 模拟器/实车验证。
