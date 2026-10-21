import { motion } from "motion/react";
import { ArrowRight, History, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import type { HistoryEntry } from "../../backend/types";
import { CategoryIcon } from "../../components/CategoryIcon";
import { Button } from "../../components/ui/Button";
import { Badge, EmptyState, Skeleton } from "../../components/ui/Controls";
import { ConfirmDialog } from "../../components/ui/Sheet";
import { formatNumber } from "../../i18n";
import { formatBytes, formatDate, formatDuration } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

const OUTCOME_TONE = { completed: "success", partial: "warning", cancelled: "neutral", failed: "danger" } as const;

export function HistoryScreen() {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const backend = useApp((s) => s.backend);
  const run = useApp((s) => s.run);
  const version = useApp((s) => s.historyVersion);
  const [items, setItems] = useState<HistoryEntry[] | null>(null);
  const [confirm, setConfirm] = useState(false);

  useEffect(() => {
    let alive = true;
    void backend.getHistory().then((h) => alive && setItems(h)).catch(() => alive && setItems([]));
    return () => {
      alive = false;
    };
  }, [backend, version]);

  return (
    <div className="mx-auto max-w-[880px]">
      <div className="mb-6 flex items-start justify-between gap-6">
        <div>
          <h1 className="font-display text-display font-semibold">{t("history.title")}</h1>
          <p className="mt-1 text-body text-text-2">{t("history.subtitle")}</p>
        </div>
        {items && items.length > 0 && (
          <Button variant="ghost" icon={Trash2} onClick={() => setConfirm(true)}>
            {t("history.clear")}
          </Button>
        )}
      </div>

      {items === null ? (
        <div className="space-y-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-[92px] rounded-2xl" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <EmptyState icon={<History size={28} />} title={t("history.empty")} hint={t("history.emptyHint")} />
      ) : (
        <ul className="space-y-3">
          {items.map((h, i) => {
            const delivered = h.categories.reduce((n, c) => n + c.delivered, 0);
            const total = h.categories.reduce((n, c) => n + c.total, 0);
            const skipped = h.categories.reduce((n, c) => n + c.skipped, 0);
            return (
              <motion.li
                key={h.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...motionTokens.springSoft, delay: Math.min(i, 8) * 0.04 }}
                className="glass rounded-2xl p-4"
              >
                <div className="flex items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 text-body font-semibold">
                      <span className="truncate">{h.sourceName || "—"}</span>
                      <ArrowRight size={15} className="shrink-0 text-text-3" />
                      <span className="truncate">{h.kind === "backup" ? t("home.thisPc") : h.targetName}</span>
                    </div>
                    <div className="mt-0.5 text-caption text-text-2">
                      {t(`history.kind.${h.kind}`)} · {formatDate(lang, h.finishedMs)} · {formatDuration(lang, h.finishedMs - h.startedMs)}
                    </div>
                  </div>
                  <Badge tone={OUTCOME_TONE[h.outcome]} dot>
                    {t(`history.outcome.${h.outcome}`)}
                  </Badge>
                </div>
                <div className="mt-3 flex items-center gap-4 border-t border-line pt-3">
                  <div className="flex -space-x-1.5">
                    {h.categories.map((c) => (
                      <div key={c.category} className="rounded-[13px] ring-2 ring-[var(--bg)]">
                        <CategoryIcon category={c.category} size={28} />
                      </div>
                    ))}
                  </div>
                  <span className="tabular text-callout text-text-2">
                    {t("history.delivered", { count: `${formatNumber(lang, delivered)} / ${formatNumber(lang, total)}` })} · {formatBytes(lang, h.deliveredBytes)}
                    {skipped > 0 && ` · ${t("history.skipped", { count: formatNumber(lang, skipped) })}`}
                  </span>
                </div>
              </motion.li>
            );
          })}
        </ul>
      )}
      <ConfirmDialog
        open={confirm}
        title={t("history.clear")}
        body={t("history.clearConfirm")}
        confirmLabel={t("history.clear")}
        danger
        onCancel={() => setConfirm(false)}
        onConfirm={async () => {
          setConfirm(false);
          await run((b) => b.clearHistory());
          setItems([]);
        }}
      />
    </div>
  );
}
