import { create } from "zustand";
import type { Backend } from "../backend/api";
import { isTauri } from "../backend/api";
import { DemoBackend } from "../backend/demo";
import { TauriBackend } from "../backend/tauri";
import {
  DEFAULT_SETTINGS,
  EMPTY_SNAPSHOT,
  isBackendError,
  type Category,
  type ContactsPolicy,
  type HistoryEntry,
  type Settings,
  type Snapshot,
} from "../backend/types";
import { resolveLang, translate, hasKey, type Lang } from "../i18n";

export type Screen = "home" | "history" | "backups" | "settings" | "help" | "about";

export interface Toast {
  id: number;
  kind: "error" | "info" | "success";
  text: string;
}

export interface Selection {
  categories: Category[];
  /** `null` or missing = all albums. */
  albums: Partial<Record<Category, string[] | null>>;
  contactsPolicy: ContactsPolicy;
  includePrevious: boolean;
}

interface AppState {
  backend: Backend;
  realAvailable: boolean;
  demo: boolean;
  ready: boolean;
  snapshot: Snapshot;
  settings: Settings;
  lang: Lang;
  theme: "light" | "dark";
  systemReducedMotion: boolean;
  screen: Screen;
  pairingOpen: boolean;
  toasts: Toast[];
  selection: Selection;
  historyVersion: number;

  init(): Promise<void>;
  setScreen(screen: Screen): void;
  setDemo(on: boolean): Promise<void>;
  saveSettings(patch: Partial<Settings>): Promise<void>;
  toast(kind: Toast["kind"], text: string): void;
  dismissToast(id: number): void;
  /** Runs a backend call; failures become a translated toast instead of an exception. */
  run<T>(fn: (b: Backend) => Promise<T>): Promise<T | undefined>;
  t(key: string, params?: Record<string, string | number>): string;

  toggleCategory(c: Category, on?: boolean): void;
  setAlbums(c: Category, ids: string[] | null): void;
  setContactsPolicy(p: ContactsPolicy): void;
  setIncludePrevious(on: boolean): void;
  setSelected(categories: Category[]): void;
  openPairing(): void;
  closePairing(): void;
}

const DEMO_KEY = "sf.demo";

function readDemoFlag(): boolean {
  try {
    return localStorage.getItem(DEMO_KEY) === "1";
  } catch {
    return false;
  }
}

function writeDemoFlag(on: boolean) {
  try {
    localStorage.setItem(DEMO_KEY, on ? "1" : "0");
  } catch {
    /* storage unavailable: the flag simply is not remembered */
  }
}

let unsubscribers: Array<() => void> = [];
let toastId = 0;
let settingsBackend: Backend | null = null;

function systemDark(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-color-scheme: dark)").matches;
}

function resolveTheme(setting: Settings["theme"]): "light" | "dark" {
  if (setting === "light" || setting === "dark") return setting;
  return systemDark() ? "dark" : "light";
}

async function notifyFinished(entry: HistoryEntry, lang: Lang, enabled: boolean) {
  if (!enabled || !isTauri() || document.hasFocus()) return;
  try {
    const n = await import("@tauri-apps/plugin-notification");
    let granted = await n.isPermissionGranted();
    if (!granted) granted = (await n.requestPermission()) === "granted";
    if (!granted) return;
    const title = translate(lang, `report.${entry.outcome === "partial" ? "partial" : entry.outcome}`);
    const delivered = entry.categories.reduce((s, c) => s + c.delivered, 0);
    const total = entry.categories.reduce((s, c) => s + c.total, 0);
    n.sendNotification({ title: `SwitchFlow — ${title}`, body: translate(lang, "transfer.transferred", { done: delivered, total }) });
  } catch {
    /* notifications are a convenience */
  }
}

