import type { Backend } from "./api";
import {
  DEFAULT_SETTINGS,
  EMPTY_SNAPSHOT,
  type AppInfo,
  type BackupInfo,
  type Category,
  type CategorySummary,
  type DeviceInfo,
  type DeviceView,
  type HistoryEntry,
  type PairingInfo,
  type PreflightCheck,
  type SessionView,
  type Settings,
  type Snapshot,
  type StartRequest,
} from "./types";
import { compatRule, isTransferableStatus } from "../lib/compat";

/*
 * DEMO MODE. Everything here is simulated so the interface can be explored without
 * phones. The UI labels every screen as demo while this backend is active, and the
 * real backend never falls back to it.
 */

const GB = 1024 ** 3;
const MB = 1024 ** 2;

const DEMO_ANDROID: DeviceInfo = {
  id: "demo-android",
  platform: "android",
  manufacturer: "Google",
  model: "Pixel 8 Pro",
  modelCode: "GC3VE",
  osVersion: "15",
  name: "Pixel 8 Pro",
  appVersion: "0.1.0",
  storageFreeBytes: 58 * GB,
  storageTotalBytes: 128 * GB,
  batteryPercent: 76,
  charging: false,
};

const DEMO_IPHONE: DeviceInfo = {
  id: "demo-iphone",
  platform: "ios",
  manufacturer: "Apple",
  model: "iPhone 16",
  osVersion: "18.4",
  name: "iPhone 16",
  appVersion: "0.1.0",
  storageFreeBytes: 201 * GB,
  storageTotalBytes: 256 * GB,
  batteryPercent: 92,
  charging: true,
};

function inventoryFor(platform: DeviceInfo["platform"], category: Category): CategorySummary {
  const base = { category, permission: "granted" as const, note: null, albums: [], scannedMs: Date.now() };
  switch (category) {
    case "photos":
      return {
        ...base,
        count: 2418,
        totalBytes: Math.round(9.6 * GB),
        albums:
          platform === "android"
            ? [
                { id: "cam", name: "Camera", count: 1980, totalBytes: Math.round(8.4 * GB) },
                { id: "scr", name: "Screenshots", count: 312, totalBytes: Math.round(0.7 * GB) },
                { id: "wa", name: "WhatsApp Images", count: 126, totalBytes: Math.round(0.5 * GB) },
              ]
            : [
                { id: "fav", name: "Избранное", count: 214, totalBytes: Math.round(1.1 * GB) },
                { id: "trip", name: "Алматы 2025", count: 389, totalBytes: Math.round(1.9 * GB) },
              ],
      };
    case "videos":
      return { ...base, count: 184, totalBytes: Math.round(14.2 * GB) };
    case "contacts":
      return { ...base, count: 642, totalBytes: 410 * 1024 };
    case "documents":
      return { ...base, count: 23, totalBytes: 180 * MB };
    case "music":
      return platform === "ios"
        ? { ...base, permission: "unavailable", count: 0, totalBytes: 0 }
        : { ...base, count: 312, totalBytes: Math.round(2.1 * GB) };
    case "calendars":
      return { ...base, count: 128, totalBytes: 96 * 1024 };
    default:
      return { ...base, permission: "unavailable", count: 0, totalBytes: 0 };
  }
}

function demoQr(): string {
  // A decorative QR-like pattern. Demo mode cannot pair real phones.
  let rects = "";
  let seed = 7;
  const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  const finder = (x: number, y: number) => {
    // Three 7×7 corner markers, like a real QR code.
    for (const [ox, oy] of [[0, 0], [18, 0], [0, 18]] as const) {
      const dx = x - ox;
      const dy = y - oy;
      if (dx >= 0 && dx < 7 && dy >= 0 && dy < 7) {
        const ring = dx === 0 || dy === 0 || dx === 6 || dy === 6;
        const core = dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4;
        return ring || core;
      }
    }
    return null;
  };
  for (let y = 0; y < 25; y++)
    for (let x = 0; x < 25; x++) {
      const on = finder(x, y) ?? rnd() > 0.52;
      if (on) rects += `<rect x="${x + 2}" y="${y + 2}" width="1" height="1"/>`;
    }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29" shape-rendering="crispEdges"><rect width="29" height="29" fill="#fff"/><g fill="#0b1220">${rects}</g></svg>`;
}

