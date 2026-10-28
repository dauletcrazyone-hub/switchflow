//! Hands the SwitchFlow Android app to a phone that does not have it yet, without USB debugging.
//!
//! When the PC switches a phone into accessory mode it also sends a URI (AOA string 4). A phone
//! with SwitchFlow opens the app; a phone without it shows Android's own "USB accessory" dialog
//! with that link. The link points here: a tiny plain-HTTP page on the PC's LAN address with the
//! app file. The phone's browser downloads it and Android asks the user to install it.
//!
//! Plain HTTP on purpose: a self-signed certificate would stop the phone's browser with a warning.
//! Only the public app file is served — read-only, nothing about the user, no other paths.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

pub const PORT: u16 = 47823;
const FILE: &str = "/SwitchFlow.apk";

/// Starts serving `apk` on all interfaces; returns the port, or None when nothing could be bound.
pub fn start(apk: PathBuf) -> Option<u16> {
    if !apk.is_file() {
        tracing::warn!("apk server: no app file bundled");
        return None;
    }
    let listener = TcpListener::bind(("0.0.0.0", PORT)).or_else(|_| TcpListener::bind(("0.0.0.0", 0))).ok()?;
    let port = listener.local_addr().ok()?.port();
    let apk = Arc::new(apk);
    std::thread::Builder::new()
        .name("apk-server".into())
        .spawn(move || {
            for conn in listener.incoming().flatten() {
                let apk = apk.clone();
                let _ = std::thread::Builder::new().name("apk-conn".into()).spawn(move || {
                    let _ = handle(conn, &apk);
                });
            }
        })
        .ok()?;
    tracing::info!(port, "apk server listening");
    Some(port)
}

/// The link the phone shows when SwitchFlow is not installed, or None without a LAN address.
pub fn link(port: u16) -> Option<String> {
    let host = crate::net::lan_hosts().into_iter().find(|h| h != "127.0.0.1")?;
    Some(format!("http://{host}:{port}/"))
}

fn handle(mut conn: TcpStream, apk: &PathBuf) -> std::io::Result<()> {
    conn.set_read_timeout(Some(Duration::from_secs(10)))?;
    conn.set_write_timeout(Some(Duration::from_secs(60)))?;
    let mut reader = BufReader::new(conn.try_clone()?);
    let mut first = String::new();
    reader.by_ref().take(2048).read_line(&mut first)?;
    // Skip the headers; nothing in them matters here.
    let mut total = first.len();
    loop {
        let mut line = String::new();
        let n = reader.by_ref().take(2048).read_line(&mut line)?;
        total += n;
        if n == 0 || line == "\r\n" || line == "\n" || total > 16 * 1024 {
            break;
        }
    }
    let mut parts = first.split_whitespace();
    let (method, path) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
    let head = method == "HEAD";
    if method != "GET" && !head {
        return respond(&mut conn, "405 Method Not Allowed", "text/plain", b"", head);
    }
    match path.split('?').next().unwrap_or("") {
        "/" | "/index.html" => respond(&mut conn, "200 OK", "text/html; charset=utf-8", page().as_bytes(), head),
        FILE => {
            let mut f = std::fs::File::open(apk)?;
            let len = f.metadata()?.len();
            let header = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: {len}\r\n\
                 Content-Disposition: attachment; filename=\"SwitchFlow.apk\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            );
            conn.write_all(header.as_bytes())?;
            if !head {
                std::io::copy(&mut f, &mut conn)?;
            }
            tracing::info!(bytes = len, "apk server: app file sent to a phone");
            Ok(())
        }
        _ => respond(&mut conn, "404 Not Found", "text/plain", b"not found", head),
    }
}

fn respond(conn: &mut TcpStream, status: &str, kind: &str, body: &[u8], head: bool) -> std::io::Result<()> {
    let header = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {kind}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    );
    conn.write_all(header.as_bytes())?;
    if !head {
        conn.write_all(body)?;
    }
    Ok(())
}

/// A one-button page in Russian and English: the phone's browser opens it from the USB dialog.
fn page() -> String {
    r##"<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SwitchFlow</title><style>
body{margin:0;font-family:system-ui,sans-serif;background:#080b12;color:#f6f8fc;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:420px;padding:28px}
h1{font-size:28px;margin:0 0 8px}
p,li{color:#9caac0;font-size:16px;line-height:1.5}
a.b{display:block;text-align:center;margin:26px 0;padding:18px;border-radius:20px;background:linear-gradient(90deg,#478bff,#75c8ff);color:#fff;font-size:19px;font-weight:600;text-decoration:none}
</style></head><body><main>
<h1>SwitchFlow</h1>
<p>Приложение для переноса данных с этого компьютера.<br>The data transfer app from this computer.</p>
<a class="b" href="/SwitchFlow.apk">Скачать · Download</a>
<ol><li>Нажмите «Скачать», затем откройте файл SwitchFlow.apk.</li>
<li>Если Android спросит — разрешите установку из этого источника.</li>
<li>Нажмите «Установить», откройте SwitchFlow и снова нажмите на компьютере «Подключить по кабелю».</li></ol>
</main></body></html>"##
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn get(port: u16, path: &str) -> (String, Vec<u8>) {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.write_all(format!("GET {path} HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes()).unwrap();
        let mut out = Vec::new();
        s.read_to_end(&mut out).unwrap();
        let split = out.windows(4).position(|w| w == b"\r\n\r\n").unwrap();
        (String::from_utf8_lossy(&out[..split]).into_owned(), out[split + 4..].to_vec())
    }

    #[test]
    fn serves_the_app_and_nothing_else() {
        let dir = std::env::temp_dir().join(format!("sf-apk-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let apk = dir.join("SwitchFlow.apk");
        std::fs::write(&apk, b"PK\x03\x04fake").unwrap();
        let port = start(apk).unwrap();
        let (h, body) = get(port, "/SwitchFlow.apk");
        assert!(h.starts_with("HTTP/1.1 200"));
        assert!(h.contains("application/vnd.android.package-archive"));
        assert_eq!(body, b"PK\x03\x04fake");
        let (h, body) = get(port, "/");
        assert!(h.starts_with("HTTP/1.1 200"));
        assert!(String::from_utf8_lossy(&body).contains("/SwitchFlow.apk"));
        assert!(get(port, "/../Cargo.toml").0.starts_with("HTTP/1.1 404"));
        assert!(get(port, "/C:/Windows/win.ini").0.starts_with("HTTP/1.1 404"));
    }
}
