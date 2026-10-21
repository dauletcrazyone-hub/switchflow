import type { DeviceInfo } from "../backend/types";

/** What to call a phone: its real model, not the name the user typed into its settings. */
export function deviceTitle(info: Pick<DeviceInfo, "model" | "name">): string {
  return info.model.trim() || info.name;
}

/** The user-set name, only when it adds something beyond the model ("Galaxy s23" on a Galaxy A25). */
export function deviceNickname(info: Pick<DeviceInfo, "model" | "name">): string | null {
  const name = info.name.trim();
  if (!name) return null;
  const norm = (s: string) => s.toLowerCase().replace(/\s+/g, " ").trim();
  return norm(name) === norm(info.model) ? null : name;
}

/** "Samsung SM-A256E · Android 16". */
export function deviceDetails(info: Pick<DeviceInfo, "manufacturer" | "model" | "modelCode" | "platform" | "osVersion">): string {
  const code = info.modelCode && info.modelCode !== info.model ? info.modelCode : null;
  const os = `${info.platform === "ios" ? "iOS" : "Android"}${info.osVersion ? ` ${info.osVersion}` : ""}`;
  return [[info.manufacturer, code].filter(Boolean).join(" "), os].filter(Boolean).join(" · ");
}