interface SimCategory {
  category: Category;
  count: number;
  bytes: number;
  doneBytes: number;
  failing: number;
}

export interface DemoOptions {
  /** Multiplies simulated delays; tests use small values. */
  timeScale?: number;
}

export class DemoBackend implements Backend {
  readonly kind = "demo" as const;
  private snap: Snapshot = structuredClone(EMPTY_SNAPSHOT);
  private listeners = new Set<(s: Snapshot) => void>();
  private finishedListeners = new Set<(h: HistoryEntry) => void>();
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private ticker: ReturnType<typeof setInterval> | null = null;
  private history: HistoryEntry[] = [];
  private settings: Settings = { ...DEFAULT_SETTINGS };
  private sim: SimCategory[] = [];
  private scale: number;
  private nameCounter = 0;

  constructor(opts: DemoOptions = {}) {
    this.scale = opts.timeScale ?? 1;
    this.snap.server = { running: true, port: 47820, hosts: ["192.168.1.24", "127.0.0.1"], fingerprint: "demo", error: null };
    this.snap.desktopName = "DEMO-PC";
    this.history = [
      {
        id: "demo-h1",
        kind: "transfer",
        outcome: "completed",
        startedMs: Date.now() - 3 * 86400_000 - 900_000,
        finishedMs: Date.now() - 3 * 86400_000,
        sourceName: "Galaxy S21",
        sourcePlatform: "android",
        targetName: "Pixel 8 Pro",
        targetPlatform: "android",
        categories: [
          { category: "photos", total: 1204, delivered: 1204, skipped: 0, failed: 0, deliveredBytes: 4.2 * GB },
          { category: "contacts", total: 1, delivered: 1, skipped: 0, failed: 0, deliveredBytes: 300 * 1024 },
        ],
        totalBytes: 4.2 * GB,
        deliveredBytes: 4.2 * GB,
        verifiedItems: 1205,
      },
    ];
  }

  /** Stops every simulated timer (tests and mode switches). */
  dispose() {
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    if (this.ticker) clearInterval(this.ticker);
    this.ticker = null;
    this.listeners.clear();
    this.finishedListeners.clear();
  }

  private later(ms: number, fn: () => void) {
    const t = setTimeout(() => {
      this.timers.delete(t);
      fn();
    }, ms * this.scale);
    this.timers.add(t);
  }

  private emit() {
    const copy = structuredClone(this.snap);
    this.listeners.forEach((l) => l(copy));
  }

  private device(id: string): DeviceView | undefined {
    return this.snap.devices.find((d) => d.info.id === id);
  }

  getSnapshot = async () => structuredClone(this.snap);
  onSnapshot = (cb: (s: Snapshot) => void) => {
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  };
  onSessionFinished = (cb: (h: HistoryEntry) => void) => {
    this.finishedListeners.add(cb);
    return () => this.finishedListeners.delete(cb);
  };

  // ------------------------------------------------------------- pairing

  private nextDemoDevice(): DeviceInfo | null {
    const have = new Set(this.snap.devices.map((d) => d.info.id));
    const pending = new Set(this.snap.pairRequests.map((r) => r.device.id));
    for (const d of [DEMO_ANDROID, DEMO_IPHONE]) if (!have.has(d.id) && !pending.has(d.id)) return d;
    return null;
  }

  private scheduleRequest() {
    const next = this.nextDemoDevice();
    if (!next || !this.snap.pairing) return;
    this.later(1600, () => {
      if (!this.snap.pairing || this.snap.pairRequests.some((r) => r.device.id === next.id)) return;
      this.snap.pairRequests.push({
        id: `pr-${next.id}`,
        device: next,
        code: next.platform === "ios" ? "604218" : "381927",
        status: "pending",
        createdMs: Date.now(),
        viaUsb: false,
      });
      this.emit();
    });
  }

