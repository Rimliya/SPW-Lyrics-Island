# 安装与更新

1. 确认 SPW 为 1.19.0 或更高版本，使用 Java 25。
2. 从 [Releases](https://github.com/Rimliya/SPW-Lyrics-Island/releases/latest) 下载 `spw-lyrics-island-<版本>.spmod`。
3. 在 SPW 的创意工坊 / 插件管理中选择本地安装或导入。
4. 启用插件。设置页会同时出现“灵动词岛”“SPW Lyrics”和“音乐标签工作台”；顶部歌词入口默认打开标签工作台。

只安装这一个合并包。不要同时保留旧的词岛、歌词或标签工作台插件，否则播放回调或页面入口可能被重复注册。

更新时直接导入新的 `.spmod`。插件配置分别保存在 `island.json`、`spw-lyrics.json` 与 `tag-workbench.json`，通常不需要清空。旧的独立标签工作台草稿不会自动迁移；建议先在旧插件中保存需要保留的文件改动。歌词缓存默认位于 `D:\SPW Lyrics`，D 盘不可写时回退 `%LOCALAPPDATA%`。