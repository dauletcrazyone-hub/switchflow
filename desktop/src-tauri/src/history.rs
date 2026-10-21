//! TransferHistory and the delivery ledger.
//!
//! History stores only counts, sizes and device names, never file names or contents.
//! The ledger remembers which source items already reached which target so a repeated
//! transfer skips them instead of creating duplicates.

use crate::model::{Category, Platform};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CategoryResult {
    pub category: Category,
    pub total: u64,
    pub delivered: u64,
    pub skipped: u64,
    pub failed: u64,
    pub delivered_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub id: String,
    /// "transfer" | "backup" | "restore"
    pub kind: String,
    /// "completed" | "cancelled" | "failed" | "partial"
    pub outcome: String,
    pub started_ms: i64,
    pub finished_ms: i64,
    pub source_name: String,
    pub source_platform: Option<Platform>,
    pub target_name: String,
    pub target_platform: Option<Platform>,
    pub categories: Vec<CategoryResult>,
    pub total_bytes: u64,
    pub delivered_bytes: u64,
    pub verified_items: u64,
}

pub struct History {
    path: PathBuf,
}

const MAX_ENTRIES: usize = 500;

impl History {
    pub fn new(path: PathBuf) -> Self {
        History { path }
    }

    pub fn list(&self) -> Vec<HistoryEntry> {
        std::fs::read(&self.path)
            .ok()
            .and_then(|b| serde_json::from_slice::<Vec<HistoryEntry>>(&b).ok())
            .unwrap_or_default()
    }

    pub fn append(&self, entry: HistoryEntry) -> std::io::Result<()> {
        let mut all = self.list();
        all.retain(|e| e.id != entry.id);
        all.insert(0, entry);
        all.truncate(MAX_ENTRIES);
        crate::persist::write_json_atomic(&self.path, &all)
    }

    pub fn clear(&self) -> std::io::Result<()> {
        match std::fs::remove_file(&self.path) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

/// Append-only ledger of delivered items, one line per item.
pub struct Ledger {
    path: PathBuf,
    entries: HashSet<String>,
}

impl Ledger {
    pub fn open(path: PathBuf) -> Self {
        let entries = std::fs::read_to_string(&path)
            .map(|s| s.lines().map(str::to_owned).collect())
            .unwrap_or_default();
        Ledger { path, entries }
    }

    pub fn key(target_id: &str, source_id: &str, category: Category, source_item: &str, size: u64) -> String {
        // Hash the identifiers so the ledger file does not reveal library structure.
        crate::checksum::sha256_bytes(
            format!("{target_id}\u{1f}{source_id}\u{1f}{}\u{1f}{source_item}\u{1f}{size}", category.as_str()).as_bytes(),
        )
    }

    pub fn contains(&self, key: &str) -> bool {
        self.entries.contains(key)
    }

    pub fn insert(&mut self, key: String) -> std::io::Result<()> {
        if self.entries.contains(&key) {
            return Ok(());
        }
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&self.path)?;
        writeln!(f, "{key}")?;
        self.entries.insert(key);
        Ok(())
    }

    pub fn clear(&mut self) -> std::io::Result<()> {
        self.entries.clear();
        match std::fs::remove_file(&self.path) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(id: &str) -> HistoryEntry {
        HistoryEntry {
            id: id.into(),
            kind: "transfer".into(),
            outcome: "completed".into(),
            started_ms: 1,
            finished_ms: 2,
            source_name: "A".into(),
            source_platform: Some(Platform::Android),
            target_name: "B".into(),
            target_platform: Some(Platform::Ios),
            categories: vec![],
            total_bytes: 0,
            delivered_bytes: 0,
            verified_items: 0,
        }
    }

    #[test]
    fn history_newest_first() {
        let dir = tempfile::tempdir().unwrap();
        let h = History::new(dir.path().join("h.json"));
        h.append(entry("1")).unwrap();
        h.append(entry("2")).unwrap();
        let ids: Vec<_> = h.list().into_iter().map(|e| e.id).collect();
        assert_eq!(ids, vec!["2", "1"]);
        h.clear().unwrap();
        assert!(h.list().is_empty());
    }

    #[test]
    fn ledger_persists() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("ledger.txt");
        let k = Ledger::key("t", "s", Category::Photos, "42", 100);
        {
            let mut l = Ledger::open(p.clone());
            l.insert(k.clone()).unwrap();
            l.insert(k.clone()).unwrap();
        }
        let l = Ledger::open(p.clone());
        assert!(l.contains(&k));
        assert_eq!(std::fs::read_to_string(&p).unwrap().lines().count(), 1);
    }
}