  startPairing = async (): Promise<PairingInfo> => {
    const info: PairingInfo = {
      uri: "switchflow://pair?v=1&demo=1",
      qrSvg: demoQr(),
      port: 47820,
      hosts: ["192.168.1.24"],
      expiresMs: Date.now() + 600_000,
      fingerprint: "demo",
    };
    this.snap.pairing = info;
    this.emit();
    this.scheduleRequest();
    return info;
  };

  stopPairing = async () => {
    this.snap.pairing = null;
    this.snap.pairRequests = [];
    this.emit();
  };

  respondPairing = async (id: string, approve: boolean) => {
    const req = this.snap.pairRequests.find((r) => r.id === id);
    if (!req) throw { code: "not_found", message: "not found" };
    this.snap.pairRequests = this.snap.pairRequests.filter((r) => r.id !== id);
    if (approve) {
      this.snap.devices.push({
        info: req.device,
        connected: true,
        viaUsb: false,
        role: "none",
        lastSeenMs: Date.now(),
        pairedMs: Date.now(),
        inventory: [],
        scanning: [],
      });
      if (!this.snap.sourceId) this.snap.sourceId = req.device.id;
      else if (!this.snap.targetId) this.snap.targetId = req.device.id;
      this.syncRoles();
    }
    this.emit();
    this.scheduleRequest();
  };

  private syncRoles() {
    for (const d of this.snap.devices) {
      d.role = d.info.id === this.snap.sourceId ? "source" : d.info.id === this.snap.targetId ? "target" : "none";
    }
  }

  setRoles = async (source: string | null, target: string | null) => {
    this.snap.sourceId = source;
    this.snap.targetId = target;
    this.syncRoles();
    this.emit();
  };

  swapRoles = async () => {
    if (this.snap.session && !["completed", "cancelled", "failed"].includes(this.snap.session.state))
      throw { code: "session_active", message: "session active" };
    [this.snap.sourceId, this.snap.targetId] = [this.snap.targetId, this.snap.sourceId];
    this.syncRoles();
    this.emit();
  };

  unpairDevice = async (id: string) => {
    this.snap.devices = this.snap.devices.filter((d) => d.info.id !== id);
    if (this.snap.sourceId === id) this.snap.sourceId = null;
    if (this.snap.targetId === id) this.snap.targetId = null;
    this.emit();
  };

  requestScan = async (categories: Category[]) => {
    const src = this.snap.sourceId ? this.device(this.snap.sourceId) : undefined;
    if (!src) throw { code: "no_source", message: "no source" };
    const tgt = this.snap.targetId ? this.device(this.snap.targetId) : undefined;
    const cats = categories.filter((c) => isTransferableStatus(compatRule(c, src.info.platform, tgt?.info.platform ?? null).status));
    src.scanning = Array.from(new Set([...src.scanning, ...cats]));
    this.emit();
    cats.forEach((c, i) =>
      this.later(900 + i * 350, () => {
        const d = this.device(src.info.id);
        if (!d) return;
        d.scanning = d.scanning.filter((x) => x !== c);
        d.inventory = [...d.inventory.filter((x) => x.category !== c), inventoryFor(d.info.platform, c)];
        this.emit();
      }),
    );
  };

  // ------------------------------------------------------------ sessions

