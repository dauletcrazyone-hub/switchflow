//! Cable link: Android phones over USB without USB debugging (Android Open Accessory 1.0).
//!
//! The PC asks a phone to switch into accessory mode; the phone re-appears as 18D1:2D00/2D01
//! with two bulk endpoints, and the SwitchFlow app on it opens the accessory. Over those
//! endpoints both sides exchange frames that carry ordinary TCP streams: the phone listens on
//! 127.0.0.1:47821, and each connection the app makes there becomes a stream that the PC hands
//! to its own SwitchFlow server. The HTTPS protocol, certificate pinning and every check stay
//! exactly as over Wi-Fi; the cable only replaces the network.
//!
//! The first frame from the PC (HELLO) carries what the QR code carries over Wi-Fi: the pairing
//! code with the certificate fingerprint and a one-time secret. The cable itself proves the phone
//! is physically at this PC, so no camera is needed.
//!
//! Windows has no driver for accessory mode and keeps the phone's normal interfaces for MTP, so
//! USB access goes through libusb's UsbDk backend (the installer adds UsbDk).

use crate::core::Core;
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

// ------------------------------------------------------------------ frames

pub const HEADER: usize = 9;
/// Android reads the accessory in 16 KiB chunks; a frame never exceeds one read.
pub const MAX_TRANSFER: usize = 16_384;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    /// PC → phone, stream 0: JSON `{ "v": 1, "uri": "switchflow://pair?..." }`.
    Hello = 1,
    /// phone → PC: a new connection on the phone's local port.
    Open = 2,
    Data = 3,
    /// Either side: the stream's connection closed.
    Close = 4,
    /// phone → PC, stream 0: the app opened the accessory and is reading.
    Ready = 5,
}

