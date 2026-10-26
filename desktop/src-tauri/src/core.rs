//! Application core: pairing (part of SecureLocalTransport), DeviceConnection,
//! PermissionManager (scan requests) and the command queues phones long-poll.
//!
//! All state lives behind one `std::sync::Mutex`. Critical sections are short and
//! never span an `.await`; long-polls subscribe to `watch` channels instead.

use crate::error::{FResult, Fault};
use crate::history::{History, Ledger};
use crate::model::*;
use crate::session::Session;
use crate::settings::Settings;
use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tokio::sync::watch;

pub const PAIR_SECRET_TTL: Duration = Duration::from_secs(600);
pub const PAIR_REQUEST_TTL: Duration = Duration::from_secs(120);
pub const DEVICE_TIMEOUT: Duration = Duration::from_secs(15);
/// How long a phone may be offline before it gives up its source/target slot to a connected phone.
/// Long enough for a Wi-Fi blip or an app restart, and for remembered phones to check in after the
/// PC app starts.
pub const SLOT_GRACE: Duration = Duration::from_secs(60);
const MAX_BAD_SECRETS: u32 = 5;
const MAX_PENDING_REQUESTS: usize = 4;
const MAX_DEVICES: usize = 8;
const MAX_QUEUED_COMMANDS: usize = 256;
pub const MAX_INVENTORY_ITEMS: usize = 250_000;
const MAX_ENTRIES: usize = 50_000;

#[derive(Debug, Clone)]
pub struct Paths {
    pub data_dir: PathBuf,
    pub tls_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub settings_file: PathBuf,
    pub history_file: PathBuf,
    pub ledger_file: PathBuf,
    pub session_file: PathBuf,
    /// Paired phones (token hashes only), so phones stay paired across restarts.
    pub devices_file: PathBuf,
    pub default_staging: PathBuf,
    pub default_backups: PathBuf,
}