  startSession = async (req: StartRequest) => {
    if (this.snap.session && !["completed", "cancelled", "failed"].includes(this.snap.session.state))
      throw { code: "session_active", message: "session active" };
    const src = req.kind === "restore" ? null : this.snap.sourceId ? this.device(this.snap.sourceId) : undefined;
    const tgt = req.kind === "backup" ? null : this.snap.targetId ? this.device(this.snap.targetId) : undefined;
    if (req.kind !== "restore" && !src) throw { code: "no_source", message: "no source" };
    if (req.kind !== "backup" && !tgt) throw { code: "no_target", message: "no target" };

    this.sim = req.selections.map((s) => {
      const inv = src?.inventory.find((i) => i.category === s.category) ?? inventoryFor("android", s.category);
      let count = inv.count;
      let bytes = inv.totalBytes;
      if (s.albumIds && inv.albums.length) {
        const albums = inv.albums.filter((a) => s.albumIds!.includes(a.id));
        count = albums.reduce((n, a) => n + a.count, 0);
        bytes = albums.reduce((n, a) => n + a.totalBytes, 0);
      }
      const files = s.category === "contacts" || s.category === "calendars" ? 1 : count;
      return { category: s.category, count: files, bytes, doneBytes: 0, failing: s.category === "photos" ? 3 : 0 };
    });
    const logical = new Map(req.selections.map((s) => [s.category, src?.inventory.find((i) => i.category === s.category)?.count ?? 1]));

    const session: SessionView = {
      id: `demo-${Date.now()}`,
      kind: req.kind,
      state: "preparing",
      source:
        req.kind === "restore"
          ? { type: "backup", path: "C:\\Users\\Demo\\Documents\\SwitchFlow Backups\\Pixel 8 Pro", name: "Pixel 8 Pro", platform: "android" }
          : { type: "device", id: src!.info.id, name: src!.info.model, model: src!.info.modelCode ?? src!.info.model, platform: src!.info.platform },
      target:
        req.kind === "backup"
          ? { type: "folder", path: "C:\\Users\\Demo\\Documents\\SwitchFlow Backups\\Pixel 8 Pro — демо" }
          : { type: "device", id: tgt!.info.id, name: tgt!.info.model, model: tgt!.info.modelCode ?? tgt!.info.model, platform: tgt!.info.platform },
      categories: this.sim.map((c) => ({
        category: c.category,
        total: c.count,
        delivered: 0,
        skipped: 0,
        failed: 0,
        totalBytes: c.bytes,
        deliveredBytes: 0,
        logicalTotal: c.count === 1 ? logical.get(c.category) ?? 1 : c.count,
        logicalDelivered: 0,
      })),
      totals: this.computeTotals(),
      bytesPerSec: null,
      etaSec: null,
      currentCategory: null,
      currentName: null,
      preflight: [
        this.check("connection", "pending"),
        this.check("sourceData", "pending"),
        this.check("compatibility", "pending"),
        ...(req.kind === "backup" ? [] : [this.check("targetPermissions", "pending"), this.check("targetStorage", "pending")]),
        ...(req.kind === "restore" ? [] : [this.check("pcStorage", "pending")]),
      ],
      startedMs: Date.now(),
      finishedMs: null,
      failedItems: [],
      skippedReasons: {},
      backupPath: req.kind === "backup" ? "C:\\Users\\Demo\\Documents\\SwitchFlow Backups\\Pixel 8 Pro — демо" : null,
    };
    this.snap.session = session;
    this.emit();

    const order: PreflightCheck["id"][] = ["connection", "sourceData", "compatibility", "pcStorage", "targetStorage", "targetPermissions"];
    order.forEach((id, i) =>
      this.later(450 + i * 420, () => {
        const c = this.snap.session?.preflight.find((p) => p.id === id);
        if (!c || this.snap.session?.state !== "preparing") return;
        c.status = id === "compatibility" && src?.info.platform === "ios" && tgt?.info.platform === "android" ? "warning" : "ok";
        c.message = `preflight.${id}.ok`;
        if (id === "compatibility" && c.status === "warning") c.params = { categories: [], notes: ["compat.photos.heic"] };
        this.emit();
        if (this.snap.session.preflight.every((p) => p.status === "ok" || p.status === "warning")) this.run();
      }),
    );
    return session.id;
  };

  private check(id: PreflightCheck["id"], status: PreflightCheck["status"]): PreflightCheck {
    const waitingKey = id === "targetPermissions" || id === "targetStorage" ? `preflight.${id}.waiting` : "preflight.checking";
    return { id, status, message: status === "pending" ? waitingKey : `preflight.${id}.ok`, params: {} };
  }

