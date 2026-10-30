<#
.SYNOPSIS
    Deterministic UI driver for the Baato CBF app on a named adb device.

.DESCRIPTION
    Resolves a node by its text or content-desc, taps the centre of its nearest
    clickable ancestor, and reports what is on screen afterwards.

    Screens in this app reflow constantly (the live bus count changes the height
    of the "Buses near you" card), so hardcoded tap coordinates go stale within
    minutes and silently land on the wrong row. Always resolve first.

.EXAMPLE
    .\tap.ps1 -Device emulator-5554 -Text 'View on map' -WaitMs 12000
    .\tap.ps1 -Device emulator-5554 -Text 'View on map' -Shot C:\temp\map.png
#>
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [Parameter(Mandatory = $true)][string]$Text,
    [int]$Index = 0,
    [int]$WaitMs = 3000,
    [string]$Shot = '',
    [switch]$DumpOnly
)

$ErrorActionPreference = 'Stop'
$Dump = '/sdcard/_ui.xml'

function Get-Ui {
    & adb -s $Device shell uiautomator dump $Dump *> $null
    $raw = & adb -s $Device shell cat $Dump
    return [xml]$raw
}

# Depth-first walk returning @{Text;Desc;Bounds;Clickable} for every node.
function Get-Nodes($node) {
    $out = @()
    if ($node -is [System.Xml.XmlElement]) {
        if ($node.LocalName -eq 'node') {
            $out += [pscustomobject]@{
                Text      = $node.GetAttribute('text')
                Desc      = $node.GetAttribute('content-desc')
                Bounds    = $node.GetAttribute('bounds')
                Clickable = $node.GetAttribute('clickable') -eq 'true'
            }
        }
        foreach ($child in $node.ChildNodes) { $out += Get-Nodes $child }
    }
    return $out
}

# "[x1,y1][x2,y2]" -> centre point
function Get-Centre([string]$bounds) {
    if ($bounds -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
    $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    return @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
}

$ui = Get-Ui
$all = Get-Nodes $ui.hierarchy
$hits = $all | Where-Object { $_.Text -eq $Text -or $_.Desc -eq $Text }

if (-not $hits) {
    Write-Output "NOT FOUND: '$Text'"
    Write-Output "visible texts:"
    $all | Where-Object { $_.Text } | Select-Object -ExpandProperty Text -Unique |
        Select-Object -First 25 | ForEach-Object { "  $_" }
    exit 1
}

$hit = $hits[[Math]::Min($Index, $hits.Count - 1)]
Write-Output "matched '$Text' bounds=$($hit.Bounds) clickable=$($hit.Clickable)"

if ($DumpOnly) { exit 0 }

# If the matched node is not itself clickable, walk to the first clickable
# ancestor by expanding the search outward via the clickable nodes that contain
# the matched node's centre.
$centre = Get-Centre $hit.Bounds
if (-not $centre) { Write-Output 'bad bounds'; exit 1 }

if (-not $hit.Clickable) {
    $cx = $centre[0]; $cy = $centre[1]
    $container = $all | Where-Object {
        $_.Clickable -and (Get-Centre $_.Bounds) -ne $null
    } | Where-Object {
        $c = Get-Centre $_.Bounds
        $cx -ge ([int]($_.Bounds -replace '.*\[(-?\d+),.*','$1')) -and $cy -ge 0
    }
    # Simplest reliable choice: the smallest clickable node whose box contains
    # the matched node's centre.
    $cands = $all | Where-Object { $_.Clickable } | Where-Object {
        $b = $_.Bounds
        if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
            $cx -ge [int]$Matches[1] -and $cx -le [int]$Matches[3] -and
            $cy -ge [int]$Matches[2] -and $cy -le [int]$Matches[4]
        }
    }
    if ($cands) {
        $pick = $cands | Sort-Object {
            $b = $_.Bounds
            if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
                ([int]$Matches[3] - [int]$Matches[1]) * ([int]$Matches[4] - [int]$Matches[2])
            } else { [long]::MaxValue }
        } | Select-Object -First 1
        $centre = Get-Centre $pick.Bounds
        Write-Output "tapping clickable ancestor bounds=$($pick.Bounds)"
    }
}

Write-Output "tap ($($centre[0]),$($centre[1]))"
& adb -s $Device shell input tap $centre[0] $centre[1] | Out-Null

if ($WaitMs -gt 0) { Start-Sleep -Milliseconds $WaitMs }

if ($Shot) {
    & adb -s $Device shell "screencap -p /sdcard/_shot.png" | Out-Null
    & adb -s $Device pull '/sdcard/_shot.png' $Shot *> $null
    Write-Output "shot -> $Shot"
}

$after = Get-Ui
$texts = Get-Nodes $after.hierarchy | Where-Object { $_.Text } |
    Select-Object -ExpandProperty Text -Unique
if ($texts -match 'MapLibre') {
    Write-Output 'STATE: map screen'
} else {
    Write-Output ("STATE: " + (($texts | Select-Object -First 8) -join ' | '))
}
