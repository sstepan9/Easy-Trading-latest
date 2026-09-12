<#
.SYNOPSIS
Installs Paper, Folia, Purpur and SpongeVanilla test servers for Minecraft
1.21.1 through 1.21.9. Velocity is installed once because it is a proxy and
does not have a Minecraft-server-version-specific jar.

The script uses official download services and keeps each server in:
  C:\EasyTrading\Testing Servers\<minecraft-version>\<Platform> SERVER

Paper/Folia builds are selected from PaperMC's current Downloads Service;
Purpur and SpongeVanilla are selected from their official download services.
#>

[CmdletBinding()]
param(
    [string]$TestingRoot = 'C:\EasyTrading\Testing Servers',
    [string[]]$MinecraftVersions = @('1.21.1','1.21.2','1.21.3','1.21.4','1.21.5','1.21.6','1.21.7','1.21.8','1.21.9'),
    [switch]$IncludeVelocity = $true,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$userAgent = 'EasyTrading-TestServer-Installer/1.0 (https://github.com/)'
$headers = @{ 'User-Agent' = $userAgent }

function Get-FillBuild {
    param([string]$Project, [string]$MinecraftVersion)

    try {
        # The endpoint returns a JSON array. Casting (rather than wrapping in
        # @(...)) keeps the individual build objects enumerable in PowerShell.
        $builds = [object[]](Invoke-RestMethod -Uri "https://fill.papermc.io/v3/projects/$Project/versions/$MinecraftVersion/builds" -Headers $headers)
    } catch {
        Write-Warning "$Project ${MinecraftVersion}: no build list ($($_.Exception.Message))"
        return $null
    }

    if ($builds.Count -eq 0) {
        Write-Warning "$Project ${MinecraftVersion}: no builds available"
        return $null
    }

    $build = $builds | Where-Object { $_.channel -eq 'STABLE' } | Select-Object -First 1
    if ($null -eq $build) {
        $build = $builds | Select-Object -First 1
        Write-Warning "$Project ${MinecraftVersion}: no STABLE build; using $($build.channel) build $($build.id)"
    }

    $download = $build.downloads.'server:default'
    if ($null -eq $download -or [string]::IsNullOrWhiteSpace($download.url)) {
        Write-Warning "$Project ${MinecraftVersion}: selected build has no server download"
        return $null
    }
    return [PSCustomObject]@{ Name = $download.name; Url = $download.url; FallbackUrl = $null; Channel = $build.channel; Build = $build.id }
}

function Get-PurpurBuild {
    param([string]$MinecraftVersion)
    try {
        $info = Invoke-RestMethod -Uri "https://api.purpurmc.org/v2/purpur/$MinecraftVersion" -Headers $headers
        $build = [string]$info.builds.latest
        if ([string]::IsNullOrWhiteSpace($build)) { throw 'latest build is empty' }
        return [PSCustomObject]@{
            Name = "purpur-$MinecraftVersion-$build.jar"
            # Use the fast mirror first; the build number comes from Purpur's
            # official API and the API URL remains as a fallback.
            Url = "https://new-versions.revivenode.com/purpur/purpur-$MinecraftVersion-$build.jar"
            FallbackUrl = "https://api.purpurmc.org/v2/purpur/$MinecraftVersion/$build/download"
            Channel = 'STABLE'
            Build = $build
        }
    } catch {
        Write-Warning "Purpur ${MinecraftVersion}: no build ($($_.Exception.Message))"
        return $null
    }
}

function Get-SpongeVanillaBuild {
    param([string]$MinecraftVersion)
    try {
        $html = (Invoke-WebRequest -Uri "https://dl.spongepowered.org/spongevanilla?minecraft=$MinecraftVersion" -Headers $headers -UseBasicParsing).Content
        # The first official jar link is the recommended build shown by the
        # downloads page. RC builds follow it in the page's build list.
        $match = [regex]::Match($html, 'https://repo\.spongepowered\.org/[^"'' ]+spongevanilla-[^"'' ]+-universal\.jar')
        if (-not $match.Success) { throw 'no SpongeVanilla universal jar link found' }
        $url = $match.Value
        return [PSCustomObject]@{ Name = [IO.Path]::GetFileName($url); Url = $url; FallbackUrl = $null; Channel = 'RECOMMENDED'; Build = $null }
    } catch {
        Write-Warning "SpongeVanilla ${MinecraftVersion}: no build ($($_.Exception.Message))"
        return $null
    }
}

function Install-Jar {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)]$Build,
        [Parameter(Mandatory)][string]$Platform,
        [Parameter(Mandatory)][int]$Port
    )

    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $destination = Join-Path $Directory $Build.Name
    if ($Force -or -not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $temporary = "$destination.download"
        Write-Host "  Downloading $($Build.Name)..."
        try {
            $curlArgs = @('-L', '--fail', '--retry', '0', '--connect-timeout', '20', '--max-time', '300', '--speed-limit', '10000', '--speed-time', '20', '-A', $userAgent, '-o', $temporary, $Build.Url)
            & curl.exe @curlArgs
            if ($LASTEXITCODE -ne 0) {
                if ([string]::IsNullOrWhiteSpace($Build.FallbackUrl)) { throw "Download failed with curl exit code $LASTEXITCODE" }
                Write-Warning "Primary download failed; trying fallback mirror for $($Build.Name)"
                & curl.exe -L --fail --retry 0 --connect-timeout 20 --max-time 300 --speed-limit 10000 --speed-time 20 -A $userAgent -o $temporary $Build.FallbackUrl
                if ($LASTEXITCODE -ne 0) { throw "Fallback download failed with curl exit code $LASTEXITCODE" }
            }
            if ((Get-Item -LiteralPath $temporary).Length -lt 1MB) { throw 'downloaded file is unexpectedly small' }
            Move-Item -LiteralPath $temporary -Destination $destination -Force
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
        }
    } else {
        Write-Host "  Already present: $($Build.Name)"
    }

    # Keep local test servers easy to launch and independent from Mojang auth.
    $propertiesPath = Join-Path $Directory 'server.properties'
    if ($Force -or -not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
        @(
            '# Generated by install-test-servers.ps1'
            "server-port=$Port"
            "query.port=$Port"
            'online-mode=false'
            'enable-query=false'
            "motd=EasyTrading $Platform test server"
        ) | Set-Content -LiteralPath $propertiesPath -Encoding UTF8
    }
    $eulaPath = Join-Path $Directory 'eula.txt'
    if ($Force -or -not (Test-Path -LiteralPath $eulaPath -PathType Leaf)) {
        'eula=true' | Set-Content -LiteralPath $eulaPath -Encoding ASCII
    }
    $startPath = Join-Path $Directory 'start.cmd'
    if ($Force -or -not (Test-Path -LiteralPath $startPath -PathType Leaf)) {
        @('@echo off', ('java -jar "{0}" --nogui' -f $Build.Name), 'pause') |
            Set-Content -LiteralPath $startPath -Encoding ASCII
    }
    return $destination
}

