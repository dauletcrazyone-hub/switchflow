import { AnimatePresence, motion } from "motion/react";
import { Pause, Play, ShieldCheck, WifiOff, X } from "lucide-react";
import { useState } from "react";
import type { SessionView } from "../../backend/types";
import { CategoryIcon } from "../../components/CategoryIcon";
import { FlowBridge } from "../../components/device/FlowBridge";
import { AnimatedNumber } from "../../components/ui/AnimatedNumber";
import { Button } from "../../components/ui/Button";
import { ProgressBar } from "../../components/ui/Controls";
import { ConfirmDialog } from "../../components/ui/Sheet";
import { formatNumber } from "../../i18n";
import { formatBytes, formatEta, formatSpeed, percent } from "../../lib/format";
import { useApp, useT } from "../../state/store";
import { EndpointView } from "./DeviceStage";

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="glass rounded-2xl px-4 py-3.5">
      <div className="text-caption text-text-2">{label}</div>
      <div className="tabular mt-1 truncate text-headline font-semibold">{children}</div>
    </div>
  );
}

export function TransferView({ session }: { session: SessionView }) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const devices = useApp((s) => s.snapshot.devices);
  const run = useApp((s) => s.run);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const { totals } = session;
  const pct = percent(totals.workDone, totals.workTotal);
  const running = session.state === "running";
  const paused = session.state === "paused";
  const waiting = session.state === "waiting_reconnect";
  const doneItems = totals.deliveredItems + totals.skippedItems;

  const title =
    paused ? t("transfer.paused") : waiting ? t("transfer.waiting") : session.kind === "backup" ? t("transfer.backup") : session.kind === "restore" ? t("transfer.restore") : t("transfer.running");

  return (
    <div className="mx-auto max-w-[1040px]">
      <div className="text-center">
        <motion.h1 key={title} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="font-display text-title font-semibold">
          {title}
        </motion.h1>
        <p className="mt-1 flex items-center justify-center gap-1.5 text-callout text-text-2">
          <ShieldCheck size={14} className="text-success" /> {t("transfer.originalsSafe")} · {t("transfer.keepOpen")}
        </p>
      </div>

      <div className="mt-7 flex items-center">
        <EndpointView endpoint={session.source} role="source" devices={devices} active={running} />
        <FlowBridge connected active={running} height={110} />
        <EndpointView endpoint={session.target} role="target" devices={devices} active={running} />
      </div>

      <AnimatePresence>
        {(waiting || paused) && (
          <motion.div
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            className={`mt-5 flex items-center gap-3 rounded-2xl p-4 text-callout ${waiting ? "bg-warning-soft" : "bg-accent-soft"}`}
          >
            {waiting ? <WifiOff size={18} className="shrink-0 text-warning" /> : <Pause size={18} className="shrink-0 text-accent" />}
            {waiting ? t("transfer.waitingBanner") : t("transfer.pausedBanner")}
          </motion.div>
        )}
      </AnimatePresence>

      <div className="mt-8 flex flex-col items-center">
        <div className="flex items-baseline font-display">
          <AnimatedNumber value={pct} format={(n) => formatNumber(lang, Math.floor(n))} className="tabular text-[64px] leading-none font-semibold tracking-tight" />
          <span className="ml-1 text-title font-semibold text-text-2">%</span>
        </div>
        <div className="tabular mt-2 text-body text-text-2">
          {t("transfer.transferred", { done: formatNumber(lang, doneItems), total: formatNumber(lang, totals.totalItems) })}
        </div>
        <ProgressBar value={pct} className="mt-5 h-2 w-full max-w-[640px]" />
      </div>

      <div className="mt-8 grid grid-cols-4 gap-3">
        <Stat label={t("transfer.files")}>
          {formatNumber(lang, doneItems)} <span className="text-text-3">/ {formatNumber(lang, totals.totalItems)}</span>
        </Stat>
        <Stat label={t("transfer.data")}>
          {formatBytes(lang, totals.deliveredBytes)} <span className="text-text-3">/ {formatBytes(lang, totals.totalBytes)}</span>
        </Stat>
        <Stat label={t("transfer.speed")}>{session.bytesPerSec ? formatSpeed(lang, session.bytesPerSec) : <span className="text-text-3">—</span>}</Stat>
        <Stat label={t("transfer.eta")}>
          {session.etaSec != null ? formatEta(lang, session.etaSec) : <span className="text-text-3">{running ? t("transfer.calculating") : "—"}</span>}
        </Stat>
      </div>

      <div className="glass mt-3 rounded-2xl p-4">
        <AnimatePresence mode="wait">
          {session.currentName && session.currentCategory && (
            <motion.div
              key="cur"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="mb-3 flex items-center gap-2 border-b border-line pb-3 text-callout"
            >
              <span className="text-text-2">{t("transfer.current")}:</span>
              <span className="font-medium">{t(`categories.${session.currentCategory}.title`)}</span>
              <span className="truncate text-text-3">· {session.currentName}</span>
            </motion.div>
          )}
        </AnimatePresence>
        <ul className="space-y-3">
          {session.categories.map((c) => {
            const p = percent(c.delivered + c.skipped + c.failed, c.total);
            return (
              <li key={c.category} className="flex items-center gap-3">
                <CategoryIcon category={c.category} size={32} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-3 text-callout">
                    <span className="font-medium">{t(`categories.${c.category}.title`)}</span>
                    <span className="tabular text-text-2">
                      {c.total === 1 && c.logicalTotal > 1
                        ? t(`units.${c.category}`, { count: c.logicalTotal })
                        : `${formatNumber(lang, c.delivered)} / ${formatNumber(lang, c.total)}`}
                      {c.failed > 0 && <span className="text-danger"> · {c.failed} ✕</span>}
                    </span>
                  </div>
                  <ProgressBar value={p} tone={p >= 100 && c.failed === 0 ? "success" : "accent"} className="mt-1.5 h-1" />
                </div>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="mt-6 flex justify-center gap-3">
        {running && (
          <Button size="lg" icon={Pause} onClick={() => void run((b) => b.pauseSession())}>
            {t("transfer.pause")}
          </Button>
        )}
        {(paused || waiting) && (
          <Button size="lg" variant="primary" icon={Play} onClick={() => void run((b) => b.resumeSession())}>
            {t("transfer.resume")}
          </Button>
        )}
        <Button size="lg" variant="ghost" icon={X} onClick={() => setConfirmCancel(true)}>
          {t("transfer.cancel")}
        </Button>
      </div>

      <ConfirmDialog
        open={confirmCancel}
        title={t("transfer.cancelTitle")}
        body={t("transfer.cancelBody")}
        confirmLabel={t("transfer.cancelConfirm")}
        cancelLabel={t("transfer.keepGoing")}
        danger
        onCancel={() => setConfirmCancel(false)}
        onConfirm={() => {
          setConfirmCancel(false);
          void run((b) => b.cancelSession());
        }}
      />
    </div>
  );
}
