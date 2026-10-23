// Mirrors the Rust snapshot and command types (src-tauri/src/snapshot.rs, model.rs).

export type Platform = "android" | "ios";

export type Category =
  | "photos"
  | "videos"
  | "contacts"
  | "documents"
  | "music"
  | "calendars"
  | "messages"
  | "callHistory"
  | "settings"
  | "appData"
  | "whatsapp";

export const TRANSFERABLE: Category[] = ["photos", "videos", "contacts", "documents", "music", "calendars"];
export const EXTRA: Category[] = ["messages", "callHistory", "settings", "appData", "whatsapp"];

export type Permission = "granted" | "limited" | "denied" | "unavailable";
export type Role = "none" | "source" | "target";
export type ContactsPolicy = "merge" | "skip" | "keepBoth";
export type CompatStatus = "supported" | "action_required" | "official_tool" | "unsupported";

export interface DeviceInfo {
  id: string;
  platform: Platform;
  manufacturer: string;
  /** Marketing name ("Galaxy A25 5G"). */
  model: string;
  /** Hardware code ("SM-A256E"), when the phone reports it. */
  modelCode?: string | null;
  osVersion: string;
  /** User-set device name; can be anything (a Galaxy A25 named "Galaxy s23"). */
  name: string;
  appVersion: string;
  storageFreeBytes: number;
  storageTotalBytes: number;
  batteryPercent: number | null;
  charging: boolean | null;
}

export interface Album {
  id: string;
  name: string;
  count: number;
  totalBytes: number;
}

export interface CategorySummary {
  category: Category;
  permission: Permission;
  count: number;
  totalBytes: number;
  albums: Album[];
  note: string | null;
  /** When the PC received this count (ms since epoch). */
  scannedMs: number;
}

export interface DeviceView {
  info: DeviceInfo;
  connected: boolean;
  viaUsb: boolean;
  role: Role;
  lastSeenMs: number;
  pairedMs: number;
  inventory: CategorySummary[];
  scanning: Category[];
}

export interface PairRequestView {
  id: string;
  device: DeviceInfo;
  code: string;
  status: "pending" | "approved" | "denied" | "expired";
  createdMs: number;
  viaUsb: boolean;
}

export interface PairingInfo {
  uri: string;
  qrSvg: string;
  port: number;
  hosts: string[];
  expiresMs: number;
  fingerprint: string;
}

export interface ServerStatus {
  running: boolean;
  port: number;
  hosts: string[];
  fingerprint: string;
  error: string | null;
}

export interface UsbDevice {
  id: string;
  source: "wpd" | "adb" | "usb";
  platform: Platform | null;
  manufacturer: string;
  model: string;
  status: "ready" | "unauthorized" | "driver_missing" | "mtp_driver_missing" | "locked" | "offline";
  batteryPercent: number | null;
  serial: string | null;
}

export type SessionKind = "transfer" | "backup" | "restore";
export type SessionState = "preparing" | "running" | "paused" | "waiting_reconnect" | "completed" | "cancelled" | "failed";

export type Endpoint =
  | { type: "device"; id: string; name: string; model: string; platform: Platform }
  | { type: "folder"; path: string }
  | { type: "backup"; path: string; name: string; platform: Platform | null };

export interface CategoryProgress {
  category: Category;
  total: number;
  delivered: number;
  skipped: number;
  failed: number;
  totalBytes: number;
  deliveredBytes: number;
  logicalTotal: number;
  logicalDelivered: number;
}

export interface Totals {
  totalItems: number;
  deliveredItems: number;
  skippedItems: number;
  failedItems: number;
  totalBytes: number;
  deliveredBytes: number;
  workTotal: number;
  workDone: number;
  verifiedItems: number;
}

export type CheckStatus = "pending" | "ok" | "warning" | "failed";

export interface PreflightCheck {
  id: "connection" | "sourceData" | "compatibility" | "targetPermissions" | "targetStorage" | "pcStorage";
  status: CheckStatus;
  message: string;
  params: Record<string, unknown>;
}

