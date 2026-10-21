import { formatNumber, translate, type Lang } from "../i18n";

const UNITS = ["b", "kb", "mb", "gb", "tb"] as const;

/** 6 871 947 674 → "6,4 ГБ" / "6.4 GB" (binary units, as Windows Explorer shows them). */
export function formatBytes(lang: Lang, bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) bytes = 0;
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < UNITS.length - 1) {
    v /= 1024;
    i++;
  }
  const digits = i === 0 ? 0 : v < 10 ? 1 : 0;
  return `${formatNumber(lang, v, digits)} ${translate(lang, `format.bytes.${UNITS[i]}`)}`;
}

export function formatSpeed(lang: Lang, bytesPerSec: number): string {
  return translate(lang, "format.perSec", { value: formatBytes(lang, bytesPerSec) });
}

/** Rounded, human ETA: "≈ 3 мин", "≈ 1 ч 20 мин", "меньше минуты". */
export function formatEta(lang: Lang, seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "";
  if (seconds < 50) return translate(lang, "format.lessThanMinute");
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return translate(lang, "format.about", { value: translate(lang, "format.min", { n: minutes }) });
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const value = m === 0 ? translate(lang, "format.hour", { n: h }) : translate(lang, "format.hourMin", { h, m });
  return translate(lang, "format.about", { value });
}

/** Exact elapsed time for reports: "42 с", "12 мин", "1 ч 5 мин". */
export function formatDuration(lang: Lang, ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return translate(lang, "format.sec", { n: s });
  const minutes = Math.round(s / 60);
  if (minutes < 60) return translate(lang, "format.min", { n: minutes });
  return translate(lang, "format.hourMin", { h: Math.floor(minutes / 60), m: minutes % 60 });
}

export function formatDate(lang: Lang, ms: number): string {
  return new Intl.DateTimeFormat(lang === "en" ? "en-GB" : lang === "kk" ? "kk-KZ" : "ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(ms));
}

export function formatCountdown(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export function percent(done: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(100, Math.max(0, (done / total) * 100));
}
