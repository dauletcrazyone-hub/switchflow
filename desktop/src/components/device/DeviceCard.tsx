import { AnimatePresence, motion } from "motion/react";
import { BatteryCharging, BatteryFull, BatteryLow, BatteryMedium, Cable, HardDrive, MonitorSmartphone, QrCode, Unplug, Wifi, WifiOff } from "lucide-react";
import { useState } from "react";
import type { DeviceView } from "../../backend/types";
import { deviceDetails, deviceNickname, deviceTitle } from "../../lib/device";
import { formatBytes } from "../../lib/format";
import { cn, motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";
import { Badge, ProgressBar } from "../ui/Controls";
import { Button, IconButton } from "../ui/Button";
import { ConfirmDialog } from "../ui/Sheet";
import { PhoneArt } from "./PhoneArt";

function BatteryIcon({ percent, charging }: { percent: number; charging: boolean | null }) {
  const Icon = charging ? BatteryCharging : percent > 70 ? BatteryFull : percent > 25 ? BatteryMedium : BatteryLow;
  return <Icon size={15} className={percent <= 15 && !charging ? "text-danger" : ""} />;
}

export function RoleLabel({ role }: { role: "source" | "target" }) {
  const t = useT();
  return (
    <span className={cn("text-micro font-semibold tracking-[0.08em] uppercase", role === "source" ? "text-accent" : "text-accent-2")}>
      {t(role === "source" ? "home.source" : "home.target")}
    </span>
  );
}

export function DeviceCard({ device, role, compact, active }: { device: DeviceView; role: "source" | "target"; compact?: boolean; active?: boolean }) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const demo = useApp((s) => s.demo);
  const run = useApp((s) => s.run);
  const session = useApp((s) => s.snapshot.session);
  const [confirm, setConfirm] = useState(false);
  const { info } = device;
  const nickname = deviceNickname(info);
  const used = info.storageTotalBytes > 0 ? 100 - (info.storageFreeBytes / info.storageTotalBytes) * 100 : 0;
  const busy = session && !["completed", "cancelled", "failed"].includes(session.state);

  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.94, y: 10 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.96 }}
      transition={motionTokens.springSoft}
      className={cn("glass relative flex-1 rounded-[22px]", compact ? "p-4" : "p-5")}
    >
      {/* one-shot "detected" ring */}
      <motion.span
        className="pointer-events-none absolute inset-0 rounded-[22px] ring-2 ring-accent"
        initial={{ opacity: 0.8, scale: 1 }}
        animate={{ opacity: 0, scale: 1.04 }}
        transition={{ duration: 0.9, ease: "easeOut" }}
        aria-hidden
      />
      <div className="flex items-start justify-between">
        <RoleLabel role={role} />
        <div className="flex items-center gap-1.5">
          {demo && <Badge tone="warning">{t("app.demoBadge")}</Badge>}
          <Badge tone={device.connected ? "success" : "danger"} dot>
            {device.connected ? (device.viaUsb ? t("home.viaUsb") : t("home.viaWifi")) : t("home.lost")}
          </Badge>
          {!compact && (
            <IconButton icon={Unplug} label={t("home.unpair")} size={28} disabled={!!busy} onClick={() => setConfirm(true)} />
          )}
        </div>
      </div>

      <div className={cn("flex items-center", compact ? "mt-2 gap-4" : "mt-3 gap-5")}>
        <PhoneArt platform={info.platform} width={compact ? 44 : 60} active={active} />
        <div className="min-w-0 flex-1">
          <div className={cn("truncate font-display font-semibold", compact ? "text-headline" : "text-title")} title={deviceTitle(info)}>
            {deviceTitle(info)}
          </div>
          <div className="mt-0.5 truncate text-callout text-text-2">{deviceDetails(info)}</div>
          {nickname && (
            <div className="mt-0.5 truncate text-caption text-text-3" title={t("home.nicknameHint")}>
              {t("home.nickname", { name: nickname })}
            </div>
          )}
          {!compact && (
            <div className="mt-4 space-y-2.5">
              <div>
                <div className="mb-1.5 flex items-center gap-1.5 text-caption text-text-2">
                  <HardDrive size={13} />
                  <span className="tabular">
                    {t("home.free", { free: formatBytes(lang, info.storageFreeBytes), total: formatBytes(lang, info.storageTotalBytes) })}
                  </span>
                </div>
                <ProgressBar value={used} tone={used > 92 ? "warning" : "accent"} className="h-1" />
              </div>
              {info.batteryPercent != null && (
                <div className="flex items-center gap-1.5 text-caption text-text-2">
                  <BatteryIcon percent={info.batteryPercent} charging={info.charging} />
                  <span className="tabular">
                    {t("home.battery", { value: info.batteryPercent })}
                    {info.charging ? ` · ${t("home.charging")}` : ""}
                  </span>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
      <ConfirmDialog
        open={confirm}
        title={t("home.unpair")}
        body={t("home.unpairConfirm", { name: deviceTitle(info) })}
        confirmLabel={t("home.unpair")}
        danger
        onCancel={() => setConfirm(false)}
        onConfirm={() => {
          setConfirm(false);
          void run((b) => b.unpairDevice(info.id));
        }}
      />
    </motion.div>
  );
}

/** Card for a non-phone endpoint: this PC (backup target) or a backup folder (restore source). */
export function EndpointCard({ title, subtitle, role, compact }: { title: string; subtitle: string; role: "source" | "target"; compact?: boolean }) {
  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.94 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={motionTokens.springSoft}
      className={cn("glass flex-1 rounded-[22px]", compact ? "p-4" : "p-5")}
    >
      <RoleLabel role={role} />
      <div className="mt-2 flex items-center gap-4">
        <div className="flex h-[90px] w-[44px] items-center justify-center text-accent">
          <MonitorSmartphone size={36} strokeWidth={1.6} />
        </div>
        <div className="min-w-0">
          <div className="truncate font-display text-headline font-semibold">{title}</div>
          <div className="mt-0.5 truncate text-callout text-text-2" title={subtitle}>
            {subtitle}
          </div>
        </div>
      </div>
    </motion.div>
  );
}

export function EmptySlot({ index, onConnect }: { index: 1 | 2; onConnect: () => void }) {
  const t = useT();
  const usb = useApp((s) => s.snapshot.usb);
  const serverRunning = useApp((s) => s.snapshot.server.running);
  const hint = usb[index - 1];
  return (
    <motion.div
      layout
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={motionTokens.small}
      className="relative flex flex-1 flex-col items-center justify-center rounded-[22px] border border-dashed border-line-strong px-5 py-6 text-center"
    >
      <div className="relative mb-4 flex items-center justify-center">
        <span className="pulse-ring absolute h-24 w-24 rounded-full border border-accent/50" aria-hidden />
        <span className="pulse-ring absolute h-24 w-24 rounded-full border border-accent/40 [animation-delay:1.2s]" aria-hidden />
        <PhoneArt platform={null} width={40} ghost />
      </div>
      <div className="font-display text-headline font-semibold">{t(index === 1 ? "home.connectFirst" : "home.connectSecond")}</div>
      <p className="mt-1 max-w-[260px] text-callout text-text-2">{t("home.connectHint")}</p>
      <AnimatePresence>
        {hint && (
          <motion.p
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            className="mt-2 flex items-center gap-1.5 text-caption text-accent"
          >
            {hint.source === "adb" ? <Cable size={13} /> : hint.status === "ready" ? <Wifi size={13} /> : <WifiOff size={13} />}
            {t("home.unlockHint")}
          </motion.p>
        )}
      </AnimatePresence>
      <Button variant="subtle" size="md" icon={QrCode} className="mt-4" onClick={onConnect} disabled={!serverRunning}>
        {t("home.showQr")}
      </Button>
    </motion.div>
  );
}
