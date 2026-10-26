//! End-to-end protocol test: two simulated companion apps talk to the real HTTPS
//! server over TLS with certificate pinning, exactly as the Android/iOS apps do.
//!
//! These are test clients, not devices: they exist only inside this test binary.

use serde_json::{json, Value};
use std::time::Duration;
use switchflow_lib::core::Core;
use switchflow_lib::engine::StartRequest;
use switchflow_lib::model::{Category, CategorySelection};
use switchflow_lib::session::{SessionKind, SessionState};

mod common;
use common::{device, pinned_client, start_server};

struct Phone {
    http: reqwest::Client,
    base: String,
    token: String,
    after: u64,
}

impl Phone {
    async fn get(&self, path: &str) -> reqwest::Response {
        self.http.get(format!("{}{path}", self.base)).bearer_auth(&self.token).send().await.unwrap()
    }
    async fn post(&self, path: &str, body: Value) -> reqwest::Response {
        self.http.post(format!("{}{path}", self.base)).bearer_auth(&self.token).json(&body).send().await.unwrap()
    }
    async fn put(&self, path: &str, body: Vec<u8>) -> reqwest::Response {
        self.http.put(format!("{}{path}", self.base)).bearer_auth(&self.token).body(body).send().await.unwrap()
    }
    /// Polls commands until one of `kind` arrives.
    async fn wait_for(&mut self, kind: &str) -> Value {
        for _ in 0..20 {
            let r: Value = self.get(&format!("/v1/commands?after={}&wait=2", self.after)).await.json().await.unwrap();
            for c in r["commands"].as_array().unwrap() {
                self.after = self.after.max(c["seq"].as_u64().unwrap());
                if c["type"] == kind {
                    return c["payload"].clone();
                }
            }
        }
        panic!("command {kind} never arrived");
    }
}

async fn pair(core: &Core, base: &str, fp: &str, id: &str, platform: &str) -> Phone {
    let info = core.start_pairing();
    let secret = info.uri.split("&s=").nth(1).unwrap().split('&').next().unwrap().to_string();
    let http = pinned_client(fp);
    let r: Value = http
        .post(format!("{base}/v1/pair"))
        .json(&json!({ "secret": secret, "device": device(id, platform) }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let pid = r["pairingId"].as_str().unwrap().to_string();
    assert_eq!(r["verificationCode"].as_str().unwrap().len(), 6);
    let s: Value = http.get(format!("{base}/v1/pair/{pid}")).send().await.unwrap().json().await.unwrap();
    assert_eq!(s["status"], "pending");
    assert!(s["token"].is_null());
    core.respond_pairing(&pid, true).unwrap();
    let s: Value = http.get(format!("{base}/v1/pair/{pid}")).send().await.unwrap().json().await.unwrap();
    assert_eq!(s["status"], "approved");
    Phone { http, base: base.to_string(), token: s["token"].as_str().unwrap().to_string(), after: 0 }
}

fn sha(data: &[u8]) -> String {
    switchflow_lib::checksum::sha256_bytes(data)
}

fn bytes(n: usize, seed: u8) -> Vec<u8> {
    (0..n).map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed)).collect()
}

async fn upload(phone: &Phone, sid: &str, key: &str, data: &[u8], chunk: usize) {
    let mut off = 0;
    while off < data.len() {
        let end = (off + chunk).min(data.len());
        let r = phone.put(&format!("/v1/sessions/{sid}/items/{key}/chunk?offset={off}"), data[off..end].to_vec()).await;
        assert_eq!(r.status(), 200, "chunk upload");
        off = end;
    }
    let r = phone
        .post(&format!("/v1/sessions/{sid}/items/{key}/complete"), json!({ "size": data.len(), "sha256": sha(data) }))
        .await;
    assert_eq!(r.status(), 200, "complete");
}