  private computeTotals() {
    const totalBytes = this.sim.reduce((n, c) => n + c.bytes, 0);
    const done = this.sim.reduce((n, c) => n + c.doneBytes, 0);
    const totalItems = this.sim.reduce((n, c) => n + c.count, 0);
    const delivered = this.sim.reduce((n, c) => n + Math.min(c.count, Math.floor((c.doneBytes / Math.max(c.bytes, 1)) * c.count)), 0);
    return {
      totalItems,
      deliveredItems: delivered,
      skippedItems: 0,
      failedItems: 0,
      totalBytes,
      deliveredBytes: done,
      workTotal: totalBytes,
      workDone: done,
      verifiedItems: delivered,
    };
  }

  private run() {
    const s = this.snap.session;
    if (!s) return;
    s.state = "running";
    if (this.ticker) clearInterval(this.ticker);
    let phase = 0;
    this.ticker = setInterval(() => this.step((phase += 1)), 250 * this.scale);
    this.emit();
  }

  private step(phase: number) {
    const s = this.snap.session;
    if (!s || s.state !== "running") return;
    // Simulated throughput drifts between ~180 and ~260 MB/s so the whole demo takes under a minute.
    const speed = (220 + Math.sin(phase / 6) * 40) * MB;
    let budget = speed * 0.25;
    const current = this.sim.find((c) => c.doneBytes < c.bytes);
    for (const c of this.sim) {
      if (budget <= 0) break;
      const left = c.bytes - c.doneBytes;
      const take = Math.min(left, budget);
      c.doneBytes += take;
      budget -= take;
    }
    s.categories = s.categories.map((p) => {
      const c = this.sim.find((x) => x.category === p.category)!;
      const ratio = c.doneBytes / Math.max(c.bytes, 1);
      const finished = ratio >= 1;
      const failed = finished ? c.failing : 0;
      const delivered = Math.min(c.count - failed, Math.floor(ratio * c.count));
      return {
        ...p,
        delivered,
        failed,
        deliveredBytes: Math.round(c.doneBytes),
        logicalDelivered: c.count === 1 ? (finished ? p.logicalTotal : 0) : delivered,
      };
    });
    const t = this.computeTotals();
    t.failedItems = s.categories.reduce((n, c) => n + c.failed, 0);
    t.deliveredItems = s.categories.reduce((n, c) => n + c.delivered, 0);
    t.verifiedItems = t.deliveredItems;
    s.totals = t;
    s.bytesPerSec = phase > 4 ? speed : null;
    s.etaSec = s.bytesPerSec ? (t.workTotal - t.workDone) / s.bytesPerSec : null;
    if (current) {
      s.currentCategory = current.category;
      this.nameCounter += 1;
      const n = 2000 + (this.nameCounter % 900);
      s.currentName =
        current.category === "photos"
          ? s.source.type === "device" && s.source.platform === "ios" ? `IMG_${n}.HEIC` : `PXL_20250${(n % 9) + 1}14_${n}.jpg`
          : current.category === "videos"
            ? `VID_${n}.mp4`
            : current.category === "contacts"
              ? "contacts.vcf"
              : current.category === "calendars"
                ? "calendar-events.json"
                : current.category === "music"
                  ? `Track ${n % 40}.mp3`
                  : `Документ ${n % 30}.pdf`;
    }
    if (this.sim.every((c) => c.doneBytes >= c.bytes)) this.finish("completed");
    this.emit();
  }

