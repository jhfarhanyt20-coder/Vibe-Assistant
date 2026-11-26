# Publishes a new Vibe version that installed phones pick up through auto-update.
#
#   .\scripts\release.ps1 -Version 0.4.0 -Notes "What changed"
#
# It bumps versionName/versionCode, builds the APK (signed with this PC's debug
# key - keep releasing from the same PC, or phones will refuse the update),
# commits, tags, pushes, and creates the GitHub release with the APK attached.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

$gradleFile = Join-Path $root "app\build.gradle.kts"
$text = [System.IO.File]::ReadAllText($gradleFile)
if ($text -notmatch 'versionCode = (\d+)') { throw "versionCode not found" }
$code = [int]$Matches[1] + 1
$text = $text -replace 'versionCode = \d+', "versionCode = $code"
$text = $text -replace 'versionName = "[^"]*"', "versionName = `"$Version`""
[System.IO.File]::WriteAllText($gradleFile, $text, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Version $Version (code $code)"

& .\gradlew.bat assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

New-Item -ItemType Directory -Force (Join-Path $root "dist") | Out-Null
$apk = Join-Path $root "dist\Vibe-v$Version.apk"
Copy-Item (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk") $apk -Force

if (-not $Notes) { $Notes = "Vibe v$Version" }
# git and gh print progress on stderr; in Windows PowerShell that must not count as failure.
$ErrorActionPreference = "Continue"
function Invoke-Step([scriptblock]$cmd, [string]$what) {
    & $cmd 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$what failed" }
}
Invoke-Step { git add -A } "git add"
Invoke-Step { git commit -q -m "Release v$Version" } "git commit"
Invoke-Step { git tag "v$Version" } "git tag"
Invoke-Step { git push -q origin HEAD --follow-tags } "git push"
Invoke-Step { gh release create "v$Version" $apk --repo jhfarhanyt20-coder/Vibe-Assistant --title "Vibe v$Version" --notes $Notes } "gh release"
Write-Host "Published v$Version"
