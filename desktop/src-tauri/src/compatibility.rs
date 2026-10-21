//! DataCompatibility: which category can move in which direction.
//! The table lives in `shared/compatibility.json` and is shared with the UI.

use crate::model::{Category, Platform};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CompatStatus {
    Supported,
    ActionRequired,
    OfficialTool,
    Unsupported,
}

impl CompatStatus {
    /// Whether SwitchFlow itself moves this category (possibly after a user action).
    pub fn is_transferable(self) -> bool {
        matches!(self, CompatStatus::Supported | CompatStatus::ActionRequired)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub status: CompatStatus,
    pub note: Option<String>,
}

#[derive(Debug, Deserialize)]
struct Table {
    version: u32,
    rules: HashMap<Category, HashMap<String, Rule>>,
}

static TABLE: OnceLock<Table> = OnceLock::new();

fn table() -> &'static Table {
    TABLE.get_or_init(|| {
        let table: Table = serde_json::from_str(include_str!("../../shared/compatibility.json"))
            .expect("shared/compatibility.json is valid");
        assert_eq!(table.version, 1);
        table
    })
}

pub fn rule(category: Category, from: Platform, to: Platform) -> Rule {
    let key = format!("{}>{}", from.key(), to.key());
    table()
        .rules
        .get(&category)
        .and_then(|m| m.get(&key))
        .cloned()
        .unwrap_or(Rule { status: CompatStatus::Unsupported, note: None })
}

/// Target for backups (a PC folder) accepts every category the source can read.
pub fn can_transfer(category: Category, from: Platform, to: Option<Platform>) -> bool {
    match to {
        Some(to) => category.is_transferable() && rule(category, from, to).status.is_transferable(),
        None => category.is_transferable(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_category_has_all_four_directions() {
        let all = [Platform::Android, Platform::Ios];
        for (cat, map) in &table().rules {
            for a in all {
                for b in all {
                    let k = format!("{}>{}", a.key(), b.key());
                    assert!(map.contains_key(&k), "{cat:?} misses {k}");
                }
            }
        }
    }

    #[test]
    fn honest_limits() {
        // iOS never lets third-party apps read SMS, nor export the Music library.
        assert_eq!(rule(Category::Messages, Platform::Ios, Platform::Android).status, CompatStatus::OfficialTool);
        assert_eq!(rule(Category::Music, Platform::Ios, Platform::Android).status, CompatStatus::Unsupported);
        assert!(!can_transfer(Category::Whatsapp, Platform::Android, Some(Platform::Ios)));
        assert!(can_transfer(Category::Photos, Platform::Android, Some(Platform::Ios)));
        assert!(can_transfer(Category::Documents, Platform::Ios, Some(Platform::Android)));
        assert!(!can_transfer(Category::Messages, Platform::Android, None));
    }
}
