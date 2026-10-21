import { motion } from "motion/react";
import { FolderOpen, HardDriveDownload, HardDriveUpload, Smartphone } from "lucide-react";
import { useEffect, useState } from "react";
import { TRANSFERABLE, type BackupInfo, type Category } from "../../backend/types";
import { CategoryIcon } from "../../components/CategoryIcon";
import { Button } from "../../components/ui/Button";
import { Card, EmptyState, Skeleton } from "../../components/ui/Controls";
import { ConfirmDialog } from "../../components/ui/Sheet";
import { formatNumber } from "../../i18n";
import { deviceTitle } from "../../lib/device";
import { formatBytes, formatDate } from "../../lib/format";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";
import { CategoryPanel } from "../home/CategoryPanel";
import { useSlots } from "../home/DeviceStage";

export function BackupsScreen() {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const backend = useApp((s) => s.backend);
  const run = useApp((s) => s.run);
  const settings = useApp((s) => s.settings);
  const saveSettings = useApp((s) => s.saveSettings);
  const selection = useApp((s) => s.selection);
  const setScreen = useApp((s) => s.setScreen);
  const version = useApp((s) => s.historyVersion);
  const { source, target } = useSlots();
  const [list, setList] = useState<BackupInfo[] | null>(null);
  const [backupDir, setBackupDir] = useState<string>("");
  const [restore, setRestore] = useState<BackupInfo | null>(null);
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    let alive = true;
    void backend.listBackups().then((l) => alive && setList(l)).catch(() => alive && setList([]));
    void backend.getAppInfo().then((i) => alive && setBackupDir(i.backupDir)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [backend, version, settings.backupDir]);

  const startBackup = async () => {
    setStarting(true);
    const id = await run((b) =>
      b.startSession({
        kind: "backup",
        selections: selection.categories.map((c) => ({ category: c, albumIds: selection.albums[c] ?? null })),
      }),
    );
    setStarting(false);
    if (id) setScreen("home");
  };

  const startRestore = async (b: BackupInfo) => {
    setRestore(null);
    const cats = (Object.keys(b.counts) as Category[]).filter((c) => TRANSFERABLE.includes(c));
    const id = await run((x) =>
      x.startSession({ kind: "restore", backupPath: b.path, selections: cats.map((c) => ({ category: c, albumIds: null })) }),
    );
    if (id) setScreen("home");
  };

  return (
    <div className="mx-auto max-w-[1040px]">
      <h1 className="font-display text-display font-semibold">{t("backups.title")}</h1>
      <p className="mt-1 text-body text-text-2">{t("backups.subtitle")}</p>

      <Card className="mt-6 p-5">
        <div className="flex items-start gap-4">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-accent-soft text-accent">
            <HardDriveDownload size={22} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-headline font-semibold">{t("backups.newTitle")}</div>
            <p className="mt-0.5 text-callout text-text-2">{t("backups.newHint")}</p>
            <div className="mt-3 flex items-center gap-2 text-caption text-text-2">
              <span>{t("backups.folder")}:</span>
              <span className="selectable truncate font-medium text-text">{backupDir}</span>
              <button
                className="font-medium text-accent hover:underline"
                onClick={async () => {
                  const dir = await backend.pickFolder();
                  if (dir) await saveSettings({ backupDir: dir });
                }}
              >
                {t("backups.change")}
              </button>
            </div>
          </div>
        </div>
        {source && source.connected ? (
          <div className="mt-5">
            <div className="mb-4 flex items-center gap-2 text-callout text-text-2">
              <Smartphone size={15} /> {deviceTitle(source.info)}
            </div>
            <CategoryPanel source={source} targetPlatform={null} onStart={() => void startBackup()} startLabel={t("backups.start")} starting={starting} compactExtras />
          </div>
        ) : (
          <p className="mt-4 rounded-xl bg-line/60 p-3 text-callout text-text-2">{t("backups.noSource")}</p>
        )}
      </Card>

      <h2 className="mt-9 mb-3 font-display text-headline font-semibold">{t("backups.listTitle")}</h2>
      {list === null ? (
        <Skeleton className="h-24 rounded-2xl" />
      ) : list.length === 0 ? (
        <EmptyState icon={<HardDriveUpload size={28} />} title={t("backups.empty")} />
      ) : (
        <ul className="space-y-3">
          {list.map((b, i) => (
            <motion.li
              key={b.path}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...motionTokens.springSoft, delay: i * 0.04 }}
              className="glass flex items-center gap-4 rounded-2xl p-4"
            >
              <div className="min-w-0 flex-1">
                <div className="truncate text-body font-semibold">{b.device.name}</div>
                <div className="text-caption text-text-2">
                  {formatDate(lang, b.createdMs)} · {formatBytes(lang, b.totalBytes)}
                </div>
                <div className="mt-2 flex flex-wrap gap-3">
                  {(Object.entries(b.counts) as Array<[Category, number]>).map(([c, n]) => (
                    <span key={c} className="tabular flex items-center gap-1.5 text-caption text-text-2">
                      <CategoryIcon category={c} size={22} /> {formatNumber(lang, n)}
                    </span>
                  ))}
                </div>
              </div>
              <Button size="sm" variant="ghost" icon={FolderOpen} onClick={() => void run((x) => x.revealPath(b.path))}>
                {t("report.openFolder")}
              </Button>
              <Button
                size="sm"
                variant="subtle"
                disabled={!target || !target.connected}
                title={!target ? t("backups.needTarget") : undefined}
                onClick={() => setRestore(b)}
              >
                {target ? t("backups.restoreTo", { name: deviceTitle(target.info) }) : t("backups.restore")}
              </Button>
            </motion.li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={!!restore}
        title={t("backups.restoreTitle")}
        body={restore ? t("backups.restoreBody", { name: restore.device.name, target: target ? deviceTitle(target.info) : "" }) : ""}
        confirmLabel={t("backups.restore")}
        onCancel={() => setRestore(null)}
        onConfirm={() => restore && void startRestore(restore)}
      />
    </div>
  );
}
