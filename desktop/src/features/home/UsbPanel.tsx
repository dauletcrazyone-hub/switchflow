import { AnimatePresence, motion } from "motion/react";
import { useState } from "react";
import { Cable, CircleAlert, Download, ExternalLink, Smartphone, Usb } from "lucide-react";
import { openUrl } from "@tauri-apps/plugin-opener";
import type { UsbDevice } from "../../backend/types";
import { Button } from "../../components/ui/Button";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

const APPLE_DEVICES_URL = "https://apps.microsoft.com/detail/9np83lwlpz9k";

function describe(d: UsbDevice, t: ReturnType<typeof useT>) {
  switch (d.status) {
    case "driver_missing":
      return { title: t("usb.driverMissing"), hint: t("usb.driverMissingHint"), warn: true };
    case "locked":
      return { title: t("usb.locked"), hint: t("usb.lockedHint"), warn: true };
    case "unauthorized":
      return { title: t("usb.unauthorized"), hint: t("usb.unauthorizedHint"), warn: true };
    case "mtp_driver_missing":
      return { title: t("usb.mtpBroken"), hint: t("usb.mtpBrokenHint"), warn: true };
    case "offline":
      return { title: t("usb.offline"), hint: "", warn: true };
    default:
      return {
        title: d.platform === "ios" ? t("usb.iphoneDetected") : t("usb.detected", { model: d.model || "Android" }),
        hint: d.platform === "ios" ? t("usb.iphoneCable") : t("usb.openCompanion"),
        warn: false,
      };
  }
}

/** Phones Windows sees on USB. Shown only for real detections; nothing is invented. */
export function UsbPanel() {
  const t = useT();
  const usb = useApp((s) => s.snapshot.usb);
  const run = useApp((s) => s.run);
  const toast = useApp((s) => s.toast);
  const cable = useApp((s) => s.snapshot.cable);
  const [switching, setSwitching] = useState(false);
  // Phones the app file was copied to in this window, and the one being copied to right now.
  const [copied, setCopied] = useState<string[]>([]);
  const [copying, setCopying] = useState<string | null>(null);
  const cablePhones = cable.available ? cable.phones : 0;
  // Without the UsbDk driver Windows cannot switch phones to the cable link (cable.rs).
  const needsDriver = !cable.available && usb.some((d) => d.platform === "android");
  if (usb.length === 0 && cablePhones === 0) return null;

  const installDriver = async () => {
    setSwitching(true);
    const ok = await run((b) => b.installCableDriver().then(() => true));
    setSwitching(false);
    if (ok) toast("success", t("usb.driverInstalled"));
  };

  const installApp = async (id: string) => {
    setCopying(id);
    const ok = await run((b) => b.installCompanion(id).then(() => true));
    setCopying(null);
    if (ok) setCopied((c) => (c.includes(id) ? c : [...c, id]));
  };

  const connectByCable = async () => {
    setSwitching(true);
    const n = await run((b) => b.cableConnect());
    setSwitching(false);
    if (n === undefined) return;
    if (n > 0) toast("success", t("usb.cableSwitched"));
    else toast("error", t("usb.cableNone"));
  };
  return (
    <section aria-label={t("usb.title")}>
      <div className="mb-2 flex items-center gap-2 text-caption font-semibold tracking-wide text-text-3 uppercase">
        <Usb size={13} /> {t("usb.title")}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <AnimatePresence initial={false}>
          {cablePhones > 0 && (
            <motion.div
              key="cable"
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.97 }}
              transition={motionTokens.springSoft}
              className="glass flex items-start gap-3 rounded-2xl p-4"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent">
                <Cable size={18} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-body font-semibold">{t("usb.cableTitle", { count: cablePhones })}</div>
                <p className="mt-0.5 text-caption text-text-2">{t("usb.cableHint")}</p>
                {!usb.some((d) => d.platform === "android" && d.source === "wpd") && (
                  <p className="mt-1 text-caption text-text-2">{t("usb.installHowTo")}</p>
                )}
                <div className="mt-2">
                  <Button size="sm" variant="primary" icon={Cable} loading={switching} onClick={() => void connectByCable()}>
                    {t("usb.cableConnect")}
                  </Button>
                </div>
              </div>
            </motion.div>
          )}
          {needsDriver && (
            <motion.div
              key="cable-driver"
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.97 }}
              transition={motionTokens.springSoft}
              className="glass flex items-start gap-3 rounded-2xl p-4"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-warning-soft text-warning">
                <Cable size={18} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-body font-semibold">{t("usb.driverNeeded")}</div>
                <p className="mt-0.5 text-caption text-text-2">{t("usb.driverNeededHint")}</p>
                <div className="mt-2">
                  <Button size="sm" variant="subtle" icon={Cable} loading={switching} onClick={() => void installDriver()}>
                    {t("usb.installDriver")}
                  </Button>
                </div>
              </div>
            </motion.div>
          )}
          {usb.map((d) => {
            const info = describe(d, t);
            return (
              <motion.div
                key={d.id}
                layout
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.97 }}
                transition={motionTokens.springSoft}
                className="glass flex items-start gap-3 rounded-2xl p-4"
              >
                <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${info.warn ? "bg-warning-soft text-warning" : "bg-accent-soft text-accent"}`}>
                  {info.warn ? <CircleAlert size={18} /> : <Smartphone size={18} />}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-body font-semibold">{info.title}</div>
                  {info.hint && <p className="mt-0.5 text-caption text-text-2">{info.hint}</p>}
                  {copied.includes(d.id) && (
                    <div className="mt-2 rounded-xl bg-accent-soft p-3 text-caption" role="status">
                      <div className="font-semibold">{t("usb.installCopied")}</div>
                      <ol className="mt-1 list-decimal space-y-0.5 pl-4 text-text-2">
                        <li>{t("usb.installStep1")}</li>
                        <li>{t("usb.installStep2")}</li>
                        <li>{t("usb.installStep3")}</li>
                        <li>{t("usb.installStep4")}</li>
                      </ol>
                    </div>
                  )}
                  <div className="mt-2 flex flex-wrap gap-2">
                    {d.platform === "android" && d.status === "ready" && d.source === "wpd" && (
                      <Button size="sm" variant="subtle" icon={Download} loading={copying === d.id} onClick={() => void installApp(d.id)}>
                        {t(copied.includes(d.id) ? "usb.installAgain" : "usb.installApp")}
                      </Button>
                    )}
                    {d.status === "driver_missing" && (
                      <Button size="sm" variant="subtle" iconRight={ExternalLink} onClick={() => void openUrl(APPLE_DEVICES_URL).catch(() => undefined)}>
                        Apple Devices
                      </Button>
                    )}
                    {d.source === "adb" && d.status === "ready" && d.serial && (
                      <Button
                        size="sm"
                        variant="subtle"
                        icon={Cable}
                        onClick={async () => {
                          const ok = await run((b) => b.adbReverse(d.serial!).then(() => true));
                          if (ok) toast("success", t("usb.cableReady"));
                        }}
                      >
                        {t("usb.useCable")}
                      </Button>
                    )}
                  </div>
                </div>
              </motion.div>
            );
          })}
        </AnimatePresence>
      </div>
    </section>
  );
}
