import { AnimatePresence, motion } from "motion/react";
import { Cable, CircleAlert, ExternalLink, Smartphone, Usb } from "lucide-react";
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
    case "offline":
      return { title: t("usb.offline"), hint: "", warn: true };
    default:
      return {
        title: d.platform === "ios" ? t("usb.iphoneDetected") : t("usb.detected", { model: d.model || "Android" }),
        hint: t("usb.openCompanion"),
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
  if (usb.length === 0) return null;
  return (
    <section aria-label={t("usb.title")}>
      <div className="mb-2 flex items-center gap-2 text-caption font-semibold tracking-wide text-text-3 uppercase">
        <Usb size={13} /> {t("usb.title")}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <AnimatePresence initial={false}>
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
                  <div className="mt-2 flex flex-wrap gap-2">
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
