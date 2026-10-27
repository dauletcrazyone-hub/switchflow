# Downloads the UsbDk driver package that the SwitchFlow installer carries (cable transfer without
# USB debugging needs it: see src/cable.rs). Official release by Red Hat / Daynix, Apache-2.0.
# Run once before `npm run tauri:build`; the build fails without the file.
$ErrorActionPreference = "Stop"
# Started from PowerShell 7, Windows PowerShell inherits its module path and cannot load its own
# Get-FileHash / Get-AuthenticodeSignature. Use this PowerShell's modules.
$env:PSModulePath = "$PSHOME\Modules;" + [Environment]::GetEnvironmentVariable("PSModulePath", "Machine")
$url = "https://github.com/daynix/UsbDk/releases/download/v1.00-22/UsbDk_1.0.22_x64.msi"
$sha256 = "91f6f695e1e13c656024e6d3b55620bf08d8835ef05ee0496935ba6bb62466a5"
$out = Join-Path $PSScriptRoot "drivers\UsbDk_x64.msi"

if ((Test-Path $out) -and (Get-FileHash $out -Algorithm SHA256).Hash.ToLower() -eq $sha256) {
    Write-Host "UsbDk package already present"
    exit 0
}
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
$got = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
if ($got -ne $sha256) {
    Remove-Item $out
    throw "UsbDk package checksum mismatch: $got"
}
$sig = Get-AuthenticodeSignature $out
if ($sig.Status -ne "Valid" -or $sig.SignerCertificate.Subject -notmatch "Red Hat") {
    Remove-Item $out
    throw "UsbDk package is not signed by Red Hat"
}
Write-Host "UsbDk package downloaded and verified"
