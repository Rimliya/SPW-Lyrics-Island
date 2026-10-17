# 架构与模块边界

## 目录

| 目录 | 职责 |
| --- | --- |
| `modules/island` | 灵动词岛上游快照，包含窗口、绘制、Spout2、原生组件与设置 |
| `modules/lyrics` | SPW Lyrics 上游快照，包含来源搜索、匹配、缓存、手动搜索和批量处理 |
| `modules/combined` | 只包含合并生命周期和播放扩展适配器 |
| `src/main/resources` | 单一插件清单、源码链接和构建时生成的合并设置页 |
| `scripts` | 上游同步与 `.spmod` 校验 |
| `.github/workflows` | Windows 构建、artifact 和 tag 发布 |

两个上游目录视为只读快照。合并相关的新逻辑应放在 `modules/combined`；需要修改上游行为时，先在对应的 fork 仓库提交，再通过 subtree 同步回来。

## 生命周期

`SPWLyricsIslandPlugin` 继承词岛的 `IslandPlugin`：

1. 启动词岛运行时；
2. 安装 `PluginRuntime`（SPW Lyrics）；
3. 任一步失败时回滚已启动的部分；
4. 停止时先关闭歌词运行时，再关闭词岛运行时。

## 播放回调

宿主只看到一个 `SPWLyricsIslandPlaybackExtension`。它把回调同时转发给：

- `IslandPlaybackExtension`：播放时间线、歌词行和状态；
- `SpwLyricsExtension`：本地歌词缺失检查与歌词替换。

这样既不依赖宿主对多个 Extension 的加载顺序，也不需要把两边的业务代码交叉引用。

## 资源合并

构建任务读取：

```text
modules/island/src/main/resources/preference_config.json
modules/lyrics/src/main/resources/preference_config.json
```

然后把两个 `configs` 数组拼成一个 `preference_config.json`。两个模块自己的 `META-INF/extensions.idx` 和 `project.properties` 不参与根包，根项目提供唯一版本。

## 依赖

根项目取两个模块依赖的并集，并只打一个 `.spmod`。Kotlin、Workshop API 和 PF4J 仍由 SPW 宿主提供，不随包重复分发。