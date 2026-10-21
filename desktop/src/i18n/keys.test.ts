import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { hasKey } from "./index";
import table from "../../shared/compatibility.json";

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return sources(p);
    return /\.(tsx?)$/.test(f) && !/\.test\./.test(f) ? [p] : [];
  });
}

describe("translation keys", () => {
  const root = join(__dirname, "..");
  const used = new Set<string>();
  for (const file of sources(root)) {
    const text = readFileSync(file, "utf8");
    for (const m of text.matchAll(/\bt\(\s*"([a-zA-Z][\w.]+)"/g)) used.add(m[1]!);
  }

  it("finds keys to check", () => {
    expect(used.size).toBeGreaterThan(100);
  });

  it.each(["ru", "en"] as const)("every literal key exists in %s", (lang) => {
    const missing = [...used].filter((k) => !hasKey(lang, k));
    expect(missing).toEqual([]);
  });

  it("every compatibility note has a translation", () => {
    const notes = new Set<string>();
    for (const rules of Object.values(table.rules)) for (const r of Object.values(rules)) if (r.note) notes.add(r.note);
    for (const lang of ["ru", "en"] as const) expect([...notes].filter((n) => !hasKey(lang, n))).toEqual([]);
  });
});