/// Pulls items until `n` were handled; `corrupt` makes the target lie about one checksum.
async fn deliver(target: &Phone, sid: &str, n: usize, corrupt: Option<&str>) -> Vec<(String, Vec<u8>)> {
    let mut got = Vec::new();
    while got.len() < n {
        let r: Value = target.get(&format!("/v1/sessions/{sid}/next?wait=5")).await.json().await.unwrap();
        match r["state"].as_str().unwrap() {
            "item" => {}
            "done" => break,
            _ => continue,
        }
        let item = &r["item"];
        let key = item["key"].as_str().unwrap().to_string();
        let size = item["size"].as_u64().unwrap() as usize;
        // Download in two parts to exercise Range resume.
        let cut = size / 3;
        // First part with a bounded range (as the iOS app does), the rest open-ended (as Android does).
        let mut data = if cut > 0 {
            let head = target
                .http
                .get(format!("{}/v1/sessions/{sid}/items/{key}/content", target.base))
                .bearer_auth(&target.token)
                .header("Range", format!("bytes=0-{}", cut - 1))
                .send()
                .await
                .unwrap();
            assert_eq!(head.status(), 206);
            head.bytes().await.unwrap().to_vec()
        } else {
            Vec::new()
        };
        assert_eq!(data.len(), cut);
        let rest = target
            .http
            .get(format!("{}/v1/sessions/{sid}/items/{key}/content", target.base))
            .bearer_auth(&target.token)
            .header("Range", format!("bytes={cut}-"))
            .send()
            .await
            .unwrap();
        if cut > 0 {
            assert_eq!(rest.status(), 206);
        }
        data.extend_from_slice(&rest.bytes().await.unwrap());
        assert_eq!(data.len(), size);
        assert_eq!(sha(&data), item["sha256"].as_str().unwrap(), "PC served the verified bytes");
        let reported = if corrupt == Some(key.as_str()) { "0".repeat(64) } else { sha(&data) };
        let r = target
            .post(
                &format!("/v1/sessions/{sid}/items/{key}/ack"),
                json!({ "result": "saved", "sha256": reported, "verification": "read_back" }),
            )
            .await;
        assert_eq!(r.status(), 204);
        got.push((key, data));
    }
    got
}

