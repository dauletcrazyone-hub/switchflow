//! Backups to a PC folder and restore from them.
//!
//! A backup is a plain folder anyone can browse (Photos/, Videos/, Contacts/contacts.vcf…)
//! plus `switchflow-backup.json` listing every file with its SHA-256.

use crate::core::Core;
use crate::error::{FResult, Fault};
use crate::model::{now_ms, Category, DeviceInfo, Platform};
use crate::session::{sanitize_file_name, unique_name, Endpoint, ItemState, Session, SessionState};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

pub const MANIFEST: &str = "switchflow-backup.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupDevice {
    pub name: String,
    pub model: String,
    pub manufacturer: String,
    pub platform: Platform,
    pub os_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestItem {
    pub category: Category,
    /// Relative path with forward slashes.
    pub path: String,
    pub name: String,
    pub size: u64,
    pub sha256: String,
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub created_ms: Option<i64>,
    #[serde(default)]
    pub modified_ms: Option<i64>,
    #[serde(default)]
    pub album_name: Option<String>,
    #[serde(default)]
    pub item_count: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version: u32,
    pub created_ms: i64,
    pub device: BackupDevice,
    pub items: Vec<ManifestItem>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupInfo {
    pub path: PathBuf,
    pub device: BackupDevice,
    pub created_ms: i64,
    pub total_bytes: u64,
    pub counts: BTreeMap<Category, u64>,
}

pub fn new_backup_dir(root: &Path, device_name: &str) -> PathBuf {
    let stamp = chrono::Local::now().format("%Y-%m-%d %H-%M").to_string();
    let base = sanitize_file_name(&format!("{device_name} — {stamp}"));
    unique_name(root, &base)
}

fn is_safe_relative(p: &str) -> bool {
    let path = Path::new(p);
    !p.is_empty() && path.components().all(|c| matches!(c, Component::Normal(_)))
}

pub fn read_manifest(dir: &Path) -> FResult<Manifest> {
    let bytes = std::fs::read(dir.join(MANIFEST)).map_err(|_| Fault::State("backup_invalid"))?;
    let m: Manifest = serde_json::from_slice(&bytes).map_err(|_| Fault::State("backup_invalid"))?;
    if m.version != 1 {
        return Err(Fault::State("backup_unsupported_version"));
    }
    // A tampered manifest must not point outside the backup folder.
    if m.items.iter().any(|i| !is_safe_relative(&i.path) || !crate::checksum::is_valid_sha256_hex(&i.sha256)) {
        return Err(Fault::State("backup_invalid"));
    }
    Ok(m)
}

pub fn write_manifest(dir: &Path, s: &Session, device: Option<&DeviceInfo>) -> std::io::Result<()> {
    let items: Vec<ManifestItem> = s
        .items
        .iter()
        .filter(|i| i.state == ItemState::Delivered)
        .filter_map(|i| {
            Some(ManifestItem {
                category: i.category,
                path: i.backup_rel_path.clone()?,
                name: i.name.clone(),
                size: i.size,
                sha256: i.sha256.clone()?,
                mime: i.mime.clone(),
                created_ms: i.created_ms,
                modified_ms: i.modified_ms,
                album_name: i.album_name.clone(),
                item_count: i.item_count,
            })
        })
        .collect();
    if items.is_empty() {
        return Ok(());
    }
    let (name, model, platform) = match &s.source {
        Endpoint::Device { name, model, platform, .. } => (name.clone(), model.clone(), *platform),
        _ => return Ok(()),
    };
    let m = Manifest {
        version: 1,
        created_ms: now_ms(),
        device: BackupDevice {
            name,
            model,
            manufacturer: device.map(|d| d.manufacturer.clone()).unwrap_or_default(),
            platform,
            os_version: device.map(|d| d.os_version.clone()).unwrap_or_default(),
        },
        items,
    };
    crate::persist::write_json_atomic(&dir.join(MANIFEST), &m)
}

pub fn list_backups(root: &Path) -> Vec<BackupInfo> {
    let Ok(rd) = std::fs::read_dir(root) else { return vec![] };
    let mut out: Vec<BackupInfo> = rd
        .flatten()
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let m = read_manifest(&e.path()).ok()?;
            let mut counts = BTreeMap::new();
            for i in &m.items {
                *counts.entry(i.category).or_insert(0) += i.item_count.map(u64::from).unwrap_or(1);
            }
            Some(BackupInfo {
                path: e.path(),
                total_bytes: m.items.iter().map(|i| i.size).sum(),
                created_ms: m.created_ms,
                device: m.device,
                counts,
            })
        })
        .collect();
    out.sort_by(|a, b| b.created_ms.cmp(&a.created_ms));
    out
}