  private finish(state: "completed" | "cancelled") {
    const s = this.snap.session;
    if (!s) return;
    if (this.ticker) clearInterval(this.ticker);
    this.ticker = null;
    s.state = state;
    s.finishedMs = Date.now();
    s.currentName = null;
    s.currentCategory = null;
    s.bytesPerSec = null;
    s.etaSec = null;
    s.failedItems =
      state === "completed"
        ? s.categories
            .filter((c) => c.failed > 0)
            .flatMap((c) =>
              Array.from({ length: c.failed }, (_, i) => ({
                key: `f${i}`,
                category: c.category,
                name: `IMG_${4100 + i}.HEIC`,
                reason: "icloud_unavailable",
              })),
            )
        : [];
    const entry: HistoryEntry = {
      id: s.id,
      kind: s.kind,
      outcome: state === "cancelled" ? "cancelled" : s.totals.failedItems > 0 ? "partial" : "completed",
      startedMs: s.startedMs,
      finishedMs: s.finishedMs,
      sourceName: s.source.type === "folder" ? "" : s.source.name,
      sourcePlatform: s.source.type === "device" ? s.source.platform : null,
      targetName: s.target.type === "device" ? s.target.name : "Этот компьютер",
      targetPlatform: s.target.type === "device" ? s.target.platform : null,
      categories: s.categories.map((c) => ({
        category: c.category,
        total: c.total,
        delivered: c.delivered,
        skipped: c.skipped,
        failed: c.failed,
        deliveredBytes: c.deliveredBytes,
      })),
      totalBytes: s.totals.totalBytes,
      deliveredBytes: s.totals.deliveredBytes,
      verifiedItems: s.totals.verifiedItems,
    };
    this.history.unshift(entry);
    this.finishedListeners.forEach((l) => l(entry));
  }

  pauseSession = async () => {
    if (this.snap.session?.state !== "running") throw { code: "not_running", message: "" };
    this.snap.session.state = "paused";
    this.snap.session.bytesPerSec = null;
    this.snap.session.etaSec = null;
    this.emit();
  };

  resumeSession = async () => {
    if (this.snap.session?.state !== "paused") throw { code: "not_paused", message: "" };
    this.snap.session.state = "running";
    this.emit();
  };

  cancelSession = async () => {
    if (!this.snap.session) throw { code: "not_found", message: "" };
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    this.finish("cancelled");
    this.emit();
  };

  retryFailed = async () => {
    const s = this.snap.session;
    if (!s) return 0;
    const n = s.failedItems.length;
    for (const c of this.sim) c.failing = 0;
    s.categories = s.categories.map((c) => ({ ...c, delivered: c.delivered + c.failed, failed: 0 }));
    s.totals = { ...s.totals, failedItems: 0, deliveredItems: s.totals.deliveredItems + n, verifiedItems: s.totals.verifiedItems + n };
    s.failedItems = [];
    s.state = "completed";
    this.emit();
    return n;
  };

  rerunPreflight = async () => {};

  dismissSession = async () => {
    this.snap.session = null;
    this.emit();
  };

  resumeSavedSession = async () => {
    throw { code: "not_found", message: "" };
  };
  discardSavedSession = async () => {
    this.snap.resumable = null;
    this.emit();
  };

  getHistory = async () => structuredClone(this.history);
  clearHistory = async () => {
    this.history = [];
  };
  getSettings = async () => ({ ...this.settings });
  updateSettings = async (s: Settings) => {
    this.settings = { ...s };
    return { ...s };
  };
  listBackups = async (): Promise<BackupInfo[]> => [
    {
      path: "C:\\Users\\Demo\\Documents\\SwitchFlow Backups\\Galaxy S21 — 2025-09-02 18-40",
      device: { name: "Galaxy S21", model: "SM-G991B", manufacturer: "Samsung", platform: "android", osVersion: "14" },
      createdMs: Date.now() - 40 * 86400_000,
      totalBytes: Math.round(6.3 * GB),
      counts: { photos: 1873, videos: 64, contacts: 512 },
    },
  ];
  getAppInfo = async (): Promise<AppInfo> => ({
    version: "0.1.0",
    desktopName: "DEMO-PC",
    dataDir: "C:\\Users\\Demo\\AppData\\Local\\app.switchflow.desktop",
    logsDir: "C:\\Users\\Demo\\AppData\\Local\\app.switchflow.desktop\\logs",
    stagingDir: "C:\\Users\\Demo\\AppData\\Local\\app.switchflow.desktop\\staging",
    backupDir: "C:\\Users\\Demo\\Documents\\SwitchFlow Backups",
    fingerprint: "demo",
    adbFound: false,
  });
  revealPath = async () => {};
  adbReverse = async () => {};
  cableConnect = async () => 0;
  installCableDriver = async () => {};
  installCompanion = async () => {};
  resetIdentity = async () => {};
  pickFolder = async () => null;
}
