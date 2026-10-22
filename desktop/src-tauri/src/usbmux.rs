//! iPhone over the USB cable through Apple's usbmuxd.
//!
//! On Windows usbmuxd is the Apple Mobile Device Service (installed with iTunes or Apple Devices),
//! listening on 127.0.0.1:27015. It can open a TCP connection to a port of an app on a connected,
//! trusted iPhone. The SwitchFlow iPhone app listens on the phone's 127.0.0.1:[DEVICE_PORT] while
//! it is open (loopback only, so nothing on Wi-Fi can reach it); the PC connects through usbmuxd
//! and runs the same cable link as with Android accessories (cable.rs). No developer account, no
//! jailbreak and no USB debugging: only "Trust this computer" on the iPhone.
//!
//! Protocol: 16-byte little-endian header (length, version 1, type 8 = plist, tag) + XML plist.

use plist::{Dictionary, Value};
use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// Port the SwitchFlow iPhone app listens on (ios/.../CableListener.swift).
pub const DEVICE_PORT: u16 = 47822;
const MUXD_ADDR: &str = "127.0.0.1:27015";
const PLIST_MESSAGE: u32 = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Device {
    pub id: u64,
    pub serial: String,
}

fn request(stream: &mut TcpStream, tag: u32, message: &str, extra: Dictionary) -> io::Result<Dictionary> {
    let mut dict = Dictionary::new();
    dict.insert("MessageType".into(), message.into());
    dict.insert("ClientVersionString".into(), "SwitchFlow".into());
    dict.insert("ProgName".into(), "SwitchFlow".into());
    dict.insert("kLibUSBMuxVersion".into(), Value::Integer(3.into()));
    for (k, v) in extra {
        dict.insert(k, v);
    }
    let mut body = Vec::new();
    plist::to_writer_xml(&mut body, &Value::Dictionary(dict)).map_err(io::Error::other)?;
    let mut msg = Vec::with_capacity(16 + body.len());
    msg.extend_from_slice(&((16 + body.len()) as u32).to_le_bytes());
    msg.extend_from_slice(&1u32.to_le_bytes());
    msg.extend_from_slice(&PLIST_MESSAGE.to_le_bytes());
    msg.extend_from_slice(&tag.to_le_bytes());
    msg.extend_from_slice(&body);
    stream.write_all(&msg)?;
    read_reply(stream)
}

fn read_reply(stream: &mut TcpStream) -> io::Result<Dictionary> {
    let mut header = [0u8; 16];
    stream.read_exact(&mut header)?;
    let len = u32::from_le_bytes(header[0..4].try_into().expect("4 bytes")) as usize;
    if !(16..=1 << 20).contains(&len) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "usbmuxd reply length"));
    }
    let mut body = vec![0u8; len - 16];
    stream.read_exact(&mut body)?;
    plist::from_bytes::<Value>(&body)
        .ok()
        .and_then(Value::into_dictionary)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "usbmuxd reply is not a plist dictionary"))
}

fn open() -> io::Result<TcpStream> {
    let addr = MUXD_ADDR.parse().expect("valid address");
    let s = TcpStream::connect_timeout(&addr, Duration::from_millis(500))?;
    s.set_read_timeout(Some(Duration::from_secs(5)))?;
    Ok(s)
}

/// Whether usbmuxd (Apple Mobile Device Service) is running on this PC.
pub fn available() -> bool {
    open().is_ok()
}

/// iPhones and iPads connected by USB (usbmuxd also lists Wi-Fi-synced devices; those are skipped).
pub fn list_devices() -> io::Result<Vec<Device>> {
    let mut s = open()?;
    let reply = request(&mut s, 1, "ListDevices", Dictionary::new())?;
    Ok(parse_device_list(&reply))
}

pub fn parse_device_list(reply: &Dictionary) -> Vec<Device> {
    let Some(list) = reply.get("DeviceList").and_then(Value::as_array) else { return Vec::new() };
    list.iter()
        .filter_map(|d| {
            let props = d.as_dictionary()?.get("Properties")?.as_dictionary()?;
            if props.get("ConnectionType").and_then(Value::as_string) != Some("USB") {
                return None;
            }
            Some(Device {
                id: props.get("DeviceID")?.as_unsigned_integer()?,
                serial: props.get("SerialNumber").and_then(Value::as_string).unwrap_or_default().to_string(),
            })
        })
        .collect()
}