impl Kind {
    fn from_u8(b: u8) -> Option<Kind> {
        Some(match b {
            1 => Kind::Hello,
            2 => Kind::Open,
            3 => Kind::Data,
            4 => Kind::Close,
            5 => Kind::Ready,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: Kind,
    pub stream: u32,
    pub payload: Vec<u8>,
}

/// A USB bulk transfer whose length is a multiple of the packet size has no short packet at the
/// end, and the phone keeps waiting for more. 64 divides every packet size (64/512/1024).
fn needs_shortening(frame_len: usize) -> bool {
    frame_len % 64 == 0
}

pub fn encode(kind: Kind, stream: u32, payload: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(HEADER + payload.len());
    f.push(kind as u8);
    f.extend_from_slice(&stream.to_be_bytes());
    f.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    f.extend_from_slice(payload);
    f
}

/// Splits stream data into DATA frames that each fit one transfer and never end on a packet boundary.
pub fn data_frames(stream: u32, mut data: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    while !data.is_empty() {
        let mut n = data.len().min(MAX_TRANSFER - HEADER);
        if needs_shortening(HEADER + n) {
            n -= 1;
        }
        out.push(encode(Kind::Data, stream, &data[..n]));
        data = &data[n..];
    }
    out
}

/// A control frame (HELLO, OPEN, CLOSE): padded JSON whitespace keeps HELLO off packet boundaries.
fn control_frame(kind: Kind, stream: u32, payload: &[u8]) -> Vec<u8> {
    if needs_shortening(HEADER + payload.len()) {
        let mut p = payload.to_vec();
        p.push(b' ');
        return encode(kind, stream, &p);
    }
    encode(kind, stream, payload)
}

/// Reassembles frames from USB reads, which may split or join frames arbitrarily.
#[derive(Default)]
pub struct Decoder {
    buf: Vec<u8>,
}

impl Decoder {
    pub fn push(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
    }

    /// Next complete frame; `Err` means the stream is corrupt and the link must be dropped.
    pub fn next(&mut self) -> Result<Option<Frame>, &'static str> {
        if self.buf.len() < HEADER {
            return Ok(None);
        }
        let kind = Kind::from_u8(self.buf[0]).ok_or("unknown frame kind")?;
        let stream = u32::from_be_bytes(self.buf[1..5].try_into().expect("4 bytes"));
        let len = u32::from_be_bytes(self.buf[5..9].try_into().expect("4 bytes")) as usize;
        if len > MAX_TRANSFER {
            return Err("frame too large");
        }
        if self.buf.len() < HEADER + len {
            return Ok(None);
        }
        let payload = self.buf[HEADER..HEADER + len].to_vec();
        self.buf.drain(..HEADER + len);
        Ok(Some(Frame { kind, stream, payload }))
    }
}

// --------------------------------------------------------------- USB side

/// Strings the phone matches against the app's accessory filter (res/xml/accessory_filter.xml).
const ACCESSORY_MANUFACTURER: &str = "SwitchFlow";
const ACCESSORY_MODEL: &str = "SwitchFlow Cable";
const ACCESSORY_DESCRIPTION: &str = "SwitchFlow: перенос данных по кабелю";
const ACCESSORY_VERSION: &str = "1";

const GOOGLE_VID: u16 = 0x18D1;
/// How long an accessory-mode device must be on the bus before the link opens it.
const SETTLE: Duration = Duration::from_secs(2);
/// Accessory, accessory+ADB, and the audio variants.
const ACCESSORY_PIDS: [u16; 6] = [0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05];
/// Phone makers (USB vendor ids). Opening a device through UsbDk briefly takes it away from its
/// Windows driver, so only phones are ever touched, never keyboards, headsets or disks.
const ANDROID_VIDS: [u16; 22] = [
    0x04E8, // Samsung
    0x18D1, // Google
    0x2717, // Xiaomi
    0x12D1, // Huawei
    0x339B, // Honor
    0x22B8, // Motorola
    0x1004, // LG
    0x0FCE, // Sony
    0x2A70, // OnePlus
    0x22D9, // OPPO / realme
    0x2D95, // vivo
    0x19D2, // ZTE
    0x17EF, // Lenovo
    0x0BB4, // HTC
    0x2E04, // HMD / Nokia
    0x0B05, // ASUS
    0x2A45, // Meizu
    0x1EBF, // Coolpad
    0x29A9, // Smartisan
    0x2B4C, // Nothing
    0x1782, // Spreadtrum / Unisoc phones
    0x0E8D, // MediaTek phones (also Tecno, Infinix, itel)
];

/// Whether a USB vendor id belongs to an Android phone maker.
pub fn is_phone_vendor(vid: u16) -> bool {
    ANDROID_VIDS.contains(&vid)
}

fn is_accessory(vid: u16, pid: u16) -> bool {
    vid == GOOGLE_VID && ACCESSORY_PIDS.contains(&pid)
}

/// Shared libusb state; `None` when UsbDk is not installed (then the cable option is hidden).
pub struct Cable {
    ctx: Option<rusb::Context>,
    /// USB (bus, address) of accessories with a running link.
    linked: Mutex<Vec<(u8, u8)>>,
    /// Android phones seen on USB in normal mode at the last poll.
    phones: AtomicUsize,
    /// When each accessory-mode device first appeared, to let Windows finish starting it first.
    first_seen: Mutex<HashMap<(u8, u8), std::time::Instant>>,
    /// iPhones (usbmuxd device ids) with a running link.
    linked_iphones: Mutex<Vec<u64>>,
    /// Keeps accessory-mode phones hidden from Windows drivers while SwitchFlow runs.
    #[cfg(windows)]
    _hider: Option<hider::Hider>,
}

impl Cable {
    pub fn new() -> Arc<Cable> {
        let ctx = open_context();
        if ctx.is_none() {
            tracing::info!("cable transfer unavailable: no UsbDk");
        }
        #[cfg(windows)]
        let _hider = ctx.as_ref().and_then(|_| hider::Hider::accessories());
        Arc::new(Cable {
            ctx,
            linked: Mutex::new(Vec::new()),
            phones: AtomicUsize::new(0),
            first_seen: Mutex::default(),
            linked_iphones: Mutex::default(),
            #[cfg(windows)]
            _hider,
        })
    }

    pub fn available(&self) -> bool {
        self.ctx.is_some()
    }

    /// Accessory-mode phones are hidden from Windows drivers (UsbDk hide rules are active).
    pub fn hiding(&self) -> bool {
        #[cfg(windows)]
        return self._hider.is_some();
        #[cfg(not(windows))]
        false
    }

    /// Phones that "Connect by cable" would switch (descriptors only: nothing is opened).
    pub fn phones(&self) -> usize {
        self.phones.load(Ordering::Relaxed)
    }

    pub fn links(&self) -> usize {
        self.linked.lock().unwrap_or_else(|p| p.into_inner()).len() + self.linked_iphones.lock().unwrap_or_else(|p| p.into_inner()).len()
    }

    /// Links every connected iPhone whose SwitchFlow app is open (it listens while open).
    /// Needs only usbmuxd (Apple Mobile Device Service), not UsbDk; "Trust this computer" suffices.
    fn poll_iphones(self: &Arc<Self>, core: &Arc<Core>) {
        let Ok(devices) = crate::usbmux::list_devices() else { return };
        for d in devices {
            if self.linked_iphones.lock().unwrap_or_else(|p| p.into_inner()).contains(&d.id) {
                continue;
            }
            // Refused = the app is closed; asked again at the next poll.
            let Ok(stream) = crate::usbmux::connect(d.id, crate::usbmux::DEVICE_PORT) else { continue };
            let Ok(transport) = TcpTransport::new(stream) else { continue };
            self.linked_iphones.lock().unwrap_or_else(|p| p.into_inner()).push(d.id);
            let (me, core, id) = (self.clone(), core.clone(), d.id);
            let spawned = std::thread::Builder::new().name("sf-cable-iphone".into()).spawn(move || {
                tracing::info!("iPhone cable link open");
                run_link(Arc::new(transport), &core);
                tracing::info!("iPhone cable link closed");
                me.linked_iphones.lock().unwrap_or_else(|p| p.into_inner()).retain(|x| *x != id);
                core.notify_ui();
            });
            if spawned.is_err() {
                self.linked_iphones.lock().unwrap_or_else(|p| p.into_inner()).retain(|x| *x != id);
            }
        }
    }

    /// Asks every connected Android phone to switch into accessory mode. Returns how many agreed.
    /// The phone then shows "Open SwitchFlow?"; its MTP file access pauses until it is unplugged.
    pub fn switch_phones(&self) -> usize {
        use rusb::UsbContext;
        let Some(ctx) = &self.ctx else { return 0 };
        let Ok(list) = ctx.devices() else { return 0 };
        let mut switched = 0;
        for dev in list.iter() {
            let Ok(desc) = dev.device_descriptor() else { continue };
            if is_accessory(desc.vendor_id(), desc.product_id()) || !ANDROID_VIDS.contains(&desc.vendor_id()) {
                continue;
            }
            if !has_phone_interface(&dev) {
                continue;
            }
            #[cfg(windows)]
            {
                let keys: Vec<String> = usbflags::accessory_keys(usbflags::bcd(desc.device_version()))
                    .into_iter()
                    .filter(|k| usbflags::needs_fix(k))
                    .collect();
                if !keys.is_empty() {
                    let ok = usbflags::turn_off_os_descriptors(&keys);
                    tracing::info!(ok, "accessory ids: Microsoft OS descriptor query turned off");
                }
            }
            match switch_to_accessory(&dev) {
                Ok(proto) => {
                    tracing::info!(protocol = proto, "phone switched to accessory mode");
                    switched += 1;
                }
                Err(e) => tracing::info!(error = %e, "phone did not switch to accessory mode"),
            }
        }
        switched
    }

    /// Starts a link for every accessory-mode phone that has none yet. Called from discovery.
    pub fn poll(self: &Arc<Self>, core: &Arc<Core>) {
        use rusb::UsbContext;
        self.poll_iphones(core);
        let Some(ctx) = &self.ctx else { return };
        let Ok(list) = ctx.devices() else { return };
        let mut phones = 0;
        let mut present = Vec::new();
        for dev in list.iter() {
            let Ok(desc) = dev.device_descriptor() else { continue };
            if !is_accessory(desc.vendor_id(), desc.product_id()) {
                if ANDROID_VIDS.contains(&desc.vendor_id()) && has_phone_interface(&dev) {
                    phones += 1;
                }
                continue;
            }
            let key = (dev.bus_number(), dev.address());
            present.push(key);
            // Opening through UsbDk takes the device away from Windows. Doing that while Windows is
            // still starting it (right after the switch) fails the start with code 10, and the phone
            // stays unusable until the cable is replugged (seen on a Galaxy S22).
            let settled = {
                let mut seen = self.first_seen.lock().unwrap_or_else(|p| p.into_inner());
                seen.entry(key).or_insert_with(std::time::Instant::now).elapsed() >= SETTLE
            };
            if !settled {
                continue;
            }
            {
                let mut linked = self.linked.lock().unwrap_or_else(|p| p.into_inner());
                if linked.contains(&key) {
                    continue;
                }
                linked.push(key);
            }
            let (me, core) = (self.clone(), core.clone());
            let spawned = std::thread::Builder::new().name("sf-cable".into()).spawn(move || {
                match open_link(&dev) {
                    Ok((h, ep_in, ep_out)) => {
                        tracing::info!("cable link open");
                        run_link(Arc::new(UsbTransport { h, ep_in, ep_out }), &core);
                        tracing::info!("cable link closed");
                    }
                    Err(e) => tracing::warn!(error = %e, "cable link failed to open"),
                }
                me.linked.lock().unwrap_or_else(|p| p.into_inner()).retain(|k| *k != key);
                core.notify_ui();
            });
            if spawned.is_err() {
                self.linked.lock().unwrap_or_else(|p| p.into_inner()).retain(|k| *k != key);
            }
        }
        self.phones.store(phones, Ordering::Relaxed);
        self.first_seen.lock().unwrap_or_else(|p| p.into_inner()).retain(|k, _| present.contains(k));
    }
}

/// UsbDk hide rules: a phone in accessory mode is kept away from Windows drivers. Without this,
/// a driver that claims accessory devices (Samsung's USB driver binds WinUSB to 18D1:2D00) may
/// fail to start on the phone (code 10, seen with a Galaxy S22) and leave it unusable until the
/// cable is replugged. The rules live as long as the handle, i.e. while SwitchFlow runs.
#[cfg(windows)]
mod hider {
    use windows::core::{s, w};
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

    #[repr(C)]
    struct HideRule {
        hide: u64,
        class: u64,
        vid: u64,
        pid: u64,
        bcd: u64,
    }
    /// `USB_DK_HIDE_RULE_MATCH_ALL ((ULONG64)(-1))` in UsbDkDataHiderPublic.h. A 32-bit -1 here
    /// would only match a device class of 0xFFFFFFFF, i.e. nothing (the bug seen on the Galaxy S22).
    pub(super) const MATCH_ALL: u64 = u64::MAX;

    type CreateFn = unsafe extern "C" fn() -> HANDLE;
    type AddFn = unsafe extern "C" fn(HANDLE, *const HideRule) -> i32;
    type CloseFn = unsafe extern "C" fn(HANDLE);

    pub struct Hider {
        handle: HANDLE,
        close: CloseFn,
    }

    // The handle is only passed back to UsbDk on drop.
    unsafe impl Send for Hider {}
    unsafe impl Sync for Hider {}

    impl Hider {
        pub fn accessories() -> Option<Hider> {
            unsafe {
                let lib = LoadLibraryW(w!("UsbDkHelper.dll")).ok()?;
                let create: CreateFn = std::mem::transmute(GetProcAddress(lib, s!("UsbDk_CreateHiderHandle"))?);
                let add: AddFn = std::mem::transmute(GetProcAddress(lib, s!("UsbDk_AddHideRule"))?);
                let close: CloseFn = std::mem::transmute(GetProcAddress(lib, s!("UsbDk_CloseHiderHandle"))?);
                let handle = create();
                if handle.is_invalid() || handle.0.is_null() {
                    return None;
                }
                for pid in super::ACCESSORY_PIDS {
                    let rule = HideRule { hide: 1, class: MATCH_ALL, vid: super::GOOGLE_VID as u64, pid: pid as u64, bcd: MATCH_ALL };
                    if add(handle, &rule) == 0 {
                        tracing::warn!("UsbDk did not accept a hide rule");
                    }
                }
                Some(Hider { handle, close })
            }
        }
    }

    impl Drop for Hider {
        fn drop(&mut self) {
            unsafe { (self.close)(self.handle) }
        }
    }
}

fn open_context() -> Option<rusb::Context> {
    #[cfg(windows)]
    {
        // Without UsbDk libusb would only see WinUSB devices, which phones without debugging are not.
        let helper = std::path::Path::new(&std::env::var_os("SystemRoot")?).join("System32").join("UsbDkHelper.dll");
        if !helper.exists() {
            return None;
        }
        rusb::Context::with_options(&[rusb::UsbOption::use_usbdk()]).ok()
    }
    #[cfg(not(windows))]
    {
        rusb::Context::new().ok()
    }
}

/// A USB interface a phone exposes in one of its USB modes ("File transfer", "Photos", "MIDI",
/// "USB tethering") or with USB debugging. Together with the maker's vendor id this tells a phone
/// apart from the same maker's disks, monitors or keyboards (mass storage, hub, HID are never matched).
pub fn is_phone_interface(class: u8, subclass: u8, protocol: u8) -> bool {
    matches!(
        (class, subclass, protocol),
        (0x06, 0x01, 0x01)          // MTP / PTP
            | (0xFF, 0xFF, 0x00)    // MTP with Microsoft OS descriptors
            | (0xFF, 0x42, 0x01)    // ADB
            | (0x01, 0x03, _)       // MIDI streaming
            | (0xE0, 0x01, 0x03)    // RNDIS (USB tethering)
            | (0xEF, 0x04, 0x01)    // RNDIS, misc-class variant
    )
}

fn has_phone_interface(dev: &rusb::Device<rusb::Context>) -> bool {
    let Ok(cfg) = dev.active_config_descriptor().or_else(|_| dev.config_descriptor(0)) else { return false };
    cfg.interfaces().any(|i| i.descriptors().any(|d| is_phone_interface(d.class_code(), d.sub_class_code(), d.protocol_code())))
}

/// Windows asks a USB device for Microsoft OS descriptors when its `usbflags` entry says it has
/// them (`osvc` = 01 xx). A phone may advertise them in normal mode and keep advertising them in
/// accessory mode, yet answer wrongly there: Windows then resets the port again and again and the
/// phone never comes up (Galaxy S22, Android 16: its log showed "connected, configured: 0" in a
/// loop). Accessory mode needs no OS descriptors, so SwitchFlow records `osvc` = 00 00 for the
/// phone's accessory ids before switching it — once per phone model, with administrator approval.
#[cfg(windows)]
pub mod usbflags {
    use std::process::Command;

    const KEY: &str = r"HKLM\SYSTEM\CurrentControlSet\Control\usbflags";

    /// Back from rusb's version to the raw bcdDevice (Windows names the key after it).
    pub fn bcd(v: rusb::Version) -> u16 {
        ((v.major() as u16 / 10) << 12) | ((v.major() as u16 % 10) << 8) | ((v.minor() as u16) << 4) | v.sub_minor() as u16
    }

    /// `usbflags` key names for the accessory ids the phone comes back as (without and with ADB).
    pub fn accessory_keys(bcd_device: u16) -> Vec<String> {
        [0x2D00u16, 0x2D01].iter().map(|pid| format!("{:04X}{:04X}{:04X}", super::GOOGLE_VID, pid, bcd_device)).collect()
    }

    fn hidden(cmd: &mut Command) -> &mut Command {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000) // CREATE_NO_WINDOW
    }

    /// `reg query` output → whether `osvc` is already 00 00.
    pub fn osvc_is_off(reg_output: &str) -> bool {
        reg_output.lines().any(|l| {
            let mut parts = l.split_whitespace();
            parts.next() == Some("osvc")
                && parts.next() == Some("REG_BINARY")
                && parts.next().is_some_and(|v| v.chars().all(|c| c == '0'))
        })
    }

    pub fn needs_fix(key: &str) -> bool {
        let out = hidden(Command::new("reg").args(["query", &format!(r"{KEY}\{key}"), "/v", "osvc"])).output();
        !out.is_ok_and(|o| osvc_is_off(&String::from_utf8_lossy(&o.stdout)))
    }

    /// One administrator prompt for all keys. False if the user declined or it failed.
    pub fn turn_off_os_descriptors(keys: &[String]) -> bool {
        // The path has no spaces, so no quotes that PowerShell and cmd would each have to unwrap.
        let cmds: Vec<String> = keys.iter().map(|k| format!(r"reg add {KEY}\{k} /v osvc /t REG_BINARY /d 0000 /f")).collect();
        let script = format!(
            "$p = Start-Process cmd -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList '/c {}'; exit $p.ExitCode",
            cmds.join(" & ")
        );
        hidden(Command::new("powershell").args(["-NoProfile", "-Command", &script])).status().is_ok_and(|s| s.success())
    }
}

fn switch_to_accessory(dev: &rusb::Device<rusb::Context>) -> rusb::Result<u16> {
    const TIMEOUT: Duration = Duration::from_secs(2);
    let h = dev.open()?;
    let mut ver = [0u8; 2];
    // ACCESSORY_GET_PROTOCOL
    h.read_control(0xC0, 51, 0, 0, &mut ver, TIMEOUT)?;
    let protocol = u16::from_le_bytes(ver);
    if protocol == 0 {
        return Err(rusb::Error::NotSupported);
    }
    // ACCESSORY_SEND_STRING: manufacturer, model, description, version (required before Android 11).
    for (index, s) in [ACCESSORY_MANUFACTURER, ACCESSORY_MODEL, ACCESSORY_DESCRIPTION, ACCESSORY_VERSION].iter().enumerate() {
        let mut bytes = s.as_bytes().to_vec();
        bytes.push(0);
        h.write_control(0x40, 52, 0, index as u16, &bytes, TIMEOUT)?;
    }
    // ACCESSORY_START: the phone disconnects and comes back as 18D1:2D0x.
    h.write_control(0x40, 53, 0, 0, &[], TIMEOUT)?;
    Ok(protocol)
}

fn open_link(dev: &rusb::Device<rusb::Context>) -> rusb::Result<(rusb::DeviceHandle<rusb::Context>, u8, u8)> {
    let h = dev.open()?;
    let cfg = dev.active_config_descriptor().or_else(|_| dev.config_descriptor(0))?;
    // Interface 0 is the accessory; with debugging on, interface 1 is ADB.
    let iface = cfg.interfaces().next().ok_or(rusb::Error::NotFound)?;
    let alt = iface.descriptors().next().ok_or(rusb::Error::NotFound)?;
    let (mut ep_in, mut ep_out) = (None, None);
    for ep in alt.endpoint_descriptors() {
        if ep.transfer_type() == rusb::TransferType::Bulk {
            match ep.direction() {
                rusb::Direction::In => ep_in = ep_in.or(Some(ep.address())),
                rusb::Direction::Out => ep_out = ep_out.or(Some(ep.address())),
            }
        }
    }
    let _ = h.set_active_configuration(cfg.number());
    h.claim_interface(alt.interface_number())?;
    Ok((h, ep_in.ok_or(rusb::Error::NotFound)?, ep_out.ok_or(rusb::Error::NotFound)?))
}

/// What a read from the cable gave.
pub enum ReadResult {
    Data(usize),
    /// Nothing within the read timeout.
    Idle,
    /// Failed, but the device is still there (the app has not opened the accessory yet).
    Retry,
    /// Unplugged or closed.
    Gone,
}

/// The cable underneath a link: Android accessory bulk endpoints, or a TCP stream to an iPhone app.
pub trait Transport: Send + Sync {
    fn read(&self, buf: &mut [u8]) -> ReadResult;
    fn write(&self, frame: &[u8]) -> bool;
}

struct UsbTransport {
    h: rusb::DeviceHandle<rusb::Context>,
    ep_in: u8,
    ep_out: u8,
}

impl Transport for UsbTransport {
    fn read(&self, buf: &mut [u8]) -> ReadResult {
        match self.h.read_bulk(self.ep_in, buf, Duration::from_secs(1)) {
            Ok(n) => ReadResult::Data(n),
            Err(rusb::Error::Timeout) => ReadResult::Idle,
            Err(rusb::Error::NoDevice) => ReadResult::Gone,
            Err(_) => ReadResult::Retry,
        }
    }
    fn write(&self, frame: &[u8]) -> bool {
        self.h.write_bulk(self.ep_out, frame, Duration::from_secs(10)).is_ok_and(|n| n == frame.len())
    }
}

/// A TCP stream to the iPhone app (through usbmuxd), or to the simulator in the cloud test.
pub struct TcpTransport(TcpStream);

impl TcpTransport {
    pub fn new(stream: TcpStream) -> std::io::Result<TcpTransport> {
        stream.set_read_timeout(Some(Duration::from_secs(1)))?;
        stream.set_nodelay(true)?;
        Ok(TcpTransport(stream))
    }
}

impl Transport for TcpTransport {
    fn read(&self, buf: &mut [u8]) -> ReadResult {
        match (&self.0).read(buf) {
            Ok(0) => ReadResult::Gone,
            Ok(n) => ReadResult::Data(n),
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => ReadResult::Idle,
            Err(_) => ReadResult::Gone,
        }
    }
    fn write(&self, frame: &[u8]) -> bool {
        (&self.0).write_all(frame).is_ok()
    }
}

/// Carries the phone's TCP streams to the local SwitchFlow server until the cable goes away.
pub fn run_link(t: Arc<dyn Transport>, core: &Arc<Core>) {
    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(64);
    let writer = {
        let t = t.clone();
        std::thread::Builder::new().name("sf-cable-write".into()).spawn(move || {
            for frame in rx {
                if !t.write(&frame) {
                    break;
                }
            }
        })
    };
    if writer.is_err() {
        return;
    }
    let port = core.lock().server.port;
    let streams: Arc<Mutex<HashMap<u32, TcpStream>>> = Arc::default();
    let mut decoder = Decoder::default();
    let mut buf = vec![0u8; MAX_TRANSFER];
    // Until the app opens the accessory (the phone is still showing "Open SwitchFlow?"), reads
    // fail. Keep the device and wait: reopening it would take it from Windows and back each time,
    // which some phones answer by dropping off USB until the cable is replugged.
    let mut ready = false;
    let waiting_since = std::time::Instant::now();
    'link: loop {
        let n = match t.read(&mut buf) {
            ReadResult::Data(n) => n,
            ReadResult::Idle => continue,
            ReadResult::Gone => break,
            ReadResult::Retry if !ready && waiting_since.elapsed() < Duration::from_secs(120) => {
                std::thread::sleep(Duration::from_millis(300));
                continue;
            }
            ReadResult::Retry => {
                tracing::info!("cable link read failed");
                break;
            }
        };
        decoder.push(&buf[..n]);
        loop {
            let frame = match decoder.next() {
                Ok(Some(f)) => f,
                Ok(None) => break,
                Err(e) => {
                    tracing::warn!(error = e, "cable link: corrupt frame");
                    break 'link;
                }
            };
            match frame.kind {
                // Sent each time the app (re)opens the accessory, e.g. after it was restarted:
                // its old connections are gone, and it needs the pairing data again.
                Kind::Ready => {
                    ready = true;
                    for (_, s) in streams.lock().unwrap_or_else(|p| p.into_inner()).drain() {
                        let _ = s.shutdown(Shutdown::Both);
                    }
                    // What the QR code would carry; the phone reaches the server through this cable.
                    let uri = core.start_pairing().uri;
                    let hello = serde_json::json!({ "v": 1, "uri": uri }).to_string();
                    if tx.send(control_frame(Kind::Hello, 0, hello.as_bytes())).is_err() {
                        break 'link;
                    }
                }
                Kind::Open => open_stream(frame.stream, port, &streams, &tx),
                Kind::Data => {
                    let failed = {
                        let mut map = streams.lock().unwrap_or_else(|p| p.into_inner());
                        match map.get_mut(&frame.stream) {
                            Some(s) => s.write_all(&frame.payload).is_err(),
                            None => false,
                        }
                    };
                    if failed {
                        close_stream(frame.stream, &streams);
                        let _ = tx.send(control_frame(Kind::Close, frame.stream, &[]));
                    }
                }
                Kind::Close => close_stream(frame.stream, &streams),
                _ => {}
            }
        }
    }
    for (_, s) in streams.lock().unwrap_or_else(|p| p.into_inner()).drain() {
        let _ = s.shutdown(Shutdown::Both);
    }
    drop(tx);
}