try {
    $root = (Resolve-Path -LiteralPath $TestingRoot -ErrorAction SilentlyContinue).Path
    if ($null -eq $root) {
        $root = (New-Item -ItemType Directory -Path $TestingRoot -Force).FullName
    }
    $versions = @($MinecraftVersions | Where-Object { $_ -match '^1\.21\.[1-9]$' } | Select-Object -Unique)
    if ($versions.Count -eq 0) { throw 'No valid Minecraft versions selected.' }

    $installed = [System.Collections.Generic.List[string]]::new()
    $skipped = [System.Collections.Generic.List[string]]::new()
    $platforms = @(
        [PSCustomObject]@{ Name = 'Paper'; Project = 'paper'; Resolver = { param($v) Get-FillBuild 'paper' $v } },
        [PSCustomObject]@{ Name = 'Folia'; Project = 'folia'; Resolver = { param($v) Get-FillBuild 'folia' $v } },
        [PSCustomObject]@{ Name = 'Purpur'; Project = 'purpur'; Resolver = { param($v) Get-PurpurBuild $v } },
        [PSCustomObject]@{ Name = 'SpongeVanilla'; Project = 'spongevanilla'; Resolver = { param($v) Get-SpongeVanillaBuild $v } }
    )

    $versionIndex = 0
    foreach ($version in $versions) {
        $versionIndex++
        Write-Host "=== Minecraft $version ===" -ForegroundColor Cyan
        $platformIndex = 0
        foreach ($platform in $platforms) {
            $platformIndex++
            $build = & $platform.Resolver $version
            if ($null -eq $build) {
                $skipped.Add("$version/$($platform.Name)")
                continue
            }
            $directory = Join-Path (Join-Path $root $version) ("{0} SERVER" -f $platform.Name)
            # Distinct ports permit manual parallel launches. Existing 1.21.11
            # servers are intentionally left unchanged.
            # Derive the port block from the Minecraft minor version so that
            # installing a subset later cannot reuse another version's ports.
            $minor = [int]($version.Split('.')[-1])
            $port = 25565 + (($minor - 1) * 10) + ($platformIndex - 1)
            try {
                Install-Jar -Directory $directory -Build $build -Platform $platform.Name -Port $port | Out-Null
                $installed.Add("$version/$($platform.Name)")
                Write-Host "  $($platform.Name): $($build.Name) [$($build.Channel)] port $port" -ForegroundColor Green
            } catch {
                $skipped.Add("$version/$($platform.Name)")
                Write-Warning "$version/$($platform.Name): download failed ($($_.Exception.Message))"
            }
        }
    }

    if ($IncludeVelocity) {
        Write-Host '=== Velocity proxy ===' -ForegroundColor Cyan
        try {
            $velocity = Get-FillBuild 'velocity' '3.5.1'
            if ($null -eq $velocity) { throw 'no recommended Velocity build found' }
            $velocityDir = Join-Path $root 'Velocity SERVER'
            Install-Jar -Directory $velocityDir -Build $velocity -Platform 'Velocity' -Port 25555 | Out-Null
            $installed.Add('Velocity')
            Write-Host "  Velocity: $($velocity.Name) [$($velocity.Channel)]" -ForegroundColor Green
        } catch {
            $skipped.Add('Velocity')
            Write-Warning "Velocity: $($_.Exception.Message)"
        }
    }

    Write-Host ''
    Write-Host "Installed: $($installed.Count)" -ForegroundColor Green
    if ($skipped.Count -gt 0) {
        Write-Host "Unavailable/skipped: $($skipped -join ', ')" -ForegroundColor Yellow
        Write-Host 'Skipped entries are unavailable from the official download service, or had no compatible build.' -ForegroundColor Yellow
    }
    if ($installed.Count -eq 0) { exit 1 }
    exit 0
} catch {
    Write-Host "INSTALL FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
