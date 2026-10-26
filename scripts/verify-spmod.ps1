param(
    [string]$Path
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $Path) {
    $packages = @(Get-ChildItem (Join-Path $root "build/distributions") -Filter "*.spmod" -File)
    if ($packages.Count -ne 1) { throw "Expected exactly one .spmod, found $($packages.Count)" }
    $Path = $packages[0].FullName
}
if (-not (Test-Path -LiteralPath $Path)) { throw "Package not found: $Path" }

$versionLine = Get-Content (Join-Path $root "gradle.properties") |
    Where-Object { $_ -match '^version=(.+)$' } | Select-Object -First 1
if (-not $versionLine) { throw "version is missing from gradle.properties" }
$version = [regex]::Match($versionLine, '^version=(.+)$').Groups[1].Value.Trim()

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
try {
    $entries = @{}
    foreach ($entry in $archive.Entries) { $entries[$entry.FullName] = $entry }

    function Read-Entry([string]$name) {
        $entry = $entries[$name]
        if (-not $entry) { throw "Missing package entry: $name" }
        $stream = $entry.Open()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose(); $stream.Dispose() }
    }

    $preferences = Read-Entry "classes/preference_config.json" | ConvertFrom-Json
    $configNames = @($preferences.configs | ForEach-Object { $_.config })
    foreach ($required in @("island.json", "spw-lyrics.json", "tag-workbench.json")) {
        if ($configNames -notcontains $required) { throw "Merged preferences are missing $required" }
    }

    $extensionClass = "io.github.solitaire.spwlyricsisland.SPWLyricsIslandPlaybackExtension"
    $extensionIndex = (Read-Entry "classes/META-INF/extensions.idx").Trim()
    if ($extensionIndex -ne $extensionClass) {
        throw "Unexpected extensions.idx: $extensionIndex"
    }

    $manifest = (Read-Entry "classes/META-INF/MANIFEST.MF") -replace "\r?\n ", ""
    $expectedManifest = [ordered]@{
        "Plugin-Class" = "io.github.solitaire.spwlyricsisland.SPWLyricsIslandPlugin"
        "Plugin-Id" = "io.github.solitaire.spwlyricsisland"
        "Plugin-Version" = $version
        "Plugin-Provider" = "solitaire + 大肥鱼（感谢原作者 GaBoron、univers629 / salt-workshop contributors）"
        "Plugin-Open-Source-Url" = "https://github.com/Rimliya/SPW-Lyrics-Island"
    }
    foreach ($key in $expectedManifest.Keys) {
        $pattern = "(?m)^$([regex]::Escape($key)): $([regex]::Escape($expectedManifest[$key]))\r?$"
        if ($manifest -notmatch $pattern) { throw "Manifest does not contain $key=$($expectedManifest[$key])" }
    }

    $sourceProperties = Read-Entry "classes/project.properties"
    if ($sourceProperties -notmatch "source\.url=https://github\.com/Rimliya/SPW-Lyrics-Island") {
        throw "project.properties does not point at the combined repository"
    }

    if ($entries.ContainsKey("classes/fonts/MiSansVF.ttf")) {
        throw "Package must not bundle MiSansVF.ttf"
    }
    if (@($entries.Keys | Where-Object { $_ -like "source/*" }).Count -gt 0) {
        throw "Package must not embed the source archive; publish it as a separate release asset"
    }

    $requiredEntries = @(
        "classes/fonts/PingFang.ttc",
        "classes/native/spw-spectrum.exe",
        "classes/native/spw-island-tray.exe",
        "classes/native/spw-spout.dll",
        "classes/native/spw-island-glass.dll",
        "classes/native/compose/skiko-windows-x64.dll",
        "classes/licenses/tag-workbench/LICENSE",
        "classes/licenses/tag-workbench/THIRD_PARTY_NOTICES.md",
        "classes/licenses/tag-workbench/libebur128-COPYING.txt",
        "classes/native/win-x64/ebur128.dll",
        "classes/i18n/strings-zh.properties",
        "classes/io/github/solitaire/spwlyrics/integration/BassTimingAudioDecoder.class"
    )
    foreach ($required in $requiredEntries) {
        if (-not $entries.ContainsKey($required)) { throw "Missing package entry: $required" }
    }

    $libraries = @($entries.Keys | Where-Object { $_ -match "^lib/.+\.jar$" })
    if (@($libraries | Where-Object { $_ -match "^lib/kotlin-stdlib-" }).Count) {
        throw "Package must not bundle the host-provided Kotlin standard library"
    }
    $duplicateLibraries = @($libraries | ForEach-Object { Split-Path $_ -Leaf } |
        Group-Object | Where-Object Count -gt 1)
    if ($duplicateLibraries.Count) { throw "Duplicate library file names: $($duplicateLibraries.Name -join ', ')" }
    if ($libraries.Count -eq 0) { throw "No runtime libraries were packaged" }

    $forbiddenEntries = @(
        "classes/native/windows-x64/assets.properties",
        "classes/native/windows-x64/ffmpeg.exe",
        "classes/native/windows-x64/ffprobe.exe",
        "classes/native/windows-x64/spw-jvm-worker.exe",
        "classes/native/windows-x64/spw-platform.exe",
        "classes/licenses/media-runtime/LICENSE"
    )
    foreach ($forbidden in $forbiddenEntries) {
        if ($entries.ContainsKey($forbidden)) { throw "BASS-only package must not contain: $forbidden" }
    }
    foreach ($library in $libraries) {
        $name = Split-Path $library -Leaf
        if ($name -match "^spw-media-(spw-adapter|sdk|runtime-core|native-worker)-") {
            throw "BASS-only package must not contain Media Runtime library: $name"
        }
    }
    Write-Host "PASS: $Path"
    Write-Host "  version: $version"
    Write-Host "  settings: $($configNames -join ', ')"
    Write-Host "  extension: $extensionClass"
    Write-Host "  runtime libraries: $($libraries.Count)"
} finally {
    $archive.Dispose()
}