export const useApp = create<AppState>((set, get) => ({
  backend: new DemoBackend(),
  realAvailable: false,
  demo: true,
  ready: false,
  snapshot: EMPTY_SNAPSHOT,
  settings: DEFAULT_SETTINGS,
  lang: resolveLang("system"),
  theme: resolveTheme("system"),
  systemReducedMotion: false,
  screen: "home",
  pairingOpen: false,
  toasts: [],
  selection: { categories: [], albums: {}, contactsPolicy: "merge", includePrevious: false },
  historyVersion: 0,

  async init() {
    const realAvailable = isTauri();
    const demo = !realAvailable || readDemoFlag();
    settingsBackend = realAvailable ? new TauriBackend() : new DemoBackend();
    let settings = DEFAULT_SETTINGS;
    try {
      settings = await settingsBackend.getSettings();
    } catch {
      /* defaults */
    }
    const media = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    set({
      realAvailable,
      settings,
      lang: resolveLang(settings.language),
      theme: resolveTheme(settings.theme),
      systemReducedMotion: !!media?.matches,
      selection: { ...get().selection, contactsPolicy: settings.contactsPolicy },
    });
    media?.addEventListener?.("change", (e) => set({ systemReducedMotion: e.matches }));
    window.matchMedia?.("(prefers-color-scheme: dark)").addEventListener?.("change", () => {
      set({ theme: resolveTheme(get().settings.theme) });
    });
    await attach(demo ? new DemoBackend() : new TauriBackend(), demo);
    set({ ready: true });
  },

  setScreen(screen) {
    set({ screen });
  },

  async setDemo(on) {
    if (!get().realAvailable && !on) return;
    writeDemoFlag(on);
    set({ pairingOpen: false, selection: { ...get().selection, categories: [], albums: {} } });
    await attach(on ? new DemoBackend() : new TauriBackend(), on);
  },

  async saveSettings(patch) {
    const next = { ...get().settings, ...patch };
    set({ settings: next, lang: resolveLang(next.language), theme: resolveTheme(next.theme) });
    try {
      const saved = await (settingsBackend ?? get().backend).updateSettings(next);
      set({ settings: saved });
    } catch (e) {
      get().toast("error", errorText(e, get().lang));
    }
  },

  toast(kind, text) {
    const id = ++toastId;
    set({ toasts: [...get().toasts.slice(-3), { id, kind, text }] });
    setTimeout(() => get().dismissToast(id), kind === "error" ? 7000 : 4000);
  },

  dismissToast(id) {
    set({ toasts: get().toasts.filter((t) => t.id !== id) });
  },

  async run(fn) {
    try {
      return await fn(get().backend);
    } catch (e) {
      get().toast("error", errorText(e, get().lang));
      return undefined;
    }
  },

  t(key, params) {
    return translate(get().lang, key, params);
  },

  toggleCategory(c, on) {
    const sel = get().selection;
    const has = sel.categories.includes(c);
    const want = on ?? !has;
    if (want === has) return;
    set({ selection: { ...sel, categories: want ? [...sel.categories, c] : sel.categories.filter((x) => x !== c) } });
  },

  setAlbums(c, ids) {
    const sel = get().selection;
    set({ selection: { ...sel, albums: { ...sel.albums, [c]: ids } } });
  },

  setContactsPolicy(p) {
    set({ selection: { ...get().selection, contactsPolicy: p } });
  },

  setIncludePrevious(on) {
    set({ selection: { ...get().selection, includePrevious: on } });
  },

  setSelected(categories) {
    set({ selection: { ...get().selection, categories } });
  },

  openPairing() {
    set({ pairingOpen: true });
    void get().run((b) => b.startPairing());
  },

  closePairing() {
    set({ pairingOpen: false });
    void get().run((b) => b.stopPairing());
  },
}));

async function attach(backend: Backend, demo: boolean) {
  unsubscribers.forEach((u) => u());
  unsubscribers = [];
  const old = useApp.getState().backend;
  if (old !== backend && old instanceof DemoBackend) old.dispose();
  useApp.setState({ backend, demo, snapshot: EMPTY_SNAPSHOT });
  unsubscribers.push(
    backend.onSnapshot((snapshot) => {
      if (useApp.getState().backend === backend) useApp.setState({ snapshot });
    }),
  );
  unsubscribers.push(
    backend.onSessionFinished((entry) => {
      const st = useApp.getState();
      useApp.setState({ historyVersion: st.historyVersion + 1 });
      void notifyFinished(entry, st.lang, st.settings.notifications);
    }),
  );
  try {
    const snapshot = await backend.getSnapshot();
    if (useApp.getState().backend === backend) useApp.setState({ snapshot });
  } catch {
    /* the snapshot event will arrive */
  }
}

export function errorText(e: unknown, lang: Lang): string {
  if (isBackendError(e)) {
    const key = `errors.${e.code}`;
    return hasKey(lang, key) ? translate(lang, key) : translate(lang, "errors.unknown");
  }
  return translate(lang, "errors.unknown");
}

/** Translation hook bound to the current language. */
export function useT() {
  const lang = useApp((s) => s.lang);
  return (key: string, params?: Record<string, string | number>) => translate(lang, key, params);
}

export function useReducedMotion(): boolean {
  const sys = useApp((s) => s.systemReducedMotion);
  const pref = useApp((s) => s.settings.reduceMotion);
  return sys || pref;
}
