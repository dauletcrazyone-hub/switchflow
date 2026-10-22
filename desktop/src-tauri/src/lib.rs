//! SwitchFlow desktop backend.

pub mod backup;
pub mod cable;
pub mod checksum;
pub mod commands;
pub mod compatibility;
pub mod core;
pub mod discovery;
pub mod engine;
pub mod error;
pub mod history;
pub mod logging;
pub mod model;
pub mod net;
pub mod persist;
pub mod server;
pub mod session;
pub mod settings;
pub mod snapshot;
pub mod storage;
pub mod tls;
pub mod usbmux;

use std::sync::Arc;
use std::time::Duration;
use tauri::{Emitter, Manager};

pub const SNAPSHOT_EVENT: &str = "sf://snapshot";
pub const FINISHED_EVENT: &str = "sf://session-finished";

/// Starts the background tasks every running core needs (also used by integration tests).
pub fn spawn_background(core: Arc<core::Core>) {
    let c = core.clone();
    tauri::async_runtime::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_millis(500));
        let mut n = 0u64;
        loop {
            tick.tick().await;
            c.tick();
            n += 1;
            if n % 4 == 0 {
                c.check_liveness();
            }
            if n % 3 == 0 {
                c.persist_if_dirty();
            }
        }
    });
    tauri::async_runtime::spawn(backup::run_worker(core));
}

fn spawn_ui_bridge(app: tauri::AppHandle, core: Arc<core::Core>) {
    let c = core.clone();
    let a = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut rx = c.subscribe_ui();
        loop {
            if rx.changed().await.is_err() {
                break;
            }
            let _ = a.emit(SNAPSHOT_EVENT, c.snapshot());
            // Coalesce bursts (chunk uploads) into at most ~8 UI updates per second.
            tokio::time::sleep(Duration::from_millis(120)).await;
        }
    });
    tauri::async_runtime::spawn(async move {
        let mut rx = core.subscribe_finished();
        while rx.changed().await.is_ok() {
            let entry = rx.borrow_and_update().clone();
            if let Some(entry) = entry {
                let _ = app.emit(FINISHED_EVENT, entry);
            }
        }
    });
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let data_dir = app.path().app_local_data_dir()?;
            std::fs::create_dir_all(&data_dir)?;
            let documents = app.path().document_dir().ok();
            let paths = core::Paths::new(data_dir, documents);
            let guard = logging::init(&paths.logs_dir);
            app.manage(LogGuard(guard));
            tracing::info!(version = env!("CARGO_PKG_VERSION"), "SwitchFlow starting");

            let desktop_name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".into());
            let core = Arc::new(core::Core::new(paths, desktop_name));
            let identity = tls::load_or_create(&core.paths.tls_dir).map_err(|e| e.to_string())?;

            tauri::async_runtime::spawn(server::serve(core.clone(), identity));
            spawn_background(core.clone());
            let cable = cable::Cable::new();
            discovery::spawn(core.clone(), cable.clone());
            app.manage(cable);
            spawn_ui_bridge(app.handle().clone(), core.clone());
            app.manage(core);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_snapshot,
            commands::start_pairing,
            commands::stop_pairing,
            commands::respond_pairing,
            commands::set_roles,
            commands::swap_roles,
            commands::unpair_device,
            commands::request_scan,
            commands::start_session,
            commands::pause_session,
            commands::resume_session,
            commands::cancel_session,
            commands::retry_failed,
            commands::rerun_preflight,
            commands::dismiss_session,
            commands::resume_saved_session,
            commands::discard_saved_session,
            commands::get_history,
            commands::clear_history,
            commands::get_settings,
            commands::update_settings,
            commands::list_backups,
            commands::get_app_info,
            commands::reveal_path,
            commands::adb_reverse,
            commands::cable_connect,
            commands::install_cable_driver,
            commands::reset_identity,
        ])
        .run(tauri::generate_context!())
        .expect("error while running SwitchFlow");
}

struct LogGuard(#[allow(dead_code)] Option<tracing_appender::non_blocking::WorkerGuard>);
