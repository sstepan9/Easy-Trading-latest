param(
    [string[]]$MinecraftVersions,
    [switch]$NoClean
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$distDir = Join-Path $root "dist"
$baseModVersion = "0.9"

$targets = @(
    "1.21.1",
    "1.21.2",
    "1.21.3",
    "1.21.4",
    "1.21.5",
    "1.21.6",
    "1.21.7",
    "1.21.8",
    "1.21.9",
    "1.21.10"
)

if ($MinecraftVersions -and $MinecraftVersions.Count -gt 0) {
    $targets = $targets | Where-Object { $MinecraftVersions -contains $_ }
}

if (-not $targets -or $targets.Count -eq 0) {
    throw "No build targets selected."
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$failed = @()

foreach ($mcVersion in $targets) {
    $modVersion = "$mcVersion-$baseModVersion"
    $gradleTasks = @()
    if (-not $NoClean) {
        $gradleTasks += "clean"
    }
    $gradleTasks += "build"

    Write-Host "=== Building Paper plugin for MC $mcVersion (version: $modVersion) ==="

    & (Join-Path $root "gradlew.bat") @gradleTasks `
        "-Pmc_version=$mcVersion" `
        "-Pmod_version=$modVersion" `
        --console plain

    if ($LASTEXITCODE -ne 0) {
        $failed += $mcVersion
        Write-Warning "Build failed for Minecraft $mcVersion"
        continue
    }

    $jarName = "EasyTrading-Paper-$modVersion.jar"
    $sourceJar = Get-ChildItem (Join-Path $root "build\libs") -Filter "EasyTrading-Paper-*.jar" |
        Where-Object { $_.Name -notlike "*-sources*" } |
        Select-Object -First 1

    if ($sourceJar) {
        Copy-Item -LiteralPath $sourceJar.FullName -Destination (Join-Path $distDir $jarName) -Force
        Write-Host "  -> $jarName"
    } else {
        $failed += $mcVersion
        Write-Warning "Jar not found for MC $mcVersion"
    }
}

Write-Host ""
Write-Host "Built artifacts copied to $distDir"
if ($failed.Count -gt 0) {
    Write-Warning ("Failed versions: " + ($failed -join ", "))
    exit 1
} else {
    Write-Host "All $($targets.Count) versions built successfully!"
}
