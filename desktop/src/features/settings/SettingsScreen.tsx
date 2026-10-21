import { FolderOpen, KeyRound, ShieldCheck } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import type { AppInfo, Settings } from "../../backend/types";
import { Button } from "../../components/ui/Button";
import { Card, Segmented, Switch } from "../../components/ui/Controls";
import { ConfirmDialog } from "../../components/ui/Sheet";
import { useApp, useT } from "../../state/store";

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-7">
      <h2 className="mb-2 px-1 text-caption font-semibold tracking-wide text-text-3 uppercase">{title}</h2>
      <Card className="divide-y divide-line">{children}</Card>
    </section>
  );
}

function Row({ title, hint, children }: { title: string; hint?: string; children?: ReactNode }) {
  return (
    <div className="flex items-center gap-6 px-5 py-4">
      <div className="min-w-0 flex-1">
        <div className="text-body font-medium">{title}</div>
        {hint && <p className="mt-0.5 text-caption text-text-2">{hint}</p>}
      </div>
      <div className="flex shrink-0 items-center gap-2">{children}</div>
    </div>
  );
}

function PathRow({ title, hint, value, fallback, onPick, onReset }: { title: string; hint?: string; value: string | null; fallback: string; onPick: () => void; onReset: () => void }) {
  const t = useT();
  return (
    <div className="px-5 py-4">
      <div className="flex items-center gap-6">
        <div className="min-w-0 flex-1">
          <div className="text-body font-medium">{title}</div>
          {hint && <p className="mt-0.5 text-caption text-text-2">{hint}</p>}
        </div>
        {value && (
          <Button size="sm" variant="ghost" onClick={onReset}>
            {t("settings.reset_default")}
          </Button>
        )}
        <Button size="sm" icon={FolderOpen} onClick={onPick}>
          {t("settings.choose")}
        </Button>
      </div>
      <div className="selectable mt-2 truncate rounded-lg bg-line/60 px-3 py-1.5 font-mono text-caption text-text-2">{value ?? fallback}</div>
    </div>
  );
}