impl Paths {
    pub fn new(data_dir: PathBuf, documents_dir: Option<PathBuf>) -> Self {
        Paths {
            tls_dir: data_dir.join("tls"),
            logs_dir: data_dir.join("logs"),
            settings_file: data_dir.join("settings.json"),
            history_file: data_dir.join("history.json"),
            ledger_file: data_dir.join("ledger.txt"),
            session_file: data_dir.join("session.json"),
            devices_file: data_dir.join("devices.json"),
            default_staging: data_dir.join("staging"),
            default_backups: documents_dir.unwrap_or_else(|| data_dir.clone()).join("SwitchFlow Backups"),
            data_dir,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub running: bool,
    pub port: u16,
    pub hosts: Vec<String>,
    pub fingerprint: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PairStatus {
    Pending,
    Approved,
    Denied,
    Expired,
}

#[derive(Debug, Clone)]
pub struct PairRequest {
    pub id: String,
    pub device: DeviceInfo,
    pub code: String,
    pub status: PairStatus,
    pub token: Option<String>,
    pub created: Instant,
    pub created_ms: i64,
    pub via_usb: bool,
}

#[derive(Debug, Clone)]
pub struct PairSecret {
    pub value: String,
    pub expires: Instant,
    pub expires_ms: i64,
    pub failures: u32,
}

#[derive(Debug, Default)]
pub struct Pairing {
    pub secret: Option<PairSecret>,
    pub requests: Vec<PairRequest>,
}

#[derive(Debug)]
pub struct DeviceConn {
    pub info: DeviceInfo,
    /// SHA-256 of the phone's bearer token; the token itself is never stored.
    pub token_hash: String,
    pub last_seen: Instant,
    pub last_seen_ms: i64,
    pub paired_ms: i64,
    pub connected: bool,
    pub via_usb: bool,
    pub app_state: String,
    /// Random id of the phone app's current process; a change means the app was restarted.
    pub instance_id: Option<String>,
    pub role: Role,
    pub commands: VecDeque<Command>,
    pub next_seq: u64,
    pub cmd_tx: watch::Sender<u64>,
    pub inventory: BTreeMap<Category, CategoryInventory>,
    pub scanning: BTreeSet<Category>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsbDevice {
    pub id: String,
    /// "wpd" | "adb" | "usb"
    pub source: String,
    pub platform: Option<Platform>,
    pub manufacturer: String,
    pub model: String,
    /// "ready" | "unauthorized" | "driver_missing" | "offline"
    pub status: String,
    pub battery_percent: Option<u8>,
    pub serial: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResumableInfo {
    pub id: String,
    pub kind: String,
    pub source_name: String,
    pub target_name: String,
    pub source_id: Option<String>,
    pub target_id: Option<String>,
    pub total_items: u64,
    pub delivered_items: u64,
    pub started_ms: i64,
}

#[derive(Default)]
pub struct State {
    pub server: ServerStatus,
    pub pairing: Pairing,
    pub devices: BTreeMap<String, DeviceConn>,
    /// token hash → device id
    pub tokens: HashMap<String, String>,
    pub source_id: Option<String>,
    pub target_id: Option<String>,
    pub session: Option<Session>,
    /// A session restored from disk after a restart, waiting for the user.
    pub saved_session: Option<Session>,
    pub usb: Vec<UsbDevice>,
    pub cable: CableStatus,
}

/// Cable transfer without USB debugging (see cable.rs).
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CableStatus {
    /// UsbDk is installed, so phones can be switched into accessory mode.
    pub available: bool,
    /// Phones currently linked over the cable.
    pub links: usize,
    /// Android phones on USB that could be switched to the cable link.
    pub phones: usize,
    /// USB devices cannot be listed: a phone hung on USB blocks the others (see cable.rs).
    pub stuck: bool,
}

pub struct Core {
    pub(crate) state: Mutex<State>,
    pub(crate) ui_tx: watch::Sender<u64>,
    pub(crate) work_tx: watch::Sender<u64>,
    pub(crate) finished_tx: watch::Sender<Option<crate::history::HistoryEntry>>,
    pub paths: Paths,
    pub(crate) settings: Mutex<Settings>,
    pub(crate) history: History,
    pub(crate) ledger: Mutex<Ledger>,
    pub desktop_name: String,
    /// Last rendered QR (uri, svg): snapshots are built several times a second.
    qr_cache: Mutex<Option<(String, String)>>,
}

pub fn random_token(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

/// Tokens are 32 random bytes, so a plain SHA-256 is enough: there is nothing to brute-force.
pub fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// One paired phone as kept in `devices.json`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SavedDevice {
    info: DeviceInfo,
    token_sha256: String,
    paired_ms: i64,
    last_seen_ms: i64,
    via_usb: bool,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SavedDevices {
    version: u32,
    devices: Vec<SavedDevice>,
    source_id: Option<String>,
    target_id: Option<String>,
}

impl DeviceConn {
    fn new(info: DeviceInfo, token_hash: String, paired_ms: i64, last_seen_ms: i64, via_usb: bool, connected: bool) -> Self {
        DeviceConn {
            info,
            token_hash,
            last_seen: Instant::now(),
            last_seen_ms,
            paired_ms,
            connected,
            via_usb,
            app_state: "foreground".into(),
            instance_id: None,
            role: Role::None,
            commands: VecDeque::new(),
            next_seq: 1,
            cmd_tx: watch::channel(0).0,
            inventory: BTreeMap::new(),
            scanning: BTreeSet::new(),
        }
    }
}

/// Restores paired phones as offline; they reconnect with their saved token on the next heartbeat.
fn load_devices(path: &Path, state: &mut State) {
    let Some(saved) = std::fs::read(path).ok().and_then(|b| serde_json::from_slice::<SavedDevices>(&b).ok()) else {
        return;
    };
    for d in saved.devices.into_iter().take(MAX_DEVICES) {
        let info = d.info.sanitized();
        let valid_hash = d.token_sha256.len() == 64 && d.token_sha256.bytes().all(|b| b.is_ascii_hexdigit());
        if info.id.is_empty() || !valid_hash || state.devices.contains_key(&info.id) {
            continue;
        }
        state.tokens.insert(d.token_sha256.clone(), info.id.clone());
        let id = info.id.clone();
        state.devices.insert(id, DeviceConn::new(info, d.token_sha256, d.paired_ms, d.last_seen_ms, d.via_usb, false));
    }
    let known = |id: &Option<String>| id.clone().filter(|i| state.devices.contains_key(i));
    let (source, target) = (known(&saved.source_id), known(&saved.target_id));
    state.source_id = source;
    state.target_id = target.filter(|t| state.source_id.as_ref() != Some(t));
    state.sync_roles();
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl Core {
    pub fn new(paths: Paths, desktop_name: String) -> Core {
        let settings = Settings::load(&paths.settings_file).validated();
        let history = History::new(paths.history_file.clone());
        let ledger = Ledger::open(paths.ledger_file.clone());
        let mut state = State::default();
        if let Some(mut s) = std::fs::read(&paths.session_file)
            .ok()
            .and_then(|b| serde_json::from_slice::<Session>(&b).ok())
        {
            if !s.state.is_finished() {
                s.normalize_after_load();
                state.saved_session = Some(s);
            }
        }
        load_devices(&paths.devices_file, &mut state);
        Core {
            state: Mutex::new(state),
            ui_tx: watch::channel(0).0,
            work_tx: watch::channel(0).0,
            finished_tx: watch::channel(None).0,
            paths,
            settings: Mutex::new(settings),
            history,
            ledger: Mutex::new(ledger),
            desktop_name,
            qr_cache: Mutex::new(None),
        }
    }

    pub(crate) fn lock(&self) -> MutexGuard<'_, State> {
        // A panic while holding the lock must not brick the app; the state stays usable.
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Runs `f` on the state and tells the UI that something changed.
    pub fn mutate<R>(&self, f: impl FnOnce(&mut State) -> R) -> R {
        let r = f(&mut self.lock());
        self.notify_ui();
        r
    }

    pub fn notify_ui(&self) {
        self.ui_tx.send_modify(|v| *v = v.wrapping_add(1));
    }

    pub fn subscribe_ui(&self) -> watch::Receiver<u64> {
        self.ui_tx.subscribe()
    }

    pub fn subscribe_finished(&self) -> watch::Receiver<Option<crate::history::HistoryEntry>> {
        self.finished_tx.subscribe()
    }

    pub fn settings(&self) -> Settings {
        self.settings.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    pub fn update_settings(&self, new: Settings) -> FResult<Settings> {
        let new = new.validated();
        new.save(&self.paths.settings_file)?;
        *self.settings.lock().unwrap_or_else(|p| p.into_inner()) = new.clone();
        self.notify_ui();
        Ok(new)
    }

    pub fn staging_root(&self) -> PathBuf {
        self.settings().staging_dir.unwrap_or_else(|| self.paths.default_staging.clone())
    }

    pub fn backup_root(&self) -> PathBuf {
        self.settings().backup_dir.unwrap_or_else(|| self.paths.default_backups.clone())
    }

    // ---------------------------------------------------------------- server

    pub fn set_server_status(&self, status: ServerStatus) {
        self.mutate(|st| st.server = status);
    }

    pub fn refresh_hosts(&self) {
        let hosts = crate::net::lan_hosts();
        self.mutate(|st| st.server.hosts = hosts);
    }

    /// Writes the paired phones to disk. Called on pairing, unpairing and role changes; a failure
    /// only costs re-pairing after a restart, so it is logged and not reported.
    fn save_devices(&self, st: &State) {
        let saved = SavedDevices {
            version: 1,
            devices: st
                .devices
                .values()
                .map(|d| SavedDevice {
                    info: d.info.clone(),
                    token_sha256: d.token_hash.clone(),
                    paired_ms: d.paired_ms,
                    last_seen_ms: d.last_seen_ms,
                    via_usb: d.via_usb,
                })
                .collect(),
            source_id: st.source_id.clone(),
            target_id: st.target_id.clone(),
        };
        if let Err(e) = crate::persist::write_json_atomic(&self.paths.devices_file, &saved) {
            tracing::warn!(error = %e, "could not save paired devices");
        }
    }

    // --------------------------------------------------------------- pairing

    /// Opens (or re-opens) the pairing window with a fresh one-time secret.
    pub fn start_pairing(&self) -> PairingInfo {
        self.refresh_hosts();
        self.mutate(|st| {
            let now_ms = now_ms();
            st.pairing.secret = Some(PairSecret {
                value: random_token(16),
                expires: Instant::now() + PAIR_SECRET_TTL,
                expires_ms: now_ms + PAIR_SECRET_TTL.as_millis() as i64,
                failures: 0,
            });
            self.pairing_info(st).expect("secret was just set")
        })
    }

    pub fn stop_pairing(&self) {
        self.mutate(|st| {
            st.pairing.secret = None;
            for r in &mut st.pairing.requests {
                if r.status == PairStatus::Pending {
                    r.status = PairStatus::Expired;
                }
            }
        });
    }

    pub(crate) fn pairing_info(&self, st: &State) -> Option<PairingInfo> {
        let secret = st.pairing.secret.as_ref()?;
        let uri = pairing_uri(&st.server, &secret.value, &self.desktop_name);
        Some(PairingInfo {
            qr_svg: self.qr_for(&uri),
            uri,
            port: st.server.port,
            hosts: st.server.hosts.clone(),
            expires_ms: secret.expires_ms,
            fingerprint: st.server.fingerprint.clone(),
        })
    }

    fn qr_for(&self, uri: &str) -> String {
        let mut cache = self.qr_cache.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((u, svg)) = cache.as_ref() {
            if u == uri {
                return svg.clone();
            }
        }
        let svg = crate::net::qr_svg(uri);
        *cache = Some((uri.to_string(), svg.clone()));
        svg
    }

    pub fn handle_pair(&self, secret: &str, device: DeviceInfo, via_usb: bool) -> FResult<(String, String)> {
        let device = device.sanitized();
        if device.id.is_empty() {
            return Err(Fault::BadRequest("device.id"));
        }
        self.mutate(|st| {
            st.pairing.requests.retain(|r| r.created.elapsed() < PAIR_REQUEST_TTL + Duration::from_secs(60));
            let Some(active) = st.pairing.secret.as_mut() else {
                return Err(Fault::BadSecret);
            };
            if Instant::now() > active.expires {
                st.pairing.secret = None;
                return Err(Fault::BadSecret);
            }
            if !constant_time_eq(active.value.as_bytes(), secret.as_bytes()) {
                active.failures += 1;
                if active.failures >= MAX_BAD_SECRETS {
                    // Guessing is pointless: the QR on screen now shows a new secret.
                    active.value = random_token(16);
                    active.failures = 0;
                    tracing::warn!("pairing secret rotated after repeated failures");
                }
                return Err(Fault::BadSecret);
            }
            let pending = st.pairing.requests.iter().filter(|r| r.status == PairStatus::Pending).count();
            if pending >= MAX_PENDING_REQUESTS {
                return Err(Fault::TooManyRequests);
            }
            // A repeated request from the same phone replaces its previous pending one.
            st.pairing.requests.retain(|r| !(r.device.id == device.id && r.status == PairStatus::Pending));
            let code = format!("{:06}", rand::random::<u32>() % 1_000_000);
            let id = random_token(12);
            tracing::info!(platform = device.platform.key(), via_usb, "pairing requested");
            st.pairing.requests.push(PairRequest {
                id: id.clone(),
                device,
                code: code.clone(),
                status: PairStatus::Pending,
                token: None,
                created: Instant::now(),
                created_ms: now_ms(),
                via_usb,
            });
            Ok((id, code))
        })
    }

    pub fn pair_status(&self, pairing_id: &str) -> FResult<(PairStatus, Option<String>)> {
        let mut st = self.lock();
        let r = st.pairing.requests.iter_mut().find(|r| r.id == pairing_id).ok_or(Fault::NotFound)?;
        if r.status == PairStatus::Pending && r.created.elapsed() > PAIR_REQUEST_TTL {
            r.status = PairStatus::Expired;
        }
        Ok((r.status, r.token.clone()))
    }

    /// The user's answer to a pairing request (the PC side of the confirmation).
    pub fn respond_pairing(&self, pairing_id: &str, approve: bool) -> FResult<()> {
        self.mutate(|st| {
            let idx = st.pairing.requests.iter().position(|r| r.id == pairing_id).ok_or(Fault::NotFound)?;
            if st.pairing.requests[idx].status != PairStatus::Pending {
                return Err(Fault::State("pairing_not_pending"));
            }
            if st.pairing.requests[idx].created.elapsed() > PAIR_REQUEST_TTL {
                st.pairing.requests[idx].status = PairStatus::Expired;
                return Err(Fault::State("pairing_expired"));
            }
            if !approve {
                st.pairing.requests[idx].status = PairStatus::Denied;
                return Ok(());
            }
            let req = st.pairing.requests[idx].clone();
            let replacing = st.devices.contains_key(&req.device.id);
            if !replacing && st.devices.len() >= MAX_DEVICES {
                return Err(Fault::State("too_many_devices"));
            }
            // The plain token goes to the phone once (via pair_status); the PC keeps only its hash.
            let token = random_token(32);
            let hash = token_hash(&token);
            if let Some(old) = st.devices.remove(&req.device.id) {
                st.tokens.remove(&old.token_hash);
            }
            st.tokens.insert(hash.clone(), req.device.id.clone());
            let now = now_ms();
            st.devices.insert(req.device.id.clone(), DeviceConn::new(req.device.clone(), hash, now, now, req.via_usb, true));
            let r = &mut st.pairing.requests[idx];
            r.status = PairStatus::Approved;
            r.token = Some(token);
            tracing::info!(platform = req.device.platform.key(), "device paired");
            st.take_slot(&req.device.id);
            st.sync_roles();
            self.save_devices(st);
            Ok(())
        })
    }

    pub fn authenticate(&self, token: &str) -> FResult<String> {
        let mut st = self.lock();
        let id = st.tokens.get(&token_hash(token)).cloned().ok_or(Fault::Unauthorized)?;
        if let Some(d) = st.devices.get_mut(&id) {
            d.last_seen = Instant::now();
            d.last_seen_ms = now_ms();
        }
        Ok(id)
    }

    // --------------------------------------------------------------- devices

    pub fn heartbeat(&self, device_id: &str, hb: Heartbeat, via_usb: bool) -> FResult<Role> {
        let reconnected = self.mutate(|st| {
            let d = st.devices.get_mut(device_id).ok_or(Fault::Unauthorized)?;
            // A paired phone may move between Wi-Fi and the cable.
            d.via_usb = via_usb;
            d.info.storage_free_bytes = hb.storage_free_bytes;
            d.info.storage_total_bytes = hb.storage_total_bytes;
            d.info.battery_percent = hb.battery_percent.map(|b| b.min(100));
            d.info.charging = hb.charging;
            if let Some(s) = hb.app_state {
                d.app_state = s.chars().take(16).collect();
            }
            let was_lost = !d.connected;
            d.connected = true;
            // The app was killed and started again (common on Android): its in-memory work
            // queue is gone, so the PC has to hand out the current session's work again.
            let restarted = match (&d.instance_id, &hb.instance_id) {
                (Some(old), Some(new)) => old != new,
                _ => false,
            };
            if let Some(id) = &hb.instance_id {
                d.instance_id = Some(id.chars().take(64).collect());
            }
            // A remembered phone that comes back takes a slot left free (or held by an offline phone).
            if was_lost && st.take_slot(device_id) {
                st.sync_roles();
                self.save_devices(st);
            }
            let role = st.devices.get(device_id).map_or(Role::None, |d| d.role);
            Ok::<_, Fault>((was_lost, restarted, role))
        })?;
        if reconnected.0 {
            tracing::info!("device reconnected");
            self.on_device_reconnected(device_id);
        } else if reconnected.1 {
            tracing::info!("device app restarted, resending session work");
            self.on_device_restarted(device_id);
        }
        Ok(reconnected.2)
    }

    /// Marks phones that stopped sending heartbeats as lost. Called every 2 seconds.
    pub fn check_liveness(&self) {
        let mut lost = Vec::new();
        {
            let mut st = self.lock();
            for (id, d) in st.devices.iter_mut() {
                if d.connected && d.last_seen.elapsed() > DEVICE_TIMEOUT {
                    d.connected = false;
                    lost.push(id.clone());
                }
            }
            st.pairing.requests.retain(|r| r.created.elapsed() < PAIR_REQUEST_TTL + Duration::from_secs(60));
            for r in &mut st.pairing.requests {
                if r.status == PairStatus::Pending && r.created.elapsed() > PAIR_REQUEST_TTL {
                    r.status = PairStatus::Expired;
                }
            }
            if let Some(s) = st.pairing.secret.as_ref() {
                if Instant::now() > s.expires {
                    st.pairing.secret = None;
                }
            }
            if st.free_stale_slots() {
                st.sync_roles();
                self.save_devices(&st);
            }
        }
        for id in &lost {
            tracing::warn!("device connection lost");
            self.on_device_lost(id);
        }
        self.notify_ui();
    }

    pub fn set_roles(&self, source: Option<String>, target: Option<String>) -> FResult<()> {
        self.mutate(|st| {
            if st.session.as_ref().is_some_and(|s| !s.state.is_finished()) {
                return Err(Fault::State("session_active"));
            }
            if source.is_some() && source == target {
                return Err(Fault::BadRequest("same_device"));
            }
            for id in [&source, &target].into_iter().flatten() {
                if !st.devices.contains_key(id) {
                    return Err(Fault::NotFound);
                }
            }
            st.source_id = source;
            st.target_id = target;
            st.sync_roles();
            self.save_devices(st);
            Ok(())
        })
    }

    pub fn swap_roles(&self) -> FResult<()> {
        let (s, t) = {
            let st = self.lock();
            (st.target_id.clone(), st.source_id.clone())
        };
        self.set_roles(s, t)
    }

    pub fn unpair(&self, device_id: &str) -> FResult<()> {
        self.mutate(|st| {
            if let Some(s) = st.session.as_ref() {
                let involved = s.source.device_id() == Some(device_id) || s.target.device_id() == Some(device_id);
                if involved && !s.state.is_finished() {
                    return Err(Fault::State("session_active"));
                }
            }
            let d = st.devices.remove(device_id).ok_or(Fault::NotFound)?;
            st.tokens.remove(&d.token_hash);
            if st.source_id.as_deref() == Some(device_id) {
                st.source_id = None;
            }
            if st.target_id.as_deref() == Some(device_id) {
                st.target_id = None;
            }
            // Wake its long-poll so the phone learns the token is gone.
            d.cmd_tx.send_modify(|v| *v += 1);
            self.save_devices(st);
            Ok(())
        })
    }

    // -------------------------------------------------------------- commands

    pub async fn wait_commands(&self, device_id: &str, after: u64, wait: Duration) -> FResult<Vec<Command>> {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            let mut rx = {
                let mut st = self.lock();
                let d = st.devices.get_mut(device_id).ok_or(Fault::Unauthorized)?;
                // The phone remembers the last command number across restarts of the PC app, which
                // numbers from 1 again: continue after the phone's number instead of going unseen.
                if after >= d.next_seq {
                    let mut seq = after + 1;
                    for c in d.commands.iter_mut() {
                        c.seq = seq;
                        seq += 1;
                    }
                    d.next_seq = seq;
                }
                let pending: Vec<Command> = d.commands.iter().filter(|c| c.seq > after).cloned().collect();
                if !pending.is_empty() {
                    return Ok(pending);
                }
                d.cmd_tx.subscribe()
            };
            match tokio::time::timeout_at(deadline, rx.changed()).await {
                Ok(Ok(())) => continue,
                _ => return Ok(Vec::new()),
            }
        }
    }

    // ------------------------------------------------------------- inventory

    /// PermissionManager: asks the source phone to request access to exactly these categories.
    pub fn request_scan(&self, categories: Vec<Category>) -> FResult<()> {
        self.mutate(|st| {
            let source = st.source_id.clone().ok_or(Fault::State("no_source"))?;
            let target_platform = st.target_id.as_ref().and_then(|t| st.devices.get(t)).map(|d| d.info.platform);
            let d = st.devices.get_mut(&source).ok_or(Fault::State("no_source"))?;
            if !d.connected {
                return Err(Fault::State("source_disconnected"));
            }
            let cats: Vec<Category> = categories
                .into_iter()
                .filter(|c| crate::compatibility::can_transfer(*c, d.info.platform, target_platform))
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect();
            if cats.is_empty() {
                return Err(Fault::BadRequest("no_transferable_categories"));
            }
            d.scanning.extend(cats.iter().copied());
            push_command(d, "scan", serde_json::json!({ "categories": cats }));
            Ok(())
        })
    }

    pub fn handle_inventory(&self, device_id: &str, categories: Vec<CategoryInventory>) -> FResult<()> {
        let total: usize = categories.iter().map(|c| c.items.len()).sum();
        if total > MAX_INVENTORY_ITEMS {
            return Err(Fault::TooLarge);
        }
        self.mutate(|st| {
            let d = st.devices.get_mut(device_id).ok_or(Fault::Unauthorized)?;
            for mut inv in categories {
                if !inv.category.is_transferable() {
                    continue;
                }
                let cat = inv.category;
                // Trust nothing: recompute totals from the items and drop foreign ones.
                inv.items.retain(|i| i.category == cat && !i.id.is_empty());
                for it in &mut inv.items {
                    it.name = clean_name(&it.name);
                    it.mime = it.mime.chars().take(100).collect();
                    it.album_ids.truncate(16);
                }
                let mut seen = std::collections::HashSet::new();
                inv.items.retain(|i| seen.insert(i.id.clone()));
                inv.total_bytes = inv.items.iter().map(|i| i.size).sum();
                let logical: u64 = inv.items.iter().map(|i| i.item_count.map(u64::from).unwrap_or(1)).sum();
                inv.count = logical;
                for a in &mut inv.albums {
                    a.name = a.name.chars().filter(|c| !c.is_control()).take(120).collect();
                }
                inv.albums.truncate(500);
                // Only single-file categories (contacts) can be picked entry by entry.
                if inv.items.len() == 1 && cat == Category::Contacts {
                    inv.entries.truncate(MAX_ENTRIES);
                    for e in &mut inv.entries {
                        e.id = e.id.chars().take(16).collect();
                        e.name = e.name.chars().filter(|c| !c.is_control()).take(120).collect();
                        e.detail = e.detail.chars().filter(|c| !c.is_control()).take(80).collect();
                    }
                } else {
                    inv.entries.clear();
                }
                inv.scanned_ms = now_ms();
                d.scanning.remove(&cat);
                tracing::info!(category = cat.as_str(), count = inv.count, "inventory received");
                d.inventory.insert(cat, inv);
            }
            Ok(())
        })
    }

    /// The contacts of a phone's last count, for the pick list in the UI.
    pub fn entries(&self, device_id: &str, category: Category) -> FResult<Vec<crate::model::Entry>> {
        let st = self.lock();
        let d = st.devices.get(device_id).ok_or(Fault::NotFound)?;
        Ok(d.inventory.get(&category).map(|i| i.entries.clone()).unwrap_or_default())
    }

    // --------------------------------------------------------------- USB list

    pub fn set_cable(&self, status: CableStatus) {
        let changed = {
            let mut st = self.lock();
            std::mem::replace(&mut st.cable, status.clone()) != status
        };
        if changed {
            self.notify_ui();
        }
    }

    pub fn set_usb_devices(&self, list: Vec<UsbDevice>) {
        let changed = {
            let mut st = self.lock();
            if st.usb != list {
                st.usb = list;
                true
            } else {
                false
            }
        };
        if changed {
            self.notify_ui();
        }
    }

    pub fn history(&self) -> Vec<crate::history::HistoryEntry> {
        self.history.list()
    }

    pub fn data_dir(&self) -> &Path {
        &self.paths.data_dir
    }
}

impl State {
    /// Frees the source/target slot of a phone offline for longer than `SLOT_GRACE`, so the card
    /// does not show a phone that is not there (found with a Galaxy A10 left as "source" for days
    /// while an iPhone was plugged in). Never during a session: a dropped phone is awaited there.
    fn free_stale_slots(&mut self) -> bool {
        if self.session.as_ref().is_some_and(|s| !s.state.is_finished()) {
            return false;
        }
        let stale = |id: &Option<String>| {
            id.as_ref()
                .and_then(|i| self.devices.get(i))
                .is_some_and(|d| !d.connected && d.last_seen.elapsed() > SLOT_GRACE)
        };
        let (free_source, free_target) = (stale(&self.source_id), stale(&self.target_id));
        if free_source {
            self.source_id = None;
        }
        if free_target {
            self.target_id = None;
        }
        free_source || free_target
    }

    /// Puts a phone into the first free slot: the first phone becomes the source, the second the
    /// target. Returns whether a slot changed.
    fn take_slot(&mut self, id: &str) -> bool {
        if self.source_id.as_deref() == Some(id) || self.target_id.as_deref() == Some(id) {
            return false;
        }
        self.free_stale_slots();
        if self.source_id.is_none() {
            self.source_id = Some(id.to_string());
        } else if self.target_id.is_none() {
            self.target_id = Some(id.to_string());
        } else {
            return false;
        }
        true
    }

    /// Sends `role` commands so each phone knows what it is.
    pub fn sync_roles(&mut self) {
        let src = self.source_id.clone();
        let tgt = self.target_id.clone();
        for (id, d) in self.devices.iter_mut() {
            let role = if Some(id) == src.as_ref() {
                Role::Source
            } else if Some(id) == tgt.as_ref() {
                Role::Target
            } else {
                Role::None
            };
            if d.role != role || d.next_seq == 1 {
                d.role = role;
                d.commands.retain(|c| c.kind != "role");
                push_command(d, "role", serde_json::json!({ "role": role }));
            }
        }
    }

    pub fn push(&mut self, device_id: &str, kind: &str, payload: serde_json::Value) {
        if let Some(d) = self.devices.get_mut(device_id) {
            push_command(d, kind, payload);
        }
    }
}

pub fn push_command(d: &mut DeviceConn, kind: &str, payload: serde_json::Value) {
    let seq = d.next_seq;
    d.next_seq += 1;
    d.commands.push_back(Command { seq, kind: kind.into(), payload });
    while d.commands.len() > MAX_QUEUED_COMMANDS {
        d.commands.pop_front();
    }
    d.cmd_tx.send_modify(|v| *v = seq);
}

fn clean_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    let s: String = base.chars().filter(|c| !c.is_control()).take(255).collect();
    if s.trim().is_empty() {
        "file".into()
    } else {
        s
    }
}

pub fn pairing_uri(server: &ServerStatus, secret: &str, desktop_name: &str) -> String {
    let name: String = desktop_name.chars().take(40).collect();
    format!(
        "switchflow://pair?v=1&hosts={}&port={}&fp={}&s={}&n={}",
        server.hosts.join(","),
        server.port,
        server.fingerprint,
        secret,
        crate::net::url_encode(&name)
    )
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingInfo {
    pub uri: String,
    pub qr_svg: String,
    pub port: u16,
    pub hosts: Vec<String>,
    pub expires_ms: i64,
    pub fingerprint: String,
}

#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Heartbeat {
    pub storage_free_bytes: u64,
    pub storage_total_bytes: u64,
    pub battery_percent: Option<u8>,
    pub charging: Option<bool>,
    pub app_state: Option<String>,
    #[serde(default)]
    pub instance_id: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn test_core() -> (Core, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::new(Paths::new(dir.path().to_path_buf(), None), "PC".into());
        core.set_server_status(ServerStatus {
            running: true,
            port: 47820,
            hosts: vec!["192.168.1.2".into()],
            fingerprint: "ab".repeat(32),
            error: None,
        });
        (core, dir)
    }

    pub fn device(id: &str, platform: Platform) -> DeviceInfo {
        DeviceInfo {
            id: id.into(),
            platform,
            manufacturer: "M".into(),
            model: "Model".into(),
            model_code: None,
            os_version: "1".into(),
            name: format!("Phone {id}"),
            app_version: "0.1".into(),
            storage_free_bytes: 10_000_000_000,
            storage_total_bytes: 20_000_000_000,
            battery_percent: Some(50),
            charging: Some(false),
        }
    }

    #[test]
    fn pairing_flow_assigns_roles() {
        let (core, _d) = test_core();
        assert!(matches!(core.handle_pair("x", device("a", Platform::Android), false), Err(Fault::BadSecret)));
        let info = core.start_pairing();
        let secret = info.uri.split("&s=").nth(1).unwrap().split('&').next().unwrap().to_string();
        let (pid, code) = core.handle_pair(&secret, device("a", Platform::Android), false).unwrap();
        assert_eq!(code.len(), 6);
        assert_eq!(core.pair_status(&pid).unwrap().0, PairStatus::Pending);
        core.respond_pairing(&pid, true).unwrap();
        let (status, token) = core.pair_status(&pid).unwrap();
        assert_eq!(status, PairStatus::Approved);
        assert_eq!(core.authenticate(&token.unwrap()).unwrap(), "a");

        let (pid2, _) = core.handle_pair(&secret, device("b", Platform::Ios), false).unwrap();
        core.respond_pairing(&pid2, true).unwrap();
        let st = core.lock();
        assert_eq!(st.source_id.as_deref(), Some("a"));
        assert_eq!(st.target_id.as_deref(), Some("b"));
        assert_eq!(st.devices["b"].role, Role::Target);
    }

    #[test]
    fn secret_rotates_after_failures() {
        let (core, _d) = test_core();
        let info = core.start_pairing();
        for _ in 0..MAX_BAD_SECRETS {
            let _ = core.handle_pair("wrong", device("a", Platform::Android), false);
        }
        let secret = info.uri.split("&s=").nth(1).unwrap().split('&').next().unwrap().to_string();
        assert!(matches!(core.handle_pair(&secret, device("a", Platform::Android), false), Err(Fault::BadSecret)));
    }

    #[test]
    fn denied_pairing_gets_no_token() {
        let (core, _d) = test_core();
        let info = core.start_pairing();
        let secret = info.uri.split("&s=").nth(1).unwrap().split('&').next().unwrap().to_string();
        let (pid, _) = core.handle_pair(&secret, device("a", Platform::Android), false).unwrap();
        core.respond_pairing(&pid, false).unwrap();
        assert_eq!(core.pair_status(&pid).unwrap(), (PairStatus::Denied, None));
        assert!(core.lock().devices.is_empty());
    }

    fn pair(core: &Core, id: &str) -> String {
        let info = core.start_pairing();
        let secret = info.uri.split("&s=").nth(1).unwrap().split('&').next().unwrap().to_string();
        let (pid, _) = core.handle_pair(&secret, device(id, Platform::Android), false).unwrap();
        core.respond_pairing(&pid, true).unwrap();
        core.pair_status(&pid).unwrap().1.unwrap()
    }

    fn restart(core: Core) -> Core {
        Core::new(core.paths.clone(), "PC".into())
    }

    #[test]
    fn paired_phones_survive_a_restart_without_storing_tokens() {
        let (core, _d) = test_core();
        let (ta, tb) = (pair(&core, "a"), pair(&core, "b"));
        core.swap_roles().unwrap();
        let file = std::fs::read_to_string(&core.paths.devices_file).unwrap();
        assert!(!file.contains(&ta) && !file.contains(&tb), "plain tokens must not reach the disk");
        assert!(file.contains(&token_hash(&ta)));

        let core = restart(core);
        assert_eq!(core.authenticate(&ta).unwrap(), "a");
        assert_eq!(core.authenticate(&tb).unwrap(), "b");
        assert!(matches!(core.authenticate("not-a-token"), Err(Fault::Unauthorized)));
        let st = core.lock();
        assert!(!st.devices["a"].connected, "offline until the phone checks in");
        assert_eq!((st.source_id.as_deref(), st.target_id.as_deref()), (Some("b"), Some("a")));
        assert_eq!(st.devices["a"].role, Role::Target);
    }

    #[test]
    fn an_offline_phone_gives_up_its_slot() {
        let (core, _d) = test_core();
        let ta = pair(&core, "a");
        let hb = || Heartbeat { storage_free_bytes: 0, storage_total_bytes: 0, battery_percent: None, charging: None, app_state: None, instance_id: None };
        // Restarted PC: "a" is remembered as the source but has not checked in yet.
        let core = restart(core);
        core.check_liveness();
        assert_eq!(core.lock().source_id.as_deref(), Some("a"), "kept while it may still come back");

        core.lock().devices.get_mut("a").unwrap().last_seen = Instant::now() - SLOT_GRACE - Duration::from_secs(1);
        core.check_liveness();
        assert_eq!(core.lock().source_id, None);
        assert_eq!(core.lock().devices["a"].role, Role::None);

        // A new phone takes the free source slot; "a" coming back becomes the target.
        let _tb = pair(&core, "b");
        assert_eq!(core.authenticate(&ta).unwrap(), "a");
        assert_eq!(core.heartbeat("a", hb(), false).unwrap(), Role::Target);
        let st = core.lock();
        assert_eq!((st.source_id.as_deref(), st.target_id.as_deref()), (Some("b"), Some("a")));
    }

    #[test]
    fn a_phone_dropped_mid_session_keeps_its_slot() {
        let (core, _d) = test_core();
        pair(&core, "a");
        let mut st = core.lock();
        st.devices.get_mut("a").unwrap().connected = false;
        st.devices.get_mut("a").unwrap().last_seen = Instant::now() - SLOT_GRACE * 2;
        assert!(st.free_stale_slots());
        st.source_id = Some("a".into());
        use crate::session::{Endpoint, Session, SessionKind, SessionState};
        st.session = Some(Session {
            id: "s".into(),
            kind: SessionKind::Backup,
            source: Endpoint::Device { id: "a".into(), name: "A".into(), model: "A".into(), platform: Platform::Android },
            target: Endpoint::Folder { path: "backup".into() },
            categories: vec![],
            contacts_policy: Default::default(),
            state: SessionState::Running,
            items: vec![],
            preflight: vec![],
            started_ms: 0,
            finished_ms: None,
            staging_dir: "staging".into(),
            error: None,
            target_prepare: None,
            rt: Default::default(),
        });
        assert!(!st.free_stale_slots(), "an unfinished session waits for the phone");
        assert_eq!(st.source_id.as_deref(), Some("a"));
    }

    #[test]
    fn unpaired_phone_stays_forgotten_after_a_restart() {
        let (core, _d) = test_core();
        let token = pair(&core, "a");
        core.unpair("a").unwrap();
        let core = restart(core);
        assert!(matches!(core.authenticate(&token), Err(Fault::Unauthorized)));
        assert!(core.lock().devices.is_empty());
    }

    #[tokio::test]
    async fn command_numbers_continue_after_a_restart() {
        let (core, _d) = test_core();
        pair(&core, "a");
        let core = restart(core);
        // The phone saw commands up to 57 before the PC app restarted.
        let cmds = core.wait_commands("a", 57, Duration::from_millis(10)).await.unwrap();
        assert_eq!(cmds.len(), 1);
        assert_eq!((cmds[0].kind.as_str(), cmds[0].seq), ("role", 58));
        core.lock().push("a", "scan", serde_json::json!({}));
        let next = core.wait_commands("a", 58, Duration::from_millis(10)).await.unwrap();
        assert_eq!(next[0].seq, 59);
    }

    #[test]
    fn damaged_devices_file_is_ignored() {
        let (core, _d) = test_core();
        std::fs::write(&core.paths.devices_file, b"{ not json").unwrap();
        let core = restart(core);
        assert!(core.lock().devices.is_empty());
    }

    #[test]
    fn uri_contains_everything() {
        let (core, _d) = test_core();
        let info = core.start_pairing();
        assert!(info.uri.starts_with("switchflow://pair?v=1&hosts="));
        assert!(info.uri.contains("127.0.0.1&port=47820&"));
        assert!(info.uri.contains(&format!("fp={}", "ab".repeat(32))));
        assert!(info.qr_svg.contains("<svg"));
    }
}
