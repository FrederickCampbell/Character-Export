# Run-Dev.ps1
# Mirrors the project source to a disposable Windows build directory, then
# builds and launches the RuneLite developer harness with Character Export loaded.
#
# Usage (from PowerShell, anywhere):
#   & "<repo>\windows\Run-Dev.ps1"
#
# Output log:
#   %USERPROFILE%\.runelite\character-exporter\dev-harness.log

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$buildDir = Join-Path $env:TEMP 'Character-Export-Dev'
$logFile = Join-Path $env:USERPROFILE '.runelite\character-exporter\dev-harness.log'

Write-Host "==> Mirroring source to $buildDir"
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
robocopy $projectRoot $buildDir /MIR /XD '.gradle' 'build' '.git' '.rc-backups' /XF '*.log' '*.class' | Out-Null
if ($LASTEXITCODE -ge 8)
{
    throw "robocopy failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null

Write-Host "==> Building and launching (log: $logFile)"
Write-Host '    Press Ctrl+C to stop.'
Push-Location $buildDir
try
{
    .\gradlew.bat run --console=plain 2>&1 | Tee-Object -FilePath $logFile
}
finally
{
    Pop-Location
}
