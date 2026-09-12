<#
Runs all available server implementations for each Minecraft version as one
batch. Paper, Folia and Purpur load the plugin. SpongeVanilla is a smoke test
because EasyTrading is a Bukkit/Paper/Folia plugin. Velocity is a separate
proxy and is not included in version batches.
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$TestingRoot = 'C:\EasyTrading\Testing Servers',
    [string[]]$MinecraftVersions,
    [ValidateRange(20000, 60000)][int]$BasePort = 26000,
    [ValidateRange(10, 1800)][int]$StartupTimeoutSec = 180,
    [ValidateRange(0, 120)][int]$PostStartupDelaySec = 5,
    [ValidateRange(5, 300)][int]$StopTimeoutSec = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$readyPattern = 'Done \(.*\)! For help'
$errorPattern = '(?i)(?:\[(?:ERROR|SEVERE)\]|\b(?:ERROR|SEVERE|Exception|LinkageError|NoClassDefFoundError|UnsupportedClassVersionError)\b|\bError(?:\s|:)|Could not load plugin|Error occurred while (?:enabling|disabling)|Failed to (?:load|enable|start)|InvalidPluginException)'

function Drain-Output {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)][string]$ReadyRegex)
    foreach ($stream in $Record.Streams) {
        if ($null -eq $stream.Task) { continue }
        while ($stream.Task.IsCompleted) {
            try { $line = $stream.Task.GetAwaiter().GetResult() }
            catch { $Record.Errors.Add("[$($stream.Name)] Read failed: $($_.Exception.Message)"); $stream.Task = $null; break }
            if ($null -eq $line) { $stream.Task = $null; break }
            $Record.Lines.Add("[$($stream.Name)] $line")
            if ($line -match $ReadyRegex) { $Record.Ready = $true }
            if ($line -match $errorPattern) { $Record.Errors.Add("[$($stream.Name)] $line") }
            $stream.Task = $stream.Reader.ReadLineAsync()
        }
    }
}

function Save-Log {
    param([Parameter(Mandatory)]$Record)
    New-Item -ItemType Directory -Path $Record.LogDirectory -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $name = ('{0}-{1}-{2}.log' -f $Record.Version, $Record.Platform, $stamp).ToLowerInvariant()
    $path = Join-Path $Record.LogDirectory $name
    [IO.File]::WriteAllLines($path, [string[]]$Record.Lines, (New-Object System.Text.UTF8Encoding($false)))
    $Record.LogPath = $path
}

