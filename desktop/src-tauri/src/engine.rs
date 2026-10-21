//! TransferEngine + ErrorRecovery.
//!
//! The PC is a verifying relay: the source uploads each file in chunks, the PC checks
//! its SHA-256, the target downloads it (with resume), saves it through the OS APIs
//! and reports the SHA-256 of what it saved. Originals on the source are never touched.

use crate::backup;
use crate::compatibility::{self, CompatStatus};
use crate::core::{Core, State};
use crate::error::{FResult, Fault};
use crate::history::{CategoryResult, HistoryEntry, Ledger};
use crate::model::*;
use crate::session::*;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::time::{Duration, Instant};

pub const MAX_CHUNK: u64 = 16 * 1024 * 1024;
const UPLOAD_BATCH: usize = 2000;
const MAX_BUFFER: u64 = 4 * 1024 * 1024 * 1024;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRequest {
    pub kind: SessionKind,
    pub selections: Vec<CategorySelection>,
    #[serde(default)]
    pub contacts_policy: Option<ContactsPolicy>,
    /// Restore: the backup folder to read from.
    #[serde(default)]
    pub backup_path: Option<PathBuf>,
    /// Re-send items that already reached this target in an earlier session.
    #[serde(default)]
    pub include_previously_transferred: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AckBody {
    pub result: String,
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub verification: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub detail: Option<AckDetail>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeliveryItem {
    pub key: String,
    pub category: Category,
    pub name: String,
    pub size: u64,
    pub sha256: String,
    pub mime: String,
    pub created_ms: Option<i64>,
    pub modified_ms: Option<i64>,
    pub album_name: Option<String>,
    pub item_count: Option<u32>,
    pub contacts_policy: ContactsPolicy,
}

pub enum NextResult {
    Item(DeliveryItem),
    Wait,
    Paused,
    Done,
    Cancelled,
}

fn sanitize_error_code(code: Option<String>) -> String {
    code.map(|c| c.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_').take(40).collect::<String>())
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| "unknown".into())
}

impl Core {
    // ------------------------------------------------------------ start

    pub fn start_session(&self, req: StartRequest) -> FResult<String> {
        let staging_root = self.staging_root();
        let backup_root = self.backup_root();
        let policy = req.contacts_policy.unwrap_or(self.settings().contacts_policy);
        let restore_manifest = match (&req.kind, &req.backup_path) {
            (SessionKind::Restore, Some(p)) => Some((p.clone(), backup::read_manifest(p)?)),
            (SessionKind::Restore, None) => return Err(Fault::BadRequest("backup_path")),
            _ => None,
        };
        let id = uuid::Uuid::new_v4().simple().to_string()[..12].to_string();

        let result = self.mutate(|st| {
            if st.session.as_ref().is_some_and(|s| !s.state.is_finished()) {
                return Err(Fault::State("session_active"));
            }
            let device_endpoint = |st: &State, id: &Option<String>, err: &'static str| -> FResult<Endpoint> {
                let id = id.clone().ok_or(Fault::State(err))?;
                let d = st.devices.get(&id).ok_or(Fault::State(err))?;
                Ok(Endpoint::Device {
                    id,
                    // The real model, not the user-set name: the user recognises "Galaxy A25 5G".
                    name: d.info.model.clone(),
                    model: d.info.model_code.clone().unwrap_or_else(|| d.info.model.clone()),
                    platform: d.info.platform,
                })
            };
            let (source, target) = match req.kind {
                SessionKind::Transfer => (
                    device_endpoint(st, &st.source_id, "no_source")?,
                    device_endpoint(st, &st.target_id, "no_target")?,
                ),
                SessionKind::Backup => {
                    let src = device_endpoint(st, &st.source_id, "no_source")?;
                    let folder = backup::new_backup_dir(&backup_root, &src.display_name());
                    (src, Endpoint::Folder { path: folder })
                }
                SessionKind::Restore => {
                    let (path, m) = restore_manifest.as_ref().expect("checked above");
                    (
                        Endpoint::Backup { path: path.clone(), name: m.device.name.clone(), platform: Some(m.device.platform) },
                        device_endpoint(st, &st.target_id, "no_target")?,
                    )
                }
            };
            if source.device_id().is_some() && source.device_id() == target.device_id() {
                return Err(Fault::State("same_device"));
            }

            let mut categories: Vec<Category> =
                req.selections.iter().map(|s| s.category).collect::<BTreeSet<_>>().into_iter().collect();
            categories.retain(|c| c.is_transferable());
            if categories.is_empty() {
                return Err(Fault::BadRequest("no_categories"));
            }

            let ledger = self.ledger.lock().unwrap_or_else(|p| p.into_inner());
            let mut items = Vec::new();
            match &source {
                Endpoint::Device { id: sid, .. } => {
                    let dev = st.devices.get(sid).ok_or(Fault::State("no_source"))?;
                    for sel in &req.selections {
                        let Some(inv) = dev.inventory.get(&sel.category) else {
                            return Err(Fault::State("not_scanned"));
                        };
                        for si in &inv.items {
                            if let Some(albums) = &sel.album_ids {
                                if !si.album_ids.iter().any(|a| albums.contains(a)) {
                                    continue;
                                }
                            }
                            let album_name = si
                                .album_ids
                                .first()
                                .and_then(|a| inv.albums.iter().find(|x| &x.id == a))
                                .map(|a| a.name.clone());
                            let mut item = new_item(items.len(), si, album_name);
                            if let Some(tid) = target.device_id() {
                                let k = Ledger::key(tid, sid, si.category, &si.id, si.size);
                                if !req.include_previously_transferred && ledger.contains(&k) {
                                    item.state = ItemState::Skipped;
                                    item.reason = Some("already_transferred".into());
                                }
                            }
                            items.push(item);
                        }
                    }
                }
                Endpoint::Backup { path, .. } => {
                    let (_, m) = restore_manifest.as_ref().expect("checked above");
                    for e in m.items.iter().filter(|e| categories.contains(&e.category)) {
                        let file = path.join(&e.path);
                        let mut item = new_item(
                            items.len(),
                            &SourceItem {
                                id: e.path.clone(),
                                category: e.category,
                                name: e.name.clone(),
                                size: e.size,
                                size_exact: true,
                                mime: e.mime.clone(),
                                created_ms: e.created_ms,
                                modified_ms: e.modified_ms,
                                album_ids: vec![],
                                item_count: e.item_count,
                            },
                            e.album_name.clone(),
                        );
                        item.staged_owned = false;
                        if file.is_file() {
                            item.state = ItemState::Staged;
                            item.received = e.size;
                            item.sha256 = Some(e.sha256.clone());
                            item.staged_path = Some(file);
                        } else {
                            item.state = ItemState::Failed;
                            item.failed_at = Some(Stage::Upload);
                            item.reason = Some("backup_file_missing".into());
                        }
                        items.push(item);
                    }
                }
                Endpoint::Folder { .. } => unreachable!("folders are never sources"),
            }
            drop(ledger);
            if items.is_empty() {
                return Err(Fault::State("nothing_selected"));
            }

            let staging_dir = staging_root.join(&id);
            std::fs::create_dir_all(&staging_dir)?;
            let session = Session {
                id: id.clone(),
                kind: req.kind,
                source,
                target,
                categories,
                contacts_policy: policy,
                state: SessionState::Preparing,
                items,
                preflight: vec![],
                started_ms: now_ms(),
                finished_ms: None,
                staging_dir,
                error: None,
                target_prepare: None,
                rt: Runtime::default(),
            };
            tracing::info!(kind = session.kind.as_str(), items = session.items.len(), "session created");
            st.session = Some(session);
            st.saved_session = None;
            self.send_prepare(st);
            self.evaluate_preflight(st);
            Ok(id.clone())
        });
        self.after_change();
        result
    }

    fn send_prepare(&self, st: &mut State) {
        let Some(s) = st.session.as_ref() else { return };
        let Some(tid) = s.target.device_id().map(str::to_owned) else { return };
        let pending: Vec<&Item> = s.items.iter().filter(|i| !i.state.is_terminal()).collect();
        let total: u64 = pending.iter().map(|i| i.size).sum();
        let cats: BTreeSet<Category> = pending.iter().map(|i| i.category).collect();
        let payload = json!({
            "sessionId": s.id,
            "categories": cats,
            "totalBytes": total,
            "contactsPolicy": s.contacts_policy,
        });
        st.push(&tid, "prepare", payload);
    }

    // -------------------------------------------------------- preflight

    /// Re-evaluates every pre-transfer check and starts the transfer once all pass.
    pub(crate) fn evaluate_preflight(&self, st: &mut State) {
        let staging_free = |p: &std::path::Path| crate::storage::available_space(p);
        let Some(s) = st.session.as_ref() else { return };
        if s.state != SessionState::Preparing {
            return;
        }
        let mut checks = Vec::new();
        let connected = |id: Option<&str>| id.map(|i| st.devices.get(i).is_some_and(|d| d.connected)).unwrap_or(true);

        // 1. Both ends reachable.
        let src_ok = connected(s.source.device_id());
        let tgt_ok = connected(s.target.device_id());
        checks.push(PreflightCheck::new(
            "connection",
            if src_ok && tgt_ok { CheckStatus::Ok } else { CheckStatus::Failed },
            if src_ok && tgt_ok { "preflight.connection.ok" } else { "preflight.connection.lost" },
            json!({ "source": src_ok, "target": tgt_ok }),
        ));

        // 2. Source data readable.
        let mut denied = Vec::new();
        if let Some(sid) = s.source.device_id() {
            if let Some(d) = st.devices.get(sid) {
                for c in &s.categories {
                    if !d.inventory.get(c).is_some_and(|i| i.permission.allows_read()) {
                        denied.push(*c);
                    }
                }
            }
        }
        let missing = s.items.iter().filter(|i| i.reason.as_deref() == Some("backup_file_missing")).count();
        checks.push(PreflightCheck::new(
            "sourceData",
            if denied.is_empty() && missing == 0 { CheckStatus::Ok } else if denied.is_empty() { CheckStatus::Warning } else { CheckStatus::Failed },
            if !denied.is_empty() { "preflight.sourceData.denied" } else if missing > 0 { "preflight.sourceData.missing" } else { "preflight.sourceData.ok" },
            json!({ "categories": denied, "missing": missing }),
        ));

        // 3. Compatibility of each category for this direction.
        let mut blocked = Vec::new();
        let mut notes = Vec::new();
        if let (Some(from), Some(to)) = (s.source.platform(), s.target.platform()) {
            for c in &s.categories {
                let r = compatibility::rule(*c, from, to);
                if !r.status.is_transferable() {
                    blocked.push(*c);
                } else if let Some(n) = r.note {
                    notes.push(n);
                }
            }
        }
        checks.push(PreflightCheck::new(
            "compatibility",
            if !blocked.is_empty() { CheckStatus::Failed } else if !notes.is_empty() { CheckStatus::Warning } else { CheckStatus::Ok },
            if !blocked.is_empty() { "preflight.compatibility.blocked" } else { "preflight.compatibility.ok" },
            json!({ "categories": blocked, "notes": notes }),
        ));

        let remaining: u64 = s.items.iter().filter(|i| !i.state.is_terminal()).map(|i| i.size).sum();

        // 4–5. Target permissions and free space.
        match &s.target {
            Endpoint::Device { .. } => match &s.target_prepare {
                None => {
                    checks.push(PreflightCheck::new("targetPermissions", CheckStatus::Pending, "preflight.targetPermissions.waiting", json!({})));
                    checks.push(PreflightCheck::new("targetStorage", CheckStatus::Pending, "preflight.targetStorage.waiting", json!({})));
                }
                Some(p) => {
                    // Only categories that still have work matter (a retry may cover just one).
                    let pending: BTreeSet<Category> =
                        s.items.iter().filter(|i| !i.state.is_terminal()).map(|i| i.category).collect();
                    let denied: Vec<Category> = pending
                        .iter()
                        .filter(|c| !p.permissions.get(c).is_some_and(|x| x.allows_read()))
                        .copied()
                        .collect();
                    checks.push(PreflightCheck::new(
                        "targetPermissions",
                        if denied.is_empty() && p.ok { CheckStatus::Ok } else { CheckStatus::Failed },
                        if denied.is_empty() && p.ok { "preflight.targetPermissions.ok" } else { "preflight.targetPermissions.denied" },
                        json!({ "categories": denied, "error": p.error }),
                    ));
                    let need = crate::storage::required_on_target(remaining);
                    let ok = p.storage_free_bytes >= need;
                    checks.push(PreflightCheck::new(
                        "targetStorage",
                        if ok { CheckStatus::Ok } else { CheckStatus::Failed },
                        if ok { "preflight.targetStorage.ok" } else { "preflight.targetStorage.insufficient" },
                        json!({ "need": need, "free": p.storage_free_bytes }),
                    ));
                }
            },
            Endpoint::Folder { path } => {
                let free = staging_free(path);
                let need = remaining + 200 * 1024 * 1024;
                let ok = free.map(|f| f >= need).unwrap_or(true);
                checks.push(PreflightCheck::new(
                    "targetStorage",
                    if ok { CheckStatus::Ok } else { CheckStatus::Failed },
                    if ok { "preflight.folderStorage.ok" } else { "preflight.folderStorage.insufficient" },
                    json!({ "need": need, "free": free }),
                ));
            }
            Endpoint::Backup { .. } => {}
        }

        // 6. Room on the PC for in-flight files.
        if s.source.is_device() {
            let need = crate::storage::required_on_pc(s.largest_item(), remaining);
            let free = staging_free(&s.staging_dir);
            let ok = free.map(|f| f >= need).unwrap_or(true);
            checks.push(PreflightCheck::new(
                "pcStorage",
                if ok { CheckStatus::Ok } else { CheckStatus::Failed },
                if ok { "preflight.pcStorage.ok" } else { "preflight.pcStorage.insufficient" },
                json!({ "need": need, "free": free }),
            ));
        }

        let ready = checks.iter().all(|c| matches!(c.status, CheckStatus::Ok | CheckStatus::Warning));
        let s = st.session.as_mut().expect("checked");
        s.preflight = checks;
        s.rt.dirty = true;
        if ready {
            self.begin_running(st);
        }
    }

    fn begin_running(&self, st: &mut State) {
        let Some(s) = st.session.as_mut() else { return };
        s.state = SessionState::Running;
        s.rt.rate.reset();
        tracing::info!("session running");
        self.push_session_work(st, None);
        self.check_completion(st);
    }

    /// Sends the upload list to the source and `download` to the target (or only to `only`).
    fn push_session_work(&self, st: &mut State, only: Option<&str>) {
        let Some(s) = st.session.as_mut() else { return };
        let sid = s.id.clone();
        let uploads: Vec<serde_json::Value> = s
            .items
            .iter()
            .filter(|i| matches!(i.state, ItemState::Pending | ItemState::Uploading))
            .map(|i| json!({ "key": i.key, "sourceId": i.source_id }))
            .collect();
        let source = s.source.device_id().map(str::to_owned);
        let target = s.target.device_id().map(str::to_owned);
        s.bump_items();
        let wanted = |id: &String| only.is_none_or(|o| o == id.as_str());
        if let Some(src) = source.filter(wanted) {
            for batch in uploads.chunks(UPLOAD_BATCH) {
                st.push(&src, "upload", json!({ "sessionId": sid, "items": batch }));
            }
        }
        if let Some(tgt) = target.filter(wanted) {
            st.push(&tgt, "download", json!({ "sessionId": sid }));
        }
        self.work_tx.send_modify(|v| *v += 1);
    }

    /// The phone app restarted and lost its queue: hand the running session's work out again.
    pub fn on_device_restarted(&self, device_id: &str) {
        self.mutate(|st| {
            let Some(s) = st.session.as_ref() else { return };
            let involved = s.source.device_id() == Some(device_id) || s.target.device_id() == Some(device_id);
            if !involved || !matches!(s.state, SessionState::Running | SessionState::Paused) {
                return;
            }
            let paused = s.state == SessionState::Paused;
            let sid = s.id.clone();
            self.push_session_work(st, Some(device_id));
            if paused {
                st.push(device_id, "pause", json!({ "sessionId": sid }));
            }
        });
    }

    pub fn handle_prepare_result(&self, device_id: &str, res: PrepareResult) -> FResult<()> {
        self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if s.id != res.session_id || s.target.device_id() != Some(device_id) {
                return Err(Fault::NotFound);
            }
            tracing::info!(ok = res.ok, "target prepared");
            s.target_prepare = Some(res);
            self.evaluate_preflight(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    /// The user asks to run the checks again (e.g. after freeing space on the phone).
    pub fn rerun_preflight(&self) -> FResult<()> {
        self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if s.state != SessionState::Preparing {
                return Err(Fault::State("not_preparing"));
            }
            s.target_prepare = None;
            self.send_prepare(st);
            self.evaluate_preflight(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    // ------------------------------------------------------- uploading

    fn session_for_source<'a>(st: &'a mut State, device_id: &str, sid: &str) -> FResult<&'a mut Session> {
        let s = st.session.as_mut().filter(|s| s.id == sid).ok_or(Fault::NotFound)?;
        if s.source.device_id() != Some(device_id) {
            return Err(Fault::NotFound);
        }
        Ok(s)
    }

    fn session_for_target<'a>(st: &'a mut State, device_id: &str, sid: &str) -> FResult<&'a mut Session> {
        let s = st.session.as_mut().filter(|s| s.id == sid).ok_or(Fault::NotFound)?;
        if s.target.device_id() != Some(device_id) {
            return Err(Fault::NotFound);
        }
        Ok(s)
    }

    fn require_running(s: &Session) -> FResult<()> {
        match s.state {
            SessionState::Running => Ok(()),
            SessionState::Cancelled | SessionState::Completed | SessionState::Failed => Err(Fault::Cancelled),
            _ => Err(Fault::Paused),
        }
    }

    pub fn upload_status(&self, device_id: &str, sid: &str, key: &str) -> FResult<(u64, &'static str)> {
        let mut st = self.lock();
        let s = Self::session_for_source(&mut st, device_id, sid)?;
        let it = s.item(key).ok_or(Fault::NotFound)?;
        let state = match it.state {
            ItemState::Pending => "pending",
            ItemState::Uploading => "receiving",
            ItemState::Skipped => "skipped",
            ItemState::Failed if it.failed_at == Some(Stage::Upload) => "failed",
            _ => "staged",
        };
        Ok((it.received, state))
    }

    /// Validates a chunk and reserves the item; returns the `.part` path to write to.
    pub fn begin_chunk(&self, device_id: &str, sid: &str, key: &str, offset: u64, len: u64) -> FResult<PathBuf> {
        if len > MAX_CHUNK {
            return Err(Fault::TooLarge);
        }
        let mut st = self.lock();
        let s = Self::session_for_source(&mut st, device_id, sid)?;
        Self::require_running(s)?;
        let buffered = s.buffered_bytes();
        let staging = s.staging_dir.clone();
        let it = s.item_mut(key).ok_or(Fault::NotFound)?;
        match it.state {
            ItemState::Pending | ItemState::Uploading => {}
            ItemState::Skipped | ItemState::Failed => return Err(Fault::State("item_closed")),
            _ => return Err(Fault::OffsetMismatch(it.size)),
        }
        if it.busy || offset != it.received {
            return Err(Fault::OffsetMismatch(it.received));
        }
        if offset == 0 && buffered > MAX_BUFFER {
            // Back-pressure: the target is slower than the source.
            return Err(Fault::Busy);
        }
        // The phone's size is a hint: MediaStore sizes go stale when a file is edited after the
        // scan. Accept growth up to a generous cap; the real size is fixed at `complete`.
        let limit = it.size.saturating_mul(4).max(it.size + 1024 * 1024 * 1024);
        if offset + len > limit {
            return Err(Fault::TooLarge);
        }
        it.busy = true;
        it.state = ItemState::Uploading;
        Ok(staging.join(format!("{key}.part")))
    }

    pub fn end_chunk(&self, sid: &str, key: &str, offset: u64, len: u64, ok: bool) -> u64 {
        let mut st = self.lock();
        let Some(s) = st.session.as_mut().filter(|s| s.id == sid) else { return 0 };
        let Some(idx) = Item::index(key) else { return 0 };
        let received = {
            let Some(it) = s.items.get_mut(idx) else { return 0 };
            it.busy = false;
            if ok {
                it.received = offset + len;
            }
            it.received
        };
        if ok {
            s.rt.bytes_moved += len;
            s.rt.current = Some(idx);
        }
        drop(st);
        self.notify_ui();
        received
    }

    pub async fn complete_upload(&self, device_id: &str, sid: &str, key: &str, size: u64, sha: &str) -> FResult<()> {
        let sha = sha.to_ascii_lowercase();
        if !crate::checksum::is_valid_sha256_hex(&sha) {
            return Err(Fault::BadRequest("sha256"));
        }
        let part = {
            let mut st = self.lock();
            let s = Self::session_for_source(&mut st, device_id, sid)?;
            let staging = s.staging_dir.clone();
            let it = s.item_mut(key).ok_or(Fault::NotFound)?;
            if it.state == ItemState::Staged || it.state == ItemState::Delivering || it.state == ItemState::Delivered {
                return Ok(());
            }
            if it.busy || it.received != size {
                return Err(Fault::OffsetMismatch(it.received));
            }
            it.busy = true;
            staging.join(format!("{key}.part"))
        };
        let hashed = {
            let part = part.clone();
            tokio::task::spawn_blocking(move || crate::checksum::sha256_file(&part))
                .await
                .map_err(|e| Fault::Io(e.to_string()))?
        };
        let result = self.mutate(|st| {
            let Some(s) = st.session.as_mut().filter(|s| s.id == sid) else { return Err(Fault::Cancelled) };
            let it = s.item_mut(key).ok_or(Fault::NotFound)?;
            it.busy = false;
            match &hashed {
                Ok((h, n)) if *h == sha && *n == size => {
                    let bin = part.with_extension("bin");
                    std::fs::rename(&part, &bin)?;
                    it.state = ItemState::Staged;
                    it.size = size;
                    it.received = size;
                    it.sha256 = Some(sha.clone());
                    it.staged_path = Some(bin);
                    s.bump_items();
                    Ok(())
                }
                Ok(_) => {
                    tracing::warn!(key, "upload checksum mismatch, restarting item");
                    let _ = std::fs::remove_file(&part);
                    it.state = ItemState::Pending;
                    it.received = 0;
                    it.attempts += 1;
                    Err(Fault::ChecksumMismatch)
                }
                Err(e) => Err(Fault::Io(e.kind().to_string())),
            }
        });
        if result.is_ok() {
            self.work_tx.send_modify(|v| *v += 1);
        }
        result
    }

    pub fn fail_upload(&self, device_id: &str, sid: &str, key: &str, code: Option<String>) -> FResult<()> {
        self.mutate(|st| -> FResult<()> {
            let s = Self::session_for_source(st, device_id, sid)?;
            let staging = s.staging_dir.clone();
            let it = s.item_mut(key).ok_or(Fault::NotFound)?;
            if it.state.is_terminal() || matches!(it.state, ItemState::Staged | ItemState::Delivering) {
                return Ok(());
            }
            let _ = std::fs::remove_file(staging.join(format!("{key}.part")));
            it.state = ItemState::Failed;
            it.failed_at = Some(Stage::Upload);
            it.reason = Some(sanitize_error_code(code));
            it.received = 0;
            it.busy = false;
            s.bump_items();
            self.check_completion(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    // -------------------------------------------------------- delivery

    pub async fn next_item(&self, device_id: &str, sid: &str, wait: Duration) -> FResult<NextResult> {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            let mut rx = {
                let mut st = self.lock();
                let s = Self::session_for_target(&mut st, device_id, sid)?;
                match s.state {
                    SessionState::Running => {}
                    SessionState::Completed => return Ok(NextResult::Done),
                    SessionState::Cancelled | SessionState::Failed => return Ok(NextResult::Cancelled),
                    _ => return Ok(NextResult::Paused),
                }
                let policy = s.contacts_policy;
                // Re-offer an item the target was already working on (after a reconnect).
                let idx = s
                    .items
                    .iter()
                    .position(|i| i.state == ItemState::Delivering)
                    .or_else(|| s.items.iter().position(|i| i.state == ItemState::Staged));
                if let Some(idx) = idx {
                    let it = &mut s.items[idx];
                    it.state = ItemState::Delivering;
                    let out = DeliveryItem {
                        key: it.key.clone(),
                        category: it.category,
                        name: it.name.clone(),
                        size: it.size,
                        sha256: it.sha256.clone().unwrap_or_default(),
                        mime: it.mime.clone(),
                        created_ms: it.created_ms,
                        modified_ms: it.modified_ms,
                        album_name: it.album_name.clone(),
                        item_count: it.item_count,
                        contacts_policy: policy,
                    };
                    s.rt.current = Some(idx);
                    s.rt.dirty = true;
                    return Ok(NextResult::Item(out));
                }
                if s.all_terminal() {
                    return Ok(NextResult::Done);
                }
                s.rt.items_tx.subscribe()
            };
            match tokio::time::timeout_at(deadline, rx.changed()).await {
                Ok(Ok(())) => continue,
                _ => return Ok(NextResult::Wait),
            }
        }
    }

    pub fn open_content(&self, device_id: &str, sid: &str, key: &str) -> FResult<(PathBuf, u64)> {
        let mut st = self.lock();
        let s = Self::session_for_target(&mut st, device_id, sid)?;
        Self::require_running(s)?;
        let it = s.item(key).ok_or(Fault::NotFound)?;
        if !matches!(it.state, ItemState::Delivering | ItemState::Staged) {
            return Err(Fault::State("item_not_ready"));
        }
        let path = it.staged_path.clone().ok_or(Fault::NotFound)?;
        Ok((path, it.size))
    }

    pub fn add_served(&self, sid: &str, key: &str, upto: u64, n: u64) {
        {
            let mut st = self.lock();
            let Some(s) = st.session.as_mut().filter(|s| s.id == sid) else { return };
            s.rt.bytes_moved += n;
            if let Some(it) = s.item_mut(key) {
                it.served = it.served.max(upto);
            }
        }
        self.notify_ui();
    }

    pub fn ack(&self, device_id: &str, sid: &str, key: &str, ack: AckBody) -> FResult<()> {
        let cleanup = self.settings().cleanup_staging;
        self.mutate(|st| {
            let s = Self::session_for_target(st, device_id, sid)?;
            let source_device = s.source.device_id().map(str::to_owned);
            let target_device = s.target.device_id().map(str::to_owned);
            let it = s.item_mut(key).ok_or(Fault::NotFound)?;
            if it.state.is_terminal() {
                return Ok(());
            }
            let mut ledger_key = None;
            let mut delete_staged = false;
            match ack.result.as_str() {
                "saved" => {
                    let got = ack.sha256.as_deref().map(str::to_ascii_lowercase);
                    if got.is_some() && got == it.sha256 {
                        it.state = ItemState::Delivered;
                        it.served = it.size;
                        it.verification = Some(match ack.verification.as_deref() {
                            Some("read_back") => "read_back".into(),
                            _ => "written_file".into(),
                        });
                        it.detail = ack.detail.clone();
                        delete_staged = it.staged_owned && cleanup;
                        if let (Some(src), Some(tgt)) = (&source_device, &target_device) {
                            ledger_key = Some(Ledger::key(tgt, src, it.category, &it.source_id, it.size));
                        }
                    } else {
                        tracing::warn!(key, "target reported a different checksum");
                        it.state = ItemState::Failed;
                        it.failed_at = Some(Stage::Delivery);
                        it.reason = Some("checksum_mismatch".into());
                    }
                }
                "duplicate" => {
                    it.state = ItemState::Skipped;
                    it.reason = Some("duplicate".into());
                    it.served = it.size;
                    delete_staged = it.staged_owned && cleanup;
                    if let (Some(src), Some(tgt)) = (&source_device, &target_device) {
                        ledger_key = Some(Ledger::key(tgt, src, it.category, &it.source_id, it.size));
                    }
                }
                "failed" => {
                    it.state = ItemState::Failed;
                    it.failed_at = Some(Stage::Delivery);
                    it.reason = Some(sanitize_error_code(ack.error.clone()));
                }
                _ => return Err(Fault::BadRequest("result")),
            }
            it.attempts += 1;
            if delete_staged {
                if let Some(p) = it.staged_path.take() {
                    let _ = std::fs::remove_file(p);
                }
            }
            s.bump_items();
            if let Some(k) = ledger_key {
                let _ = self.ledger.lock().unwrap_or_else(|p| p.into_inner()).insert(k);
            }
            self.check_completion(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    // ------------------------------------------------------- controls

    pub fn pause(&self) -> FResult<()> {
        self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if s.state != SessionState::Running {
                return Err(Fault::State("not_running"));
            }
            s.state = SessionState::Paused;
            s.rt.rate.reset();
            s.bump_items();
            let sid = s.id.clone();
            for id in [s.source.device_id().map(str::to_owned), s.target.device_id().map(str::to_owned)].into_iter().flatten() {
                st.push(&id, "pause", json!({ "sessionId": sid }));
            }
            Ok(())
        })
    }

    pub fn resume(&self) -> FResult<()> {
        self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if !matches!(s.state, SessionState::Paused | SessionState::WaitingReconnect) {
                return Err(Fault::State("not_paused"));
            }
            let sid = s.id.clone();
            let ids: Vec<String> =
                [s.source.device_id().map(str::to_owned), s.target.device_id().map(str::to_owned)].into_iter().flatten().collect();
            if ids.iter().any(|id| !st.devices.get(id).is_some_and(|d| d.connected)) {
                return Err(Fault::State("device_disconnected"));
            }
            for id in &ids {
                st.push(id, "resume", json!({ "sessionId": sid }));
            }
            self.begin_running(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    /// Safe cancel: stops both phones, deletes staged copies, keeps the originals.
    pub fn cancel(&self) -> FResult<()> {
        self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if s.state.is_finished() {
                return Err(Fault::State("already_finished"));
            }
            let sid = s.id.clone();
            for id in [s.source.device_id().map(str::to_owned), s.target.device_id().map(str::to_owned)].into_iter().flatten() {
                st.push(&id, "cancel", json!({ "sessionId": sid }));
            }
            self.finish(st, SessionState::Cancelled);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    /// Puts failed items back in the queue and continues.
    pub fn retry_failed(&self) -> FResult<u64> {
        let n = self.mutate(|st| {
            let s = st.session.as_mut().ok_or(Fault::NotFound)?;
            if !matches!(s.state, SessionState::Completed | SessionState::Running | SessionState::Paused) {
                return Err(Fault::State("cannot_retry"));
            }
            let mut n = 0;
            for it in &mut s.items {
                if it.state != ItemState::Failed || it.reason.as_deref() == Some("backup_file_missing") {
                    continue;
                }
                n += 1;
                it.reason = None;
                it.served = 0;
                let staged = it.staged_path.as_ref().is_some_and(|p| p.exists());
                if it.failed_at == Some(Stage::Delivery) && staged {
                    it.state = ItemState::Staged;
                } else {
                    it.state = ItemState::Pending;
                    it.received = 0;
                    it.sha256 = None;
                    it.staged_path = None;
                }
                it.failed_at = None;
            }
            if n == 0 {
                return Ok(0);
            }
            s.finished_ms = None;
            s.state = SessionState::Preparing;
            s.target_prepare = None;
            s.bump_items();
            self.send_prepare(st);
            self.evaluate_preflight(st);
            Ok(n)
        })?;
        self.after_change();
        Ok(n)
    }

    /// Clears a finished session from the screen and removes its staging folder.
    pub fn dismiss_session(&self) -> FResult<()> {
        let dir = self.mutate(|st| {
            let s = st.session.as_ref().ok_or(Fault::NotFound)?;
            if !s.state.is_finished() {
                return Err(Fault::State("session_active"));
            }
            let dir = s.staging_dir.clone();
            st.session = None;
            Ok(dir)
        })?;
        let _ = std::fs::remove_dir_all(dir);
        let _ = std::fs::remove_file(&self.paths.session_file);
        Ok(())
    }

    // ---------------------------------------------- saved session (restart)

    pub fn resume_saved_session(&self) -> FResult<()> {
        self.mutate(|st| {
            if st.session.as_ref().is_some_and(|s| !s.state.is_finished()) {
                return Err(Fault::State("session_active"));
            }
            let saved = st.saved_session.as_ref().ok_or(Fault::NotFound)?;
            for id in [saved.source.device_id(), saved.target.device_id()].into_iter().flatten() {
                if !st.devices.get(id).is_some_and(|d| d.connected) {
                    return Err(Fault::State("devices_not_paired"));
                }
            }
            let mut s = st.saved_session.take().expect("checked");
            s.state = SessionState::Preparing;
            if let Some(id) = s.source.device_id() {
                st.source_id = Some(id.to_owned());
            }
            if let Some(id) = s.target.device_id() {
                st.target_id = Some(id.to_owned());
            }
            st.sync_roles();
            st.session = Some(s);
            self.send_prepare(st);
            self.evaluate_preflight(st);
            Ok(())
        })?;
        self.after_change();
        Ok(())
    }

    pub fn discard_saved_session(&self) -> FResult<()> {
        let dir = self.mutate(|st| st.saved_session.take().map(|s| s.staging_dir)).ok_or(Fault::NotFound)?;
        let _ = std::fs::remove_dir_all(dir);
        let _ = std::fs::remove_file(&self.paths.session_file);
        Ok(())
    }

    // --------------------------------------------- connection recovery

    pub fn on_device_lost(&self, device_id: &str) {
        self.mutate(|st| {
            if let Some(s) = st.session.as_mut() {
                let involved = s.source.device_id() == Some(device_id) || s.target.device_id() == Some(device_id);
                if involved && s.state == SessionState::Running {
                    s.state = SessionState::WaitingReconnect;
                    s.rt.rate.reset();
                    s.bump_items();
                }
            }
        });
    }

    pub(crate) fn on_device_reconnected(&self, device_id: &str) {
        self.mutate(|st| {
            let Some(s) = st.session.as_ref() else { return };
            let involved = s.source.device_id() == Some(device_id) || s.target.device_id() == Some(device_id);
            if !involved {
                return;
            }
            let all_back = [s.source.device_id(), s.target.device_id()]
                .into_iter()
                .flatten()
                .all(|id| st.devices.get(id).is_some_and(|d| d.connected));
            match s.state {
                SessionState::WaitingReconnect if all_back => self.begin_running(st),
                SessionState::Preparing => self.evaluate_preflight(st),
                _ => {}
            }
        });
        self.after_change();
    }

    // ------------------------------------------------------ completion

    pub(crate) fn check_completion(&self, st: &mut State) {
        let Some(s) = st.session.as_ref() else { return };
        if s.state == SessionState::Running && s.all_terminal() {
            self.finish(st, SessionState::Completed);
        }
    }

    fn finish(&self, st: &mut State, state: SessionState) {
        let Some(s) = st.session.as_mut() else { return };
        s.state = state;
        s.finished_ms = Some(now_ms());
        s.rt.current = None;
        s.bump_items();
        let t = s.totals();
        tracing::info!(
            state = ?state,
            delivered = t.delivered_items,
            failed = t.failed_items,
            skipped = t.skipped_items,
            "session finished"
        );
        if let Endpoint::Folder { path } = &s.target {
            if let Err(e) = backup::write_manifest(path, s, st.devices.get(s.source.device_id().unwrap_or_default()).map(|d| &d.info)) {
                tracing::error!("backup manifest write failed: {}", e.kind());
            }
        }
        // Staged copies of finished items are useless; keep only those of failed items for retry.
        if state == SessionState::Cancelled {
            for it in &mut s.items {
                if it.staged_owned {
                    it.staged_path = None;
                }
            }
            let _ = std::fs::remove_dir_all(&s.staging_dir);
        }
        let outcome = match state {
            SessionState::Completed if t.failed_items > 0 => "partial",
            SessionState::Completed => "completed",
            SessionState::Cancelled => "cancelled",
            _ => "failed",
        };
        let entry = HistoryEntry {
            id: s.id.clone(),
            kind: s.kind.as_str().into(),
            outcome: outcome.into(),
            started_ms: s.started_ms,
            finished_ms: s.finished_ms.unwrap_or_default(),
            source_name: s.source.display_name(),
            source_platform: s.source.platform(),
            target_name: s.target.display_name(),
            target_platform: s.target.platform(),
            categories: s
                .category_progress()
                .into_iter()
                .map(|p| CategoryResult {
                    category: p.category,
                    total: p.total,
                    delivered: p.delivered,
                    skipped: p.skipped,
                    failed: p.failed,
                    delivered_bytes: p.delivered_bytes,
                })
                .collect(),
            total_bytes: t.total_bytes,
            delivered_bytes: t.delivered_bytes,
            verified_items: t.verified_items,
        };
        if let Err(e) = self.history.append(entry.clone()) {
            tracing::error!("history write failed: {}", e.kind());
        }
        let _ = std::fs::remove_file(&self.paths.session_file);
        self.finished_tx.send_replace(Some(entry));
    }

    /// Hook after state changes that may need background work.
    pub(crate) fn after_change(&self) {
        self.work_tx.send_modify(|v| *v = v.wrapping_add(1));
        self.notify_ui();
    }

    // ------------------------------------------------------ persistence

    /// Writes the active session to disk if it changed (ErrorRecovery after a crash).
    pub fn persist_if_dirty(&self) {
        let data = {
            let mut st = self.lock();
            let Some(s) = st.session.as_mut() else { return };
            if !s.rt.dirty || s.state.is_finished() {
                return;
            }
            s.rt.dirty = false;
            serde_json::to_vec(&*s)
        };
        if let Ok(bytes) = data {
            if let Err(e) = crate::persist::write_atomic(&self.paths.session_file, &bytes) {
                tracing::error!("session persist failed: {}", e.kind());
            }
        }
    }

    /// Samples throughput; called every 500 ms by the ticker.
    pub fn tick(&self) {
        let running = {
            let mut st = self.lock();
            match st.session.as_mut() {
                Some(s) if s.state == SessionState::Running => {
                    let moved = s.rt.bytes_moved;
                    s.rt.rate.sample(Instant::now(), moved);
                    true
                }
                _ => false,
            }
        };
        if running {
            self.notify_ui();
        }
    }
}

fn new_item(index: usize, si: &SourceItem, album_name: Option<String>) -> Item {
    Item {
        key: Item::key_for(index),
        source_id: si.id.clone(),
        category: si.category,
        name: si.name.clone(),
        size: si.size,
        size_exact: si.size_exact,
        mime: si.mime.clone(),
        created_ms: si.created_ms,
        modified_ms: si.modified_ms,
        album_name,
        item_count: si.item_count,
        state: ItemState::Pending,
        reason: None,
        failed_at: None,
        received: 0,
        served: 0,
        sha256: None,
        staged_path: None,
        staged_owned: true,
        backup_rel_path: None,
        verification: None,
        detail: None,
        attempts: 0,
        busy: false,
    }
}

/// Used by the UI to show a compatibility badge without a round trip.
pub fn compat_status(category: Category, from: Platform, to: Option<Platform>) -> CompatStatus {
    match to {
        Some(to) => compatibility::rule(category, from, to).status,
        None if category.is_transferable() => CompatStatus::Supported,
        None => CompatStatus::Unsupported,
    }
}
