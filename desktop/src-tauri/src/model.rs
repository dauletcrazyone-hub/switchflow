//! Wire and domain types shared by the HTTPS protocol (docs/PROTOCOL.md)
//! and the UI snapshot. All JSON is camelCase.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum Category {
    #[default]
    Photos,
    Videos,
    Contacts,
    Documents,
    Music,
    Calendars,
    Messages,
    CallHistory,
    Settings,
    AppData,
    Whatsapp,
}

impl Category {
    /// Categories SwitchFlow can actually move through the companion apps.
    pub const TRANSFERABLE: [Category; 6] = [
        Category::Photos,
        Category::Videos,
        Category::Contacts,
        Category::Documents,
        Category::Music,
        Category::Calendars,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Category::Photos => "photos",
            Category::Videos => "videos",
            Category::Contacts => "contacts",
            Category::Documents => "documents",
            Category::Music => "music",
            Category::Calendars => "calendars",
            Category::Messages => "messages",
            Category::CallHistory => "callHistory",
            Category::Settings => "settings",
            Category::AppData => "appData",
            Category::Whatsapp => "whatsapp",
        }
    }

    /// Folder name used inside a backup on the PC.
    pub fn backup_folder(self) -> &'static str {
        match self {
            Category::Photos => "Photos",
            Category::Videos => "Videos",
            Category::Contacts => "Contacts",
            Category::Documents => "Documents",
            Category::Music => "Music",
            Category::Calendars => "Calendars",
            _ => "Other",
        }
    }

    pub fn is_transferable(self) -> bool {
        Self::TRANSFERABLE.contains(&self)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Android,
    Ios,
}

impl Platform {
    pub fn key(self) -> &'static str {
        match self {
            Platform::Android => "android",
            Platform::Ios => "ios",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Permission {
    Granted,
    Limited,
    Denied,
    Unavailable,
}

impl Permission {
    pub fn allows_read(self) -> bool {
        matches!(self, Permission::Granted | Permission::Limited)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    #[default]
    None,
    Source,
    Target,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum ContactsPolicy {
    #[default]
    Merge,
    Skip,
    KeepBoth,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub id: String,
    pub platform: Platform,
    pub manufacturer: String,
    /// Marketing name ("Galaxy A25 5G"). `name` is the user-set device name and may be anything.
    pub model: String,
    #[serde(default)]
    pub model_code: Option<String>,
    pub os_version: String,
    pub name: String,
    pub app_version: String,
    pub storage_free_bytes: u64,
    pub storage_total_bytes: u64,
    pub battery_percent: Option<u8>,
    pub charging: Option<bool>,
}

fn clip(s: &str, max: usize) -> String {
    s.chars().filter(|c| !c.is_control()).take(max).collect()
}

impl DeviceInfo {
    /// Bounds every free-text field so a misbehaving client cannot bloat state or the UI.
    pub fn sanitized(mut self) -> Self {
        self.id = clip(&self.id, 64);
        self.manufacturer = clip(&self.manufacturer, 64);
        self.model = clip(&self.model, 64);
        self.model_code = self.model_code.map(|c| clip(&c, 32)).filter(|c| !c.is_empty());
        self.os_version = clip(&self.os_version, 32);
        self.name = clip(&self.name, 80);
        self.app_version = clip(&self.app_version, 32);
        self.battery_percent = self.battery_percent.map(|b| b.min(100));
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SourceItem {
    pub id: String,
    pub category: Category,
    pub name: String,
    pub size: u64,
    #[serde(default = "default_true")]
    pub size_exact: bool,
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub created_ms: Option<i64>,
    #[serde(default)]
    pub modified_ms: Option<i64>,
    #[serde(default)]
    pub album_ids: Vec<String>,
    #[serde(default)]
    pub item_count: Option<u32>,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Album {
    pub id: String,
    pub name: String,
    pub count: u64,
    pub total_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CategoryInventory {
    pub category: Category,
    pub permission: Permission,
    pub count: u64,
    pub total_bytes: u64,
    #[serde(default)]
    pub albums: Vec<Album>,
    #[serde(default)]
    pub items: Vec<SourceItem>,
    #[serde(default)]
    pub note: Option<String>,
    /// Set by the PC when the inventory arrives (phones do not send it).
    #[serde(default, skip_deserializing)]
    pub scanned_ms: i64,
}

/// Inventory without the per-item list, as sent to the UI.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CategorySummary {
    pub category: Category,
    pub permission: Permission,
    pub count: u64,
    pub total_bytes: u64,
    pub albums: Vec<Album>,
    pub note: Option<String>,
    pub scanned_ms: i64,
}

impl From<&CategoryInventory> for CategorySummary {
    fn from(c: &CategoryInventory) -> Self {
        CategorySummary {
            category: c.category,
            permission: c.permission,
            count: c.count,
            total_bytes: c.total_bytes,
            albums: c.albums.clone(),
            note: c.note.clone(),
            scanned_ms: c.scanned_ms,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Command {
    pub seq: u64,
    #[serde(rename = "type")]
    pub kind: String,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PrepareResult {
    pub session_id: String,
    pub ok: bool,
    #[serde(default)]
    pub permissions: BTreeMap<Category, Permission>,
    pub storage_free_bytes: u64,
    #[serde(default)]
    pub error: Option<String>,
}

/// Selection made in the UI: a category plus an optional album filter.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategorySelection {
    pub category: Category,
    #[serde(default)]
    pub album_ids: Option<Vec<String>>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn category_serializes_camel_case() {
        assert_eq!(serde_json::to_string(&Category::CallHistory).unwrap(), "\"callHistory\"");
        assert_eq!(serde_json::to_string(&Category::Photos).unwrap(), "\"photos\"");
        let c: Category = serde_json::from_str("\"appData\"").unwrap();
        assert_eq!(c, Category::AppData);
        for c in Category::TRANSFERABLE {
            assert_eq!(serde_json::to_string(&c).unwrap(), format!("\"{}\"", c.as_str()));
        }
    }

    #[test]
    fn device_info_is_sanitized() {
        let info = DeviceInfo {
            id: "x".repeat(200),
            platform: Platform::Ios,
            manufacturer: "Apple\u{0007}".into(),
            model: "iPhone".into(),
            model_code: None,
            os_version: "18".into(),
            name: "n".into(),
            app_version: "1".into(),
            storage_free_bytes: 1,
            storage_total_bytes: 2,
            battery_percent: Some(250),
            charging: None,
        }
        .sanitized();
        assert_eq!(info.id.len(), 64);
        assert_eq!(info.manufacturer, "Apple");
        assert_eq!(info.battery_percent, Some(100));
    }

    #[test]
    fn source_item_defaults() {
        let item: SourceItem = serde_json::from_str(
            r#"{"id":"1","category":"photos","name":"a.jpg","size":10}"#,
        )
        .unwrap();
        assert!(item.size_exact);
        assert!(item.album_ids.is_empty());
    }
}
