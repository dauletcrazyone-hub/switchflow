import { AnimatePresence, motion } from "motion/react";
import { Check, RefreshCw, ShieldCheck, Smartphone, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "../../components/ui/Button";
import { Sheet } from "../../components/ui/Sheet";
import { Spinner } from "../../components/ui/Spinner";
import { formatCountdown } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";
import { deviceDetails, deviceNickname, deviceTitle } from "../../lib/device";

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

export function PairingSheet() {
  const t = useT();
  const open = useApp((s) => s.pairingOpen);
  const close = useApp((s) => s.closePairing);
  const pairing = useApp((s) => s.snapshot.pairing);
  const requests = useApp((s) => s.snapshot.pairRequests);
  const devices = useApp((s) => s.snapshot.devices);
  const sourceId = useApp((s) => s.snapshot.sourceId);
  const targetId = useApp((s) => s.snapshot.targetId);
  const demo = useApp((s) => s.demo);
  const run = useApp((s) => s.run);
  const toast = useApp((s) => s.toast);
  const now = useNow(1000);
  const [answered, setAnswered] = useState<string | null>(null);

  // Close automatically once both slots are filled by phones paired in *this* window,
  // so the window still works for re-pairing when two phones are already connected.
  const bothFilled = !!sourceId && !!targetId && devices.length >= 2;
  const countAtOpen = useRef<number | null>(null);
  useEffect(() => {
    countAtOpen.current = open ? devices.length : null;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- remember the count only when opening
  }, [open]);
  const addedHere = countAtOpen.current !== null && devices.length > countAtOpen.current;
  useEffect(() => {
    if (open && bothFilled && addedHere && requests.length === 0) {
      const id = setTimeout(close, 900);
      return () => clearTimeout(id);
    }
  }, [open, bothFilled, addedHere, requests.length, close]);

  const remaining = pairing ? pairing.expiresMs - now : 0;
  const expired = !!pairing && remaining <= 0;
  const request = requests[0];

  const respond = async (approve: boolean) => {
    if (!request) return;
    setAnswered(request.id);
    await run((b) => b.respondPairing(request.id, approve));
    if (approve) toast("success", t("pairing.connected", { name: deviceTitle(request.device) }));
  };

  return (
    <Sheet open={open} onClose={close} width={720} label={t("pairing.title")}>
      <div className="grid grid-cols-[1fr_300px]">
        <div className="p-7">
          <div className="flex items-center gap-2 text-success">
            <ShieldCheck size={18} />
            <span className="text-caption font-semibold tracking-wide uppercase">TLS · SHA-256</span>
          </div>
          <h2 className="mt-2 font-display text-title font-semibold">{t("pairing.title")}</h2>
          <p className="mt-1.5 text-body text-text-2">{t("pairing.subtitle")}</p>

          <AnimatePresence mode="wait">
            {request && request.id !== answered ? (
              <motion.div
                key={request.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                transition={motionTokens.springSoft}
                className="mt-6 rounded-2xl bg-accent-soft p-5"
              >
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent text-white">
                    <Smartphone size={20} />
                  </div>
                  <div className="min-w-0">
                    <div className="truncate font-semibold">{t("pairing.request", { name: deviceTitle(request.device) })}</div>
                    <div className="text-caption text-text-2">
                      {deviceDetails(request.device)}
                      {deviceNickname(request.device) ? ` · «${deviceNickname(request.device)}»` : ""}
                    </div>
                  </div>
                </div>
                <p className="mt-4 text-callout text-text-2">{t("pairing.compare")}</p>
                <div className="tabular mt-2 font-display text-[34px] font-semibold tracking-[0.18em] text-text">
                  {request.code.slice(0, 3)} {request.code.slice(3)}
                </div>
                <div className="mt-4 flex gap-2">
                  <Button variant="primary" icon={Check} onClick={() => void respond(true)} autoFocus>
                    {t("pairing.allow")}
                  </Button>
                  <Button variant="ghost" icon={X} onClick={() => void respond(false)}>
                    {t("pairing.deny")}
                  </Button>
                </div>
              </motion.div>
            ) : (
              <motion.ol key="steps" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="mt-6 space-y-3">
                {[t("pairing.step1"), t("pairing.step2"), t("pairing.step3")].map((s, i) => (
                  <li key={i} className="flex items-start gap-3">
                    <span className="mt-px flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-accent-soft text-caption font-semibold text-accent">
                      {i + 1}
                    </span>
                    <span className="text-body">{s}</span>
                  </li>
                ))}
                <li className="flex items-center gap-2 pt-2 text-callout text-text-2">
                  <Spinner size={14} /> {t("pairing.waiting")}
                </li>
              </motion.ol>
            )}
          </AnimatePresence>

          <p className="mt-6 text-caption text-text-3">{t("pairing.sameNetwork")}</p>
        </div>

        <div className="flex flex-col items-center justify-center border-l border-line bg-bg-2/40 p-6">
          <div className="relative rounded-[20px] bg-white p-3 shadow-[var(--shadow-lg)]">
            {pairing?.qrSvg ? (
              <div
                className={`h-[220px] w-[220px] [&>svg]:h-full [&>svg]:w-full ${expired ? "opacity-20 blur-[2px]" : ""}`}
                dangerouslySetInnerHTML={{ __html: pairing.qrSvg }}
                aria-label="QR"
                role="img"
              />
            ) : (
              <div className="flex h-[220px] w-[220px] items-center justify-center text-[#0b1220]">
                <Spinner size={24} />
              </div>
            )}
            {expired && (
              <div className="absolute inset-0 flex items-center justify-center">
                <Button variant="primary" icon={RefreshCw} onClick={() => void run((b) => b.startPairing())}>
                  {t("pairing.refresh")}
                </Button>
              </div>
            )}
          </div>
          {demo ? (
            <p className="mt-4 text-center text-caption text-warning">{t("pairing.demoQr")}</p>
          ) : pairing ? (
            <div className="mt-4 text-center">
              <div className="text-caption text-text-3">{t("pairing.address")}</div>
              <div className="tabular selectable mt-0.5 text-callout font-medium">
                {pairing.hosts[0]}:{pairing.port}
              </div>
              <div className="mt-2 text-caption text-text-2">
                {expired ? t("pairing.expired") : t("pairing.expires", { time: formatCountdown(remaining) })}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </Sheet>
  );
}
