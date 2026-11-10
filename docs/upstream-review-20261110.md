# 1.10.0 定制审阅与上游同步

## 基线与接续范围

- 基线：`feat/1.10.0-upstream-selected @ ff6e06e`，版本仍为 `1.10.0-BASS-GPU`。
- 原始备份：本地 `backup/pre-review-20261110`，工作分支 `codex/review-upstream-20261110`。
- 已查阅工作区的 `SPW1`、`SPW2`、`SPW3`、`SPW4`、1.8.2 后续、两轮上游讨论及 1.9/1.10 开发聊天。旧聊天仅提供定制约束，开发基线没有退回旧版本。
- 原 1.10.0 测试包 SHA256：`5ADAA486DB94023D7008E3F588B7011939AE3B0B406B2619C4D071F8D92BB74C`。

## 必须保留的定制

- 一个 `.spmod`、一个播放 Extension、`io.github.solitaire.spwlyricsisland`、作者与旧设置迁移。
- GPU 渲染、不可变帧发布、Spout2 独立透明输出及桌面隐藏时的频谱；形状、圆角、左右插槽、时钟、迷彩和其它现有外观设置。
- 既有 Windows x64 置顶、启动显示恢复、全屏退出恢复与 1.10 的系统 UI 隐藏。不接入上游的播放器前台隐藏或置顶重构。
- 宿主 BASS 打轴，不重新打包 Media Runtime、FFmpeg、libmpv 或 BASS 调试日志。
- Tag Workbench 仍由 Combined 页面提供器注入，不恢复独立 Hook、侧栏、悬浮按钮或第二个 Extension；保留 1.4.0 写盘安全与 1.4.4 候选排序 / QQ 重试。
- 苹方字体精简与许可；Compose、JNA、Kotlin 由宿主提供。
- 本地 / 内嵌歌词、相似度、纯音乐占位和 inst. 标题跳过、下一首预取、逐曲延迟、手动覆盖及 1.10 的来源保存 / 预览 / 元数据导入确认。
- Provider 的 4/2/2 并发隔离、128 MiB 歌词缓存、16 MiB AMLL 索引和手动匹配保护。保留自己的 LrcShare API Key。
- 不引入已回退的 `feat/1.8.4-perf-business`、Linux/KWin 玻璃、批量元数据新架构或持续 FPS 日志。

## 上游选择

本次已 fetch 上游，按 `ce50813..5828206`（Island）和 `6a20a75..6ca00db`（Lyrics）移植源文件差异。不是整包合并这些上游版本。

| 来源 | 处理 |
| --- | --- |
| Island `e949106` | 从宿主合并的多语副歌词中选择第一条中文翻译；保留单条旧翻译。纯汉字日文仍存在上游的识别局限。 |
| Lyrics `23b57ad` | 异常处理不依赖可选 `java.net.http` 模块，保留本 fork 的未配置来源状态。 |
| Island `caaf285`、`7311689`、`5828206` | 外部音源会话、租约、控制、进度、歌词与封面；发现宿主虚拟曲目。用现有 Windows UI 和独立 Spout 输出门控接入。 |
| Lyrics `f48ff82`、`be431e1` | 外部音源歌词解析 / 选择 / 缓存身份及公共 JMX v1 helper，保留当前对象式生命周期、BASS、缓存维护与自有 Key。 |
| Tag Workbench | fetch 后上游仍为 `afde95f`，没有新的业务补丁。继续不引入独立 Overlay / Hook 路径。 |

公共 `dev.gaboron.spw` 协议名和 helper 包名保留，确保其它音源插件能互通；本 fork 的插件 ID、Kotlin 包名和 `io.github.solitaire.amllspw` 接收端不变。外部音源需主动发布状态，本插件不接管或播放音频。

## Review 修正

1. **启动回放串曲及乱序**：Tag 回放和实时回调使用同一锁投递；新曲清掉旧曲缓存的进度与歌词；stop 使已经排队的旧回调失效。曲目从 before-load 阶段开始同步，避免宿主本地歌词成功后不调用 after-missing。
2. **歌词旧结果回写**：按当前 query 和选择 revision 验证缓存读取后的加载；本地歌词确定后取消旧自动任务，外部会话 A→B→A 不接受旧结果。关闭后不再处理异步结果。
3. **维护初次执行单位错误**：启动后 45 分钟改为约定的 45 秒，每 24 小时维护一次。
4. **测试漏执行**：1.10.0 的两个 `@Test` 位于测试类外，现移回类内；普通侧挂 LRC 仍交给宿主，修正旧断言并验证内嵌读取 / 失效。
5. **停用与重启隔离**：先停 Tag 转发，再停嵌入式运行时；外部轮询捕获具体 coordinator，旧安装的 tick 不能写入重新启用后的实例。
6. README 来源表更新到真实选择范围，修正同步脚本的 Tag fork 分支；打包校验要求外部消费者存在、测试 publisher 不进入运行包。

## 验证与交付

- 本地预检：38 项 JUnit 测试通过；`combinedChecks` 和 `spoutChecks` 通过。
- 新增覆盖：Tag 回放顺序 / 切歌 / 关闭失效 / 列表快照；旧 query 加载竞态；外部来源 A→B→A、双来源交接、租约失效 / 恢复、控制路由、原始与选中歌词切换、缓存身份；多语翻译。
- 最终包按用户要求由该工作分支的 Windows GitHub Actions 构建，执行 `test combinedChecks plugin spoutChecks` 与 `verify-spmod.ps1`，不发布 Release 或修改原标签。
- 实机尚需验证：桌面和 OBS 同时播放、开始菜单 / 搜索 / 全屏切换后的恢复、BASS 打轴、保存歌词后切歌返回，以及实际音源插件的接入。JMX 测试不替代这些视觉与宿主测试。
