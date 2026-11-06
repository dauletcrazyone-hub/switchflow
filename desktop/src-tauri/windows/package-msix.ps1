# Builds the Microsoft Store package (MSIX) of SwitchFlow from the release build.
#
#   cd desktop; npm run tauri:build                      # builds target\release (exe + resources)
#   ./src-tauri/windows/package-msix.ps1 -IdentityName <Package/Identity/Name> -Publisher "CN=<...>"
#
# Take IdentityName, Publisher and PublisherDisplayName from Partner Center: the app's
# "Product identity" page (docs/store/MICROSOFT_STORE.md). The package is NOT signed: the Store
# signs it. Differences from the NSIS installer: an MSIX cannot install drivers, so UsbDk is
# installed from the app ("Install the cable driver" card) the first time an Android phone is
# plugged in; the firewall rule comes from the manifest instead of netsh.
param(
    [string]$IdentityName = "SwitchFlow.Desktop",
    [string]$Publisher = "CN=SwitchFlow",
    [string]$PublisherDisplayName = "SwitchFlow",
    [string]$Out = ""
)
$ErrorActionPreference = "Stop"
# Started from PowerShell 7, Windows PowerShell inherits its module path; use this one's modules.
$env:PSModulePath = "$PSHOME\Modules;" + [Environment]::GetEnvironmentVariable("PSModulePath", "Machine")

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$release = Join-Path $root "target\release"
$exe = Join-Path $release "switchflow.exe"
foreach ($p in @($exe, (Join-Path $release "companion"), (Join-Path $release "drivers\UsbDk_x64.msi"))) {
    if (-not (Test-Path $p)) { throw "Missing $p. Build first: cd desktop; npm run tauri:build" }
}
$cargoVersion = (Select-String -Path (Join-Path $root "Cargo.toml") -Pattern '^version = "(\d+)\.(\d+)\.(\d+)"').Matches[0].Groups
# The Store wants four numbers and the last one 0.
$version = "{0}.{1}.{2}.0" -f $cargoVersion[1].Value, $cargoVersion[2].Value, $cargoVersion[3].Value
if (-not $Out) { $Out = Join-Path $root "target\release\bundle\msix\SwitchFlow_${version}_x64.msix" }

$sdk = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.*\x64\makeappx.exe" | Sort-Object FullName | Select-Object -Last 1
if (-not $sdk) { throw "makeappx.exe not found: install the Windows 10/11 SDK." }
$makeappx = $sdk.FullName
$makepri = Join-Path $sdk.DirectoryName "makepri.exe"

$work = Join-Path ([IO.Path]::GetTempPath()) "sf-msix"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$layout = Join-Path $work "layout"
New-Item -ItemType Directory -Force (Join-Path $layout "Assets") | Out-Null
Copy-Item $exe $layout
Copy-Item (Join-Path $release "companion") $layout -Recurse
Copy-Item (Join-Path $release "drivers") $layout -Recurse

# Tile and Store images: Tauri already made the square ones; the wide tile is drawn here.
$icons = Join-Path $root "icons"
foreach ($n in "StoreLogo", "Square44x44Logo", "Square150x150Logo", "Square310x310Logo") {
    Copy-Item (Join-Path $icons "$n.png") (Join-Path $layout "Assets\$n.png")
}
Add-Type -AssemblyName System.Drawing
$wide = New-Object System.Drawing.Bitmap 310, 150
$g = [System.Drawing.Graphics]::FromImage($wide)
$g.Clear([System.Drawing.Color]::FromArgb(255, 8, 11, 18))
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$icon = [System.Drawing.Image]::FromFile((Join-Path $icons "icon.png"))
$g.DrawImage($icon, 95, 15, 120, 120)
$wide.Save((Join-Path $layout "Assets\Wide310x150Logo.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $wide.Dispose(); $icon.Dispose()

$esc = [Security.SecurityElement]
$manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:desktop2="http://schemas.microsoft.com/appx/manifest/desktop/windows10/2"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap desktop2 rescap">
  <Identity Name="$($esc::Escape($IdentityName))" Publisher="$($esc::Escape($Publisher))" Version="$version" ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>SwitchFlow</DisplayName>
    <PublisherDisplayName>$($esc::Escape($PublisherDisplayName))</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
    <Description>Перенос фото, видео, контактов и календарей между Android и iPhone — локально и с шифрованием.</Description>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>
  <Resources>
    <Resource Language="ru-RU" />
    <Resource Language="en-US" />
  </Resources>
  <Applications>
    <Application Id="SwitchFlow" Executable="switchflow.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="SwitchFlow" Description="Перенос данных между телефонами" BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png" Square44x44Logo="Assets\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" Square310x310Logo="Assets\Square310x310Logo.png" />
      </uap:VisualElements>
    </Application>
  </Applications>
  <Extensions>
    <!-- Phones reach the PC on the home network only: the server (47820), the cable bridge
         (47821/47822, loopback) and the app-file page for phones without SwitchFlow (47823). -->
    <desktop2:Extension Category="windows.firewallRules">
      <desktop2:FirewallRules Executable="switchflow.exe">
        <desktop2:Rule Direction="in" IPProtocol="TCP" LocalPortMin="47820" LocalPortMax="47823" Profile="private" />
      </desktop2:FirewallRules>
    </desktop2:Extension>
  </Extensions>
  <Capabilities>
    <Capability Name="internetClient" />
    <Capability Name="privateNetworkClientServer" />
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
"@
[IO.File]::WriteAllText((Join-Path $layout "AppxManifest.xml"), $manifest, (New-Object Text.UTF8Encoding $false))

# resources.pri: lets Windows pick the images; the Store's checks expect it.
Push-Location $work
& $makepri createconfig /cf priconfig.xml /dq ru-RU /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri createconfig failed" }
& $makepri new /pr $layout /cf priconfig.xml /of (Join-Path $layout "resources.pri") /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri new failed" }
Pop-Location

New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
& $makeappx pack /d $layout /p $Out /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makeappx pack failed (the manifest is checked here)" }
Write-Host ("MSIX for the Microsoft Store: {0} ({1:N0} bytes), identity {2}, version {3}" -f $Out, (Get-Item $Out).Length, $IdentityName, $version)
