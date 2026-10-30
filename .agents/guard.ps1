<#
  anti-stall guard  (PowerShell)

  Contract: every work-turn must make OBSERVABLE PROGRESS on disk, measured by
  this script. Run it at the START of a turn. If it reports STALLED, you are
  looping -- do NOT try a third variation of the same idea; re-classify and
  change approach.

  Usage:  pwsh -File .agents/guard.ps1 [-Root <path>]
  Exit:   0 = progressed | 1 = STALLED | 2 = baseline recorded
#>
param([string]$Root = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$stateDir = Join-Path $Root '.guard'
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

$exts = @('.kt', '.kts', '.xml', '.toml', '.gradle', '.properties', '.json', '.md', '.svg', '.sql')

function Get-Fingerprint {
    $files = Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object {
            $exts -contains $_.Extension.ToLower() -and
            $_.FullName -notmatch '\\.guard\\' -and
            $_.FullName -notmatch '\\\.gradle\\' -and
            $_.FullName -notmatch '\\build\\'
        } |
        Sort-Object FullName
    $sb = [System.Text.StringBuilder]::new()
    foreach ($f in $files) {
        [void]$sb.Append($f.FullName).Append('|').Append($f.Length).Append('|').Append($f.LastWriteTimeUtc.Ticks).Append("`n")
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $sha = [System.Security.Cryptography.SHA256]::Create()
    ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').Substring(0, 16)
}

$fp = Get-Fingerprint
$srcCount = (Get-ChildItem -Path $Root -Recurse -File -Include *.kt, *.kts, *.xml -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\' }).Count
$apk = (Get-ChildItem -Path $Root -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue | Select-Object -First 1).FullName

$stateFile = Join-Path $stateDir 'state.txt'
$streakFile = Join-Path $stateDir 'streak'

if (-not (Test-Path $stateFile)) {
    "$fp $srcCount" | Set-Content -Path $stateFile -Encoding utf8
    Write-Output "GUARD: baseline recorded  fp=$fp  src_files=$srcCount"
    exit 2
}

$prev = (Get-Content $stateFile -Raw).Trim().Split(' ')[0]
$streak = if (Test-Path $streakFile) { [int](Get-Content $streakFile -Raw).Trim() } else { 0 }

if ($fp -eq $prev) {
    $streak++
    "$streak" | Set-Content -Path $streakFile -Encoding utf8
    Write-Output "GUARD: STALLED  fp=$fp unchanged  stall_streak=$streak  src_files=$srcCount"
    if ($apk) { Write-Output "GUARD: last apk -> $apk" }
    if ($streak -ge 2) {
        Write-Output 'GUARD: ACTION REQUIRED - 2 consecutive stalled turns.'
        Write-Output 'GUARD: STOP retrying. Re-classify, then either:'
        Write-Output 'GUARD:   (a) run a DIFFERENT diagnostic that can actually fail, or'
        Write-Output 'GUARD:   (b) declare the route blocked WITH the evidence, or'
        Write-Output 'GUARD:   (c) commit the best verified artifact and report it as a checkpoint.'
    }
    exit 1
}

"$fp $srcCount" | Set-Content -Path $stateFile -Encoding utf8
'0' | Set-Content -Path $streakFile -Encoding utf8
Write-Output "GUARD: OK  fp=$fp  src_files=$srcCount  (streak reset)"
if ($apk) { Write-Output "GUARD: apk present -> $apk" }
exit 0