fn open_stream(id: u32, port: u16, streams: &Arc<Mutex<HashMap<u32, TcpStream>>>, tx: &mpsc::SyncSender<Vec<u8>>) {
    let Ok(sock) = TcpStream::connect(("127.0.0.1", port)) else {
        let _ = tx.send(control_frame(Kind::Close, id, &[]));
        return;
    };
    let _ = sock.set_nodelay(true);
    let Ok(mut reader) = sock.try_clone() else { return };
    streams.lock().unwrap_or_else(|p| p.into_inner()).insert(id, sock);
    let (streams, tx) = (streams.clone(), tx.clone());
    let _ = std::thread::Builder::new().name("sf-cable-stream".into()).spawn(move || {
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            match reader.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    for f in data_frames(id, &buf[..n]) {
                        if tx.send(f).is_err() {
                            return;
                        }
                    }
                }
            }
        }
        if streams.lock().unwrap_or_else(|p| p.into_inner()).remove(&id).is_some() {
            let _ = tx.send(control_frame(Kind::Close, id, &[]));
        }
    });
}

fn close_stream(id: u32, streams: &Arc<Mutex<HashMap<u32, TcpStream>>>) {
    if let Some(s) = streams.lock().unwrap_or_else(|p| p.into_inner()).remove(&id) {
        let _ = s.shutdown(Shutdown::Both);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip_across_arbitrary_reads() {
        let mut wire = control_frame(Kind::Hello, 0, br#"{"v":1}"#);
        let data: Vec<u8> = (0..100_000u32).map(|i| (i * 7) as u8).collect();
        for f in data_frames(5, &data) {
            wire.extend(f);
        }
        wire.extend(control_frame(Kind::Close, 5, &[]));
        // Feed in odd-sized pieces, as USB reads may split or join frames.
        let mut d = Decoder::default();
        let (mut got, mut hello, mut closed) = (Vec::new(), false, false);
        for piece in wire.chunks(777) {
            d.push(piece);
            while let Some(f) = d.next().unwrap() {
                match f.kind {
                    Kind::Hello => hello = f.payload == br#"{"v":1}"#,
                    Kind::Data => got.extend(f.payload),
                    Kind::Close => closed = f.stream == 5,
                    _ => panic!("unexpected frame"),
                }
            }
        }
        assert!(hello && closed);
        assert_eq!(got, data);
    }

    #[test]
    fn no_frame_ends_on_a_usb_packet_boundary() {
        for len in 0..40_000 {
            let data = vec![0u8; len];
            for f in data_frames(1, &data) {
                assert!(f.len() <= MAX_TRANSFER, "frame fits one 16 KiB read");
                assert_ne!(f.len() % 64, 0, "len {len}: frame of {} bytes", f.len());
            }
        }
        for len in 0..600 {
            let f = control_frame(Kind::Hello, 0, &vec![b'x'; len]);
            assert_ne!(f.len() % 64, 0);
        }
    }

    #[cfg(windows)]
    #[test]
    fn usbflags_keys_follow_the_phone_revision() {
        // Galaxy S22: bcdDevice 0x0504 in normal mode and in accessory mode.
        assert_eq!(usbflags::bcd(rusb::Version::from_bcd(0x0504)), 0x0504);
        assert_eq!(usbflags::bcd(rusb::Version::from_bcd(0x0515)), 0x0515);
        assert_eq!(usbflags::accessory_keys(0x0504), vec!["18D12D000504", "18D12D010504"]);
        let on = "\r\nHKEY_LOCAL_MACHINE\\...\\18D12D000504\r\n    osvc    REG_BINARY    0101\r\n";
        let off = "\r\nHKEY_LOCAL_MACHINE\\...\\18D12D000504\r\n    osvc    REG_BINARY    0000\r\n";
        assert!(!usbflags::osvc_is_off(on));
        assert!(usbflags::osvc_is_off(off));
        assert!(!usbflags::osvc_is_off("ERROR: The system was unable to find the specified registry key or value."));
    }

    #[cfg(windows)]
    #[test]
    fn hide_rule_wildcard_is_the_64_bit_one() {
        assert_eq!(super::hider::MATCH_ALL, 0xFFFF_FFFF_FFFF_FFFF);
    }

    #[test]
    fn only_phone_interfaces_are_matched() {
        let any = |set: &[(u8, u8, u8)]| set.iter().any(|&(c, s, p)| is_phone_interface(c, s, p));
        // Interface sets seen on this project's test PC.
        assert!(any(&[(0x06, 0x01, 0x01), (0x02, 0x02, 0x01), (0x0A, 0x00, 0x00), (0xFF, 0x42, 0x01)]), "Galaxy S22/A10, file transfer");
        assert!(any(&[(0x01, 0x01, 0x00), (0x01, 0x03, 0x00)]), "Galaxy S22 after replug: MIDI mode");
        assert!(any(&[(0xE0, 0x01, 0x03), (0x0A, 0x00, 0x00)]), "USB tethering");
        assert!(!any(&[(0x03, 0x01, 0x02)]), "mouse");
        assert!(!any(&[(0x0E, 0x01, 0x01), (0x0E, 0x02, 0x01), (0xFE, 0x01, 0x01)]), "webcam");
        assert!(!any(&[(0xE0, 0x01, 0x01)]), "Bluetooth adapter");
        assert!(!any(&[(0x08, 0x06, 0x50), (0x08, 0x06, 0x62)]), "Samsung portable SSD: mass storage / UAS");
        assert!(!any(&[(0x09, 0x00, 0x00)]), "hub in a monitor");
    }

    #[test]
    fn corrupt_input_is_rejected() {
        let mut d = Decoder::default();
        d.push(&[9, 0, 0, 0, 1, 0, 0, 0, 0]);
        assert!(d.next().is_err());
        let mut d = Decoder::default();
        d.push(&encode(Kind::Data, 1, &[])[..5]);
        d.push(&[0xFF, 0xFF, 0xFF, 0xFF]);
        assert!(d.next().is_err(), "length beyond one transfer");
    }
}
