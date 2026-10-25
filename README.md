# SPW Lyrics Island

将 **灵动词岛**、**SPW Lyrics**、**SPW Tag Workbench** 与 **SPW Media Runtime** 合并为一个 `.spmod`：顶部歌词入口统一打开标签工作台与歌词工具，词岛负责显示，SPW Lyrics 负责多来源歌词搜索与批量处理，Tag Workbench 负责标签、封面、艺术家图与 ReplayGain，Media Runtime 提供频谱打轴所需的音频处理能力。

> 插件作者：`solitaire + 大肥鱼`。本项目是基于多个上游模块的合并发行版，Windows x64 专用，保留 OBS Spout2 透明输出。感谢原作者 GaBoron，以及 Tag Workbench 的 univers629 / salt-workshop contributors。
> 当前 `feat/tag-workbench-integration-gpu` 分支在 GPU 实验版词岛模块上增加嵌入式标签工作台，尚未合并到 `main`。

<p align="center">
  <a href="https://github.com/Rimliya/SPW-Lyrics-Island/releases/latest"><img src="https://img.shields.io/github/v/release/Rimliya/SPW-Lyrics-Island?label=Release" alt="Release"></a>
  <img src="https://img.shields.io/badge/Windows-x64-0078D4" alt="Windows x64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0%20%2B%20MIT-blue" alt="License"></a>
</p>

## 一个安装包

Release 只提供一个运行包：`spw-lyrics-island-<版本>.spmod`。不要同时安装原来的词岛、歌词、标签工作台或 Media Runtime 插件，否则可能重复注册播放扩展或 Runtime endpoint。

安装要求：SPW 1.19.0 或更高版本、Workshop API dev23、Java 25。下载后直接导入 SPW 的创意工坊 / 插件管理，不要解压。

## 功能

- 灵动词岛：逐字高亮、翻译、长音强调、胶囊 / 刘海外观、封面取色、实时频谱、鼠标穿透和全屏隐藏。
- OBS Spout2：默认开启的独立透明输出，默认 30 FPS、1280×512，可在 30 / 60 FPS 间切换；低性能模式约 15 FPS。
- 默认字体：内置苹方，仍可切换内置 MiSans 或系统字体。
- SPW Lyrics：支持 AMLL TTML、QQ 音乐、汽水音乐、酷狗音乐、网易云音乐、Apple Music、LrcShare，并从 MusicBrainz 获取元数据；可选择类型优先或来源优先。
- 保留本地歌词相似度校验、纯音乐占位拦截、手动搜索、批量缓存、逐曲延迟、歌曲元数据/内嵌歌词编辑和下一首预取。
- 频谱打轴：内置 SPW Media Runtime 0.2.0，不再要求另行安装 Runtime 插件。
- 标签工作台：顶部歌词入口默认打开工作台，可编辑多容器标签、匹配歌词 / 封面 / 艺术家图、查看频谱和逐字打轴，并提供 ReplayGain 测量；侧栏、悬浮按钮和独立插件 Hook 已移除。
- 词岛右键可“禁用当前获取的逐字歌词”：自动结果不匹配时按曲目退回本地歌词，再从 SPW Lyrics 设置中手动搜索正确结果。
- 三个配置页合并到同一个插件设置中：`灵动词岛`、`SPW Lyrics` 与 `音乐标签工作台`。

## 解耦结构

仓库使用 `git subtree` 保存上游模块，合并层不复制业务实现：

```text
modules/island         ← Rimliya/SPW-island
modules/lyrics         ← Rimliya/SPW-Lyrics
modules/tag-workbench  ← Rimliya/SPW-Tag-Workbench（嵌入式分支）
modules/media-runtime  ← GaBoron/SPW-Media-Runtime
modules/combined       ← 生命周期、页面注册与播放回调适配
src/main/resources     ← 单一插件清单、合并后的设置页
```

Lyrics 模块只暴露通用的工作台页面注册点，Tag Workbench 的实现和 UI 由 Combined 适配器注入，因此两者没有直接源码依赖。根 Gradle 项目同时编译各模块，`modules/media-runtime` 作为 included build 参与编译；Runtime 的 JVM 组件进入 `lib/`，原生组件进入 `native/windows-x64/`。最终仍只生成一个 `.spmod`。

同步方法见 [docs/updating.md](docs/updating.md)，架构说明见 [docs/architecture.md](docs/architecture.md)。

## 来源版本

| 模块 | 来源 | 记录版本 | 提交 |
| --- | --- | --- | --- |
| `modules/island` | [Rimliya/SPW-island](https://github.com/Rimliya/SPW-island) | `1.3.0-GPU` + upstream `1.2.1` | `653d2e2` |
| `modules/lyrics` | [Rimliya/SPW-Lyrics](https://github.com/Rimliya/SPW-Lyrics) | `3.1.0-f.1` + Combined 页面注册 | `3a84c97` |
| `modules/tag-workbench` | [Rimliya/SPW-Tag-Workbench](https://github.com/Rimliya/SPW-Tag-Workbench) | `embedded-workbench`（上游 1.3.3） | `c5cccbc` |
| `modules/media-runtime` | [GaBoron/SPW-Media-Runtime](https://github.com/GaBoron/SPW-Media-Runtime) | `0.2.0` | `ad63eed` |

原始项目：[GaBoron/SPW-island](https://github.com/GaBoron/SPW-island)、[GaBoron/SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)、[univers629/SPW-Tag-Workbench](https://github.com/univers629/SPW-Tag-Workbench)、[GaBoron/SPW-Media-Runtime](https://github.com/GaBoron/SPW-Media-Runtime)。

## 构建与发布

本地完整构建需要 Windows、JDK 25、CMake、Visual Studio C++ 工具链和 .NET Framework 编译器。CI 使用 `windows-latest`：

- push 到 `main`：运行测试、`spoutChecks` 和打包，上传 artifact；
- push `v*` tag：额外校验 tag 与 `gradle.properties` 版本一致，并创建 GitHub Release；
- `workflow_dispatch`：手动构建。

```powershell
./gradlew --no-daemon --stacktrace test combinedChecks plugin spoutChecks
./scripts/verify-spmod.ps1
```

## 许可

灵动词岛原创概念来自 Lyricify / WXRIW（CC BY-SA 4.0）。灵动词岛程序采用 GPL-3.0-only，AMLL 动画移植部分采用 AGPL-3.0-only；SPW Lyrics 采用 MIT；SPW Tag Workbench 采用 GPL-3.0-only，其 libebur128 组件采用 MIT；SPW Media Runtime 采用 Apache-2.0，内置 FFmpeg/libmpv 等组件继续遵循各自许可。合并发行版保留全部 LICENSE、NOTICE 和第三方许可材料。详见 [NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [licenses](licenses)。
