//! The cable link end to end, without a phone: the PC side is the real link (cable.rs) over a TCP
//! "cable" — the transport the iPhone uses through usbmuxd — and the other end plays the phone app
//! exactly as CableLink.kt / CableLink.swift do: READY, HELLO with the pairing data, a local port
//! whose connections become streams. Real HTTPS with certificate pinning then runs through it.

mod common;

use common::{device, pinned_client, start_server};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use switchflow_lib::cable::{self, Decoder, Kind, TcpTransport};

/// The phone side of the cable: returns the HELLO pairing URI and the local port the app's HTTP
/// client connects to.
fn fake_phone_app(cable_end: TcpStream) -> (String, u16) {
    let writer = Arc::new(Mutex::new(cable_end.try_clone().unwrap()));
    let send = {
        let writer = writer.clone();
        move |frame: Vec<u8>| writer.lock().unwrap().write_all(&frame).unwrap()
    };
    send(cable::encode(Kind::Ready, 0, br#"{"v":1}"#));

    let local = TcpListener::bind("127.0.0.1:0").unwrap();
    let local_port = local.local_addr().unwrap().port();
    let sockets: Arc<Mutex<HashMap<u32, TcpStream>>> = Arc::default();
    let (hello_tx, hello_rx) = std::sync::mpsc::channel::<String>();

    // Cable → local connections.
    {
        let (sockets, mut cable_end) = (sockets.clone(), cable_end);
        std::thread::spawn(move || {
            let mut d = Decoder::default();
            let mut buf = vec![0u8; 16_384];
            loop {
                let n = match cable_end.read(&mut buf) {
                    Ok(0) | Err(_) => return,
                    Ok(n) => n,
                };
                d.push(&buf[..n]);
                while let Some(f) = d.next().unwrap() {
                    match f.kind {
                        Kind::Hello => {
                            let v: Value = serde_json::from_slice(&f.payload).unwrap();
                            hello_tx.send(v["uri"].as_str().unwrap().to_string()).unwrap();
                        }
                        Kind::Data => {
                            if let Some(s) = sockets.lock().unwrap().get_mut(&f.stream) {
                                let _ = s.write_all(&f.payload);
                            }
                        }
                        Kind::Close => {
                            if let Some(s) = sockets.lock().unwrap().remove(&f.stream) {
                                let _ = s.shutdown(std::net::Shutdown::Both);
                            }
                        }
                        _ => {}
                    }
                }
            }
        });
    }
    // Local connections → cable.
    std::thread::spawn(move || {
        for (id, conn) in local.incoming().enumerate() {
            let id = id as u32 + 1;
            let mut conn = conn.unwrap();
            sockets.lock().unwrap().insert(id, conn.try_clone().unwrap());
            send(cable::encode(Kind::Open, id, &[]));
            let send = send.clone();
            std::thread::spawn(move || {
                let mut buf = vec![0u8; 64 * 1024];
                loop {
                    match conn.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => cable::data_frames(id, &buf[..n]).into_iter().for_each(&send),
                    }
                }
                send(cable::encode(Kind::Close, id, &[]));
            });
        }
    });
    (hello_rx.recv_timeout(std::time::Duration::from_secs(10)).expect("HELLO from the PC"), local_port)
}

fn query(uri: &str, key: &str) -> String {
    uri.split(['?', '&']).find_map(|p| p.strip_prefix(&format!("{key}="))).unwrap().to_string()
}

#[tokio::test(flavor = "multi_thread")]
async fn phone_pairs_and_talks_over_the_cable_without_a_qr_code() {
    let (core, _base, fp, _dir) = start_server().await;

    // The "cable": the PC end runs the real link, the other end is the phone app.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let phone_end = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let pc_end = listener.accept().unwrap().0;
    {
        let core = core.clone();
        std::thread::spawn(move || cable::run_link(Arc::new(TcpTransport::new(pc_end).unwrap()), &core));
    }
    let (uri, local_port) = tokio::task::spawn_blocking(move || fake_phone_app(phone_end)).await.unwrap();

    // HELLO carries what the QR code carries: the certificate fingerprint and a one-time secret.
    assert_eq!(query(&uri, "fp"), fp, "pin comes over the cable");
    let secret = query(&uri, "s");

    // Everything below goes through the cable: TLS is end to end, pinned to the PC's certificate.
    let base = format!("https://127.0.0.1:{local_port}");
    let http = pinned_client(&fp);
    let r: Value = http.post(format!("{base}/v1/pair")).json(&json!({ "secret": secret, "device": device("cable-phone", "ios") })).send().await.unwrap().json().await.unwrap();
    let pid = r["pairingId"].as_str().unwrap().to_string();
    let req = core.snapshot().pair_requests.into_iter().find(|r| r.id == pid).unwrap();
    assert!(req.via_usb, "the PC sees the request as coming over the cable");
    core.respond_pairing(&pid, true).unwrap();
    let s: Value = http.get(format!("{base}/v1/pair/{pid}")).send().await.unwrap().json().await.unwrap();
    let token = s["token"].as_str().unwrap().to_string();

    // A request far larger than one frame, and its effect on the PC.
    let items: Vec<Value> = (0..3000)
        .map(|i| json!({ "id": format!("p/{i}"), "category": "photos", "name": format!("IMG_{i:05}.jpg"), "size": 1000 + i, "mime": "image/jpeg" }))
        .collect();
    let inv = json!({ "categories": [{ "category": "photos", "permission": "granted", "count": 3000, "totalBytes": 0, "items": items }] });
    let body = serde_json::to_vec(&inv).unwrap();
    assert!(body.len() > 200_000, "spans many 16 KiB frames");
    let st = http.post(format!("{base}/v1/inventory")).bearer_auth(&token).header("content-type", "application/json").body(body).send().await.unwrap().status();
    assert_eq!(st, 204);
    let snap = core.snapshot();
    let phone = snap.devices.iter().find(|d| d.info.id == "cable-phone").unwrap();
    assert_eq!(phone.inventory.iter().find(|c| c.category == switchflow_lib::model::Category::Photos).unwrap().count, 3000);

    // Commands (long-poll) come back through the cable too.
    let c: Value = http.get(format!("{base}/v1/commands?after=0&wait=2")).bearer_auth(&token).send().await.unwrap().json().await.unwrap();
    assert!(c["commands"].as_array().unwrap().iter().any(|c| c["type"] == "role"));
}
