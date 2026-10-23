import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { open } from "@tauri-apps/plugin-dialog";
import type { Backend } from "./api";
import type {
  AppInfo,
  BackupInfo,
  Category,
  HistoryEntry,
  PairingInfo,
  Settings,
  Snapshot,
  StartRequest,
} from "./types";

const SNAPSHOT_EVENT = "sf://snapshot";
const FINISHED_EVENT = "sf://session-finished";

function subscribe<T>(event: string, cb: (payload: T) => void): () => void {
  let unlisten: (() => void) | null = null;
  let cancelled = false;
  void listen<T>(event, (e) => cb(e.payload)).then((fn) => {
    if (cancelled) fn();
    else unlisten = fn;
  });
  return () => {
    cancelled = true;
    unlisten?.();
  };
}

/** The real backend: every call goes to the Rust core through Tauri IPC. */
export class TauriBackend implements Backend {
  readonly kind = "tauri" as const;

  getSnapshot = () => invoke<Snapshot>("get_snapshot");
  onSnapshot = (cb: (s: Snapshot) => void) => subscribe<Snapshot>(SNAPSHOT_EVENT, cb);
  onSessionFinished = (cb: (h: HistoryEntry) => void) => subscribe<HistoryEntry>(FINISHED_EVENT, cb);

  startPairing = () => invoke<PairingInfo>("start_pairing");
  stopPairing = () => invoke<void>("stop_pairing");
  respondPairing = (id: string, approve: boolean) => invoke<void>("respond_pairing", { id, approve });
  setRoles = (source: string | null, target: string | null) => invoke<void>("set_roles", { source, target });
  swapRoles = () => invoke<void>("swap_roles");
  unpairDevice = (id: string) => invoke<void>("unpair_device", { id });
  requestScan = (categories: Category[]) => invoke<void>("request_scan", { categories });

  startSession = (request: StartRequest) => invoke<string>("start_session", { request });
  pauseSession = () => invoke<void>("pause_session");
  resumeSession = () => invoke<void>("resume_session");
  cancelSession = () => invoke<void>("cancel_session");
  retryFailed = () => invoke<number>("retry_failed");
  rerunPreflight = () => invoke<void>("rerun_preflight");
  dismissSession = () => invoke<void>("dismiss_session");
  resumeSavedSession = () => invoke<void>("resume_saved_session");
  discardSavedSession = () => invoke<void>("discard_saved_session");

  getHistory = () => invoke<HistoryEntry[]>("get_history");
  clearHistory = () => invoke<void>("clear_history");
  getSettings = () => invoke<Settings>("get_settings");
  updateSettings = (settings: Settings) => invoke<Settings>("update_settings", { settings });
  listBackups = () => invoke<BackupInfo[]>("list_backups");
  getAppInfo = () => invoke<AppInfo>("get_app_info");
  revealPath = (path: string) => invoke<void>("reveal_path", { path });
  adbReverse = (serial: string) => invoke<void>("adb_reverse", { serial });
  cableConnect = () => invoke<number>("cable_connect");
  installCableDriver = () => invoke<void>("install_cable_driver");
  installCompanion = (device: string) => invoke<void>("install_companion", { device });
  resetIdentity = () => invoke<void>("reset_identity");

  pickFolder = async () => {
    const picked = await open({ directory: true, multiple: false });
    return typeof picked === "string" ? picked : null;
  };
}
