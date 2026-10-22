//! Tauri commands: the only surface the UI can call.

use crate::backup::{self, BackupInfo};
use crate::core::{Core, PairingInfo};
use crate::engine::StartRequest;
use crate::error::{FResult, Fault};
use crate::history::HistoryEntry;
use crate::model::Category;
use crate::settings::Settings;
use crate::snapshot::Snapshot;
use serde::Serialize;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

type Core_<'a> = State<'a, Arc<Core>>;

#[tauri::command]
pub fn get_snapshot(core: Core_<'_>) -> Snapshot {
    core.snapshot()
}

#[tauri::command]
pub fn start_pairing(core: Core_<'_>) -> PairingInfo {
    core.start_pairing()
}

#[tauri::command]
pub fn stop_pairing(core: Core_<'_>) {
    core.stop_pairing()
}

#[tauri::command]
pub fn respond_pairing(core: Core_<'_>, id: String, approve: bool) -> FResult<()> {
    core.respond_pairing(&id, approve)
}

#[tauri::command]
pub fn set_roles(core: Core_<'_>, source: Option<String>, target: Option<String>) -> FResult<()> {
    core.set_roles(source, target)
}

#[tauri::command]
pub fn swap_roles(core: Core_<'_>) -> FResult<()> {
    core.swap_roles()
}

#[tauri::command]
pub fn unpair_device(core: Core_<'_>, id: String) -> FResult<()> {
    core.unpair(&id)
}

#[tauri::command]
pub fn request_scan(core: Core_<'_>, categories: Vec<Category>) -> FResult<()> {
    core.request_scan(categories)
}

#[tauri::command]
pub fn start_session(core: Core_<'_>, request: StartRequest) -> FResult<String> {
    core.start_session(request)
}

#[tauri::command]
pub fn pause_session(core: Core_<'_>) -> FResult<()> {
    core.pause()
}

#[tauri::command]
pub fn resume_session(core: Core_<'_>) -> FResult<()> {
    core.resume()
}

#[tauri::command]
pub fn cancel_session(core: Core_<'_>) -> FResult<()> {
    core.cancel()
}

#[tauri::command]
pub fn retry_failed(core: Core_<'_>) -> FResult<u64> {
    core.retry_failed()
}

#[tauri::command]
pub fn rerun_preflight(core: Core_<'_>) -> FResult<()> {
    core.rerun_preflight()
}

#[tauri::command]
pub fn dismiss_session(core: Core_<'_>) -> FResult<()> {
    core.dismiss_session()
}

#[tauri::command]
pub fn resume_saved_session(core: Core_<'_>) -> FResult<()> {
    core.resume_saved_session()
}

#[tauri::command]
pub fn discard_saved_session(core: Core_<'_>) -> FResult<()> {
    core.discard_saved_session()
}

#[tauri::command]
pub fn get_history(core: Core_<'_>) -> Vec<HistoryEntry> {
    core.history.list()
}

#[tauri::command]
pub fn clear_history(core: Core_<'_>) -> FResult<()> {
    core.history.clear()?;
    core.ledger.lock().unwrap_or_else(|p| p.into_inner()).clear()?;
    Ok(())
}

#[tauri::command]
pub fn get_settings(core: Core_<'_>) -> Settings {
    core.settings()
}

#[tauri::command]
pub fn update_settings(core: Core_<'_>, settings: Settings) -> FResult<Settings> {
    core.update_settings(settings)
}

#[tauri::command]
pub fn list_backups(core: Core_<'_>) -> Vec<BackupInfo> {
    backup::list_backups(&core.backup_root())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: String,
    pub desktop_name: String,
    pub data_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub staging_dir: PathBuf,
    pub backup_dir: PathBuf,
    pub fingerprint: String,
    pub adb_found: bool,
}

#[tauri::command]
pub fn get_app_info(app: AppHandle, core: Core_<'_>) -> AppInfo {
    let settings = core.settings();
    AppInfo {
        version: app.package_info().version.to_string(),
        desktop_name: core.desktop_name.clone(),
        data_dir: core.paths.data_dir.clone(),
        logs_dir: core.paths.logs_dir.clone(),
        staging_dir: core.staging_root(),
        backup_dir: core.backup_root(),
        fingerprint: core.snapshot().server.fingerprint,
        adb_found: crate::discovery::adb::find(settings.adb_path.as_deref()).is_some(),
    }
}

/// Opens a folder in Explorer. Only folders SwitchFlow owns may be opened.
#[tauri::command]
pub fn reveal_path(app: AppHandle, core: Core_<'_>, path: PathBuf) -> FResult<()> {
    let canon = std::fs::canonicalize(&path).map_err(|_| Fault::NotFound)?;
    let allowed = [core.backup_root(), core.paths.data_dir.clone(), core.staging_root()];
    let ok = allowed
        .iter()
        .filter_map(|p| std::fs::canonicalize(p).ok())
        .any(|root| canon.starts_with(root));
    if !ok {
        return Err(Fault::State("path_not_allowed"));
    }
    app.opener().open_path(canon.to_string_lossy(), None::<&str>).map_err(|_| Fault::Io("open_failed".into()))
}

/// Routes the phone's 127.0.0.1 to this PC over USB via `adb reverse`.
#[tauri::command]
pub fn adb_reverse(core: Core_<'_>, serial: String) -> FResult<()> {
    let settings = core.settings();
    if !settings.use_adb {
        return Err(Fault::State("adb_disabled"));
    }
    let adb = crate::discovery::adb::find(settings.adb_path.as_deref()).ok_or(Fault::State("adb_not_found"))?;
    let port = core.snapshot().server.port;
    if port == 0 {
        return Err(Fault::State("server_not_running"));
    }
    if crate::discovery::adb::reverse(&adb, &serial, port) {
        Ok(())
    } else {
        Err(Fault::State("adb_reverse_failed"))
    }
}

/// Forgets the PC's TLS identity: every phone must pair again. Restarts the app.
#[tauri::command]
pub fn reset_identity(app: AppHandle, core: Core_<'_>) -> FResult<()> {
    if core.snapshot().session.as_ref().is_some_and(|s| !s.state.is_finished()) {
        return Err(Fault::State("session_active"));
    }
    crate::tls::reset(&core.paths.tls_dir)?;
    // Phones pinned the old certificate: their pairings are useless now.
    let _ = std::fs::remove_file(&core.paths.devices_file);
    app.restart();
}
