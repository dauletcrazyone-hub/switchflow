# Puts the SwitchFlow Android app into the PC installer: the PC copies it to a phone over the cable
# ("Install SwitchFlow on the phone", src/commands.rs install_companion). Only a release APK signed
# with the SwitchFlow release key is accepted — never the debug build (it has test-only hooks).
# Build it first (docs/BUILD_ANDROID.md):  . $env:USERPROFILE\.switchflow\release-signing.ps1; ./gradlew assembleRelease
$ErrorActionPreference = "Stop"
# Started from PowerShell 7, Windows PowerShell inherits its module path and cannot load its own
# Get-FileHash / Get-AuthenticodeSignature. Use this PowerShell's modules.
$env:PSModulePath = "$PSHOME\Modules;" + [Environment]::GetEnvironmentVariable("PSModulePath", "Machine")
$apk = Join-Path $PSScriptRoot "..\..\..\android\app\build\outputs\apk\release\app-release.apk"
$out = Join-Path $PSScriptRoot "companion\SwitchFlow.apk"
if (-not (Test-Path $apk)) {
    throw "No release APK at $apk. Build it: cd android; . `$env:USERPROFILE\.switchflow\release-signing.ps1; ./gradlew assembleRelease"
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { (Get-Content (Join-Path $PSScriptRoot "..\..\..\android\local.properties") | Where-Object { $_ -match "^sdk.dir=" }) -replace "^sdk.dir=", "" }
$apksigner = Get-ChildItem (Join-Path $sdk "build-tools\*\apksigner.bat") | Sort-Object FullName | Select-Object -Last 1
# apksigner runs on Java: use JAVA_HOME, java on PATH, Android Studio's runtime or a local JDK.
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:USERPROFILE\devtools\jdk\jdk-*") |
        ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jdk) { throw "Java not found: set JAVA_HOME to a JDK 17." }
    $env:JAVA_HOME = $jdk.FullName
}
$certs = & $apksigner.FullName verify --print-certs $apk 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "The release APK does not verify: $certs" }
if ($certs -match "CN=Android Debug") { throw "The APK is signed with the debug key; build it with the SwitchFlow release key." }
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Copy-Item $apk $out -Force
Write-Host ("Android app staged for the installer ({0:N0} bytes)" -f (Get-Item $out).Length)
