//! Cloud end-to-end test host for the iOS companion (see .github/workflows/ios.yml, job `e2e`).
//!
//! Runs the real SwitchFlow core and HTTPS server without a window and plays the user at the PC:
//! approves pairing, then
//!   A. iPhone → PC: backs up the simulator's photos and contacts and checks them byte by byte;
//!   B. PC → iPhone: restores new photos and two new contacts into the simulator;
//!   C. repeats B and expects everything to be skipped as duplicates.
//! Exits non-zero with a readable reason on the first failed expectation.
//!
//! Usage: ios_e2e_host --out <dir> --expect-photos <dir> --restore-photos <dir>

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use switchflow_lib::backup::{self, BackupDevice, Manifest, ManifestItem};
use switchflow_lib::checksum::sha256_file;
use switchflow_lib::core::{Core, Paths};
use switchflow_lib::engine::StartRequest;
use switchflow_lib::model::{now_ms, Category, CategorySelection, ContactsPolicy, Platform};
use switchflow_lib::session::{SessionKind, SessionState};
use switchflow_lib::snapshot::SessionView;

type R<T> = Result<T, String>;

macro_rules! check {
    ($cond:expr, $($msg:tt)+) => {
        if !$cond {
            return Err(format!($($msg)+));
        }
    };
}

fn arg(name: &str) -> PathBuf {
    let args: Vec<String> = std::env::args().collect();
    let i = args.iter().position(|a| a == name).unwrap_or_else(|| panic!("missing {name}"));
    PathBuf::from(&args[i + 1])
}

fn log(msg: impl AsRef<str>) {
    println!("[e2e {:>6.1}s] {}", START.elapsed().as_secs_f64(), msg.as_ref());
}

static START: std::sync::LazyLock<Instant> = std::sync::LazyLock::new(Instant::now);

async fn wait_for<T>(what: &str, timeout_s: u64, mut f: impl FnMut() -> Option<T>) -> R<T> {
    let deadline = Instant::now() + Duration::from_secs(timeout_s);
    loop {
        if let Some(v) = f() {
            return Ok(v);
        }
        if Instant::now() > deadline {
            return Err(format!("timed out after {timeout_s}s waiting for {what}"));
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

fn sha_set(dir: &Path) -> R<HashMap<String, String>> {
    let mut out = HashMap::new();
    for e in std::fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))?.flatten() {
        if e.path().is_file() {
            let (sha, _) = sha256_file(&e.path()).map_err(|e| e.to_string())?;
            out.insert(sha, e.file_name().to_string_lossy().into_owned());
        }
    }
    Ok(out)
}

async fn run_session(core: &Core, req: StartRequest, timeout_s: u64) -> R<SessionView> {
    let kind = req.kind;
    core.start_session(req).map_err(|e| format!("start_session({kind:?}): {e:?}"))?;
    let mut last = String::new();
    let view = wait_for(&format!("{kind:?} session to finish"), timeout_s, || {
        let s = core.snapshot().session?;
        let line = format!(
            "{:?}: {} delivered, {} skipped, {} failed of {}; preflight {:?}",
            s.state,
            s.totals.delivered_items,
            s.totals.skipped_items,
            s.totals.failed_items,
            s.totals.total_items,
            s.preflight.iter().map(|p| format!("{}={:?}", p.id, p.status)).collect::<Vec<_>>()
        );
        if line != last {
            log(&line);
            last = line;
        }
        s.state.is_finished().then_some(s)
    })
    .await?;
    check!(view.state == SessionState::Completed, "{kind:?} ended as {:?}", view.state);
    Ok(view)
}

fn restore_source(dir: &Path, photos: &Path) -> R<PathBuf> {
    let root = dir.join("restore-source");
    std::fs::create_dir_all(root.join("Photos")).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(root.join("Contacts")).map_err(|e| e.to_string())?;
    let mut items = Vec::new();
    for e in std::fs::read_dir(photos).map_err(|e| e.to_string())?.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let dest = root.join("Photos").join(&name);
        std::fs::copy(e.path(), &dest).map_err(|e| e.to_string())?;
        let (sha, size) = sha256_file(&dest).map_err(|e| e.to_string())?;
        items.push(ManifestItem {
            category: Category::Photos,
            path: format!("Photos/{name}"),
            name,
            size,
            sha256: sha,
            mime: "image/jpeg".into(),
            created_ms: Some(1_700_000_000_000),
            modified_ms: None,
            album_name: Some("SwitchFlow CI".into()),
            item_count: None,
        });
    }
    // Two contacts the simulator does not have, written the way the Android app writes them.
    let vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:SwitchFlow CI Alpha\r\nN:Alpha;SwitchFlow CI;;;\r\nTEL;TYPE=CELL:+77010000001\r\nEMAIL;TYPE=INTERNET,HOME:alpha@switchflow.test\r\nEND:VCARD\r\n\
               BEGIN:VCARD\r\nVERSION:3.0\r\nFN:SwitchFlow CI Beta\r\nN:Beta;SwitchFlow CI;;;\r\nTEL;TYPE=WORK:+77010000002\r\nEND:VCARD\r\n";
    let vcf_path = root.join("Contacts/contacts.vcf");
    std::fs::write(&vcf_path, vcf).map_err(|e| e.to_string())?;
    let (sha, size) = sha256_file(&vcf_path).map_err(|e| e.to_string())?;
    items.push(ManifestItem {
        category: Category::Contacts,
        path: "Contacts/contacts.vcf".into(),
        name: "contacts.vcf".into(),
        size,
        sha256: sha,
        mime: "text/vcard".into(),
        created_ms: None,
        modified_ms: None,
        album_name: None,
        item_count: Some(2),
    });
    let manifest = Manifest {
        version: 1,
        created_ms: now_ms(),
        device: BackupDevice {
            name: "CI Android".into(),
            model: "CI Android".into(),
            manufacturer: "CI".into(),
            platform: Platform::Android,
            os_version: "15".into(),
        },
        items,
    };
    switchflow_lib::persist::write_json_atomic(&root.join(backup::MANIFEST), &manifest).map_err(|e| e.to_string())?;
    Ok(root)
}

