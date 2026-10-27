# 架构与模块边界

## 目录

| 目录 | 职责 |
| --- | --- |
| `modules/island` | 灵动词岛上游快照，包含窗口、绘制、Spout2、原生组件与设置 |
| `modules/lyrics` | SPW Lyrics 上游快照，包含来源搜索、匹配、缓存、手动搜索和批量处理 |
| `modules/tag-workbench` | SPW Tag Workbench 嵌入式分支，包含标签、封面、艺术家图、频谱、打轴与 ReplayGain |
| `modules/combined` | 合并生命周期、通用页面适配、播放状态桥接与单一播放 Extension |
| `src/main/resources` | 单一插件清单、源码链接和构建时生成的合并设置页 |
| `scripts` | 上游同步与 `.spmod` 校验 |
| `.github/workflows` | Windows 构建、artifact 和 tag 发布 |

四个上游目录视为快照。合并相关的新逻辑应放在 `modules/combined`；需要修改上游行为时，先在对应的 fork 仓库提交，再通过 subtree 同步回来。Tag Workbench 的嵌入式生命周期只保留在 `EmbeddedWorkbench.kt`，其独立 `MainPlugin`、侧栏和 AppNavigation Hook 在 Combined 构建中排除。

## 生命周期

`SPWLyricsIslandPlugin` 继承词岛的 `IslandPlugin`：

1. 启动词岛运行时；
2. 启动 Tag Workbench 的嵌入式运行时，不注册独立插件或宿主 Hook；
3. 启动 Tag 播放桥并回放启动期间收到的最新宿主状态；
4. 向 Lyrics 的通用页面注册点注入工作台 UI；
5. 安装 `PluginRuntime`（SPW Lyrics）；
6. 任一步失败时按逆序回滚已启动的部分；
7. 停止时先解除页面注册，再关闭歌词、Tag Workbench 和词岛运行时。

## 播放回调

宿主只看到一个 `SPWLyricsIslandPlaybackExtension`。它把回调同时转发给：

- `IslandPlaybackExtension`：播放时间线、歌词行和状态；
- `SpwLyricsExtension`：本地歌词缺失检查与歌词替换；
- Tag Workbench 的 `Repo`：经 `TagPlaybackBridge` 转发曲目、播放状态、位置与歌词行；工作台尚未启动时只缓存最新状态，就绪后一次性回放。

这样既不依赖宿主对多个 Extension 的加载顺序，也不需要把各模块的业务代码交叉引用。

## 资源合并

构建任务读取：

```text
modules/island/src/main/resources/preference_config.json
modules/lyrics/src/main/resources/preference_config.json
modules/tag-workbench/src/main/resources/preference_config.json
```

然后把三个 `configs` 数组拼成一个 `preference_config.json`。各模块自己的 `META-INF/extensions.idx`、`project.properties` 和独立插件清单不参与根包，根项目提供唯一版本。Tag Workbench 的 i18n、`ebur128.dll`、GPL 文本和 libebur128 许可由根 `processResources` 合并。

## 依赖

打轴频谱直接复用 SPW 宿主已经初始化的 BASS：插件只创建短期 decode stream，不初始化、不关闭宿主引擎，也不打包 FFmpeg/Media Runtime。最终只打一个 `.spmod`。Kotlin、Workshop API、JNA 和 PF4J 仍由 SPW 宿主提供，不随包重复分发。

## 占用与回调优化

- 根项目只打包一套 Compose、Skiko、JNA、Jaudiotagger 和字体资源，各模块不会各自携带副本。
- Lyrics 只暴露 `LyricsWorkspacePageProvider` 通用注册点；Combined 注入 Tag Workbench UI，Lyrics 不直接引用 Tag Workbench 类。
- Tag Workbench 不再安装侧栏、FAB、AppNavigation 替换或第二个播放 Extension。
- 词岛启动后使用原生窗口诊断做一次显示自检；必要时先重建置顶状态，再内部重建显示层，不写回用户设置。
- `lib/kotlin-stdlib-*.jar` 由 SPW 宿主提供，打包时明确排除。
- 手动搜索和批量处理窗口在第一次打开时才加载 Compose/Skiko；不用这些窗口时不会初始化额外渲染上下文。
- 宿主只注册一个播放 Extension。词岛继续接收全部播放事件；Tag Workbench 通过可回放的状态桥接收最新快照；Lyrics 只在歌词加载链路中参与。
- 词岛在 EDT 上生成不可变 `IslandGpuFrame` 并通过 `AtomicReference` 发布；GPU 渲染线程和 Spout 发送线程不再读取 Swing 面板或可变频谱数组。
- Lyrics 的在线工作拆为播放/手动 4、预取 2、批量 2 三个 Provider 池，批量任务不会占满当前播放请求。
- 歌词缓存默认约 128 MB，AMLL 索引约 16 MB；空闲维护按最近使用时间清理，当前曲目、手动匹配和本地歌词受保护。
- AMLL 首次索引构建和刷新都在后台执行，搜索线程只读取当前不可变索引快照。
- `combinedChecks` 在不启动 SPW 的情况下验证合并设置、Extension 注册、各回调代理、Tag Workbench 资源与页面注册；`verify-spmod.ps1` 再检查重复库名和 Kotlin 标准库泄漏。
