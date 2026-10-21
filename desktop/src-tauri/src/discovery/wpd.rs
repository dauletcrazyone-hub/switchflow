//! Windows Portable Devices enumeration (COM, multithreaded apartment).
//!
//! Opening a device is slow (and can block for seconds on a locked iPhone), so each
//! device is opened once and its properties are cached until it disappears.

use crate::core::UsbDevice;
use std::collections::HashMap;
use windows::core::{w, PCWSTR, PWSTR};
use windows::Win32::Devices::PortableDevices::*;
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
};

#[derive(Clone)]
struct Cached {
    device: UsbDevice,
}

pub struct Watcher {
    manager: Option<IPortableDeviceManager>,
    cache: HashMap<String, Cached>,
}

impl Watcher {
    pub fn new() -> Self {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let manager = CoCreateInstance(&PortableDeviceManager, None, CLSCTX_INPROC_SERVER).ok();
            Watcher { manager, cache: HashMap::new() }
        }
    }

    pub fn poll(&mut self) -> Vec<UsbDevice> {
        let Some(mgr) = self.manager.clone() else { return vec![] };
        let ids = unsafe { device_ids(&mgr) };
        self.cache.retain(|id, _| ids.contains(id));
        let mut out = Vec::new();
        for id in ids {
            if let Some(c) = self.cache.get(&id) {
                out.push(c.device.clone());
                continue;
            }
            if let Some(dev) = unsafe { describe(&mgr, &id) } {
                self.cache.insert(id, Cached { device: dev.clone() });
                out.push(dev);
            }
        }
        out
    }
}

unsafe fn pwstr_to_string(p: PWSTR) -> String {
    if p.is_null() {
        return String::new();
    }
    let s = p.to_string().unwrap_or_default();
    CoTaskMemFree(Some(p.0 as *const _));
    s
}

unsafe fn device_ids(mgr: &IPortableDeviceManager) -> Vec<String> {
    let _ = mgr.RefreshDeviceList();
    let mut count = 0u32;
    if mgr.GetDevices(std::ptr::null_mut(), &mut count).is_err() || count == 0 {
        return vec![];
    }
    let mut raw = vec![PWSTR::null(); count as usize];
    if mgr.GetDevices(raw.as_mut_ptr(), &mut count).is_err() {
        return vec![];
    }
    raw.into_iter().take(count as usize).map(|p| pwstr_to_string(p)).collect()
}

/// Calls a "get string" WPD manager method using the two-call size protocol.
unsafe fn manager_string(f: impl Fn(PWSTR, *mut u32) -> windows::core::Result<()>) -> String {
    let mut len = 0u32;
    let _ = f(PWSTR::null(), &mut len);
    if len == 0 || len > 4096 {
        return String::new();
    }
    let mut buf = vec![0u16; len as usize];
    if f(PWSTR(buf.as_mut_ptr()), &mut len).is_err() {
        return String::new();
    }
    let end = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..end])
}

unsafe fn describe(mgr: &IPortableDeviceManager, id: &str) -> Option<UsbDevice> {
    let wide: Vec<u16> = id.encode_utf16().chain(std::iter::once(0)).collect();
    let pid = PCWSTR(wide.as_ptr());
    let friendly = manager_string(|b, l| mgr.GetDeviceFriendlyName(pid, b, l));
    let manufacturer = manager_string(|b, l| mgr.GetDeviceManufacturer(pid, b, l));
    let description = manager_string(|b, l| mgr.GetDeviceDescription(pid, b, l));

    let props = read_device_properties(pid);
    let (dev_type, model, battery) = props.unwrap_or((None, None, None));
    // Cameras and media players are WPD devices too; they are not phones.
    if matches!(dev_type, Some(t) if t == WPD_DEVICE_TYPE_CAMERA.0 as u32 || t == WPD_DEVICE_TYPE_MEDIA_PLAYER.0 as u32 || t == WPD_DEVICE_TYPE_AUDIO_RECORDER.0 as u32 || t == WPD_DEVICE_TYPE_VIDEO.0 as u32) {
        return None;
    }
    let platform = super::platform_from_manufacturer(&manufacturer);
    let model = model.filter(|m| !m.is_empty()).unwrap_or_else(|| {
        if !description.is_empty() {
            description.clone()
        } else {
            friendly.clone()
        }
    });
    Some(UsbDevice {
        id: format!("wpd:{}", crate::checksum::sha256_bytes(id.as_bytes())[..16].to_string()),
        source: "wpd".into(),
        platform,
        manufacturer: manufacturer.trim().to_string(),
        model: model.trim().to_string(),
        status: "ready".into(),
        battery_percent: battery.map(|b| b.min(100) as u8),
        serial: None,
    })
}

/// Opens the device read-only and reads type, model and battery level.
unsafe fn read_device_properties(pid: PCWSTR) -> Option<(Option<u32>, Option<String>, Option<u32>)> {
    let client: IPortableDeviceValues = CoCreateInstance(&PortableDeviceValues, None, CLSCTX_INPROC_SERVER).ok()?;
    client.SetStringValue(&WPD_CLIENT_NAME, w!("SwitchFlow")).ok()?;
    client.SetUnsignedIntegerValue(&WPD_CLIENT_MAJOR_VERSION, 0).ok()?;
    client.SetUnsignedIntegerValue(&WPD_CLIENT_MINOR_VERSION, 1).ok()?;
    client.SetUnsignedIntegerValue(&WPD_CLIENT_REVISION, 0).ok()?;
    // GENERIC_READ: discovery never writes to the phone.
    client.SetUnsignedIntegerValue(&WPD_CLIENT_DESIRED_ACCESS, 0x8000_0000).ok()?;

    let device: IPortableDevice = CoCreateInstance(&PortableDeviceFTM, None, CLSCTX_INPROC_SERVER).ok()?;
    device.Open(pid, &client).ok()?;
    let result = (|| {
        let content = device.Content().ok()?;
        let properties = content.Properties().ok()?;
        let keys: IPortableDeviceKeyCollection = CoCreateInstance(&PortableDeviceKeyCollection, None, CLSCTX_INPROC_SERVER).ok()?;
        keys.Add(&WPD_DEVICE_TYPE).ok()?;
        keys.Add(&WPD_DEVICE_MODEL).ok()?;
        keys.Add(&WPD_DEVICE_POWER_LEVEL).ok()?;
        let values = properties.GetValues(w!("DEVICE"), &keys).ok()?;
        let dev_type = values.GetUnsignedIntegerValue(&WPD_DEVICE_TYPE).ok();
        let model = values.GetStringValue(&WPD_DEVICE_MODEL).ok().map(|p| pwstr_to_string(p));
        let battery = values.GetUnsignedIntegerValue(&WPD_DEVICE_POWER_LEVEL).ok();
        Some((dev_type, model, battery))
    })();
    let _ = device.Close();
    result
}