struct Job {
    sid: String,
    key: String,
    src: PathBuf,
    dir: PathBuf,
    rel_dir: String,
    name: String,
    sha: String,
}

impl Core {
    fn claim_backup_item(&self) -> Option<Job> {
        let mut st = self.lock();
        let s = st.session.as_mut()?;
        if s.state != SessionState::Running {
            return None;
        }
        let Endpoint::Folder { path } = &s.target else { return None };
        let root = path.clone();
        let it = s.items.iter_mut().find(|i| i.state == ItemState::Staged)?;
        it.state = ItemState::Delivering;
        let mut rel_dir = it.category.backup_folder().to_string();
        if let Some(album) = &it.album_name {
            if matches!(it.category, Category::Photos | Category::Videos) {
                rel_dir = format!("{rel_dir}/{}", sanitize_file_name(album));
            }
        }
        Some(Job {
            sid: s.id.clone(),
            key: it.key.clone(),
            src: it.staged_path.clone()?,
            dir: root.join(&rel_dir),
            rel_dir,
            name: sanitize_file_name(&it.name),
            sha: it.sha256.clone()?,
        })
    }

    fn finish_backup_item(&self, sid: &str, key: &str, result: Result<(String, &'static str), String>) {
        self.mutate(|st| {
            let Some(s) = st.session.as_mut().filter(|s| s.id == sid) else { return };
            let Some(it) = s.item_mut(key) else { return };
            match result {
                Ok((rel, how)) => {
                    it.state = ItemState::Delivered;
                    it.served = it.size;
                    it.backup_rel_path = Some(rel);
                    it.verification = Some(how.into());
                    it.staged_path = None;
                }
                Err(code) => {
                    it.state = ItemState::Failed;
                    it.failed_at = Some(crate::session::Stage::Delivery);
                    it.reason = Some(code);
                }
            }
            s.bump_items();
            self.check_completion(st);
        });
    }
}

fn place(job: &Job) -> Result<(String, &'static str), String> {
    std::fs::create_dir_all(&job.dir).map_err(|_| "io_error".to_string())?;
    let dest = unique_name(&job.dir, &job.name);
    let file_name = dest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let rel = format!("{}/{}", job.rel_dir, file_name);
    // Same volume: an atomic rename keeps the verified bytes untouched.
    if std::fs::rename(&job.src, &dest).is_ok() {
        return Ok((rel, "moved"));
    }
    let (sha, _) = crate::checksum::copy_with_sha256(&job.src, &dest).map_err(|_| "io_error".to_string())?;
    if sha != job.sha {
        let _ = std::fs::remove_file(&dest);
        return Err("checksum_mismatch".into());
    }
    let _ = std::fs::remove_file(&job.src);
    Ok((rel, "copy_verified"))
}

/// Moves verified staged files into the backup folder while a backup session runs.
pub async fn run_worker(core: Arc<Core>) {
    let mut rx = core.work_tx.subscribe();
    loop {
        while let Some(job) = core.claim_backup_item() {
            let res = tokio::task::spawn_blocking(move || {
                let r = place(&job);
                (job, r)
            })
            .await;
            match res {
                Ok((job, r)) => core.finish_backup_item(&job.sid, &job.key, r),
                Err(_) => break,
            }
        }
        let _ = tokio::time::timeout(Duration::from_secs(2), rx.changed()).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_path_traversal() {
        assert!(is_safe_relative("Photos/a.jpg"));
        assert!(!is_safe_relative("../a.jpg"));
        assert!(!is_safe_relative("C:/Windows/a"));
        assert!(!is_safe_relative("/etc/passwd"));
        assert!(!is_safe_relative(""));
    }

    #[test]
    fn manifest_validation() {
        let dir = tempfile::tempdir().unwrap();
        let bad = serde_json::json!({
            "version": 1, "createdMs": 0,
            "device": { "name": "A", "model": "M", "manufacturer": "X", "platform": "android", "osVersion": "14" },
            "items": [{ "category": "photos", "path": "../../x", "name": "x", "size": 1, "sha256": "a".repeat(64) }]
        });
        std::fs::write(dir.path().join(MANIFEST), bad.to_string()).unwrap();
        assert!(read_manifest(dir.path()).is_err());
    }
}
