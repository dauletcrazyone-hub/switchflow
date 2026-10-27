import type {
  AppInfo,
  BackupInfo,
  Category,
  Entry,
  HistoryEntry,
  PairingInfo,
  Settings,
  Snapshot,
  StartRequest,
} from "./types";

/**
 * Everything the UI can ask of the backend. Two implementations exist:
 * `TauriBackend` (the real Rust core) and `DemoBackend` (simulated, clearly labelled).
 */
export interface Backend {
  readonly kind: "tauri" | "demo";
  getSnapshot(): Promise<Snapshot>;
  onSnapshot(cb: (s: Snapshot) => void): () => void;
  onSessionFinished(cb: (h: HistoryEntry) => void): () => void;

  startPairing(): Promise<PairingInfo>;
  stopPairing(): Promise<void>;
  respondPairing(id: string, approve: boolean): Promise<void>;
  setRoles(source: string | null, target: string | null): Promise<void>;
  swapRoles(): Promise<void>;
  unpairDevice(id: string): Promise<void>;
  requestScan(categories: Category[]): Promise<void>;

  startSession(request: StartRequest): Promise<string>;
  pauseSession(): Promise<void>;
  resumeSession(): Promise<void>;
  cancelSession(): Promise<void>;
  retryFailed(): Promise<number>;
  rerunPreflight(): Promise<void>;
  dismissSession(): Promise<void>;
  resumeSavedSession(): Promise<void>;
  discardSavedSession(): Promise<void>;

  getHistory(): Promise<HistoryEntry[]>;
  clearHistory(): Promise<void>;
  getSettings(): Promise<Settings>;
  updateSettings(settings: Settings): Promise<Settings>;
  listBackups(): Promise<BackupInfo[]>;
  /** The source's contacts from its last count, for picking them one by one. */
  getEntries(device: string, category: Category): Promise<Entry[]>;
  getAppInfo(): Promise<AppInfo>;
  revealPath(path: string): Promise<void>;
  adbReverse(serial: string): Promise<void>;
  /** Switches connected Android phones into accessory mode; resolves with how many switched. */
  cableConnect(): Promise<number>;
  /** Installs the bundled UsbDk driver (administrator approval); the cable option appears after a restart. */
  installCableDriver(): Promise<void>;
  /** Copies the SwitchFlow Android app to the phone's Download folder over "File transfer". */
  installCompanion(device: string): Promise<void>;
  /** Opens Explorer at the iPhone app file shipped with SwitchFlow (for Sideloadly / iLoader). */
  showIphoneApp(): Promise<void>;
  resetIdentity(): Promise<void>;
  pickFolder(): Promise<string | null>;
}

export function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}
