import { describe, expect, it } from "vitest";
import { formatBytes, formatDuration, formatEta, percent } from "./format";
import { translate, resolveLang } from "../i18n";

const nbsp = (s: string) => s.replace(/[  ]/g, " ");

describe("formatting", () => {
  it("formats sizes in binary units per language", () => {
    expect(nbsp(formatBytes("ru", 6.4 * 1024 ** 3))).toBe("6,4 ГБ");
    expect(formatBytes("en", 6.4 * 1024 ** 3)).toBe("6.4 GB");
    expect(formatBytes("en", 512)).toBe("512 B");
    expect(formatBytes("en", 25 * 1024 ** 2)).toBe("25 MB");
    expect(formatBytes("en", -5)).toBe("0 B");
  });

  it("rounds ETA like a person would", () => {
    expect(formatEta("ru", 20)).toBe("меньше минуты");
    expect(formatEta("ru", 180)).toBe("≈ 3 мин");
    expect(formatEta("en", 3600 + 20 * 60)).toBe("≈ 1 h 20 min");
    expect(formatEta("en", 7200)).toBe("≈ 2 h");
  });

  it("reports exact durations", () => {
    expect(formatDuration("ru", 42_000)).toBe("42 с");
    expect(formatDuration("en", 65 * 60_000)).toBe("1 h 5 min");
  });

  it("clamps percent", () => {
    expect(percent(5, 0)).toBe(0);
    expect(percent(150, 100)).toBe(100);
    expect(percent(1, 4)).toBe(25);
  });
});

describe("i18n", () => {
  it("uses Russian plural forms", () => {
    expect(translate("ru", "units.contacts", { count: 1 })).toBe("1 контакт");
    expect(translate("ru", "units.contacts", { count: 3 })).toBe("3 контакта");
    expect(nbsp(translate("ru", "units.contacts", { count: 1248 }))).toBe("1 248 контактов");
    expect(translate("ru", "report.needRetry", { count: 17 })).toBe("17 файлов требуют повторной передачи");
    expect(translate("en", "units.files", { count: 1 })).toBe("1 file");
    expect(translate("en", "units.files", { count: 2 })).toBe("2 files");
  });

  it("interpolates and falls back", () => {
    expect(translate("ru", "transfer.transferred", { done: 428, total: 510 })).toBe("Перенесено 428 из 510");
    // Kazakh is partial: missing keys fall back to Russian.
    expect(translate("kk", "nav.home")).toBe("Басты бет");
    expect(translate("kk", "settings.title")).toBe("Настройки");
    expect(translate("ru", "no.such.key")).toBe("no.such.key");
  });

  it("resolves the system language", () => {
    expect(resolveLang("system", "ru-RU")).toBe("ru");
    expect(resolveLang("system", "kk-KZ")).toBe("kk");
    expect(resolveLang("system", "de-DE")).toBe("en");
    expect(resolveLang("en", "ru-RU")).toBe("en");
  });
});