function Set-Port {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][int]$Port)
    $path = Join-Path $Directory 'server.properties'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    $text = [IO.File]::ReadAllText($path)
    if ($text -match '(?m)^server-port=') { $text = [regex]::Replace($text, '(?m)^server-port=.*$', "server-port=$Port") }
    else { $text += [Environment]::NewLine + "server-port=$Port" }
    if ($text -match '(?m)^query\.port=') { $text = [regex]::Replace($text, '(?m)^query\.port=.*$', "query.port=$Port") }
    [IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-Record {
    param(
        [Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$Platform,
        [Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][System.IO.FileInfo]$Jar,
        [Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][bool]$PluginCompatible,
        [Parameter(Mandatory)][string]$ReadyRegex, [Parameter(Mandatory)][string]$StopCommand,
        [Parameter(Mandatory)][string]$LogDirectory, [Parameter(Mandatory)][string]$PluginPath
    )
    if ($PluginCompatible) {
        $plugins = Join-Path $Directory 'plugins'
        New-Item -ItemType Directory -Path $plugins -Force | Out-Null
        Get-ChildItem -LiteralPath $plugins -File -Filter 'EasyTrading-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike 'EasyTrading-Russificator-*.jar' } | Remove-Item -Force
        Copy-Item -LiteralPath $PluginPath -Destination (Join-Path $plugins ([IO.Path]::GetFileName($PluginPath))) -Force
    }
    Set-Port -Directory $Directory -Port $Port
    [PSCustomObject]@{
        Version = $Version; Platform = $Platform; Directory = $Directory; Jar = $Jar; Port = $Port
        PluginCompatible = $PluginCompatible; ReadyRegex = $ReadyRegex; StopCommand = $StopCommand; LogDirectory = $LogDirectory
        Process = $null; Streams = @(); Lines = [System.Collections.Generic.List[string]]::new()
        Errors = [System.Collections.Generic.List[string]]::new(); Ready = $false; StartFailed = $false; LogPath = $null
    }
}

function Start-Record {
    param([Parameter(Mandatory)]$Record)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = 'java'
    $info.Arguments = '-jar "{0}" --nogui --port {1}' -f $Record.Jar.Name, $Record.Port
    $info.WorkingDirectory = $Record.Directory
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try { if (-not $process.Start()) { throw 'Java process did not start.' } }
    catch { $Record.StartFailed = $true; $Record.Errors.Add("[SCRIPT] Unable to start: $($_.Exception.Message)"); return }
    $Record.Process = $process
    $Record.Streams = @(
        [PSCustomObject]@{ Name = 'OUT'; Reader = $process.StandardOutput; Task = $process.StandardOutput.ReadLineAsync() },
        [PSCustomObject]@{ Name = 'ERR'; Reader = $process.StandardError; Task = $process.StandardError.ReadLineAsync() }
    )
}

function Stop-Record {
    param([Parameter(Mandatory)]$Record)
    if ($null -eq $Record.Process -or $Record.Process.HasExited) { return }
    try { $Record.Process.StandardInput.WriteLine($Record.StopCommand); $Record.Process.StandardInput.Flush() }
    catch { $Record.Errors.Add("[SCRIPT] Could not send stop command: $($_.Exception.Message)") }
    $deadline = [DateTime]::UtcNow.AddSeconds($StopTimeoutSec)
    while (-not $Record.Process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Drain-Output -Record $Record -ReadyRegex $Record.ReadyRegex
        Start-Sleep -Milliseconds 100
    }
    if (-not $Record.Process.HasExited) {
        & taskkill.exe /PID $Record.Process.Id /T /F | Out-Null
        $Record.Errors.Add("[SCRIPT] Process was forcibly terminated after $StopTimeoutSec seconds.")
    }
}

function Invoke-VersionBatch {
    param([Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$VersionDirectory, [Parameter(Mandatory)][int]$VersionIndex, [Parameter(Mandatory)][string]$PluginPath, [Parameter(Mandatory)][string]$LogDirectory)
    $definitions = @(
        [PSCustomObject]@{ Name = 'Paper'; Dir = 'Paper SERVER'; Pattern = 'paper-*.jar'; Compatible = $true; Ready = $readyPattern; Stop = 'stop'; Slot = 0 },
        [PSCustomObject]@{ Name = 'Folia'; Dir = 'Folia SERVER'; Pattern = 'folia-*.jar'; Compatible = $true; Ready = $readyPattern; Stop = 'stop'; Slot = 1 },
        [PSCustomObject]@{ Name = 'Purpur'; Dir = 'Purpur SERVER'; Pattern = 'purpur-*.jar'; Compatible = $true; Ready = $readyPattern; Stop = 'stop'; Slot = 2 },
        [PSCustomObject]@{ Name = 'SpongeVanilla'; Dir = 'SpongeVanilla SERVER'; Pattern = 'spongevanilla-*.jar'; Compatible = $false; Ready = $readyPattern; Stop = 'stop'; Slot = 3 }
    )
    $records = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $definitions) {
        $directory = Join-Path $VersionDirectory $definition.Dir
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
        $jar = Get-ChildItem -LiteralPath $directory -File -Filter $definition.Pattern | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($null -eq $jar) { continue }
        $port = $BasePort + (($VersionIndex - 1) * 10) + $definition.Slot
        $records.Add((New-Record -Version $Version -Platform $definition.Name -Directory $directory -Jar $jar -Port $port -PluginCompatible $definition.Compatible -ReadyRegex $definition.Ready -StopCommand $definition.Stop -LogDirectory $LogDirectory -PluginPath $PluginPath))
    }
    if ($records.Count -eq 0) { Write-Host ("{0}: no server folders found." -f $Version) -ForegroundColor Yellow; return @() }

    Write-Host ("Starting {0} batch: {1}" -f $Version, ($records.Platform -join ', ')) -ForegroundColor Cyan
    try {
        foreach ($record in $records) { Write-Host ("  {0} on port {1}" -f $record.Platform, $record.Port); Start-Record -Record $record }
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSec)
        while ([DateTime]::UtcNow -lt $deadline) {
            foreach ($record in $records) { if ($null -ne $record.Process) { Drain-Output -Record $record -ReadyRegex $record.ReadyRegex } }
            $pending = @($records | Where-Object { -not $_.StartFailed -and $null -ne $_.Process -and -not $_.Ready -and -not $_.Process.HasExited })
            if ($pending.Count -eq 0) { break }
            Start-Sleep -Milliseconds 100
        }
        foreach ($record in $records) {
            if (-not $record.Ready -and -not $record.StartFailed) {
                if ($record.Process.HasExited) { $record.Errors.Add("[SCRIPT] Exited before startup (code $($record.Process.ExitCode)).") }
                else { $record.Errors.Add("[SCRIPT] Startup timed out after $StartupTimeoutSec seconds.") }
            }
        }
        if ($PostStartupDelaySec -gt 0) {
            $until = [DateTime]::UtcNow.AddSeconds($PostStartupDelaySec)
            while ([DateTime]::UtcNow -lt $until) { foreach ($record in $records) { if ($null -ne $record.Process -and -not $record.Process.HasExited) { Drain-Output -Record $record -ReadyRegex $record.ReadyRegex } }; Start-Sleep -Milliseconds 100 }
        }
    }
    finally {
        foreach ($record in $records) { Stop-Record -Record $record }
    }
    foreach ($record in $records) {
        Drain-Output -Record $record -ReadyRegex $record.ReadyRegex
        if ($null -ne $record.Process -and $record.Process.HasExited -and $record.Process.ExitCode -ne 0) {
            $record.Errors.Add("[SCRIPT] Server process exited with code $($record.Process.ExitCode).")
        }
        Save-Log -Record $record
        $passed = $record.Ready -and $record.Errors.Count -eq 0 -and -not $record.StartFailed
        $mode = if ($record.PluginCompatible) { 'PLUGIN' } else { 'SMOKE' }
        if ($passed) { Write-Host ("{0,-13} {1,-6} .... OK ({2})" -f $record.Platform, $Version, $mode) -ForegroundColor Green }
        else { Write-Host ("{0,-13} {1,-6} .... FAILED ({2})" -f $record.Platform, $Version, $mode) -ForegroundColor Red; foreach ($errorLine in $record.Errors) { Write-Host "  $errorLine" -ForegroundColor Red }; Write-Host "  Full log: $($record.LogPath)" -ForegroundColor Yellow }
    }
    @($records)
}

try {
    $projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
    $testingPath = (Resolve-Path -LiteralPath $TestingRoot).Path
    $buildLibs = Join-Path $projectPath 'build\libs'
    $pluginJar = Get-ChildItem -LiteralPath $buildLibs -File -Filter 'EasyTrading-*.jar' | Where-Object { $_.Name -notlike '*-sources.jar' } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $pluginJar) { throw "EasyTrading JAR was not found in $buildLibs." }
    $versions = @(Get-ChildItem -LiteralPath $testingPath -Directory | Where-Object { $_.Name -match '^1\.21\.\d+$' } | Sort-Object { [version]$_.Name })
    if ($MinecraftVersions -and $MinecraftVersions.Count -gt 0) { $versions = @($versions | Where-Object { $MinecraftVersions -contains $_.Name }) }
    if ($versions.Count -eq 0) { throw "No Minecraft version folders found in $testingPath." }

    $logDirectory = Join-Path $projectPath 'build\test-server-logs'
    Write-Host "Testing plugin: $($pluginJar.Name)"
    Write-Host ("Versions:       {0}" -f ($versions.Name -join ', '))
    Write-Host "Base test port: $BasePort"
    $allRecords = [System.Collections.Generic.List[object]]::new()
    $index = 0
    foreach ($version in $versions) {
        $index++
        foreach ($record in (Invoke-VersionBatch -Version $version.Name -VersionDirectory $version.FullName -VersionIndex $index -PluginPath $pluginJar.FullName -LogDirectory $logDirectory)) { $allRecords.Add($record) }
    }
    if (Test-Path -LiteralPath (Join-Path $testingPath 'Velocity SERVER') -PathType Container) {
        Write-Host 'Velocity is not part of per-version plugin tests (it is a proxy, not a Bukkit server).' -ForegroundColor Yellow
    }
    $failed = @($allRecords | Where-Object { -not $_.Ready -or $_.Errors.Count -gt 0 -or $_.StartFailed })
    if ($failed.Count -eq 0) { Write-Host 'All available version batches completed successfully.' -ForegroundColor Green; exit 0 }
    Write-Host 'One or more server tests failed. See the full log paths above.' -ForegroundColor Red
    exit 1
} catch {
    Write-Host "TEST SCRIPT FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
