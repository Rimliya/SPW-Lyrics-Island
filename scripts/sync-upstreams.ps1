param(
    [string]$IslandRef = "main",
    [string]$LyricsRef = "main"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    git subtree pull --prefix modules/island https://github.com/Rimliya/SPW-island.git $IslandRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/island" }

    git subtree pull --prefix modules/lyrics https://github.com/Rimliya/SPW-Lyrics.git $LyricsRef --squash
    if ($LASTEXITCODE -ne 0) { throw "Failed to sync modules/lyrics" }

    $commit = git rev-parse --short=7 HEAD
    Write-Host "Synced both modules into $commit. Update the source version table before releasing."
} finally {
    Pop-Location
}