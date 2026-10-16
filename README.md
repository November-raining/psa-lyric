# PSA Lyric

PSA 车机本地播放器歌词悬浮伴侣。目标：Android 9 车机，1920×720 参考布局，ARM64 实车环境。

## 已冻结产品行为

- 车机 QQ 音乐、网易云等继续播放音频；CarLife 只导航。
- NotificationListenerService + MediaSession 读取当前活跃播放器。
- 主显示为右侧**全高** Apple Music 风格歌词卡片；1920×720 默认设计矩形为 `1373,10 / 535×700`。
- 回到车机 Launcher 自动切换为贴顶/状态栏高度的细歌词 Overlay。
- md3Music Apple 歌词行为基线：0.35 当前行锚位、0.85 非当前行缩放、弹簧滚动、逐字时间、动态色、模糊、辉光、对唱、平衡换行。
- 默认字号 29、字重 600、行间距 0.9×。
- 拖动歌词只浏览；双击歌词行 seek；5 秒自动回弹。
- 上一首 / 播放暂停 / 下一首带按压回弹。
- 背景默认专辑封面高斯模糊；可选 MediaProjection 屏幕背景模糊。
- 歌词优先级：本地 → 网易云 YRC/LRC → QQ → 酷狗。
- 支持悬浮窗触摸穿透。

## 实现

技术栈：原生 Android Kotlin + 自定义 Canvas/View。参考项目只用于理解行为，不复制其源码或资源。

当前仓库已经包含：
- Android 9 工程骨架
- MediaSession 活跃播放器选择与播放位置插值
- 本地/网易云/QQ/酷狗歌词 Provider
- LRC/YRC 解析、翻译/罗马音模型
- Apple 风格滚动/缩放/模糊/辉光/动态色/对唱/平衡换行
- Launcher 顶部细歌词 Overlay
- MediaProjection 屏幕背景模式
- 设置/权限页
- GitHub Actions 未签名 Release APK 构建

详见 [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md)、[docs/REFERENCE_AUDIT.md](docs/REFERENCE_AUDIT.md)、[docs/DECISIONS.md](docs/DECISIONS.md)。

许可证：AGPL-3.0-only。

## 分工

GitHub Actions 产出未签名 APK + SHA-256；项目所有者负责最终签名和安装，随后由 Codex 通过 ADB 做 Android 9 模拟器/实车验证。
