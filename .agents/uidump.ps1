param(
    [string]$Serial = "emulator-5554",
    [string]$Find = "",
    [int]$Limit = 30
)
# uiautomator helper.
#   -Find <text>  -> centre of the node whose text or content-desc matches
#   no -Find       -> every clickable node, as "label|cx,cy"
#
# Nodes are parsed individually because XML attribute order is not guaranteed; a
# single-order regex silently matched nothing and sent every tap to the wrong row.
$out = "C:\Users\user\AppData\Local\Temp\opencode\uu.xml"
adb -s $Serial shell uiautomator dump /sdcard/uu.xml > $null 2>&1
adb -s $Serial shell cat /sdcard/uu.xml 2>&1 | Out-File -Encoding utf8 $out
$xml = Get-Content $out -Raw

$nodes = $xml -split '<node' | Select-Object -Skip 1
$found = 0
foreach ($n in $nodes) {
    if ($n -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { continue }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
    $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    $text = ''
    if ($n -match 'text="([^"]*)"') { $text = $Matches[1] }
    $desc = ''
    if ($n -match 'content-desc="([^"]*)"') { $desc = $Matches[1] }
    $label = if ($text) { $text } else { $desc }

    $cx = [math]::Round(($x1 + $x2) / 2)
    $cy = [math]::Round(($y1 + $y2) / 2)

    if ($Find) {
        if ($label -match [regex]::Escape($Find)) {
            "{0}|{1},{2}" -f $label, $cx, $cy
            $found++
            if ($found -ge $Limit) { break }
        }
    } elseif ($n -match 'clickable="true"') {
        "{0}|{1},{2}" -f $label, $cx, $cy
        $found++
        if ($found -ge $Limit) { break }
    }
}