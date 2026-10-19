# SPW Lyrics Island

将 **灵动词岛** 与 **SPW Lyrics** 合并为一个 `.spmod`：歌词岛负责显示，SPW Lyrics 负责从多个来源搜索、匹配、缓存并刷新逐字歌词。

> 插件作者元数据：`GaBoron / Solitaire`。本项目是基于两个上游模块的合并发行版，Windows x64 专用，保留 OBS Spout2 透明输出。

<p align="center">
  <a href="https://github.com/Rimliya/SPW-Lyrics-Island/releases/latest"><img src="https://img.shields.io/github/v/release/Rimliya/SPW-Lyrics-Island?label=Release" alt="Release"></a>
  <img src="https://img.shields.io/badge/Windows-x64-0078D4" alt="Windows x64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0%20%2B%20MIT-blue" alt="License"></a>
</p>

## 一个安装包

Release 只提供一个运行包：`spw-lyrics-island-<版本>.spmod`。不要同时安装原来的两个插件，否则会重复注册播放扩展。

安装要求：SPW 1.19.0 或更高版本、Workshop API dev23、Java 25。下载后直接导入 SPW 的创意工坊 / 插件管理，不要解压。

## 功能

- 灵动词岛：逐字高亮、翻译、长音强调、胶囊 / 刘海外观、封面取色、实时频谱、鼠标穿透和全屏隐藏。
- OBS Spout2：默认开启的独立透明输出，默认 30 FPS、1280×512，可在 30 / 60 FPS 间切换；低性能模式约 15 FPS。
- 默认字体：内置苹方，仍可切换内置 MiSans 或系统字体。
- SPW Lyrics：AMLL TTML、QQ 音乐、酷狗音乐、网易云音乐和本地歌词匹配，支持相似度校验、手动搜索、批量缓存、逐曲延迟、歌曲元数据/内嵌歌词编辑和下一首预取。
- 词岛右键可“禁用当前获取的逐字歌词”：自动结果不匹配时按曲目退回本地歌词，再从 SPW Lyrics 设置中手动搜索正确结果。
- 两个配置页合并到同一个插件设置中：`灵动词岛` 与 `SPW Lyrics`。

## 解耦结构

仓库使用 `git subtree` 保存两个上游模块，合并层不复制它们的业务代码：

```text
modules/island    ← Rimliya/SPW-island
modules/lyrics    ← Rimliya/SPW-Lyrics
modules/combined  ← 只负责生命周期与播放回调适配
src/main/resources ← 单一插件清单、合并后的设置页
```

根 Gradle 项目同时编译三个 Kotlin 源目录，并只调用一次 `spmod` 打包。两边的设置 JSON 在构建时自动合并；`extensions.idx` 只注册一个适配器，由适配器分别转发给两个模块。

同步方法见 [docs/updating.md](docs/updating.md)，架构说明见 [docs/architecture.md](docs/architecture.md)。

## 来源版本

| 模块 | 来源 | 记录版本 | 提交 |
| --- | --- | --- | --- |
| `modules/island` | [Rimliya/SPW-island](https://github.com/Rimliya/SPW-island) | `1.1.0-Main` | `70c0863` |
| `modules/lyrics` | [Rimliya/SPW-Lyrics](https://github.com/Rimliya/SPW-Lyrics) | `1.2.0-f.1` | `8141b9f` |

原始项目：[GaBoron/SPW-island](https://github.com/GaBoron/SPW-island)、[GaBoron/SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)。

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

灵动词岛原创概念来自 Lyricify / WXRIW（CC BY-SA 4.0）。灵动词岛程序采用 GPL-3.0-only，AMLL 动画移植部分采用 AGPL-3.0-only；SPW Lyrics 采用 MIT。合并发行版保留两边的 LICENSE、NOTICE 和第三方许可材料。详见 [NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [licenses](licenses)。
