//! TransferSession: items, their lifecycle and real progress figures.
//!
//! Item lifecycle (device → PC → device):
//!   Pending → Uploading → Staged (SHA-256 verified on PC) → Delivering → Delivered
//!   (SHA-256 confirmed by the target). Any step can end in Failed; Skipped covers
//!   duplicates and items already moved in an earlier session.

use crate::model::{Category, ContactsPolicy, Platform};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};
use std::path::PathBuf;
use std::time::{Duration, Instant};
use tokio::sync::watch;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionKind {
    Transfer,
    Backup,
    Restore,
}

impl SessionKind {
    pub fn as_str(self) -> &'static str {
        match self {
            SessionKind::Transfer => "transfer",
            SessionKind::Backup => "backup",
            SessionKind::Restore => "restore",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionState {
    Preparing,
    Running,
    Paused,
    WaitingReconnect,
    Completed,
    Cancelled,
    Failed,
}

impl SessionState {
    pub fn is_finished(self) -> bool {
        matches!(self, SessionState::Completed | SessionState::Cancelled | SessionState::Failed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ItemState {
    Pending,
    Uploading,
    Staged,
    Delivering,
    Delivered,
    Skipped,
    Failed,
}

impl ItemState {
    pub fn is_terminal(self) -> bool {
        matches!(self, ItemState::Delivered | ItemState::Skipped | ItemState::Failed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Upload,
    Delivery,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Endpoint {
    #[serde(rename_all = "camelCase")]
    Device { id: String, name: String, model: String, platform: Platform },
    #[serde(rename_all = "camelCase")]
    Folder { path: PathBuf },
    #[serde(rename_all = "camelCase")]
    Backup { path: PathBuf, name: String, platform: Option<Platform> },
}

impl Endpoint {
    pub fn device_id(&self) -> Option<&str> {
        match self {
            Endpoint::Device { id, .. } => Some(id),
            _ => None,
        }
    }
    pub fn platform(&self) -> Option<Platform> {
        match self {
            Endpoint::Device { platform, .. } => Some(*platform),
            Endpoint::Backup { platform, .. } => *platform,
            Endpoint::Folder { .. } => None,
        }
    }
    pub fn display_name(&self) -> String {
        match self {
            Endpoint::Device { name, .. } => name.clone(),
            Endpoint::Folder { path } => path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            Endpoint::Backup { name, .. } => name.clone(),
        }
    }
    pub fn is_device(&self) -> bool {
        matches!(self, Endpoint::Device { .. })
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AckDetail {
    pub imported: u64,
    pub merged: u64,
    pub skipped: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Item {
    pub key: String,
    pub source_id: String,
    pub category: Category,
    pub name: String,
    pub size: u64,
    pub size_exact: bool,
    pub mime: String,
    pub created_ms: Option<i64>,
    pub modified_ms: Option<i64>,
    pub album_name: Option<String>,
    pub item_count: Option<u32>,
    pub state: ItemState,
    /// Error or skip code (`already_transferred`, `duplicate`, `checksum_mismatch`, ...).
    pub reason: Option<String>,
    pub failed_at: Option<Stage>,
    pub received: u64,
    pub served: u64,
    pub sha256: Option<String>,
    pub staged_path: Option<PathBuf>,
    /// False when the staged file is not ours to delete (restore from a backup).
    pub staged_owned: bool,
    /// Path relative to the backup folder, set once a backup item is written.
    pub backup_rel_path: Option<String>,
    pub verification: Option<String>,
    pub detail: Option<AckDetail>,
    pub attempts: u32,
    #[serde(skip)]
    pub busy: bool,
}

impl Item {
    pub fn index(key: &str) -> Option<usize> {
        key.strip_prefix('i')?.parse().ok()
    }
    pub fn key_for(index: usize) -> String {
        format!("i{index}")
    }
    /// Logical number of user-visible entries (contacts inside a vCard, events in a calendar file).
    pub fn logical_count(&self) -> u64 {
        self.item_count.map(u64::from).unwrap_or(1)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckStatus {
    Pending,
    Ok,
    Warning,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PreflightCheck {
    /// connection | sourceData | compatibility | targetPermissions | targetStorage | pcStorage
    pub id: String,
    pub status: CheckStatus,
    /// i18n key for the explanation.
    pub message: String,
    pub params: serde_json::Value,
}

impl PreflightCheck {
    pub fn new(id: &str, status: CheckStatus, message: &str, params: serde_json::Value) -> Self {
        PreflightCheck { id: id.into(), status, message: message.into(), params }
    }
}

/// Measures real throughput from monotonically increasing byte counters.
#[derive(Debug)]
pub struct RateMeter {
    samples: VecDeque<(Instant, u64)>,
    window: Duration,
}

impl Default for RateMeter {
    fn default() -> Self {
        RateMeter { samples: VecDeque::new(), window: Duration::from_secs(6) }
    }
}

impl RateMeter {
    pub fn sample(&mut self, now: Instant, total: u64) {
        if let Some(&(t, _)) = self.samples.back() {
            if now.duration_since(t) < Duration::from_millis(200) {
                return;
            }
        }
        self.samples.push_back((now, total));
        while let Some(&(t, _)) = self.samples.front() {
            if now.duration_since(t) > self.window && self.samples.len() > 2 {
                self.samples.pop_front();
            } else {
                break;
            }
        }
    }

    /// Bytes per second over the sampling window, `None` until there are two seconds of data.
    pub fn rate(&self) -> Option<f64> {
        let (t0, b0) = *self.samples.front()?;
        let (t1, b1) = *self.samples.back()?;
        let dt = t1.duration_since(t0).as_secs_f64();
        if dt < 2.0 {
            return None;
        }
        Some(b1.saturating_sub(b0) as f64 / dt)
    }

    pub fn reset(&mut self) {
        self.samples.clear();
    }
}

/// Runtime-only parts of a session (never persisted).
pub struct Runtime {
    pub items_tx: watch::Sender<u64>,
    pub rate: RateMeter,
    pub bytes_moved: u64,
    pub current: Option<usize>,
    pub dirty: bool,
}

impl Default for Runtime {
    fn default() -> Self {
        Runtime { items_tx: watch::channel(0).0, rate: RateMeter::default(), bytes_moved: 0, current: None, dirty: true }
    }
}

impl std::fmt::Debug for Runtime {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Runtime").field("bytes_moved", &self.bytes_moved).finish()
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub kind: SessionKind,
    pub source: Endpoint,
    pub target: Endpoint,
    pub categories: Vec<Category>,
    pub contacts_policy: ContactsPolicy,
    pub state: SessionState,
    pub items: Vec<Item>,
    pub preflight: Vec<PreflightCheck>,
    pub started_ms: i64,
    pub finished_ms: Option<i64>,
    pub staging_dir: PathBuf,
    pub error: Option<String>,
    /// Snapshot of the target's answer to `prepare`.
    pub target_prepare: Option<crate::model::PrepareResult>,
    #[serde(skip)]
    pub rt: Runtime,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryProgress {
    pub category: Category,
    pub total: u64,
    pub delivered: u64,
    pub skipped: u64,
    pub failed: u64,
    pub total_bytes: u64,
    pub delivered_bytes: u64,
    /// Contacts/events count when the category is moved as one file.
    pub logical_total: u64,
    pub logical_delivered: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Totals {
    pub total_items: u64,
    pub delivered_items: u64,
    pub skipped_items: u64,
    pub failed_items: u64,
    pub total_bytes: u64,
    pub delivered_bytes: u64,
    pub work_total: u64,
    pub work_done: u64,
    pub verified_items: u64,
}

impl Session {
    pub fn item(&self, key: &str) -> Option<&Item> {
        self.items.get(Item::index(key)?).filter(|i| i.key == key)
    }

    pub fn item_mut(&mut self, key: &str) -> Option<&mut Item> {
        self.items.get_mut(Item::index(key)?).filter(|i| i.key == key)
    }

    fn weights(&self) -> (u64, u64) {
        let up = u64::from(self.source.is_device());
        let down = u64::from(self.target.is_device());
        (up, down.max(if up == 0 { 1 } else { 0 }))
    }

    pub fn totals(&self) -> Totals {
        let (wu, wd) = self.weights();
        let mut t = Totals::default();
        for it in &self.items {
            t.total_items += 1;
            t.total_bytes += it.size;
            t.work_total += it.size * (wu + wd);
            let (up, down) = match it.state {
                ItemState::Pending | ItemState::Uploading => (it.received.min(it.size), 0),
                ItemState::Staged => (it.size, 0),
                ItemState::Delivering => (it.size, it.served.min(it.size)),
                ItemState::Delivered | ItemState::Skipped | ItemState::Failed => (it.size, it.size),
            };
            t.work_done += up * wu + down * wd;
            match it.state {
                ItemState::Delivered => {
                    t.delivered_items += 1;
                    t.delivered_bytes += it.size;
                    if it.verification.is_some() {
                        t.verified_items += 1;
                    }
                }
                ItemState::Skipped => t.skipped_items += 1,
                ItemState::Failed => t.failed_items += 1,
                _ => {}
            }
        }
        t
    }

    pub fn category_progress(&self) -> Vec<CategoryProgress> {
        let mut map: BTreeMap<Category, CategoryProgress> = BTreeMap::new();
        for c in &self.categories {
            map.insert(*c, CategoryProgress { category: *c, ..Default::default() });
        }
        for it in &self.items {
            let p = map.entry(it.category).or_insert_with(|| CategoryProgress { category: it.category, ..Default::default() });
            p.total += 1;
            p.total_bytes += it.size;
            p.logical_total += it.logical_count();
            match it.state {
                ItemState::Delivered => {
                    p.delivered += 1;
                    p.delivered_bytes += it.size;
                    p.logical_delivered += match (&it.detail, it.item_count) {
                        (Some(d), Some(_)) => d.imported + d.merged,
                        _ => it.logical_count(),
                    };
                }
                ItemState::Skipped => p.skipped += 1,
                ItemState::Failed => p.failed += 1,
                _ => {}
            }
        }
        map.into_values().collect()
    }

    pub fn all_terminal(&self) -> bool {
        self.items.iter().all(|i| i.state.is_terminal())
    }

    /// Bytes sitting on the PC that the target has not confirmed yet.
    pub fn buffered_bytes(&self) -> u64 {
        self.items
            .iter()
            .filter(|i| i.staged_owned && matches!(i.state, ItemState::Staged | ItemState::Delivering))
            .map(|i| i.size)
            .sum()
    }

    pub fn largest_item(&self) -> u64 {
        self.items.iter().map(|i| i.size).max().unwrap_or(0)
    }

    pub fn bump_items(&mut self) {
        self.rt.items_tx.send_modify(|v| *v = v.wrapping_add(1));
        self.rt.dirty = true;
    }

    /// Restores a session loaded from disk to a resumable state.
    pub fn normalize_after_load(&mut self) {
        for it in &mut self.items {
            it.busy = false;
            match it.state {
                ItemState::Uploading | ItemState::Pending => {
                    it.state = ItemState::Pending;
                    let part = self.staging_dir.join(format!("{}.part", it.key));
                    it.received = std::fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
                }
                ItemState::Staged | ItemState::Delivering => {
                    let exists = it.staged_path.as_ref().is_some_and(|p| p.exists());
                    if exists {
                        it.state = ItemState::Staged;
                        it.served = 0;
                    } else {
                        it.state = ItemState::Pending;
                        it.received = 0;
                        it.sha256 = None;
                        it.staged_path = None;
                    }
                }
                _ => {}
            }
        }
        if !self.state.is_finished() {
            self.state = SessionState::Preparing;
        }
        self.target_prepare = None;
        self.rt = Runtime::default();
    }
}

/// Makes a file name safe for Windows and unique-friendly.
pub fn sanitize_file_name(name: &str) -> String {
    let mut out: String = name
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    while out.ends_with(['.', ' ']) {
        out.pop();
    }
    let trimmed = out.trim_start().to_string();
    let mut out = if trimmed.is_empty() { "file".to_string() } else { trimmed };
    let stem_upper = out.split('.').next().unwrap_or("").to_ascii_uppercase();
    const RESERVED: [&str; 22] = [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
        "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];
    if RESERVED.contains(&stem_upper.as_str()) {
        out.insert(0, '_');
    }
    if out.chars().count() > 180 {
        let path = std::path::Path::new(&out);
        let ext = path.extension().map(|e| e.to_string_lossy().into_owned());
        let stem: String = out.chars().take(170).collect();
        out = match ext {
            Some(e) if e.len() <= 8 => format!("{stem}.{e}"),
            _ => stem,
        };
    }
    out
}

/// `photo.jpg` → `photo (2).jpg`, `photo (3).jpg`, ... until `exists` says no.
pub fn unique_name(dir: &std::path::Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let p = std::path::Path::new(name);
    let stem = p.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = p.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    for n in 2.. {
        let c = dir.join(format!("{stem} ({n}){ext}"));
        if !c.exists() {
            return c;
        }
    }
    unreachable!()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(i: usize, state: ItemState, size: u64) -> Item {
        Item {
            key: Item::key_for(i),
            source_id: i.to_string(),
            category: Category::Photos,
            name: format!("{i}.jpg"),
            size,
            size_exact: true,
            mime: "image/jpeg".into(),
            created_ms: None,
            modified_ms: None,
            album_name: None,
            item_count: None,
            state,
            reason: None,
            failed_at: None,
            received: 0,
            served: 0,
            sha256: None,
            staged_path: None,
            staged_owned: true,
            backup_rel_path: None,
            verification: Some("read_back".into()),
            detail: None,
            attempts: 0,
            busy: false,
        }
    }

    fn session(items: Vec<Item>) -> Session {
        Session {
            id: "s".into(),
            kind: SessionKind::Transfer,
            source: Endpoint::Device { id: "a".into(), name: "A".into(), model: "m".into(), platform: Platform::Android },
            target: Endpoint::Device { id: "b".into(), name: "B".into(), model: "m".into(), platform: Platform::Ios },
            categories: vec![Category::Photos],
            contacts_policy: ContactsPolicy::Merge,
            state: SessionState::Running,
            items,
            preflight: vec![],
            started_ms: 0,
            finished_ms: None,
            staging_dir: PathBuf::from("."),
            error: None,
            target_prepare: None,
            rt: Runtime::default(),
        }
    }

    #[test]
    fn progress_counts_upload_and_delivery() {
        let mut a = item(0, ItemState::Uploading, 100);
        a.received = 50;
        let b = item(1, ItemState::Staged, 100);
        let mut c = item(2, ItemState::Delivering, 100);
        c.served = 25;
        let d = item(3, ItemState::Delivered, 100);
        let s = session(vec![a, b, c, d]);
        let t = s.totals();
        assert_eq!(t.work_total, 800);
        assert_eq!(t.work_done, 50 + 100 + 125 + 200);
        assert_eq!(t.delivered_items, 1);
        assert_eq!(t.verified_items, 1);
        assert!(!s.all_terminal());
    }

    #[test]
    fn keys_roundtrip() {
        let s = session(vec![item(0, ItemState::Pending, 1), item(1, ItemState::Pending, 1)]);
        assert_eq!(s.item("i1").unwrap().source_id, "1");
        assert!(s.item("i9").is_none());
        assert!(s.item("x1").is_none());
    }

    #[test]
    fn rate_meter_needs_two_seconds() {
        let mut m = RateMeter::default();
        let t0 = Instant::now();
        m.sample(t0, 0);
        m.sample(t0 + Duration::from_secs(1), 1000);
        assert!(m.rate().is_none());
        m.sample(t0 + Duration::from_secs(4), 4000);
        assert_eq!(m.rate().unwrap().round(), 1000.0);
    }

    #[test]
    fn file_names_are_safe() {
        assert_eq!(sanitize_file_name("a/b:c?.jpg"), "a_b_c_.jpg");
        assert_eq!(sanitize_file_name("CON.txt"), "_CON.txt");
        assert_eq!(sanitize_file_name("name. "), "name");
        assert_eq!(sanitize_file_name(""), "file");
        let long = format!("{}.heic", "я".repeat(300));
        let s = sanitize_file_name(&long);
        assert!(s.ends_with(".heic") && s.chars().count() <= 180);
    }

    #[test]
    fn unique_names() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a.jpg"), b"1").unwrap();
        std::fs::write(dir.path().join("a (2).jpg"), b"1").unwrap();
        assert_eq!(unique_name(dir.path(), "a.jpg").file_name().unwrap(), "a (3).jpg");
        assert_eq!(unique_name(dir.path(), "b.jpg").file_name().unwrap(), "b.jpg");
    }
}