async fn wait_state(core: &Core, want: SessionState) {
    for _ in 0..200 {
        if core.snapshot().session.as_ref().map(|s| s.state) == Some(want) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
    panic!("session never reached {want:?}: {:?}", core.snapshot().session.map(|s| (s.state, s.preflight)));
}

async fn prepare_ok(target: &mut Phone) {
    let p = target.wait_for("prepare").await;
    let sid = p["sessionId"].as_str().unwrap();
    let perms: serde_json::Map<String, Value> =
        p["categories"].as_array().unwrap().iter().map(|c| (c.as_str().unwrap().to_string(), json!("granted"))).collect();
    let r = target
        .post("/v1/prepare-result", json!({ "sessionId": sid, "ok": true, "permissions": perms, "storageFreeBytes": 50_000_000_000u64 }))
        .await;
    assert_eq!(r.status(), 204);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn full_transfer_with_recovery() {
    let (core, base, fp, _dir) = start_server().await;

    // Wrong pin: the phone refuses to talk to an impostor.
    let impostor = pinned_client(&"0".repeat(64));
    assert!(impostor.get(format!("{base}/v1/ping")).send().await.is_err());
    // No token: nothing but pairing is reachable.
    let anon = pinned_client(&fp);
    assert_eq!(anon.get(format!("{base}/v1/commands")).send().await.unwrap().status(), 401);

    let mut src = pair(&core, &base, &fp, "android-1", "android").await;
    let mut tgt = pair(&core, &base, &fp, "iphone-1", "ios").await;
    assert_eq!(src.wait_for("role").await["role"], "source");
    assert_eq!(tgt.wait_for("role").await["role"], "target");

    // Scan: only the requested categories reach the phone.
    core.request_scan(vec![Category::Photos, Category::Contacts, Category::Messages]).unwrap();
    let scan = src.wait_for("scan").await;
    assert_eq!(scan["categories"], json!(["photos", "contacts"]));

    let big = bytes(5 * 1024 * 1024 + 123, 1);
    let small = bytes(70_000, 2);
    let lost = bytes(1000, 3);
    let vcf = b"BEGIN:VCARD\r\nVERSION:3.0\r\nFN:A\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:B\r\nEND:VCARD\r\n".to_vec();
    let inv = json!({ "categories": [
        { "category": "photos", "permission": "granted", "count": 3, "totalBytes": 0,
          "albums": [{ "id": "cam", "name": "Camera", "count": 3, "totalBytes": 0 }],
          "items": [
            { "id": "p/1", "category": "photos", "name": "IMG_1.jpg", "size": big.len(), "mime": "image/jpeg", "albumIds": ["cam"] },
            { "id": "p/2", "category": "photos", "name": "IMG_2.jpg", "size": small.len(), "mime": "image/jpeg", "albumIds": ["cam"] },
            { "id": "p/3", "category": "photos", "name": "IMG_3.jpg", "size": lost.len(), "mime": "image/jpeg", "albumIds": ["cam"] }
          ] },
        { "category": "contacts", "permission": "granted", "count": 2, "totalBytes": 0,
          "items": [{ "id": "contacts", "category": "contacts", "name": "contacts.vcf", "size": vcf.len(), "mime": "text/vcard", "itemCount": 2 }] }
    ]});
    assert_eq!(src.post("/v1/inventory", inv).await.status(), 204);
    let snap = core.snapshot();
    let photos = snap.devices[0].inventory.iter().find(|c| c.category == Category::Photos).unwrap();
    assert_eq!(photos.total_bytes, (big.len() + small.len() + lost.len()) as u64, "PC recomputes totals itself");

    let sid = core
        .start_session(StartRequest {
            kind: SessionKind::Transfer,
            selections: vec![
                CategorySelection { category: Category::Photos, album_ids: None, entry_ids: None },
                CategorySelection { category: Category::Contacts, album_ids: None, entry_ids: None },
            ],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: false,
        })
        .unwrap();
    wait_state(&core, SessionState::Preparing).await;
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;

    let up = src.wait_for("upload").await;
    let keys: Vec<(String, String)> = up["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| (i["key"].as_str().unwrap().into(), i["sourceId"].as_str().unwrap().into()))
        .collect();
    assert_eq!(keys.len(), 4);
    let key_of = |id: &str| keys.iter().find(|(_, s)| s == id).unwrap().0.clone();

    // Big file: interrupted upload, then resumed from the offset the PC reports.
    let k1 = key_of("p/1");
    let r = src.put(&format!("/v1/sessions/{sid}/items/{k1}/chunk?offset=0"), big[..2 * 1024 * 1024].to_vec()).await;
    assert_eq!(r.status(), 200);
    let st: Value = src.get(&format!("/v1/sessions/{sid}/items/{k1}/upload-status")).await.json().await.unwrap();
    assert_eq!(st["receivedBytes"], 2 * 1024 * 1024);
    let r = src.put(&format!("/v1/sessions/{sid}/items/{k1}/chunk?offset=0"), big[..100].to_vec()).await;
    assert_eq!(r.status(), 409, "wrong offset is rejected");
    let off = 2 * 1024 * 1024;
    for (i, part) in big[off..].chunks(1024 * 1024).enumerate() {
        let o = off + i * 1024 * 1024;
        assert_eq!(src.put(&format!("/v1/sessions/{sid}/items/{k1}/chunk?offset={o}"), part.to_vec()).await.status(), 200);
    }
    let r = src.post(&format!("/v1/sessions/{sid}/items/{k1}/complete"), json!({ "size": big.len(), "sha256": sha(&big) })).await;
    assert_eq!(r.status(), 200);

    // Corrupted upload is detected by the PC and restarted from zero.
    let k2 = key_of("p/2");
    let mut bad = small.clone();
    bad[10] ^= 0xff;
    assert_eq!(src.put(&format!("/v1/sessions/{sid}/items/{k2}/chunk?offset=0"), bad).await.status(), 200);
    let r = src.post(&format!("/v1/sessions/{sid}/items/{k2}/complete"), json!({ "size": small.len(), "sha256": sha(&small) })).await;
    assert_eq!(r.status(), 422);
    upload(&src, &sid, &k2, &small, 32 * 1024).await;

    // The source cannot read one photo (e.g. iCloud-only).
    let k3 = key_of("p/3");
    assert_eq!(src.post(&format!("/v1/sessions/{sid}/items/{k3}/fail"), json!({ "error": "icloud_unavailable" })).await.status(), 204);
    upload(&src, &sid, &key_of("contacts"), &vcf, 4096).await;

    // Target receives three items; it reports a wrong checksum for the small photo.
    let delivered = deliver(&tgt, &sid, 3, Some(&k2)).await;
    assert_eq!(delivered.len(), 3);
    wait_state(&core, SessionState::Completed).await;
    let s = core.snapshot().session.unwrap();
    assert_eq!(s.totals.delivered_items, 2);
    assert_eq!(s.totals.failed_items, 2);
    let reasons: Vec<&str> = s.failed_items.iter().map(|f| f.reason.as_str()).collect();
    assert!(reasons.contains(&"checksum_mismatch") && reasons.contains(&"icloud_unavailable"));
    let contacts = s.categories.iter().find(|c| c.category == Category::Contacts).unwrap();
    assert_eq!(contacts.logical_total, 2);

    // Retry only the failed ones.
    assert_eq!(core.retry_failed().unwrap(), 2);
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;
    let up = src.wait_for("upload").await;
    assert_eq!(up["items"].as_array().unwrap().len(), 1, "only the unread photo is uploaded again");
    upload(&src, &sid, &k3, &lost, 1000).await;
    let again = deliver(&tgt, &sid, 2, None).await;
    assert_eq!(again.len(), 2);
    wait_state(&core, SessionState::Completed).await;
    let s = core.snapshot().session.unwrap();
    assert_eq!((s.totals.delivered_items, s.totals.failed_items), (4, 0));
    assert_eq!(s.totals.verified_items, 4);

    let history = core.history();
    assert_eq!(history.len(), 1);
    assert_eq!(history[0].outcome, "completed");
    core.dismiss_session().unwrap();

    // Repeating the same transfer skips everything already delivered (ledger).
    core.start_session(StartRequest {
        kind: SessionKind::Transfer,
        selections: vec![CategorySelection { category: Category::Photos, album_ids: Some(vec!["cam".into()]), entry_ids: None }],
        contacts_policy: None,
        backup_path: None,
        include_previously_transferred: false,
    })
    .unwrap();
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Completed).await;
    let s = core.snapshot().session.unwrap();
    assert_eq!(s.totals.skipped_items, 3);
    assert_eq!(s.skipped_reasons.get("already_transferred"), Some(&3));
    core.dismiss_session().unwrap();

    // Pause blocks uploads, resume lets them continue; a lost device pauses the session.
    let sid = core
        .start_session(StartRequest {
            kind: SessionKind::Transfer,
            selections: vec![CategorySelection { category: Category::Photos, album_ids: None, entry_ids: None }],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: true,
        })
        .unwrap();
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;
    let up = src.wait_for("upload").await;
    let k = up["items"][0]["key"].as_str().unwrap().to_string();
    core.pause().unwrap();
    assert_eq!(src.put(&format!("/v1/sessions/{sid}/items/{k}/chunk?offset=0"), vec![1, 2, 3]).await.status(), 423);
    core.resume().unwrap();
    assert_eq!(src.put(&format!("/v1/sessions/{sid}/items/{k}/chunk?offset=0"), vec![1, 2, 3]).await.status(), 200);
    core.on_device_lost("iphone-1");
    wait_state(&core, SessionState::WaitingReconnect).await;
    let hb = json!({ "storageFreeBytes": 1, "storageTotalBytes": 2, "batteryPercent": 50, "charging": true, "appState": "foreground" });
    // Heartbeat alone does not mark it reconnected because the device was never marked lost by liveness;
    // resume restores the run once both phones are reachable.
    assert_eq!(tgt.post("/v1/heartbeat", hb).await.status(), 200);
    core.resume().unwrap();
    wait_state(&core, SessionState::Running).await;
    core.cancel().unwrap();
    wait_state(&core, SessionState::Cancelled).await;
    assert_eq!(core.history()[0].outcome, "cancelled");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_picked_contacts_reach_the_target() {
    let (core, base, fp, _dir) = start_server().await;
    let mut src = pair(&core, &base, &fp, "android-4", "android").await;
    let mut tgt = pair(&core, &base, &fp, "ios-4", "ios").await;
    src.wait_for("role").await;
    tgt.wait_for("role").await;

    core.request_scan(vec![Category::Contacts]).unwrap();
    src.wait_for("scan").await;
    let vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Личный Контакт\r\nTEL:+70000000000\r\nEND:VCARD\r\n\
               BEGIN:VCARD\r\nVERSION:3.0\r\nFN:SwitchFlow Test Алия\r\nTEL:+77030000001\r\nEND:VCARD\r\n\
               BEGIN:VCARD\r\nVERSION:3.0\r\nFN:SwitchFlow Test Бахыт\r\nTEL:+77030000002\r\nEND:VCARD\r\n"
        .as_bytes()
        .to_vec();
    let inv = json!({ "categories": [{ "category": "contacts", "permission": "granted", "count": 3, "totalBytes": 0,
        "items": [{ "id": "contacts", "category": "contacts", "name": "contacts.vcf", "size": vcf.len(), "mime": "text/vcard", "itemCount": 3 }],
        "entries": [
            { "id": "0", "name": "Личный Контакт", "detail": "+70000000000" },
            { "id": "1", "name": "SwitchFlow Test Алия", "detail": "+77030000001" },
            { "id": "2", "name": "SwitchFlow Test Бахыт" }
        ] }]});
    src.post("/v1/inventory", inv).await;
    assert_eq!(core.entries("android-4", Category::Contacts).unwrap().len(), 3);
    let summary = core.snapshot().devices.into_iter().find(|d| d.info.id == "android-4").unwrap().inventory;
    assert_eq!(summary[0].entry_count, 3);

    let sid = core
        .start_session(StartRequest {
            kind: SessionKind::Transfer,
            selections: vec![CategorySelection { category: Category::Contacts, album_ids: None, entry_ids: Some(vec!["1".into(), "2".into()]) }],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: false,
        })
        .unwrap();
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;
    let contacts = core.snapshot().session.unwrap().categories.into_iter().find(|c| c.category == Category::Contacts).unwrap();
    assert_eq!(contacts.logical_total, 2, "progress counts the picked contacts, not the whole book");

    let up = src.wait_for("upload").await;
    let key = up["items"][0]["key"].as_str().unwrap().to_string();
    upload(&src, &sid, &key, &vcf, 64).await;
    let got = deliver(&tgt, &sid, 1, None).await;
    let text = String::from_utf8(got[0].1.clone()).unwrap();
    assert!(text.contains("SwitchFlow Test Алия") && text.contains("SwitchFlow Test Бахыт"));
    assert!(!text.contains("Личный Контакт"), "a contact that was not picked never leaves the PC");
    assert_eq!(text.matches("BEGIN:VCARD").count(), 2);
    wait_state(&core, SessionState::Completed).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn backup_then_restore() {
    let (core, base, fp, dir) = start_server().await;
    let mut src = pair(&core, &base, &fp, "android-2", "android").await;
    let mut tgt = pair(&core, &base, &fp, "android-3", "android").await;
    src.wait_for("role").await;
    tgt.wait_for("role").await;

    core.request_scan(vec![Category::Photos]).unwrap();
    src.wait_for("scan").await;
    let a = bytes(300_000, 9);
    let b = bytes(1_000, 10);
    let inv = json!({ "categories": [{ "category": "photos", "permission": "granted", "count": 2, "totalBytes": 0,
        "albums": [{ "id": "x", "name": "Trip: Almaty/2025", "count": 2, "totalBytes": 0 }],
        "items": [
            { "id": "1", "category": "photos", "name": "same.jpg", "size": a.len(), "albumIds": ["x"] },
            { "id": "2", "category": "photos", "name": "same.jpg", "size": b.len(), "albumIds": ["x"] }
        ] }]});
    src.post("/v1/inventory", inv).await;

    let sid = core
        .start_session(StartRequest {
            kind: SessionKind::Backup,
            selections: vec![CategorySelection { category: Category::Photos, album_ids: None, entry_ids: None }],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: false,
        })
        .unwrap();
    wait_state(&core, SessionState::Running).await;
    let up = src.wait_for("upload").await;
    for (i, data) in [&a, &b].into_iter().enumerate() {
        let key = up["items"][i]["key"].as_str().unwrap().to_string();
        upload(&src, &sid, &key, data, 64 * 1024).await;
    }
    wait_state(&core, SessionState::Completed).await;
    let backup_dir = core.snapshot().session.unwrap().backup_path.unwrap();
    assert!(backup_dir.starts_with(dir.path().join("Documents").join("SwitchFlow Backups")));
    // Same names do not overwrite each other; album names are made safe for Windows.
    let album = backup_dir.join("Photos").join("Trip_ Almaty_2025");
    assert!(album.join("same.jpg").is_file());
    assert!(album.join("same (2).jpg").is_file());
    core.dismiss_session().unwrap();

    let backups = switchflow_lib::backup::list_backups(&dir.path().join("Documents").join("SwitchFlow Backups"));
    assert_eq!(backups.len(), 1);
    assert_eq!(backups[0].counts.get(&Category::Photos), Some(&2));

    core.start_session(StartRequest {
        kind: SessionKind::Restore,
        selections: vec![CategorySelection { category: Category::Photos, album_ids: None, entry_ids: None }],
        contacts_policy: None,
        backup_path: Some(backups[0].path.clone()),
        include_previously_transferred: false,
    })
    .unwrap();
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;
    let sid = core.snapshot().session.unwrap().id;
    let got = deliver(&tgt, &sid, 2, None).await;
    let mut sizes: Vec<usize> = got.iter().map(|(_, d)| d.len()).collect();
    sizes.sort();
    assert_eq!(sizes, vec![b.len(), a.len()]);
    wait_state(&core, SessionState::Completed).await;
    // Restoring never deletes the backup.
    assert!(album.join("same.jpg").is_file());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn app_restart_gets_its_work_again() {
    // Found on real Samsung phones: Android kills the companion mid-transfer. The restarted app
    // has already acknowledged the upload command, so the PC must hand the work out again.
    let (core, base, fp, _dir) = start_server().await;
    let mut src = pair(&core, &base, &fp, "src-restart", "android").await;
    let mut tgt = pair(&core, &base, &fp, "tgt-restart", "android").await;
    src.wait_for("role").await;
    tgt.wait_for("role").await;
    let hb = |instance: &str| json!({ "storageFreeBytes": 1, "storageTotalBytes": 2, "batteryPercent": 50, "charging": true, "appState": "foreground", "instanceId": instance });
    assert_eq!(src.post("/v1/heartbeat", hb("process-1")).await.status(), 200);

    core.request_scan(vec![Category::Photos]).unwrap();
    src.wait_for("scan").await;
    // MediaStore said 100 bytes, but the photo was edited after the scan and is now larger.
    let real = bytes(150_000, 7);
    src.post("/v1/inventory", json!({ "categories": [{ "category": "photos", "permission": "granted", "count": 1, "totalBytes": 0,
        "items": [{ "id": "edited", "category": "photos", "name": "IMG.jpg", "size": 100, "mime": "image/jpeg" }] }]}))
        .await;
    let sid = core
        .start_session(StartRequest {
            kind: SessionKind::Transfer,
            selections: vec![CategorySelection { category: Category::Photos, album_ids: None, entry_ids: None }],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: false,
        })
        .unwrap();
    prepare_ok(&mut tgt).await;
    wait_state(&core, SessionState::Running).await;
    let first = src.wait_for("upload").await;
    let key = first["items"][0]["key"].as_str().unwrap().to_string();

    // The app dies after taking the command; it comes back with a new process id and no queue.
    assert_eq!(src.post("/v1/heartbeat", hb("process-2")).await.status(), 200);
    let again = src.wait_for("upload").await;
    assert_eq!(again["items"][0]["key"], key, "the restarted app receives its upload list again");

    upload(&src, &sid, &key, &real, 64 * 1024).await;
    let got = deliver(&tgt, &sid, 1, None).await;
    assert_eq!(got[0].1.len(), real.len(), "a file that grew since the scan is still transferred whole");
    wait_state(&core, SessionState::Completed).await;
}
