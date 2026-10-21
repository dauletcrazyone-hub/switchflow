//! Local network helpers: LAN addresses for the QR code and QR rendering.

use std::net::IpAddr;

const VIRTUAL_ADAPTERS: [&str; 11] = [
    "vethernet", "virtualbox", "vmware", "hyper-v", "wsl", "docker", "loopback", "vpn", "tailscale", "zerotier", "bluetooth",
];

/// IPv4 addresses phones can reach, physical adapters first, `127.0.0.1` last (for `adb reverse`).
pub fn lan_hosts() -> Vec<String> {
    let mut physical = Vec::new();
    let mut virtual_ = Vec::new();
    if let Ok(list) = local_ip_address::list_afinet_netifas() {
        for (name, ip) in list {
            let IpAddr::V4(v4) = ip else { continue };
            if v4.is_loopback() || v4.is_link_local() || v4.is_unspecified() || v4.is_multicast() {
                continue;
            }
            let lname = name.to_lowercase();
            let s = v4.to_string();
            if VIRTUAL_ADAPTERS.iter().any(|k| lname.contains(k)) || !v4.is_private() {
                virtual_.push(s);
            } else {
                physical.push(s);
            }
        }
    }
    let mut out = Vec::new();
    for h in physical.into_iter().chain(virtual_).chain(std::iter::once("127.0.0.1".to_string())) {
        if !out.contains(&h) {
            out.push(h);
        }
    }
    out.truncate(6);
    out
}

pub fn qr_svg(data: &str) -> String {
    use qrcode::render::svg;
    match qrcode::QrCode::with_error_correction_level(data.as_bytes(), qrcode::EcLevel::M) {
        Ok(code) => code
            .render::<svg::Color>()
            .min_dimensions(256, 256)
            .quiet_zone(true)
            .dark_color(svg::Color("#0b1220"))
            .light_color(svg::Color("#ffffff"))
            .build(),
        Err(_) => String::new(),
    }
}

pub fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_cyrillic() {
        assert_eq!(url_encode("ПК 1"), "%D0%9F%D0%9A%201");
        assert_eq!(url_encode("a-b_c.d~"), "a-b_c.d~");
    }

    #[test]
    fn loopback_is_last() {
        let hosts = lan_hosts();
        assert_eq!(hosts.last().map(String::as_str), Some("127.0.0.1"));
    }

    #[test]
    fn qr_renders() {
        assert!(qr_svg("switchflow://pair?v=1").starts_with("<?xml"));
    }
}
