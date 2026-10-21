import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { CategorySummary, DeviceView } from "../../backend/types";
import { useApp } from "../../state/store";
import { CategoryPanel, STALE_SCAN_MS, needsScan } from "./CategoryPanel";

const source: DeviceView = {
  info: {
    id: "p1", platform: "android", manufacturer: "Samsung", model: "SM-A256E", osVersion: "16", name: "Galaxy",
    appVersion: "0.1.0", storageFreeBytes: 1e10, storageTotalBytes: 1e11, batteryPercent: 100, charging: true,
  },
  connected: true,
  viaUsb: false,
  role: "source",
  lastSeenMs: 0,
  pairedMs: 0,
  scanning: [],
  inventory: [
    { category: "photos", permission: "granted", count: 11956, totalBytes: 5.2 * 1024 ** 3, albums: [], note: null, scannedMs: Date.now() },
    // Found on a real Galaxy A25: no calendar events at all.
    { category: "calendars", permission: "granted", count: 0, totalBytes: 0, albums: [], note: null, scannedMs: Date.now() },
  ],
};

beforeEach(() =>
  useApp.setState({ lang: "ru", selection: { categories: ["photos", "calendars"], albums: {}, contactsPolicy: "merge", includePrevious: false } }),
);
afterEach(cleanup);

describe("CategoryPanel", () => {
  it("an empty category unticks itself instead of blocking the start button", () => {
    render(<CategoryPanel source={source} targetPlatform={null} onStart={() => undefined} startLabel="Сохранить на компьютер" />);
    expect(useApp.getState().selection.categories).toEqual(["photos"]);
    const start = screen.getByRole("button", { name: /Сохранить на компьютер/ }) as HTMLButtonElement;
    expect(start.disabled).toBe(false);
  });
});

describe("needsScan", () => {
  const inv = (over: Partial<CategorySummary>): CategorySummary => ({
    category: "photos", permission: "granted", count: 10, totalBytes: 1, albums: [], note: null, scannedMs: 1_000_000, ...over,
  });
  it("recounts missing, empty, refused or stale categories only", () => {
    expect(needsScan(undefined)).toBe(true);
    expect(needsScan(inv({}), 1_000_000 + 30_000)).toBe(false);
    expect(needsScan(inv({}), 1_000_000 + STALE_SCAN_MS + 1)).toBe(true);
    expect(needsScan(inv({ count: 0 }), 1_000_000)).toBe(true);
    expect(needsScan(inv({ permission: "denied" }), 1_000_000)).toBe(true);
  });
});
