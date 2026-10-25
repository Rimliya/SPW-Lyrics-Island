param(
    [string]$IslandRef = "main",
    [string]$LyricsRef = "feat/combined-workbench-entry",
    [string]$TagWorkbenchRef = "feat/embedded-workbench",
    [string]$MediaRuntimeRef = "main"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    git subtree pull --prefix modules/island https://github.com/Rimliya/SPW-island.git $IslandRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/island" }

    git subtree pull --prefix modules/lyrics https://github.com/Rimliya/SPW-Lyrics.git $LyricsRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/lyrics" }

    git subtree pull --prefix modules/tag-workbench https://github.com/Rimliya/SPW-Tag-Workbench.git $TagWorkbenchRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/tag-workbench" }

    git subtree pull --prefix modules/media-runtime https://github.com/GaBoron/SPW-Media-Runtime.git $MediaRuntimeRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/media-runtime" }

    $commit = git rev-parse --short=7 HEAD
    Write-Host "Synced all modules into $commit. Update the source version table before releasing."
} finally {
    Pop-Location
}
