# 同步上游与发版

## 同步三个上游

```powershell
./scripts/sync-upstreams.ps1
```

脚本分别执行：

```powershell
git subtree pull --prefix modules/island https://github.com/Rimliya/SPW-island.git main --squash
git subtree pull --prefix modules/lyrics https://github.com/Rimliya/SPW-Lyrics.git main --squash
git subtree pull --prefix modules/media-runtime https://github.com/GaBoron/SPW-Media-Runtime.git main --squash
```

如果只想同步其中一个，可直接运行对应命令。冲突通常出现在上游重命名资源或增加依赖时：

- 新业务类留在 `modules/*`，不要复制到合并层；
- 新依赖加入根 `build.gradle.kts`；
- 新设置页仍由 `preference_config.json` 自动合并；
- 若上游新增 Extension，合并适配器需要新增对应转发方法；
- 若上游新增原生文件或资源，更新根 `processResources` 和 `sourceArchive`。
- 若 Media Runtime 升级，同时更新 `mediaRuntimeVersion`、内置适配器和版本表。

同步后更新 `README.md` 的“来源版本”表，并运行：

```powershell
./gradlew --no-daemon --stacktrace test combinedChecks plugin spoutChecks
./scripts/verify-spmod.ps1
```

## 发版

1. 修改 `gradle.properties` 的 `version`；
2. 提交并推送 `main`，等待 CI 的 artifact 构建通过；
3. 创建与版本完全对应的 tag，例如版本为 `1.1.0-combined.1` 时：

```powershell
git tag v1.1.0-combined.1
git push origin v1.1.0-combined.1
```

tag 推送会触发 `.github/workflows/build.yml`，并自动创建 Release。Release 包含一个 `.spmod` 和一个源码 zip；workflow 会拒绝 tag 与 `gradle.properties` 不一致的发布。
