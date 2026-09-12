<#
Shows and stops Minecraft test-server Java processes started from the
configured Testing Servers directory. Java processes used by VS Code/Gradle
are ignored.
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$TestingRoot = 'C:\EasyTrading\Testing Servers',
    [switch]$ListOnly,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $TestingRoot -PathType Container)) {
    throw "Testing servers directory was not found: $TestingRoot"
}

$jarNames = @(
    Get-ChildItem -LiteralPath $TestingRoot -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Name -Unique
)
if ($jarNames.Count -eq 0) {
    Write-Host 'No server JAR files found in the testing directory.' -ForegroundColor Yellow
    exit 0
}

$processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $commandLine = [string]$_.CommandLine
    if ([string]::IsNullOrWhiteSpace($commandLine)) { return $false }
    $match = [regex]::Match($commandLine, '(?i)-jar\s+(?:"([^"]+\.jar)"|([^\s]+\.jar))')
    if (-not $match.Success) { return $false }
    $jar = if ($match.Groups[1].Success) { Split-Path -Leaf $match.Groups[1].Value } else { Split-Path -Leaf $match.Groups[2].Value }
    $jarNames -contains $jar
})

if ($processes.Count -eq 0) {
    Write-Host 'No running test-server processes found.' -ForegroundColor Green
    exit 0
}

$rows = foreach ($process in $processes) {
    $commandLine = [string]$process.CommandLine
    $match = [regex]::Match($commandLine, '(?i)-jar\s+(?:"([^"]+\.jar)"|([^\s]+\.jar))')
    $jar = if ($match.Success) {
        if ($match.Groups[1].Success) { Split-Path -Leaf $match.Groups[1].Value } else { Split-Path -Leaf $match.Groups[2].Value }
    } else { '-' }
    $portMatch = [regex]::Match($commandLine, '(?i)(?:--port|server-port[=\s])\s*(\d+)')
    [PSCustomObject]@{
        PID = [int]$process.ProcessId
        ParentPID = [int]$process.ParentProcessId
        Jar = $jar
        Port = if ($portMatch.Success) { $portMatch.Groups[1].Value } else { '-' }
        CommandLine = $commandLine
    }
}

Write-Host 'Running test servers:' -ForegroundColor Cyan
$rows | Sort-Object Port, PID | Format-Table PID, ParentPID, Port, Jar -AutoSize

if ($ListOnly) { exit 0 }

$knownPids = @($rows | Select-Object -ExpandProperty PID)
$rootPids = @($rows | Where-Object { $knownPids -notcontains [int]$_.ParentPID } | Select-Object -ExpandProperty PID -Unique)
if ($rootPids.Count -eq 0) { $rootPids = @($rows | Select-Object -ExpandProperty PID -Unique) }

if (-not $Force) {
    $answer = Read-Host 'Stop these test-server process trees? (Y/N)'
    if ($answer -notmatch '^(?i)y(es)?$') {
        Write-Host 'Nothing was stopped.' -ForegroundColor Yellow
        exit 0
    }
}

foreach ($serverPid in $rootPids) {
    if ($PSCmdlet.ShouldProcess("PID $serverPid", 'Terminate server process tree')) {
        & taskkill.exe /PID $serverPid /T /F | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Host "Stopped process tree rooted at PID $serverPid." -ForegroundColor Green }
        else { Write-Host "Could not stop PID $serverPid (exit code $LASTEXITCODE)." -ForegroundColor Red }
    }
}

Start-Sleep -Milliseconds 500
$remaining = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $commandLine = [string]$_.CommandLine
    $jarNames | Where-Object { $commandLine -match [regex]::Escape($_) }
})
if ($remaining.Count -eq 0) { Write-Host 'No test-server Java processes remain.' -ForegroundColor Green }
else { Write-Host 'Some matching processes remain; run the command again as Administrator if needed.' -ForegroundColor Yellow }
