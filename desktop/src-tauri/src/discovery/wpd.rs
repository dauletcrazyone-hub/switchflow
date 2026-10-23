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

/// Why copying the app to a phone failed (shown to the user as a hint).
#[derive(Debug, PartialEq, Eq)]
pub enum PushError {
    /// The phone is no longer in the WPD list (unplugged, or not in "File transfer" mode).
    NotFound,
    /// No storage or no Download folder is visible: Android hides them until the phone is unlocked.
    Locked,
    Failed,
}

/// Copies `data` as `file_name` into the phone's Download folder over MTP ("File transfer"):
/// no USB debugging, no app on the phone needed. `device` is the id the USB panel shows.
/// A file of the same name in that folder (an earlier copy) is replaced.
pub fn push_to_downloads(device: &str, file_name: &str, data: &[u8]) -> Result<(), PushError> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let mgr: IPortableDeviceManager = CoCreateInstance(&PortableDeviceManager, None, CLSCTX_INPROC_SERVER).map_err(|_| PushError::Failed)?;
        let wpd_id = device_ids(&mgr)
            .into_iter()
            .find(|id| format!("wpd:{}", &crate::checksum::sha256_bytes(id.as_bytes())[..16]) == device)
            .ok_or(PushError::NotFound)?;
        let wide: Vec<u16> = wpd_id.encode_utf16().chain(std::iter::once(0)).collect();
        let client: IPortableDeviceValues = CoCreateInstance(&PortableDeviceValues, None, CLSCTX_INPROC_SERVER).map_err(|_| PushError::Failed)?;
        client.SetStringValue(&WPD_CLIENT_NAME, w!("SwitchFlow")).map_err(|_| PushError::Failed)?;
        client.SetUnsignedIntegerValue(&WPD_CLIENT_MAJOR_VERSION, 0).map_err(|_| PushError::Failed)?;
        client.SetUnsignedIntegerValue(&WPD_CLIENT_MINOR_VERSION, 1).map_err(|_| PushError::Failed)?;
        client.SetUnsignedIntegerValue(&WPD_CLIENT_REVISION, 0).map_err(|_| PushError::Failed)?;
        // GENERIC_READ | GENERIC_WRITE: the one place SwitchFlow writes to a phone over MTP.
        client.SetUnsignedIntegerValue(&WPD_CLIENT_DESIRED_ACCESS, 0xC000_0000).map_err(|_| PushError::Failed)?;
        let dev: IPortableDevice = CoCreateInstance(&PortableDeviceFTM, None, CLSCTX_INPROC_SERVER).map_err(|_| PushError::Failed)?;
        dev.Open(PCWSTR(wide.as_ptr()), &client).map_err(|_| PushError::Failed)?;
        let result = push(&dev, file_name, data);
        let _ = dev.Close();
        result
    }
}

unsafe fn children(content: &IPortableDeviceContent, parent: &str) -> Vec<String> {
    let wide: Vec<u16> = parent.encode_utf16().chain(std::iter::once(0)).collect();
    let Ok(e) = content.EnumObjects(0, PCWSTR(wide.as_ptr()), None) else { return vec![] };
    let mut out = Vec::new();
    loop {
        let mut batch = [PWSTR::null(); 32];
        let mut fetched = 0u32;
        let hr = e.Next(&mut batch, &mut fetched);
        out.extend(batch.iter().take(fetched as usize).map(|p| pwstr_to_string(*p)));
        if hr.is_err() || fetched == 0 {
            break;
        }
    }
    out
}

unsafe fn object_name(props: &IPortableDeviceProperties, id: &str) -> String {
    let wide: Vec<u16> = id.encode_utf16().chain(std::iter::once(0)).collect();
    let Ok(keys) = CoCreateInstance::<_, IPortableDeviceKeyCollection>(&PortableDeviceKeyCollection, None, CLSCTX_INPROC_SERVER) else { return String::new() };
    let _ = keys.Add(&WPD_OBJECT_ORIGINAL_FILE_NAME);
    let _ = keys.Add(&WPD_OBJECT_NAME);
    let Ok(values) = props.GetValues(PCWSTR(wide.as_ptr()), &keys) else { return String::new() };
    values
        .GetStringValue(&WPD_OBJECT_ORIGINAL_FILE_NAME)
        .or_else(|_| values.GetStringValue(&WPD_OBJECT_NAME))
        .map(|p| pwstr_to_string(p))
        .unwrap_or_default()
}