async fn scenario(core: Arc<Core>, out: PathBuf) -> R<serde_json::Value> {
    let expect = sha_set(&arg("--expect-photos"))?;
    log(format!("{} test photos are expected in the simulator library", expect.len()));

    // Pairing: the workflow launches the app with this URI (the QR code's content).
    let info = core.start_pairing();
    std::fs::write(out.join("pair-uri.txt"), &info.uri).map_err(|e| e.to_string())?;
    log(format!("pairing URI written, port {}", info.port));
    let req = wait_for("the iPhone app to request pairing", 300, || core.snapshot().pair_requests.first().cloned()).await?;
    log(format!(
        "pairing request: model={} code={} name={} os={} via_usb={}",
        req.device.model, req.code, req.device.name, req.device.os_version, req.via_usb
    ));
    check!(req.device.platform == Platform::Ios, "request came from {:?}", req.device.platform);
    check!(req.code.len() == 6, "bad verification code");
    core.respond_pairing(&req.id, true).map_err(|e| format!("approve: {e:?}"))?;
    let device_id = req.device.id.clone();
    wait_for("the iPhone to connect", 60, || {
        core.snapshot().devices.iter().find(|d| d.info.id == device_id && d.connected).map(|_| ())
    })
    .await?;
    log("iPhone connected over TLS with the pinned certificate");

    // A. iPhone → PC backup.
    core.request_scan(vec![Category::Photos, Category::Contacts]).map_err(|e| format!("scan: {e:?}"))?;
    let inv = wait_for("photos and contacts to be counted on the iPhone", 180, || {
        let s = core.snapshot();
        let d = s.devices.iter().find(|d| d.info.id == device_id)?;
        (d.scanning.is_empty() && d.inventory.len() >= 2).then(|| d.inventory.clone())
    })
    .await?;
    for c in &inv {
        log(format!("inventory {:?}: {:?}, {} items, {} bytes", c.category, c.permission, c.count, c.total_bytes));
    }
    let photos = inv.iter().find(|c| c.category == Category::Photos).ok_or("no photo inventory")?;
    let contacts = inv.iter().find(|c| c.category == Category::Contacts).ok_or("no contact inventory")?;
    check!(photos.count as usize >= expect.len(), "iPhone reports {} photos, expected at least {}", photos.count, expect.len());
    check!(contacts.count > 0, "iPhone reports no contacts");

    let backup = run_session(
        &core,
        StartRequest {
            kind: SessionKind::Backup,
            selections: vec![
                CategorySelection { category: Category::Photos, album_ids: None },
                CategorySelection { category: Category::Contacts, album_ids: None },
            ],
            contacts_policy: None,
            backup_path: None,
            include_previously_transferred: false,
        },
        600,
    )
    .await?;
    check!(backup.totals.failed_items == 0, "backup failed items: {:?}", backup.failed_items);
    let folder = backup.backup_path.clone().ok_or("backup has no folder")?;
    let manifest = backup::read_manifest(&folder).map_err(|e| format!("manifest: {e:?}"))?;
    let mut verified = HashSet::new();
    for item in &manifest.items {
        let (sha, _) = sha256_file(&folder.join(&item.path)).map_err(|e| e.to_string())?;
        check!(sha == item.sha256, "{} on the PC differs from the iPhone's checksum", item.path);
        if expect.contains_key(&sha) {
            verified.insert(sha);
        }
    }
    let missing: Vec<&String> = expect.iter().filter(|(sha, _)| !verified.contains(*sha)).map(|(_, n)| n).collect();
    check!(missing.is_empty(), "test photos not found byte-identical in the backup: {missing:?}");
    let vcf = manifest.items.iter().find(|i| i.category == Category::Contacts).ok_or("no contacts.vcf in backup")?;
    let text = std::fs::read_to_string(folder.join(&vcf.path)).map_err(|e| e.to_string())?;
    let cards = text.matches("BEGIN:VCARD").count();
    check!(Some(cards as u32) == vcf.item_count, "contacts.vcf has {cards} cards, iPhone said {:?}", vcf.item_count);
    log(format!("A passed: {} files backed up, all {} test photos byte-identical, {cards} contacts", manifest.items.len(), expect.len()));
    core.dismiss_session().map_err(|e| format!("{e:?}"))?;

    // B. PC → iPhone restore of new photos and contacts.
    let restore_dir = restore_source(&out, &arg("--restore-photos"))?;
    core.set_roles(None, Some(device_id.clone())).map_err(|e| format!("set_roles: {e:?}"))?;
    let restore_req = || StartRequest {
        kind: SessionKind::Restore,
        selections: vec![
            CategorySelection { category: Category::Photos, album_ids: None },
            CategorySelection { category: Category::Contacts, album_ids: None },
        ],
        contacts_policy: Some(ContactsPolicy::Merge),
        backup_path: Some(restore_dir.clone()),
        include_previously_transferred: false,
    };
    let restored = run_session(&core, restore_req(), 600).await?;
    check!(restored.totals.failed_items == 0, "restore failed items: {:?}", restored.failed_items);
    check!(restored.totals.delivered_items == restored.totals.total_items, "restore delivered {} of {}", restored.totals.delivered_items, restored.totals.total_items);
    check!(restored.totals.verified_items == restored.totals.total_items, "not every restored item was checksum-confirmed");
    let restored_contacts = restored.categories.iter().find(|c| c.category == Category::Contacts).ok_or("no contacts progress")?;
    check!(restored_contacts.logical_delivered == 2, "iPhone imported {} new contacts, expected 2", restored_contacts.logical_delivered);
    log(format!("B passed: {} items restored to the iPhone and confirmed by SHA-256, 2 contacts imported", restored.totals.delivered_items));
    core.dismiss_session().map_err(|e| format!("{e:?}"))?;

    // C. Same restore again: nothing may be written twice.
    let again = run_session(&core, restore_req(), 300).await?;
    check!(again.totals.delivered_items == 0, "repeat restore wrote {} items again", again.totals.delivered_items);
    check!(again.totals.skipped_items == again.totals.total_items, "repeat restore skipped {} of {}", again.totals.skipped_items, again.totals.total_items);
    log(format!("C passed: repeat restore skipped all {} items as duplicates", again.totals.skipped_items));

    Ok(serde_json::json!({
        "device": { "model": req.device.model, "modelCode": req.device.model_code, "os": req.device.os_version },
        "backup": { "files": manifest.items.len(), "testPhotosVerified": expect.len(), "contacts": cards },
        "restore": { "delivered": restored.totals.delivered_items, "contactsImported": restored_contacts.logical_delivered },
        "repeat": { "skipped": again.totals.skipped_items },
    }))
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() {
    let _ = *START;
    let out = arg("--out");
    std::fs::create_dir_all(&out).expect("out dir");
    let core = Arc::new(Core::new(Paths::new(out.join("data"), Some(out.join("Documents"))), "CI-MAC".into()));
    let identity = switchflow_lib::tls::generate().expect("tls identity");
    tokio::spawn(switchflow_lib::server::serve(core.clone(), identity));
    switchflow_lib::spawn_background(core.clone());
    if let Err(e) = wait_for("the HTTPS server", 30, || core.snapshot().server.running.then_some(())).await {
        eprintln!("FAILED: {e}");
        std::process::exit(1);
    }
    match scenario(core, out.clone()).await {
        Ok(summary) => {
            let text = serde_json::to_string_pretty(&summary).unwrap();
            std::fs::write(out.join("summary.json"), &text).ok();
            println!("E2E PASSED\n{text}");
        }
        Err(e) => {
            eprintln!("E2E FAILED: {e}");
            std::process::exit(1);
        }
    }
}
