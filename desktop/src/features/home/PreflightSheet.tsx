import { AnimatePresence, motion } from "motion/react";
import { CircleAlert, CircleCheck, RotateCcw, TriangleAlert } from "lucide-react";
import type { Category, PreflightCheck, SessionView } from "../../backend/types";
import { Button } from "../../components/ui/Button";
import { Sheet } from "../../components/ui/Sheet";
import { Spinner } from "../../components/ui/Spinner";
import { formatBytes } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

function CheckIcon({ status }: { status: PreflightCheck["status"] }) {
  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.span
        key={status}
        initial={{ scale: 0.5, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.5, opacity: 0 }}
        transition={motionTokens.spring}
        className="flex h-6 w-6 items-center justify-center"
      >
        {status === "pending" ? (
          <Spinner size={17} className="text-accent" />
        ) : status === "ok" ? (
          <CircleCheck size={20} className="text-success" />
        ) : status === "warning" ? (
          <TriangleAlert size={19} className="text-warning" />
        ) : (
          <CircleAlert size={20} className="text-danger" />
        )}
      </motion.span>
    </AnimatePresence>
  );
}

export function useCheckText() {
  const t = useT();
  const lang = useApp((s) => s.lang);
  return (c: PreflightCheck) => {
    const p = c.params ?? {};
    const cats = Array.isArray(p.categories) ? (p.categories as Category[]).map((x) => t(`categories.${x}.title`)).join(", ") : "";
    return t(c.message, {
      categories: cats,
      need: typeof p.need === "number" ? formatBytes(lang, p.need) : "",
      free: typeof p.free === "number" ? formatBytes(lang, p.free) : "—",
      missing: typeof p.missing === "number" ? p.missing : 0,
    });
  };
}

export function PreflightSheet({ session }: { session: SessionView }) {
  const t = useT();
  const run = useApp((s) => s.run);
  const text = useCheckText();
  const open = session.state === "preparing";
  const failed = session.preflight.some((c) => c.status === "failed");
  const waitingTarget = session.preflight.find((c) => c.id === "targetPermissions")?.status === "pending";
  const notes = (session.preflight.find((c) => c.id === "compatibility")?.params?.notes as string[] | undefined) ?? [];
  const targetName = session.target.type === "device" ? session.target.name : t("home.thisPc");

  return (
    <Sheet open={open} onClose={() => undefined} locked width={540} label={t("preflight.title")}>
      <div className="p-7">
        <h2 className="font-display text-title font-semibold">{t("preflight.title")}</h2>
        <p className="mt-1 text-body text-text-2">{t("preflight.subtitle")}</p>
        <ul className="mt-6 space-y-1">
          {session.preflight.map((c, i) => (
            <motion.li
              key={c.id}
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ ...motionTokens.springSoft, delay: i * 0.05 }}
              className="flex items-start gap-3 rounded-xl px-2 py-2.5"
            >
              <CheckIcon status={c.status} />
              <div className="min-w-0">
                <div className="text-body font-medium">{t(`preflight.labels.${c.id}`)}</div>
                <div className={`text-callout ${c.status === "failed" ? "text-danger" : "text-text-2"}`}>{text(c)}</div>
              </div>
            </motion.li>
          ))}
        </ul>

        {notes.length > 0 && (
          <div className="mt-3 space-y-2 rounded-xl bg-warning-soft p-3 text-callout">
            {notes.map((n) => (
              <p key={n}>{t(n)}</p>
            ))}
          </div>
        )}

        <AnimatePresence>
          {waitingTarget && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: "auto" }}
              exit={{ opacity: 0, height: 0 }}
              className="overflow-hidden"
            >
              <div className="mt-4 rounded-2xl bg-accent-soft p-4">
                <div className="text-caption font-semibold tracking-wide text-accent uppercase">{t("preflight.steps")}</div>
                <ol className="mt-2 space-y-1.5 text-body">
                  <li>1. {t("preflight.stepOpen", { device: targetName })}</li>
                  <li>2. {t("preflight.stepAllow")}</li>
                  <li>3. {t("preflight.stepConfirm")}</li>
                </ol>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="mt-7 flex justify-end gap-2">
          <Button variant="ghost" onClick={() => void run((b) => b.cancelSession().then(() => b.dismissSession()))}>
            {t("preflight.cancel")}
          </Button>
          {failed && (
            <Button variant="primary" icon={RotateCcw} onClick={() => void run((b) => b.rerunPreflight())}>
              {t("preflight.rerun")}
            </Button>
          )}
        </div>
      </div>
    </Sheet>
  );
}
