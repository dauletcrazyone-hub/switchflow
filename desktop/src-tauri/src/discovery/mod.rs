//! DeviceDiscovery: phones physically connected over USB.
//!
//! Sources, all documented Windows mechanisms:
//! - WPD (Windows Portable Devices): Android in "File transfer" mode, iPhone with Apple's driver.
//! - SetupAPI: an Apple USB device that has no WPD driver ("install Apple Devices").
//! - ADB (only if enabled in Settings): Android with USB debugging, used for `adb reverse`.
//!
//! Discovery never reports a phone that is not really there.

pub mod adb;
#[cfg(windows)]
pub mod setupapi;
#[cfg(windows)]
pub mod wpd;

use crate::cable::Cable;
use crate::core::{Core, UsbDevice};
use crate::model::Platform;
use std::sync::Arc;
use std::time::Duration;

const POLL: Duration = Duration::from_secs(3);

pub fn spawn(core: Arc<Core>, cable: Arc<Cable>) {
    std::thread::Builder::new()
        .name("sf-discovery".into())
        .spawn(move || run(core, cable))
        .expect("spawn discovery thread");
}

fn run(core: Arc<Core>, cable: Arc<Cable>) {
    #[cfg(windows)]
    let mut wpd = wpd::Watcher::new();
    // "File transfer" interfaces seen without a driver at the previous poll.
    #[cfg(windows)]
    let mut mtp_seen: Vec<String> = Vec::new();
    loop {
        let mut list: Vec<UsbDevice> = Vec::new();
        #[cfg(windows)]
        {
            list.extend(wpd.poll());
            let has_wpd_apple = list.iter().any(|d| d.platform == Some(Platform::Ios));
            if !has_wpd_apple {
                list.extend(setupapi::apple_without_driver());
            }
            // Still without a driver one poll later: not an install in progress but a Windows that
            // cannot install it. Shown so the user is told why no install button appears.
            let mtp_now = setupapi::mtp_without_driver();
            if mtp_now.iter().any(|id| mtp_seen.contains(id)) {
                list.push(UsbDevice {
                    id: "usb:mtp-driver".into(),
                    source: "usb".into(),
                    platform: Some(Platform::Android),
                    manufacturer: String::new(),
                    model: String::new(),
                    status: "mtp_driver_missing".into(),
                    battery_percent: None,
                    serial: None,
                });
            }
            mtp_seen = mtp_now;
        }
        let settings = core.settings();
        if settings.use_adb {
            if let Some(adb) = adb::find(settings.adb_path.as_deref()) {
                list.extend(adb::devices(&adb));
            }
        }
        list.sort_by(|a, b| a.id.cmp(&b.id));
        core.set_usb_devices(list);
        // Phones already in accessory mode (switched earlier, or the app was restarted) get a link.
        cable.poll(&core);
        core.set_cable(cable.available(), cable.links(), cable.phones());
        std::thread::sleep(POLL);
    }
}

/// Classifies a WPD/USB device by its manufacturer string.
pub fn platform_from_manufacturer(manufacturer: &str) -> Option<Platform> {
    let m = manufacturer.to_ascii_lowercase();
    if m.contains("apple") {
        Some(Platform::Ios)
    } else if m.is_empty() {
        None
    } else {
        Some(Platform::Android)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies() {
        assert_eq!(platform_from_manufacturer("Apple Inc."), Some(Platform::Ios));
        assert_eq!(platform_from_manufacturer("Google"), Some(Platform::Android));
        assert_eq!(platform_from_manufacturer(""), None);
    }
}
