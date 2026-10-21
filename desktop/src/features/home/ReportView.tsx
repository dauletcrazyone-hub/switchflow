import { motion } from "motion/react";
import { ChevronDown, FolderOpen, RotateCcw } from "lucide-react";
import { useState } from "react";
import type { SessionView } from "../../backend/types";
import { CategoryIcon } from "../../components/CategoryIcon";
import { Button } from "../../components/ui/Button";
import { formatNumber, hasKey } from "../../i18n";
import { formatBytes, formatDuration } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useReducedMotion, useT } from "../../state/store";

/** Animated outcome mark: a ring and a check (or an exclamation / cross) drawn in. */
function OutcomeMark({ tone }: { tone: "success" | "warning" | "neutral" }) {
  const reduce = useReducedMotion();
  const color = tone === "success" ? "var(--success)" : tone === "warning" ? "var(--warning)" : "var(--text-3)";
  const draw = reduce ? { duration: 0 } : { duration: 0.55, ease: [0.22, 1, 0.36, 1] as const };
  return (
    <div className="relative flex h-28 w-28 items-center justify-center">
      {!reduce && tone === "success" && (
        <motion.span
          className="absolute inset-0 rounded-full"
          style={{ background: "var(--success-soft)" }}
          initial={{ scale: 0.6, opacity: 0 }}
          animate={{ scale: [0.6, 1.25, 1], opacity: [0, 1, 0.6] }}
          transition={{ duration: 0.9, ease: "easeOut" }}
        />
      )}
      <svg viewBox="0 0 100 100" width="96" height="96" className="relative">
        <motion.circle cx="50" cy="50" r="44" fill="none" stroke={color} strokeWidth="5" initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={draw} />
        {tone === "success" ? (
          <motion.path
            d="M30 52 L44 65 L71 37"
            fill="none"
            stroke={color}
            strokeWidth="6"
            strokeLinecap="round"
            strokeLinejoin="round"
            initial={{ pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ ...draw, delay: reduce ? 0 : 0.35 }}
          />
        ) : tone === "warning" ? (
          <>
            <motion.path d="M50 28 L50 56" stroke={color} strokeWidth="6" strokeLinecap="round" initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ ...draw, delay: 0.3 }} />
            <motion.circle cx="50" cy="70" r="4" fill={color} initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ delay: reduce ? 0 : 0.6 }} />
          </>
        ) : (
          <motion.path d="M36 36 L64 64 M64 36 L36 64" stroke={color} strokeWidth="6" strokeLinecap="round" initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ ...draw, delay: 0.3 }} />
        )}
      </svg>
    </div>
  );
}

export function ReportView({ session }: { session: SessionView }) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const run = useApp((s) => s.run);
  const [showFailed, setShowFailed] = useState(false);
  const { totals } = session;
  const failed = totals.failedItems;
  const cancelled = session.state === "cancelled";
  const tone = cancelled || session.state === "failed" ? "neutral" : failed > 0 ? "warning" : "success";
  const title = cancelled
    ? t("report.cancelled")
    : session.state === "failed"
      ? t("report.failed")
      : failed > 0
        ? t("report.partial")
        : session.kind === "backup"
          ? t("report.backupCompleted")
          : session.kind === "restore"
            ? t("report.restoreCompleted")
            : t("report.completed");
  const reason = (code: string) => (hasKey(lang, `report.reasons.${code}`) ? t(`report.reasons.${code}`) : t("report.reasons.unknown"));

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={motionTokens.screen} className="mx-auto max-w-[720px] pt-4">
      <div className="flex flex-col items-center text-center">
        <OutcomeMark tone={tone} />
        <h1 className="mt-4 font-display text-display font-semibold">{title}</h1>
        <p className="tabular mt-2 text-body text-text-2">
          {t("report.summary", {
            done: formatNumber(lang, totals.deliveredItems),
            total: formatNumber(lang, totals.totalItems),
            size: formatBytes(lang, totals.deliveredBytes),
          })}
        </p>
        <p className="tabular mt-1 text-callout text-text-3">
          {t("report.verified", { count: formatNumber(lang, totals.verifiedItems) })}
          {session.finishedMs ? ` · ${t("report.duration", { time: formatDuration(lang, session.finishedMs - session.startedMs) })}` : ""}
        </p>
      </div>

      <div className="glass mt-8 divide-y divide-line rounded-2xl">
        {session.categories.map((c) => (
          <div key={c.category} className="flex items-center gap-3.5 px-4 py-3">
            <CategoryIcon category={c.category} size={36} />
            <div className="flex-1">
              <div className="text-body font-medium">{t(`categories.${c.category}.title`)}</div>
              <div className="tabular text-caption text-text-2">
                {c.total === 1 && c.logicalTotal > 1 ? t(`units.${c.category}`, { count: c.logicalDelivered }) : `${formatNumber(lang, c.delivered)} / ${formatNumber(lang, c.total)}`}
                {" · "}
                {formatBytes(lang, c.deliveredBytes)}
              </div>
            </div>
            {c.failed > 0 ? (
              <span className="tabular text-callout font-medium text-danger">{c.failed} ✕</span>
            ) : c.delivered + c.skipped === c.total && !cancelled ? (
              <span className="text-callout font-medium text-success">✓</span>
            ) : null}
          </div>
        ))}
      </div>

      {(session.skippedReasons.already_transferred || session.skippedReasons.duplicate) && (
        <div className="mt-3 space-y-1 px-1 text-callout text-text-2">
          {session.skippedReasons.already_transferred ? <p>{t("report.skippedAlready", { count: session.skippedReasons.already_transferred })}</p> : null}
          {session.skippedReasons.duplicate ? <p>{t("report.skippedDuplicate", { count: session.skippedReasons.duplicate })}</p> : null}
        </div>
      )}

      {failed > 0 && !cancelled && (
        <div className="mt-4 rounded-2xl bg-warning-soft p-4">
          <div className="flex items-center justify-between gap-3">
            <div className="text-body font-semibold">{t("report.needRetry", { count: failed })}</div>
            <div className="flex gap-2">
              <Button size="sm" variant="ghost" iconRight={ChevronDown} onClick={() => setShowFailed(!showFailed)}>
                {t("report.details")}
              </Button>
              <Button size="sm" variant="primary" icon={RotateCcw} onClick={() => void run((b) => b.retryFailed())}>
                {t("report.retry")}
              </Button>
            </div>
          </div>
          {showFailed && (
            <ul className="scroll-area mt-3 max-h-48 space-y-1 text-callout">
              {session.failedItems.map((f) => (
                <li key={f.key} className="flex justify-between gap-4">
                  <span className="selectable truncate">{f.name}</span>
                  <span className="shrink-0 text-text-2">{reason(f.reason)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="mt-8 flex justify-center gap-3">
        {session.backupPath && (
          <Button size="lg" icon={FolderOpen} onClick={() => void run((b) => b.revealPath(session.backupPath!))}>
            {t("report.openFolder")}
          </Button>
        )}
        <Button size="lg" variant="primary" className="min-w-[160px]" onClick={() => void run((b) => b.dismissSession())}>
          {t("report.done")}
        </Button>
      </div>
    </motion.div>
  );
}
