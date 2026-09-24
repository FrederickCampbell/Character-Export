param(
    [string]$Character
)

$ErrorActionPreference = 'Stop'
$exportRoot = Join-Path $env:USERPROFILE '.runelite\character-exporter'
$publicFiles = @(
    'character.json',
    'quests.json',
    'diaries.json',
    'combat_achievements.json',
    'collection_log.json',
    'inventory.json',
    'equipment.json',
    'bank.json',
    'seed_vault.json',
    'storage.json',
    'activities.json',
    'progress.json',
    'live.json'
)

if (-not (Test-Path -LiteralPath $exportRoot))
{
    Write-Host "Export directory does not exist yet: $exportRoot"
    Write-Host 'Log in to RuneLite with Character Export loaded to create it.'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Character))
{
    $accountDirs = @(
        Get-ChildItem -LiteralPath $exportRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending
    )

    if ($accountDirs.Count -eq 0)
    {
        Write-Host "No character directories found under $exportRoot"
        exit 0
    }

    $accountDir = $accountDirs[0]
}
else
{
    $accountDir = Get-Item -LiteralPath (Join-Path $exportRoot $Character)
}

Write-Host "==> Character Export: $($accountDir.Name)"
Write-Host "    $($accountDir.FullName)"
Write-Host ''

$failed = $false
foreach ($name in $publicFiles)
{
    $path = Join-Path $accountDir.FullName $name
    if (-not (Test-Path -LiteralPath $path))
    {
        Write-Host "MISSING  $name" -ForegroundColor Yellow
        $failed = $true
        continue
    }

    try
    {
        $null = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $item = Get-Item -LiteralPath $path
        $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 1)
        Write-Host ("OK       {0,-28} {1,9} bytes  {2,8} min ago" -f $name, $item.Length, $age) -ForegroundColor Green
    }
    catch
    {
        Write-Host "INVALID  $name  $($_.Exception.Message)" -ForegroundColor Red
        $failed = $true
    }
}

Write-Host ''
$unexpected = @(
    Get-ChildItem -LiteralPath $accountDir.FullName -File -Filter '*.json' |
        Where-Object { $_.Name -notin $publicFiles }
)

if ($unexpected.Count -gt 0)
{
    Write-Host 'Unexpected root-level JSON files:' -ForegroundColor Yellow
    $unexpected | ForEach-Object { Write-Host "  $($_.Name)" }
    $failed = $true
}
else
{
    Write-Host 'Public root contract matches the 13 domain JSON files.' -ForegroundColor Green
}

$cacheDir = Join-Path $accountDir.FullName '.cache'
$cacheCount = if (Test-Path -LiteralPath $cacheDir) {
    @(Get-ChildItem -LiteralPath $cacheDir -File -ErrorAction SilentlyContinue).Count
} else { 0 }
Write-Host "Cache fragments: $cacheCount"

$diagnosticsDir = Join-Path $accountDir.FullName 'diagnostics'
if (Test-Path -LiteralPath $diagnosticsDir)
{
    Write-Host "Diagnostics: $diagnosticsDir"
}

if ($failed)
{
    exit 1
}
