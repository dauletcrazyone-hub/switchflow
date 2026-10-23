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

/// Switches the connected Android phones into USB accessory mode: transfer over the cable without
/// USB debugging and without the QR code. Returns how many phones switched.
#[tauri::command]
pub async fn cable_connect(core: Core_<'_>, cable: State<'_, Arc<crate::cable::Cable>>) -> FResult<usize> {
    if !cable.available() {
        return Err(Fault::State("cable_unavailable"));
    }
    let (cable, core) = (cable.inner().clone(), core.inner().clone());
    tauri::async_runtime::spawn_blocking(move || {
        let n = cable.switch_phones();
        // The phones come back in accessory mode within a second or two; link them right away.
        for _ in 0..(if n > 0 { 10 } else { 0 }) {
            std::thread::sleep(std::time::Duration::from_millis(500));
            cable.poll(&core);
            core.set_cable(cable.status());
        }
        n
    })
    .await
    .map_err(|_| Fault::State("cable_failed"))
}

/// Copies the SwitchFlow Android app (bundled with the PC app) into the phone's Download folder over
/// "File transfer" (MTP): no USB debugging and no app store. Installing it is the user's tap on the
/// phone — Android never lets a computer install apps silently without debugging.
#[tauri::command]
pub async fn install_companion(app: AppHandle, device: String) -> FResult<()> {
    use tauri::Manager;
    let apk = app
        .path()
        .resolve("companion/SwitchFlow.apk", tauri::path::BaseDirectory::Resource)
        .map_err(|_| Fault::State("companion_missing"))?;
    let data = std::fs::read(&apk).map_err(|_| Fault::State("companion_missing"))?;
    #[cfg(windows)]
    {
        use crate::discovery::wpd::{push_to_downloads, PushError};
        let bytes = data.len();
        tauri::async_runtime::spawn_blocking(move || push_to_downloads(&device, "SwitchFlow.apk", &data))
            .await
            .map_err(|_| Fault::State("copy_failed"))?
            .map_err(|e| match e {
                PushError::NotFound => Fault::State("phone_not_found"),
                PushError::Locked => Fault::State("phone_locked"),
                PushError::Failed => Fault::State("copy_failed"),
            })?;
        tracing::info!(bytes, "companion app copied to a phone");
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = (device, data);
        Err(Fault::State("copy_failed"))
    }
}

/// Installs the UsbDk driver bundled with the app, for when the installer could not (the .msi
/// package cannot run another installer). Windows asks for administrator approval; the cable
/// option appears after SwitchFlow is restarted.
#[tauri::command]
pub async fn install_cable_driver(app: AppHandle) -> FResult<()> {
    use tauri::Manager;
    let msi = app
        .path()
        .resolve("drivers/UsbDk_x64.msi", tauri::path::BaseDirectory::Resource)
        .map_err(|_| Fault::State("driver_missing"))?;
    if !msi.exists() {
        return Err(Fault::State("driver_missing"));
    }
    let status = tauri::async_runtime::spawn_blocking(move || {
        let mut cmd = std::process::Command::new("powershell");
        cmd.args([
            "-NoProfile",
            "-Command",
            &format!("$p = Start-Process msiexec -Verb RunAs -Wait -PassThru -ArgumentList '/i \"{}\" /qb /norestart'; exit $p.ExitCode", msi.display()),
        ]);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        cmd.status()
    })
    .await
    .map_err(|_| Fault::State("driver_install_failed"))?;
    match status {
        // 3010: installed, a reboot is suggested; UsbDk works without it.
        Ok(s) if s.success() || s.code() == Some(3010) => Ok(()),
        _ => Err(Fault::State("driver_install_failed")),
    }
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
