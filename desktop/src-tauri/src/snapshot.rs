//! UIStateManager (backend half): one serializable snapshot of everything the UI shows.

use crate::core::{Core, PairStatus, PairingInfo, ResumableInfo, ServerStatus, UsbDevice};
use crate::model::*;
use crate::session::*;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceView {
    pub info: DeviceInfo,
    pub connected: bool,
    pub via_usb: bool,
    pub role: Role,
    pub last_seen_ms: i64,
    pub paired_ms: i64,
    pub inventory: Vec<CategorySummary>,
    pub scanning: Vec<Category>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequestView {
    pub id: String,
    pub device: DeviceInfo,
    pub code: String,
    pub status: PairStatus,
    pub created_ms: i64,
    pub via_usb: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FailedItemView {
    pub key: String,
    pub category: Category,
    pub name: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionView {
    pub id: String,
    pub kind: SessionKind,
    pub state: SessionState,
    pub source: Endpoint,
    pub target: Endpoint,
    pub categories: Vec<CategoryProgress>,
    pub totals: Totals,
    pub bytes_per_sec: Option<f64>,
    pub eta_sec: Option<f64>,
    pub current_category: Option<Category>,
    pub current_name: Option<String>,
    pub preflight: Vec<PreflightCheck>,
    pub started_ms: i64,
    pub finished_ms: Option<i64>,
    pub failed_items: Vec<FailedItemView>,
    pub skipped_reasons: std::collections::BTreeMap<String, u64>,
    pub backup_path: Option<std::path::PathBuf>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub server: ServerStatus,
    pub desktop_name: String,
    pub pairing: Option<PairingInfo>,
    pub pair_requests: Vec<PairRequestView>,
    pub devices: Vec<DeviceView>,
    pub source_id: Option<String>,
    pub target_id: Option<String>,
    pub usb: Vec<UsbDevice>,
    pub cable: crate::core::CableStatus,
    pub session: Option<SessionView>,
    pub resumable: Option<ResumableInfo>,
}

impl Core {
    pub fn snapshot(&self) -> Snapshot {
        let st = self.lock();
        let mut devices: Vec<DeviceView> = st
            .devices
            .values()
            .map(|d| DeviceView {
                info: d.info.clone(),
                connected: d.connected,
                via_usb: d.via_usb,
                role: d.role,
                last_seen_ms: d.last_seen_ms,
                paired_ms: d.paired_ms,
                inventory: d.inventory.values().map(CategorySummary::from).collect(),
                scanning: d.scanning.iter().copied().collect(),
            })
            .collect();
        devices.sort_by_key(|d| d.paired_ms);

        let session = st.session.as_ref().map(|s| {
            let totals = s.totals();
            let running = s.state == SessionState::Running;
            let rate = if running { s.rt.rate.rate() } else { None };
            let eta = rate.filter(|r| *r > 1.0).map(|r| (totals.work_total.saturating_sub(totals.work_done)) as f64 / r);
            let current = s.rt.current.and_then(|i| s.items.get(i)).filter(|_| running);
            let mut skipped_reasons = std::collections::BTreeMap::new();
            for it in s.items.iter().filter(|i| i.state == ItemState::Skipped) {
                *skipped_reasons.entry(it.reason.clone().unwrap_or_default()).or_insert(0) += 1;
            }
            SessionView {
                id: s.id.clone(),
                kind: s.kind,
                state: s.state,
                source: s.source.clone(),
                target: s.target.clone(),
                categories: s.category_progress(),
                totals,
                bytes_per_sec: rate,
                eta_sec: eta,
                current_category: current.map(|i| i.category),
                current_name: current.map(|i| i.name.clone()),
                preflight: s.preflight.clone(),
                started_ms: s.started_ms,
                finished_ms: s.finished_ms,
                failed_items: s
                    .items
                    .iter()
                    .filter(|i| i.state == ItemState::Failed)
                    .take(200)
                    .map(|i| FailedItemView {
                        key: i.key.clone(),
                        category: i.category,
                        name: i.name.clone(),
                        reason: i.reason.clone().unwrap_or_default(),
                    })
                    .collect(),
                skipped_reasons,
                backup_path: match &s.target {
                    Endpoint::Folder { path } => Some(path.clone()),
                    _ => None,
                },
            }
        });

        let resumable = st.saved_session.as_ref().map(|s| {
            let t = s.totals();
            ResumableInfo {
                id: s.id.clone(),
                kind: s.kind.as_str().into(),
                source_name: s.source.display_name(),
                target_name: s.target.display_name(),
                source_id: s.source.device_id().map(str::to_owned),
                target_id: s.target.device_id().map(str::to_owned),
                total_items: t.total_items,
                delivered_items: t.delivered_items,
                started_ms: s.started_ms,
            }
        });

        Snapshot {
            server: st.server.clone(),
            desktop_name: self.desktop_name.clone(),
            pairing: self.pairing_info(&st),
            pair_requests: st
                .pairing
                .requests
                .iter()
                .filter(|r| r.status == PairStatus::Pending)
                .map(|r| PairRequestView {
                    id: r.id.clone(),
                    device: r.device.clone(),
                    code: r.code.clone(),
                    status: r.status,
                    created_ms: r.created_ms,
                    via_usb: r.via_usb,
                })
                .collect(),
            devices,
            source_id: st.source_id.clone(),
            target_id: st.target_id.clone(),
            usb: st.usb.clone(),
            cable: st.cable.clone(),
            session,
            resumable,
        }
    }
}
