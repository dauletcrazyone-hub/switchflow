import { AnimatePresence, motion } from "motion/react";
import { History, Lock, ServerCrash } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "../../components/ui/Button";
import { Badge } from "../../components/ui/Controls";
import { formatNumber } from "../../i18n";
import { motionTokens } from "../../lib/ui";
import { toSelections, useApp, useT } from "../../state/store";
import { CategoryPanel } from "./CategoryPanel";
import { DeviceStage, useSlots } from "./DeviceStage";
import { PairingSheet } from "./PairingSheet";
import { PreflightSheet } from "./PreflightSheet";
import { ReportView } from "./ReportView";
import { TransferView } from "./TransferView";
import { UsbPanel } from "./UsbPanel";

function ServerPill() {
  const t = useT();
  const server = useApp((s) => s.snapshot.server);
  if (server.error) {
    return (
      <Badge tone="danger" dot>
        {t("server.error")}
      </Badge>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <Badge tone="success" dot>
        <Lock size={11} /> {t("privacy.badge")}
      </Badge>
      <Badge tone={server.running ? "accent" : "neutral"} dot>
        {server.running ? t("server.ready") : t("server.starting")}
      </Badge>
    </div>
  );
}

function ResumeBanner() {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const resumable = useApp((s) => s.snapshot.resumable);
  const run = useApp((s) => s.run);
  if (!resumable) return null;
  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      className="glass mb-5 flex items-center gap-4 rounded-2xl p-4"
    >
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent">
        <History size={20} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="font-semibold">
          {resumable.sourceName} → {resumable.targetName}
        </div>
        <div className="tabular text-callout text-text-2">
          {t("transfer.transferred", { done: formatNumber(lang, resumable.deliveredItems), total: formatNumber(lang, resumable.totalItems) })} ·{" "}
          {t("errors.devices_not_paired")}
        </div>
      </div>
      <Button size="sm" variant="ghost" onClick={() => void run((b) => b.discardSavedSession())}>
        {t("common.cancel")}
      </Button>
      <Button size="sm" variant="primary" onClick={() => void run((b) => b.resumeSavedSession())}>
        {t("transfer.resume")}
      </Button>
    </motion.div>
  );
}

function SetupView() {
  const t = useT();
  const { source, target } = useSlots();
  const server = useApp((s) => s.snapshot.server);
  const selection = useApp((s) => s.selection);
  const run = useApp((s) => s.run);
  const [starting, setStarting] = useState(false);
  const bothReady = !!source && !!target && source.connected && target.connected;

  const start = async () => {
    setStarting(true);
    await run((b) =>
      b.startSession({
        kind: "transfer",
        selections: toSelections(selection),
        contactsPolicy: selection.contactsPolicy,
        includePreviouslyTransferred: selection.includePrevious,
      }),
    );
    setStarting(false);
  };

  return (
    <div className="mx-auto max-w-[1040px]">
      <div className="mb-6 flex items-start justify-between gap-6">
        <div>
          <h1 className="font-display text-display font-semibold">{t("home.title")}</h1>
          <p className="mt-1 text-body text-text-2">{t("home.subtitle")}</p>
        </div>
        <ServerPill />
      </div>
      {server.error && (
        <div className="mb-5 flex items-center gap-3 rounded-2xl bg-danger-soft p-4 text-callout">
          <ServerCrash size={18} className="shrink-0 text-danger" /> {t("server.errorHint")}
        </div>
      )}
      <ResumeBanner />
      <DeviceStage />
      <div className="mt-6">
        <UsbPanel />
      </div>
      <AnimatePresence mode="wait">
        {bothReady && source ? (
          <motion.div key="cats" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={motionTokens.screen} className="mt-8">
            <CategoryPanel source={source} targetPlatform={target!.info.platform} onStart={() => void start()} startLabel={t("select.start")} starting={starting} />
          </motion.div>
        ) : (
          <motion.p key="hint" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="mt-10 text-center text-callout text-text-3">
            {t("select.needBoth")}
          </motion.p>
        )}
      </AnimatePresence>
    </div>
  );
}

export function HomeScreen() {
  const session = useApp((s) => s.snapshot.session);
  const view =
    !session || session.state === "preparing" ? "setup" : ["running", "paused", "waiting_reconnect"].includes(session.state) ? "transfer" : "report";
  useEffect(() => {
    document.getElementById("sf-scroll")?.scrollTo({ top: 0, behavior: "smooth" });
  }, [view]);
  return (
    <>
      <AnimatePresence mode="wait">
        <motion.div
          key={view}
          initial={{ opacity: 0, scale: 0.99 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.99 }}
          transition={motionTokens.screen}
        >
          {view === "setup" && <SetupView />}
          {view === "transfer" && session && <TransferView session={session} />}
          {view === "report" && session && <ReportView session={session} />}
        </motion.div>
      </AnimatePresence>
      <PairingSheet />
      {session && <PreflightSheet session={session} />}
    </>
  );
}
