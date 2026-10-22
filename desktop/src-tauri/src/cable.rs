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
}

impl Cable {
    pub fn new() -> Arc<Cable> {
        let ctx = open_context();
        if ctx.is_none() {
            tracing::info!("cable transfer unavailable: no UsbDk");
        }
        Arc::new(Cable { ctx, linked: Mutex::new(Vec::new()), phones: AtomicUsize::new(0) })
    }

    pub fn available(&self) -> bool {
        self.ctx.is_some()
    }

    /// Phones that "Connect by cable" would switch (descriptors only: nothing is opened).
    pub fn phones(&self) -> usize {
        self.phones.load(Ordering::Relaxed)
    }

    pub fn links(&self) -> usize {
        self.linked.lock().unwrap_or_else(|p| p.into_inner()).len()
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
        let Some(ctx) = &self.ctx else { return };
        let Ok(list) = ctx.devices() else { return };
        let mut phones = 0;
        for dev in list.iter() {
            let Ok(desc) = dev.device_descriptor() else { continue };
            if !is_accessory(desc.vendor_id(), desc.product_id()) {
                if ANDROID_VIDS.contains(&desc.vendor_id()) && has_phone_interface(&dev) {
                    phones += 1;
                }
                continue;
            }
            let key = (dev.bus_number(), dev.address());
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
                    Ok((handle, ep_in, ep_out)) => {
                        tracing::info!("cable link open");
                        run_link(Arc::new(handle), ep_in, ep_out, &core);
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

/// MTP / PTP ("File transfer" / "Photos") or a vendor interface: what a phone exposes on USB.
fn has_phone_interface(dev: &rusb::Device<rusb::Context>) -> bool {
    let Ok(cfg) = dev.active_config_descriptor().or_else(|_| dev.config_descriptor(0)) else { return false };
    cfg.interfaces().any(|i| {
        i.descriptors().any(|d| {
            let (c, s, p) = (d.class_code(), d.sub_class_code(), d.protocol_code());
            (c == 0x06 && s == 0x01 && p == 0x01) || (c == 0xFF && s == 0xFF && p == 0x00)
        })
    })
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

type Handle = Arc<rusb::DeviceHandle<rusb::Context>>;

/// Carries the phone's TCP streams to the local SwitchFlow server until the cable goes away.
fn run_link(h: Handle, ep_in: u8, ep_out: u8, core: &Arc<Core>) {
    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(64);
    let writer = {
        let h = h.clone();
        std::thread::Builder::new().name("sf-cable-write".into()).spawn(move || {
            for frame in rx {
                if h.write_bulk(ep_out, &frame, Duration::from_secs(10)).map(|n| n != frame.len()).unwrap_or(true) {
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
        let n = match h.read_bulk(ep_in, &mut buf, Duration::from_secs(1)) {
            Ok(n) => n,
            Err(rusb::Error::Timeout) => continue,
            Err(rusb::Error::NoDevice) => break, // unplugged
            Err(_) if !ready && waiting_since.elapsed() < Duration::from_secs(120) => {
                std::thread::sleep(Duration::from_millis(300));
                continue;
            }
            Err(e) => {
                tracing::info!(error = %e, "cable link read failed");
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
