param([string]$Path)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Path) {
    $packages = @(Get-ChildItem (Join-Path $root "build/libs") -Filter "*.spmod" -File)
    if ($packages.Count -ne 1) { throw "Expected exactly one .spmod, found $($packages.Count)" }
    $Path = $packages[0].FullName
}
if (-not (Test-Path -LiteralPath $Path)) { throw "Package not found: $Path" }

$versionLine = Get-Content (Join-Path $root "build.gradle.kts") |
    Where-Object { $_ -match '^version = "(.+)"$' } | Select-Object -First 1
if (-not $versionLine) { throw "version is missing from build.gradle.kts" }
$version = [regex]::Match($versionLine, '^version = "(.+)"$').Groups[1].Value.Trim()

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

    $manifest = Read-Entry "classes/META-INF/MANIFEST.MF"
    $expected = [ordered]@{
        "Plugin-Class" = "dev.gaboron.spwlyrics.integration.SpwLyricsPlugin"
        "Plugin-Id" = "spw-lyrics"
        "Plugin-Version" = $version
        "Plugin-Provider" = "GaBoron / Solitaire"
        "Plugin-Open-Source-Url" = "https://github.com/Rimliya/SPW-Lyrics"
    }
    foreach ($key in $expected.Keys) {
        $pattern = "(?m)^$([regex]::Escape($key)): $([regex]::Escape($expected[$key]))\r?$"
        if ($manifest -notmatch $pattern) { throw "Manifest does not contain $key=$($expected[$key])" }
    }

    $extension = (Read-Entry "classes/META-INF/extensions.idx").Trim()
    if ($extension -ne "dev.gaboron.spwlyrics.integration.SpwLyricsExtension") {
        throw "Unexpected extensions.idx: $extension"
    }
    $preferences = Read-Entry "classes/preference_config.json" | ConvertFrom-Json
    if (@($preferences.configs | ForEach-Object config) -notcontains "spw-lyrics.json") {
        throw "spw-lyrics.json is missing from preference_config.json"
    }
    if (@($entries.Keys | Where-Object { $_ -like "classes/native/compose/*" }).Count) {
        throw "Package must not bundle the host-provided Compose/Skiko native runtime"
    }

    $libraries = @($entries.Keys | Where-Object { $_ -match "^lib/.+\.jar$" })
    if (-not $libraries.Count) { throw "No runtime libraries were packaged" }
    if (@($libraries | Where-Object { $_ -match "^lib/kotlin-stdlib-" }).Count) {
        throw "Package must not bundle the host-provided Kotlin standard library"
    }
    $duplicates = @($libraries | ForEach-Object { Split-Path $_ -Leaf } |
        Group-Object | Where-Object Count -gt 1)
    if ($duplicates.Count) { throw "Duplicate library names: $($duplicates.Name -join ', ')" }

    Write-Host "PASS: $Path"
    Write-Host "  version: $version"
    Write-Host "  extension: $extension"
    Write-Host "  runtime libraries: $($libraries.Count)"
} finally {
    $archive.Dispose()
}