export function SettingsScreen() {
  const t = useT();
  const settings = useApp((s) => s.settings);
  const save = useApp((s) => s.saveSettings);
  const backend = useApp((s) => s.backend);
  const demo = useApp((s) => s.demo);
  const realAvailable = useApp((s) => s.realAvailable);
  const setDemo = useApp((s) => s.setDemo);
  const run = useApp((s) => s.run);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const [port, setPort] = useState(String(settings.preferredPort));

  useEffect(() => {
    void backend.getAppInfo().then(setInfo).catch(() => undefined);
  }, [backend, settings]);

  const pick = async (key: keyof Pick<Settings, "stagingDir" | "backupDir">) => {
    const dir = await backend.pickFolder();
    if (dir) await save({ [key]: dir });
  };

  const setTheme = (theme: Settings["theme"]) => {
    const apply = () => void save({ theme });
    const doc = document as Document & { startViewTransition?: (cb: () => void) => unknown };
    if (doc.startViewTransition && !settings.reduceMotion) doc.startViewTransition(apply);
    else apply();
  };

  return (
    <div className="mx-auto max-w-[760px] pb-10">
      <h1 className="font-display text-display font-semibold">{t("settings.title")}</h1>

      <Group title={t("settings.appearance")}>
        <Row title={t("settings.theme")}>
          <Segmented
            label={t("settings.theme")}
            value={settings.theme}
            onChange={setTheme}
            options={[
              { value: "system", label: t("settings.themeSystem") },
              { value: "light", label: t("settings.themeLight") },
              { value: "dark", label: t("settings.themeDark") },
            ]}
          />
        </Row>
        <Row title={t("settings.language")}>
          <Segmented
            label={t("settings.language")}
            value={settings.language}
            onChange={(language) => void save({ language })}
            options={[
              { value: "system", label: t("settings.languageSystem") },
              { value: "ru", label: "Русский" },
              { value: "en", label: "English" },
              { value: "kk", label: "Қазақша β" },
            ]}
          />
        </Row>
        <Row title={t("settings.reduceMotion")} hint={t("settings.reduceMotionHint")}>
          <Switch label={t("settings.reduceMotion")} checked={settings.reduceMotion} onChange={(reduceMotion) => void save({ reduceMotion })} />
        </Row>
      </Group>

      <Group title={t("settings.transfer")}>
        <PathRow
          title={t("settings.stagingDir")}
          hint={t("settings.stagingHint")}
          value={settings.stagingDir}
          fallback={info?.stagingDir ?? ""}
          onPick={() => void pick("stagingDir")}
          onReset={() => void save({ stagingDir: null })}
        />
        <PathRow
          title={t("settings.backupDir")}
          value={settings.backupDir}
          fallback={info?.backupDir ?? ""}
          onPick={() => void pick("backupDir")}
          onReset={() => void save({ backupDir: null })}
        />
        <Row title={t("settings.contactsPolicy")} hint={t(`contactsPolicy.${settings.contactsPolicy}Hint`)}>
          <Segmented
            label={t("settings.contactsPolicy")}
            value={settings.contactsPolicy}
            onChange={(contactsPolicy) => void save({ contactsPolicy })}
            options={[
              { value: "merge", label: t("contactsPolicy.merge") },
              { value: "skip", label: t("contactsPolicy.skip") },
              { value: "keepBoth", label: t("contactsPolicy.keepBoth") },
            ]}
          />
        </Row>
        <Row title={t("settings.cleanup")} hint={t("settings.cleanupHint")}>
          <Switch label={t("settings.cleanup")} checked={settings.cleanupStaging} onChange={(cleanupStaging) => void save({ cleanupStaging })} />
        </Row>
        <Row title={t("settings.notifications")} hint={t("settings.notificationsHint")}>
          <Switch label={t("settings.notifications")} checked={settings.notifications} onChange={(notifications) => void save({ notifications })} />
        </Row>
      </Group>

      <Group title={t("settings.security")}>
        <Row title={t("settings.approval")} hint={t("settings.approvalHint")}>
          <ShieldCheck size={20} className="text-success" />
        </Row>
        <Row title={t("settings.port")} hint={t("settings.portHint")}>
          <input
            value={port}
            inputMode="numeric"
            onChange={(e) => setPort(e.target.value.replace(/\D/g, "").slice(0, 5))}
            onBlur={() => {
              const n = Number(port);
              if (n >= 1024 && n <= 65535) void save({ preferredPort: n });
              else setPort(String(settings.preferredPort));
            }}
            className="tabular h-9 w-24 rounded-[10px] border border-line-strong bg-transparent px-3 text-right text-body outline-none focus:border-accent"
            aria-label={t("settings.port")}
          />
        </Row>
        <Row title={t("settings.reset")} hint={t("settings.resetHint")}>
          <Button size="sm" variant="danger" icon={KeyRound} onClick={() => setConfirmReset(true)} disabled={demo}>
            {t("settings.reset")}
          </Button>
        </Row>
        {info && info.fingerprint && info.fingerprint !== "demo" && (
          <div className="px-5 py-3">
            <div className="text-caption text-text-3">SHA-256</div>
            <div className="selectable mt-0.5 font-mono text-micro break-all text-text-2">{info.fingerprint.match(/.{1,4}/g)?.join(" ")}</div>
          </div>
        )}
      </Group>

      <Group title={t("settings.advanced")}>
        <Row title={t("settings.adb")} hint={info && !info.adbFound ? `${t("settings.adbHint")} ${t("settings.adbMissing")}` : t("settings.adbHint")}>
          <Switch label={t("settings.adb")} checked={settings.useAdb} onChange={(useAdb) => void save({ useAdb })} />
        </Row>
        <Row title={t("settings.demo")} hint={t("settings.demoHint")}>
          <Switch label={t("settings.demo")} checked={demo} disabled={!realAvailable} onChange={(on) => void setDemo(on)} />
        </Row>
        <Row title={t("settings.logs")} hint={t("settings.logsHint")}>
          <Button size="sm" icon={FolderOpen} disabled={!info || demo} onClick={() => info && void run((b) => b.revealPath(info.logsDir))}>
            {t("settings.openLogs")}
          </Button>
        </Row>
      </Group>

      <ConfirmDialog
        open={confirmReset}
        title={t("settings.reset")}
        body={t("settings.resetConfirm")}
        confirmLabel={t("settings.reset")}
        danger
        onCancel={() => setConfirmReset(false)}
        onConfirm={() => {
          setConfirmReset(false);
          void run((b) => b.resetIdentity());
        }}
      />
    </div>
  );
}
