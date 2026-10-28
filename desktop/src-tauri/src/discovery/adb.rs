//! Android Debug Bridge, used only with the user's explicit opt-in (Settings → ADB).
//! SwitchFlow uses it for two things: listing phones and `adb reverse`, which lets the
//! companion app reach this PC through the USB cable. It never reads data through ADB.

use crate::core::UsbDevice;
use crate::model::Platform;
use std::path::{Path, PathBuf};
use std::process::Command;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub fn find(custom: Option<&Path>) -> Option<PathBuf> {
    if let Some(p) = custom.filter(|p| p.is_file()) {
        return Some(p.to_path_buf());
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        candidates.extend(std::env::split_paths(&path).map(|d| d.join("adb.exe")));
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        candidates.push(PathBuf::from(local).join("Android/Sdk/platform-tools/adb.exe"));
    }
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(sdk) = std::env::var_os(var) {
            candidates.push(PathBuf::from(sdk).join("platform-tools/adb.exe"));
        }
    }
    candidates.push(PathBuf::from(r"C:\adb\adb.exe"));
    candidates.push(PathBuf::from(r"C:\platform-tools\adb.exe"));
    candidates.into_iter().find(|p| p.is_file())
}

fn run(adb: &Path, args: &[&str]) -> Option<String> {
    let mut cmd = Command::new(adb);
    cmd.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let out = cmd.output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

pub fn devices(adb: &Path) -> Vec<UsbDevice> {
    run(adb, &["devices", "-l"]).map(|s| parse_devices(&s)).unwrap_or_default()
}

pub fn parse_devices(output: &str) -> Vec<UsbDevice> {
    output
        .lines()
        .skip_while(|l| !l.starts_with("List of devices"))
        .skip(1)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            let mut model = String::new();
            for p in parts {
                if let Some(m) = p.strip_prefix("model:") {
                    model = m.replace('_', " ");
                }
            }
            let status = match state {
                "device" => "ready",
                "unauthorized" => "unauthorized",
                _ => "offline",
            };
            Some(UsbDevice {
                id: format!("adb:{serial}"),
                source: "adb".into(),
                platform: Some(Platform::Android),
                manufacturer: String::new(),
                model,
                status: status.into(),
                battery_percent: None,
                serial: Some(serial.to_string()),
            })
        })
        .collect()
}

/// `adb -s <serial> reverse tcp:<port> tcp:<port>`: the phone's 127.0.0.1:<port> now
/// reaches this PC over the cable.
fn valid_serial(serial: &str) -> bool {
    !serial.is_empty() && serial.chars().all(|c| c.is_ascii_alphanumeric() || "._:-".contains(c))
}

/// Installs (or updates) the app with every runtime permission granted up front (`-g`), then opens it.
/// Only possible because the user turned on USB debugging and allowed this PC on the phone.
pub fn install(adb: &Path, serial: &str, apk: &Path) -> bool {
    if !valid_serial(serial) {
        return false;
    }
    let Some(apk) = apk.to_str() else { return false };
    let ok = run(adb, &["-s", serial, "install", "-r", "-g", apk]).is_some_and(|out| out.contains("Success"));
    if ok {
        let _ = run(adb, &["-s", serial, "shell", "am", "start", "-n", "app.switchflow.companion/.MainActivity"]);
    }
    ok
}

pub fn reverse(adb: &Path, serial: &str, port: u16) -> bool {
    if !valid_serial(serial) {
        return false;
    }
    let spec = format!("tcp:{port}");
    run(adb, &["-s", serial, "reverse", &spec, &spec]).is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_adb_output() {
        let out = "* daemon started successfully\nList of devices attached\n\
                   1A2B3C  device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:2\n\
                   ZX99  unauthorized usb:1-2 transport_id:3\n\n";
        let d = parse_devices(out);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0].model, "Pixel 8");
        assert_eq!(d[0].status, "ready");
        assert_eq!(d[1].status, "unauthorized");
        assert_eq!(d[1].serial.as_deref(), Some("ZX99"));
    }
}
