//! Persistent user settings (JSON in the app data folder).

use crate::model::ContactsPolicy;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// "system" | "ru" | "en" | "kk"
    pub language: String,
    /// "system" | "light" | "dark"
    pub theme: String,
    /// Where in-flight files are kept. `None` = `<app data>/staging`.
    pub staging_dir: Option<PathBuf>,
    /// Where backups are written. `None` = `Documents/SwitchFlow Backups`.
    pub backup_dir: Option<PathBuf>,
    pub preferred_port: u16,
    pub contacts_policy: ContactsPolicy,
    pub notifications: bool,
    /// Delete staged copies as soon as the target confirms them.
    pub cleanup_staging: bool,
    /// Look for Android phones with USB debugging (starts the adb daemon).
    pub use_adb: bool,
    pub adb_path: Option<PathBuf>,
    pub reduce_motion: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            language: "system".into(),
            theme: "system".into(),
            staging_dir: None,
            backup_dir: None,
            preferred_port: 47820,
            contacts_policy: ContactsPolicy::Merge,
            notifications: true,
            cleanup_staging: true,
            use_adb: false,
            adb_path: None,
            reduce_motion: false,
        }
    }
}

impl Settings {
    pub fn load(path: &Path) -> Settings {
        std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        crate::persist::write_json_atomic(path, self)
    }

    pub fn validated(mut self) -> Settings {
        if !["system", "ru", "en", "kk"].contains(&self.language.as_str()) {
            self.language = "system".into();
        }
        if !["system", "light", "dark"].contains(&self.theme.as_str()) {
            self.theme = "system".into();
        }
        if self.preferred_port < 1024 {
            self.preferred_port = 47820;
        }
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("settings.json");
        assert_eq!(Settings::load(&p), Settings::default());
        let s = Settings { theme: "dark".into(), ..Settings::default() };
        s.save(&p).unwrap();
        assert_eq!(Settings::load(&p).theme, "dark");
        // Unknown and missing fields fall back to defaults.
        std::fs::write(&p, r#"{"theme":"neon","unknown":1}"#).unwrap();
        let s = Settings::load(&p).validated();
        assert_eq!(s.theme, "system");
        assert_eq!(s.preferred_port, 47820);
    }
}
