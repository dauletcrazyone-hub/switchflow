//! Finds Apple mobile devices on USB through SetupAPI. Used only when WPD sees no
//! iPhone, to tell the user that Apple's driver is missing instead of staying silent.

use crate::core::UsbDevice;
use crate::model::Platform;
use windows::core::w;
use windows::Win32::Devices::DeviceAndDriverInstallation::{
    SetupDiDestroyDeviceInfoList, SetupDiEnumDeviceInfo, SetupDiGetClassDevsW, SetupDiGetDeviceRegistryPropertyW,
    DIGCF_ALLCLASSES, DIGCF_PRESENT, SPDRP_HARDWAREID, SPDRP_SERVICE, SP_DEVINFO_DATA,
};

pub fn apple_without_driver() -> Vec<UsbDevice> {
    let mut out = Vec::new();
    unsafe {
        let Ok(set) = SetupDiGetClassDevsW(None, w!("USB"), None, DIGCF_PRESENT | DIGCF_ALLCLASSES) else {
            return out;
        };
        let mut index = 0u32;
        loop {
            let mut data = SP_DEVINFO_DATA { cbSize: std::mem::size_of::<SP_DEVINFO_DATA>() as u32, ..Default::default() };
            if SetupDiEnumDeviceInfo(set, index, &mut data).is_err() {
                break;
            }
            index += 1;
            let mut buf = [0u8; 2048];
            let mut needed = 0u32;
            if SetupDiGetDeviceRegistryPropertyW(set, &data, SPDRP_HARDWAREID, None, Some(&mut buf), Some(&mut needed)).is_err() {
                continue;
            }
            let wide: Vec<u16> = buf.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
            let ids = String::from_utf16_lossy(&wide).to_ascii_uppercase();
            // Apple VID 0x05AC, PIDs 0x12xx are iPhone/iPad/iPod. Only the composite parent (no &MI_).
            if ids.contains("VID_05AC&PID_12") && !ids.contains("&MI_") {
                // A bound driver service means Apple's driver is there and the phone is
                // most likely locked or not yet trusted; no service means no driver.
                let mut svc = [0u8; 256];
                let has_driver = SetupDiGetDeviceRegistryPropertyW(set, &data, SPDRP_SERVICE, None, Some(&mut svc), None).is_ok()
                    && svc.iter().any(|b| *b != 0);
                out.push(UsbDevice {
                    id: format!("usb:apple:{index}"),
                    source: "usb".into(),
                    platform: Some(Platform::Ios),
                    manufacturer: "Apple".into(),
                    model: "iPhone".into(),
                    status: if has_driver { "locked" } else { "driver_missing" }.into(),
                    battery_percent: None,
                    serial: None,
                });
                break;
            }
        }
        let _ = SetupDiDestroyDeviceInfoList(set);
    }
    out
}
