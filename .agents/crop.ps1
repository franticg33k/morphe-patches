param(
  [Parameter(Mandatory=$true)][string]$In,
  [Parameter(Mandatory=$true)][string]$Out,
  [int]$X=0, [int]$Y=0, [int]$W=0, [int]$H=0, [double]$Scale=2.0
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($In)
if ($W -le 0) { $W = $src.Width - $X }
if ($H -le 0) { $H = $src.Height - $Y }
$dst = New-Object System.Drawing.Bitmap ([int]($W*$Scale)), ([int]($H*$Scale))
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0,0,([int]($W*$Scale)),([int]($H*$Scale))), (New-Object System.Drawing.Rectangle $X,$Y,$W,$H), [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose(); $src.Dispose()
Write-Output "cropped $X,$Y ${W}x${H} -> $Out"
