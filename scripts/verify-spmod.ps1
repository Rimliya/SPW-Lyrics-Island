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

    $preferences = Read-Entry "preference_config.json" | ConvertFrom-Json
    $configNames = @($preferences.configs | ForEach-Object { $_.config })
    foreach ($required in @("island.json", "spw-lyrics.json")) {
        if ($configNames -notcontains $required) { throw "Merged preferences are missing $required" }
    }

    $extensionClass = "io.github.gaboron.spwlyricsisland.SPWLyricsIslandPlaybackExtension"
    $extensionIndex = (Read-Entry "META-INF/extensions.idx").Trim()
    if ($extensionIndex -ne $extensionClass) {
        throw "Unexpected extensions.idx: $extensionIndex"
    }

    $sourceProperties = Read-Entry "project.properties"
    if ($sourceProperties -notmatch "source\.url=https://github\.com/Rimliya/SPW-Lyrics-Island") {
        throw "project.properties does not point at the combined repository"
    }

    $requiredEntries = @(
        "fonts/PingFang.ttc",
        "fonts/MiSansVF.ttf",
        "native/spw-spectrum.exe",
        "native/spw-island-tray.exe",
        "native/spw-spout.dll",
        "native/spw-island-glass.dll",
        "native/compose/skiko-windows-x64.dll"
    )
    foreach ($required in $requiredEntries) {
        if (-not $entries.ContainsKey($required)) { throw "Missing package entry: $required" }
    }

    $libraries = @($entries.Keys | Where-Object { $_ -match "^lib/.+\.jar$" })
    if ($libraries.Count -eq 0) { throw "No runtime libraries were packaged" }
    if (-not @($entries.Keys | Where-Object { $_ -match "^source/.+\.zip$" }).Count) {
        throw "Source archive is missing from the package"
    }

    $metadata = @($entries.Keys | Where-Object {
        $_ -match '(^|/)plugin\.(properties|json)$' -or $_ -eq 'META-INF/MANIFEST.MF'
    })
    foreach ($name in $metadata) {
        Write-Host "--- $name ---"
        Write-Host (Read-Entry $name)
    }

    Write-Host "PASS: $Path contains merged settings, one extension adapter, fonts, native outputs, libraries, and source archive."
} finally {
    $archive.Dispose()
}