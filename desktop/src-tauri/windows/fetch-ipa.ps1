# Puts the SwitchFlow iPhone app (unsigned .ipa from the iOS cloud build) into the PC installer, so a
# user can install it with Sideloadly or iLoader straight from the PC ("iPhone app file" button,
# src/commands.rs show_iphone_app). Takes the newest successful build of .github/workflows/ios.yml
# on main; needs the GitHub CLI signed in to the (private) repository.
$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "companion\SwitchFlow-iPhone.ipa"
$repo = "dauletcrazyone-hub/switchflow"

$run = gh run list -R $repo --workflow ios.yml --branch main --status success --limit 1 --json databaseId --jq ".[0].databaseId"
if (-not $run) { throw "No successful iOS build found in $repo" }
$tmp = Join-Path ([IO.Path]::GetTempPath()) "sf-ipa-$run"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
gh run download $run -R $repo -n ios-ipa -D $tmp
if ($LASTEXITCODE -ne 0) { throw "Could not download the .ipa of iOS build $run (artifacts are kept 30 days: rerun the iOS workflow)" }
$ipa = Get-ChildItem $tmp -Filter *.ipa | Select-Object -First 1
if (-not $ipa) { throw "iOS build $run has no .ipa" }
# An .ipa is a zip with the app under Payload/.
$head = [IO.File]::ReadAllBytes($ipa.FullName)[0..1]
if ($head[0] -ne 0x50 -or $head[1] -ne 0x4B) { throw "$($ipa.Name) is not an .ipa archive" }
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Copy-Item $ipa.FullName $out -Force
Remove-Item -Recurse -Force $tmp
Write-Host ("iPhone app staged for the installer from iOS build {0} ({1:N0} bytes)" -f $run, (Get-Item $out).Length)