/// Opens a TCP connection to `port` on the device. `ConnectionRefused` means the app is not open.
pub fn connect(device_id: u64, port: u16) -> io::Result<TcpStream> {
    let mut s = open()?;
    let mut extra = Dictionary::new();
    extra.insert("DeviceID".into(), Value::Integer(device_id.into()));
    // usbmuxd expects the port in network byte order inside a little-endian integer.
    extra.insert("PortNumber".into(), Value::Integer((port.swap_bytes() as u64).into()));
    let reply = request(&mut s, 2, "Connect", extra)?;
    match reply.get("Number").and_then(Value::as_unsigned_integer) {
        Some(0) => {
            // From here on the socket is the raw connection to the app.
            s.set_read_timeout(None)?;
            Ok(s)
        }
        Some(3) => Err(io::Error::new(io::ErrorKind::ConnectionRefused, "app is not listening")),
        Some(n) => Err(io::Error::other(format!("usbmuxd connect failed: {n}"))),
        None => Err(io::Error::new(io::ErrorKind::InvalidData, "usbmuxd connect: no result")),
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::net::TcpListener;

    /// A stand-in for usbmuxd: answers ListDevices with one USB iPhone and one Wi-Fi one, and on
    /// Connect to DEVICE_PORT hands the socket to `phone` (which plays the iPhone app).
    pub fn fake_muxd(phone: impl FnOnce(TcpStream) + Send + 'static) -> (u16, std::thread::JoinHandle<Vec<(String, u64)>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let mut seen = Vec::new();
            let mut phone = Some(phone);
            for conn in listener.incoming().take(2) {
                let mut s = conn.unwrap();
                let mut header = [0u8; 16];
                s.read_exact(&mut header).unwrap();
                assert_eq!(u32::from_le_bytes(header[8..12].try_into().unwrap()), PLIST_MESSAGE);
                let mut body = vec![0u8; u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize - 16];
                s.read_exact(&mut body).unwrap();
                let req = plist::from_bytes::<Value>(&body).unwrap().into_dictionary().unwrap();
                let kind = req.get("MessageType").unwrap().as_string().unwrap().to_string();
                let reply = match kind.as_str() {
                    "ListDevices" => plist::from_bytes::<Value>(
                        br#"<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>DeviceList</key><array>
                        <dict><key>DeviceID</key><integer>7</integer><key>MessageType</key><string>Attached</string><key>Properties</key><dict>
                          <key>ConnectionType</key><string>USB</string><key>DeviceID</key><integer>7</integer><key>SerialNumber</key><string>00008030-0019683E0A51802E</string></dict></dict>
                        <dict><key>DeviceID</key><integer>9</integer><key>MessageType</key><string>Attached</string><key>Properties</key><dict>
                          <key>ConnectionType</key><string>Network</string><key>DeviceID</key><integer>9</integer><key>SerialNumber</key><string>wifi</string></dict></dict>
                        </array></dict></plist>"#,
                    )
                    .unwrap(),
                    _ => {
                        let port = req.get("PortNumber").unwrap().as_unsigned_integer().unwrap();
                        seen.push((kind.clone(), port));
                        plist::from_bytes::<Value>(br#"<?xml version="1.0"?><plist version="1.0"><dict><key>MessageType</key><string>Result</string><key>Number</key><integer>0</integer></dict></plist>"#).unwrap()
                    }
                };
                let mut out = Vec::new();
                plist::to_writer_xml(&mut out, &reply).unwrap();
                let mut msg = ((16 + out.len()) as u32).to_le_bytes().to_vec();
                msg.extend_from_slice(&1u32.to_le_bytes());
                msg.extend_from_slice(&PLIST_MESSAGE.to_le_bytes());
                msg.extend_from_slice(&header[12..16]);
                msg.extend_from_slice(&out);
                s.write_all(&msg).unwrap();
                seen.push((kind.clone(), 0));
                if kind == "Connect" {
                    (phone.take().unwrap())(s);
                }
            }
            seen
        });
        (port, handle)
    }

    #[test]
    fn lists_usb_iphones_and_connects_to_the_app_port() {
        let (port, muxd) = fake_muxd(|mut s| {
            // The tunnel is now the app's socket: echo one line back.
            let mut buf = [0u8; 5];
            s.read_exact(&mut buf).unwrap();
            s.write_all(&buf).unwrap();
        });
        let addr = format!("127.0.0.1:{port}");
        let mut s = TcpStream::connect(&addr).unwrap();
        let devices = parse_device_list(&request(&mut s, 1, "ListDevices", Dictionary::new()).unwrap());
        assert_eq!(devices, vec![Device { id: 7, serial: "00008030-0019683E0A51802E".into() }], "Wi-Fi devices are skipped");

        let mut s = TcpStream::connect(&addr).unwrap();
        let mut extra = Dictionary::new();
        extra.insert("DeviceID".into(), Value::Integer(7u64.into()));
        extra.insert("PortNumber".into(), Value::Integer((DEVICE_PORT.swap_bytes() as u64).into()));
        let reply = request(&mut s, 2, "Connect", extra).unwrap();
        assert_eq!(reply.get("Number").and_then(Value::as_unsigned_integer), Some(0));
        s.write_all(b"hello").unwrap();
        let mut back = [0u8; 5];
        s.read_exact(&mut back).unwrap();
        assert_eq!(&back, b"hello");

        let seen = muxd.join().unwrap();
        assert!(seen.contains(&("Connect".into(), DEVICE_PORT.swap_bytes() as u64)), "port sent in network byte order: {seen:?}");
    }

    #[test]
    fn port_byte_order() {
        // 47822 = 0xBACE; usbmuxd wants 0xCEBA.
        assert_eq!(DEVICE_PORT.swap_bytes(), 0xCEBA);
    }
}