export interface FailedItemView {
  key: string;
  category: Category;
  name: string;
  reason: string;
}

export interface SessionView {
  id: string;
  kind: SessionKind;
  state: SessionState;
  source: Endpoint;
  target: Endpoint;
  categories: CategoryProgress[];
  totals: Totals;
  bytesPerSec: number | null;
  etaSec: number | null;
  currentCategory: Category | null;
  currentName: string | null;
  preflight: PreflightCheck[];
  startedMs: number;
  finishedMs: number | null;
  failedItems: FailedItemView[];
  skippedReasons: Record<string, number>;
  backupPath: string | null;
}

export interface ResumableInfo {
  id: string;
  kind: SessionKind;
  sourceName: string;
  targetName: string;
  sourceId: string | null;
  targetId: string | null;
  totalItems: number;
  deliveredItems: number;
  startedMs: number;
}

export interface Snapshot {
  server: ServerStatus;
  desktopName: string;
  pairing: PairingInfo | null;
  pairRequests: PairRequestView[];
  devices: DeviceView[];
  sourceId: string | null;
  targetId: string | null;
  usb: UsbDevice[];
  /** Cable transfer without USB debugging (Android accessory mode through UsbDk). */
  cable: { available: boolean; links: number; phones: number };
  session: SessionView | null;
  resumable: ResumableInfo | null;
}

export interface CategorySelection {
  category: Category;
  albumIds: string[] | null;
}

export interface StartRequest {
  kind: SessionKind;
  selections: CategorySelection[];
  contactsPolicy?: ContactsPolicy;
  backupPath?: string;
  includePreviouslyTransferred?: boolean;
}

export interface CategoryResult {
  category: Category;
  total: number;
  delivered: number;
  skipped: number;
  failed: number;
  deliveredBytes: number;
}

export interface HistoryEntry {
  id: string;
  kind: SessionKind;
  outcome: "completed" | "partial" | "cancelled" | "failed";
  startedMs: number;
  finishedMs: number;
  sourceName: string;
  sourcePlatform: Platform | null;
  targetName: string;
  targetPlatform: Platform | null;
  categories: CategoryResult[];
  totalBytes: number;
  deliveredBytes: number;
  verifiedItems: number;
}

export interface Settings {
  language: "system" | "ru" | "en" | "kk";
  theme: "system" | "light" | "dark";
  stagingDir: string | null;
  backupDir: string | null;
  preferredPort: number;
  contactsPolicy: ContactsPolicy;
  notifications: boolean;
  cleanupStaging: boolean;
  useAdb: boolean;
  adbPath: string | null;
  reduceMotion: boolean;
}

export interface BackupDevice {
  name: string;
  model: string;
  manufacturer: string;
  platform: Platform;
  osVersion: string;
}

export interface BackupInfo {
  path: string;
  device: BackupDevice;
  createdMs: number;
  totalBytes: number;
  counts: Partial<Record<Category, number>>;
}

export interface AppInfo {
  version: string;
  desktopName: string;
  dataDir: string;
  logsDir: string;
  stagingDir: string;
  backupDir: string;
  fingerprint: string;
  adbFound: boolean;
}

export interface BackendError {
  code: string;
  message: string;
}

export function isBackendError(e: unknown): e is BackendError {
  return typeof e === "object" && e !== null && "code" in e && typeof (e as BackendError).code === "string";
}

export const DEFAULT_SETTINGS: Settings = {
  language: "system",
  theme: "system",
  stagingDir: null,
  backupDir: null,
  preferredPort: 47820,
  contactsPolicy: "merge",
  notifications: true,
  cleanupStaging: true,
  useAdb: false,
  adbPath: null,
  reduceMotion: false,
};

export const EMPTY_SNAPSHOT: Snapshot = {
  server: { running: false, port: 0, hosts: [], fingerprint: "", error: null },
  desktopName: "",
  pairing: null,
  pairRequests: [],
  devices: [],
  sourceId: null,
  targetId: null,
  usb: [],
  cable: { available: false, links: 0, phones: 0 },
  session: null,
  resumable: null,
};
