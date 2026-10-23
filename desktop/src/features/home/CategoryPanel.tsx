import { AnimatePresence, motion } from "motion/react";
import { ArrowRight, ChevronDown, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { EXTRA, TRANSFERABLE, type Category, type CategorySummary, type DeviceView } from "../../backend/types";
import { Button } from "../../components/ui/Button";
import { Checkbox } from "../../components/ui/Controls";
import { compatRule, isTransferableStatus } from "../../lib/compat";
import { formatBytes } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";
import { CategoryCard } from "./CategoryCard";

/** Categories that can be selected for this source/target pair, with their readiness. */
export function useCategoryState(source: DeviceView | undefined, targetPlatform: DeviceView["info"]["platform"] | null) {
  const selection = useApp((s) => s.selection);
  return useMemo(() => {
    const from = source?.info.platform ?? null;
    const rows = TRANSFERABLE.map((category) => {
      const rule = compatRule(category, from, targetPlatform);
      const inventory = source?.inventory.find((i) => i.category === category);
      const scanning = !!source?.scanning.includes(category);
      return { category, rule, inventory, scanning, selected: selection.categories.includes(category) };
    });
    const selected = rows.filter((r) => r.selected);
    const ready =
      selected.length > 0 &&
      selected.every((r) => r.inventory && (r.inventory.permission === "granted" || r.inventory.permission === "limited") && r.inventory.count > 0);
    const pendingScan = selected.some((r) => r.scanning || !r.inventory);
    const bytes = selected.reduce((n, r) => {
      if (!r.inventory) return n;
      const albums = selection.albums[r.category];
      if (albums && r.inventory.albums.length) {
        return n + r.inventory.albums.filter((a) => albums.includes(a.id)).reduce((m, a) => m + a.totalBytes, 0);
      }
      return n + r.inventory.totalBytes;
    }, 0);
    return { rows, selected, ready, pendingScan, bytes };
  }, [source, targetPlatform, selection]);
}

/** Counts older than this are refreshed when a category is ticked: the phone may have new photos. */
export const STALE_SCAN_MS = 60_000;

/** A category is (re)counted when it has never been counted, came back empty or refused, or is stale. */
export function needsScan(inv: CategorySummary | undefined, now = Date.now()): boolean {
  if (!inv) return true;
  if (inv.count === 0 || inv.permission === "denied") return true;
  return now - (inv.scannedMs ?? 0) > STALE_SCAN_MS;
}

export function CategoryPanel({
  source,
  targetPlatform,
  onStart,
  startLabel,
  starting,
  compactExtras,
}: {
  source: DeviceView;
  targetPlatform: DeviceView["info"]["platform"] | null;
  onStart: () => void;
  startLabel: string;
  starting?: boolean;
  compactExtras?: boolean;
}) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const run = useApp((s) => s.run);
  const toggle = useApp((s) => s.toggleCategory);
  const setSelected = useApp((s) => s.setSelected);
  const includePrevious = useApp((s) => s.selection.includePrevious);
  const setIncludePrevious = useApp((s) => s.setIncludePrevious);
  const [showExtras, setShowExtras] = useState(false);
  const { rows, selected, ready, pendingScan, bytes } = useCategoryState(source, targetPlatform);

  // Ticks made in quick succession go to the phone as one request, so it shows one permission card.
  const pending = useRef<Set<Category>>(new Set());
  /** When a fresh count was asked for each category: an older result must not decide anything. */
  const recountAsked = useRef<Map<Category, number>>(new Map());
  const countedFor = useRef<string | null>(null);

  // A new source phone (direction swapped, another phone connected) has not counted the categories
  // that are still ticked, or counted them long ago; without asking it, the start button would
  // wait forever or act on an outdated count.
  // Runs before the check below (effects run in order), so a stale count is recounted, not acted on.
  useEffect(() => {
    if (!source.connected || countedFor.current === source.info.id) return;
    countedFor.current = source.info.id;
    recountAsked.current.clear();
    const stale = (inv: CategorySummary | undefined) => !inv || Date.now() - (inv.scannedMs ?? 0) > STALE_SCAN_MS;
    const missing = rows.filter((r) => r.selected && !r.scanning && stale(r.inventory)).map((r) => r.category);
    if (missing.length) scan(missing);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per source phone
  }, [source.info.id, source.connected, rows]);

  // A category whose access was refused, or that turned out empty, cannot stay selected:
  // otherwise it would block the start button for everything else. Only a fresh result counts:
  // found on a Galaxy A10 whose contacts were deleted and later restored — the old "0" unticked
  // contacts again right after the user ticked them, while the new count was on its way.
  useEffect(() => {
    for (const r of rows) {
      if (!r.selected || !r.inventory || r.scanning || pending.current.has(r.category)) continue;
      if ((recountAsked.current.get(r.category) ?? 0) > (r.inventory.scannedMs ?? 0)) continue;
      if (r.inventory.permission === "denied" || r.inventory.permission === "unavailable" || r.inventory.count === 0) {
        toggle(r.category, false);
      }
    }
  }, [rows, toggle]);

  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  const scan = (cats: Category[], force = false) => {
    const need = force ? cats : cats.filter((c) => !source.scanning.includes(c));
    if (!need.length) return;
    const now = Date.now();
    need.forEach((c) => {
      pending.current.add(c);
      recountAsked.current.set(c, now);
    });
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      const batch = [...pending.current];
      pending.current.clear();
      timer.current = null;
      if (batch.length) void run((b) => b.requestScan(batch));
    }, 500);
  };

  const onToggle = (category: Category, on: boolean) => {
    toggle(category, on);
    const row = rows.find((r) => r.category === category);
    if (on && row && !row.scanning && needsScan(row.inventory)) scan([category]);
  };

  const selectAll = () => {
    const available = rows.filter(
      (r) => isTransferableStatus(r.rule.status) && !(r.inventory && (r.inventory.permission === "denied" || r.inventory.permission === "unavailable" || r.inventory.count === 0)),
    );
    setSelected(available.map((r) => r.category));
    scan(available.filter((r) => !r.scanning && needsScan(r.inventory)).map((r) => r.category));
  };

  const allSelected = selected.length > 0 && selected.length === rows.filter((r) => isTransferableStatus(r.rule.status)).length;

  return (
    <section>
      <div className="mb-4 flex items-end justify-between gap-4">
        <div>
          <h2 className="font-display text-headline font-semibold">{t("select.title")}</h2>
          <p className="mt-0.5 text-callout text-text-2">{t("select.subtitle")}</p>
        </div>
        <Button size="sm" variant="ghost" onClick={allSelected ? () => setSelected([]) : selectAll}>
          {allSelected ? t("select.clear") : t("select.selectAll")}
        </Button>
      </div>

      <motion.div layout className="grid grid-cols-2 gap-3">
        {rows.map((r, i) => (
          <motion.div
            key={r.category}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...motionTokens.springSoft, delay: i * 0.035 }}
          >
            <CategoryCard
              category={r.category}
              status={r.rule.status}
              note={r.rule.note}
              inventory={r.inventory}
              scanning={r.scanning}
              selected={r.selected}
              onToggle={(on) => onToggle(r.category, on)}
              onRescan={() => scan([r.category], true)}
            />
          </motion.div>
        ))}
      </motion.div>

      {targetPlatform && !compactExtras && (
        <div className="mt-6">
          <button
            type="button"
            onClick={() => setShowExtras(!showExtras)}
            className="flex w-full items-center gap-2 text-left text-callout font-semibold text-text-2 hover:text-text"
            aria-expanded={showExtras}
          >
            <motion.span animate={{ rotate: showExtras ? 0 : -90 }} transition={motionTokens.small} className="flex">
              <ChevronDown size={16} />
            </motion.span>
            {t("select.other")}
          </button>
          <AnimatePresence initial={false}>
            {showExtras && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: "auto", opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                transition={motionTokens.screen}
                className="overflow-hidden"
              >
                <p className="mt-2 mb-3 text-callout text-text-2">{t("select.otherHint")}</p>
                <div className="grid grid-cols-2 gap-3">
                  {EXTRA.map((c) => {
                    const rule = compatRule(c, source.info.platform, targetPlatform);
                    return (
                      <CategoryCard
                        key={c}
                        category={c}
                        status={rule.status}
                        note={rule.note}
                        inventory={undefined}
                        scanning={false}
                        selected={false}
                        onToggle={() => undefined}
                        onRescan={() => undefined}
                      />
                    );
                  })}
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      )}

      {/* Sticky start bar */}
      <div className="sticky bottom-0 z-10 mt-6 -mx-1 pb-1">
        <div className="glass-strong flex items-center gap-4 rounded-[20px] p-3 pl-5">
          <ShieldCheck size={20} className="shrink-0 text-success" />
          <div className="min-w-0 flex-1">
            <div className="tabular text-body font-semibold">
              {selected.length === 0
                ? t("select.nothing")
                : t("select.summary", { items: t("units.categories", { count: selected.length }), size: formatBytes(lang, bytes) })}
            </div>
            {targetPlatform && (
              <label className="mt-1 flex items-center gap-2 text-caption text-text-2">
                <Checkbox size={16} checked={includePrevious} onChange={setIncludePrevious} label={t("select.previouslyTransferred")} />
                {t("select.previouslyTransferred")}
              </label>
            )}
          </div>
          <Button
            variant="primary"
            size="xl"
            iconRight={ArrowRight}
            disabled={!ready}
            loading={starting}
            onClick={onStart}
            title={!ready ? t(pendingScan && selected.length ? "select.needScan" : "select.nothing") : undefined}
          >
            {startLabel}
          </Button>
        </div>
      </div>
    </section>
  );
}
