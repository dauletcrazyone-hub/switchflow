import table from "../../shared/compatibility.json";
import type { Category, CompatStatus, Platform } from "../backend/types";

interface Rule {
  status: CompatStatus;
  note: string | null;
}

const rules = table.rules as unknown as Record<Category, Record<string, Rule>>;

/** Same table the Rust core uses (shared/compatibility.json). */
export function compatRule(category: Category, from: Platform | null, to: Platform | null): Rule {
  if (!from) return { status: "supported", note: null };
  if (!to) {
    // Backing up to a PC folder: everything SwitchFlow can read from the source.
    const transferable = (table.transferable as string[]).includes(category);
    const fromIos = from === "ios" && category === "music";
    return transferable && !fromIos ? { status: "supported", note: null } : { status: "unsupported", note: rules[category]?.[`${from}>${from}`]?.note ?? null };
  }
  return rules[category]?.[`${from}>${to}`] ?? { status: "unsupported", note: null };
}

export function isTransferableStatus(s: CompatStatus): boolean {
  return s === "supported" || s === "action_required";
}