unsafe fn push(dev: &IPortableDevice, file_name: &str, data: &[u8]) -> Result<(), PushError> {
    let content = dev.Content().map_err(|_| PushError::Failed)?;
    let props = content.Properties().map_err(|_| PushError::Failed)?;
    // Storages ("Internal storage", an SD card) are children of the device object; the Download
    // folder sits in the first one that has it.
    let download = children(&content, "DEVICE")
        .into_iter()
        .find_map(|storage| children(&content, &storage).into_iter().find(|o| object_name(&props, o).eq_ignore_ascii_case("Download")))
        .ok_or(PushError::Locked)?;

    // Replace an earlier copy instead of getting "SwitchFlow (1).apk".
    let old: Vec<String> = children(&content, &download).into_iter().filter(|o| object_name(&props, o).eq_ignore_ascii_case(file_name)).collect();
    if !old.is_empty() {
        let ids: IPortableDevicePropVariantCollection = CoCreateInstance(&PortableDevicePropVariantCollection, None, CLSCTX_INPROC_SERVER).map_err(|_| PushError::Failed)?;
        for o in &old {
            use windows::Win32::System::Com::StructuredStorage::{PROPVARIANT, PROPVARIANT_0, PROPVARIANT_0_0, PROPVARIANT_0_0_0};
            // A VT_LPWSTR pointing at our own buffer: Add() makes its own copy, and PROPVARIANT
            // here has no Drop, so the buffer is never freed by COM.
            let mut wide: Vec<u16> = o.encode_utf16().chain(std::iter::once(0)).collect();
            let v = PROPVARIANT {
                Anonymous: PROPVARIANT_0 {
                    Anonymous: std::mem::ManuallyDrop::new(PROPVARIANT_0_0 {
                        vt: windows::Win32::System::Variant::VT_LPWSTR,
                        wReserved1: 0,
                        wReserved2: 0,
                        wReserved3: 0,
                        Anonymous: PROPVARIANT_0_0_0 { pwszVal: PWSTR(wide.as_mut_ptr()) },
                    }),
                },
            };
            let _ = ids.Add(&v);
        }
        let _ = content.Delete(PORTABLE_DEVICE_DELETE_NO_RECURSION.0 as u32, &ids, std::ptr::null_mut());
    }

    let values: IPortableDeviceValues = CoCreateInstance(&PortableDeviceValues, None, CLSCTX_INPROC_SERVER).map_err(|_| PushError::Failed)?;
    let parent: Vec<u16> = download.encode_utf16().chain(std::iter::once(0)).collect();
    let name: Vec<u16> = file_name.encode_utf16().chain(std::iter::once(0)).collect();
    values.SetStringValue(&WPD_OBJECT_PARENT_ID, PCWSTR(parent.as_ptr())).map_err(|_| PushError::Failed)?;
    values.SetStringValue(&WPD_OBJECT_ORIGINAL_FILE_NAME, PCWSTR(name.as_ptr())).map_err(|_| PushError::Failed)?;
    values.SetStringValue(&WPD_OBJECT_NAME, PCWSTR(name.as_ptr())).map_err(|_| PushError::Failed)?;
    values.SetUnsignedLargeIntegerValue(&WPD_OBJECT_SIZE, data.len() as u64).map_err(|_| PushError::Failed)?;
    values.SetGuidValue(&WPD_OBJECT_CONTENT_TYPE, &WPD_CONTENT_TYPE_GENERIC_FILE).map_err(|_| PushError::Failed)?;
    values.SetGuidValue(&WPD_OBJECT_FORMAT, &WPD_OBJECT_FORMAT_UNSPECIFIED).map_err(|_| PushError::Failed)?;

    let mut stream: Option<windows::Win32::System::Com::IStream> = None;
    let mut chunk = 0u32;
    content
        .CreateObjectWithPropertiesAndData(&values, &mut stream, &mut chunk, std::ptr::null_mut())
        .map_err(|_| PushError::Failed)?;
    let stream = stream.ok_or(PushError::Failed)?;
    let chunk = (chunk as usize).clamp(16 * 1024, 1024 * 1024);
    for part in data.chunks(chunk) {
        let mut written = 0u32;
        stream.Write(part.as_ptr() as *const _, part.len() as u32, Some(&mut written)).ok().map_err(|_| PushError::Failed)?;
        if written as usize != part.len() {
            return Err(PushError::Failed);
        }
    }
    stream.Commit(windows::Win32::System::Com::STGC_DEFAULT).map_err(|_| PushError::Failed)
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
