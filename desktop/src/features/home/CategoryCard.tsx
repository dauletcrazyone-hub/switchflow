import { AnimatePresence, motion } from "motion/react";
import { ChevronDown, Info, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import type { Album, Category, CategorySummary, CompatStatus, Entry } from "../../backend/types";
import { CategoryIcon } from "../../components/CategoryIcon";
import { Badge, Checkbox, Segmented, Skeleton } from "../../components/ui/Controls";
import { Button } from "../../components/ui/Button";
import { Spinner } from "../../components/ui/Spinner";
import { formatBytes } from "../../lib/format";
import { cn, motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

export const STATUS_TONE: Record<CompatStatus, "success" | "warning" | "accent" | "neutral"> = {
  supported: "success",
  action_required: "warning",
  official_tool: "accent",
  unsupported: "neutral",
};

function AlbumPicker({ category, albums }: { category: Category; albums: Album[] }) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const selected = useApp((s) => s.selection.albums[category] ?? null);
  const setAlbums = useApp((s) => s.setAlbums);
  const all = albums.map((a) => a.id);
  const isOn = (id: string) => selected === null || selected.includes(id);
  const toggle = (id: string) => {
    const current = selected ?? all;
    const next = current.includes(id) ? current.filter((x) => x !== id) : [...current, id];
    setAlbums(category, next.length === all.length ? null : next);
  };
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-caption font-semibold text-text-2">{t("select.albums")}</span>
        <button className="text-caption font-medium text-accent hover:underline" onClick={() => setAlbums(category, null)}>
          {t("select.allAlbums")}
        </button>
      </div>
      <div className="grid grid-cols-2 gap-1.5">
        {albums.map((a) => (
          <label
            key={a.id}
            className="flex cursor-default items-center gap-2.5 rounded-xl px-2.5 py-2 transition-colors hover:bg-line"
            onClick={() => toggle(a.id)}
          >
            <Checkbox checked={isOn(a.id)} label={a.name} size={18} onChange={() => toggle(a.id)} />
            <span className="min-w-0 flex-1 truncate text-callout">{a.name}</span>
            <span className="tabular text-caption text-text-3">
              {a.count} · {formatBytes(lang, a.totalBytes)}
            </span>
          </label>
        ))}
      </div>
    </div>
  );
}

/** Rows rendered at once; the search narrows a big address book. */
const CONTACTS_SHOWN = 300;

/** Picks contacts one by one from the source's export (the PC sends only these to the other phone). */
export function ContactPicker({ deviceId, total }: { deviceId: string; total: number }) {
  const t = useT();
  const backend = useApp((s) => s.backend);
  const selected = useApp((s) => s.selection.albums.contacts ?? null);
  const setAlbums = useApp((s) => s.setAlbums);
  const [open, setOpen] = useState(selected !== null);
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [query, setQuery] = useState("");
  useEffect(() => {
    if (!open || entries) return;
    let live = true;
    backend
      .getEntries(deviceId, "contacts")
      .then((e) => live && setEntries(e))
      .catch(() => live && setEntries([]));
    return () => {
      live = false;
    };
  }, [open, entries, backend, deviceId]);

  const q = query.trim().toLowerCase();
  const found = (entries ?? []).filter((e) => !q || e.name.toLowerCase().includes(q) || e.detail.toLowerCase().includes(q));
  const isOn = (id: string) => selected === null || selected.includes(id);
  const toggle = (id: string) => {
    const current = selected ?? (entries ?? []).map((e) => e.id);
    const next = current.includes(id) ? current.filter((x) => x !== id) : [...current, id];
    setAlbums("contacts", entries && next.length === entries.length ? null : next);
  };

  return (
    <div>
      <div className="mb-2 flex items-center justify-between gap-3">
        <span className="text-caption font-semibold text-text-2">
          {t("select.contactsWhich")} ·{" "}
          {selected === null ? t("select.contactsAll", { count: total }) : t("select.contactsPicked", { count: selected.length, total })}
        </span>
        {open ? (
          <button
            className="text-caption font-medium text-accent hover:underline"
            onClick={() => {
              setAlbums("contacts", null);
              setOpen(false);
            }}
          >
            {t("select.contactsEveryone")}
          </button>
        ) : (
          <button className="text-caption font-medium text-accent hover:underline" onClick={() => setOpen(true)}>
            {t("select.contactsPick")}
          </button>
        )}
      </div>
      {open && (
        <div className="space-y-2">
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("select.contactsSearch")}
            aria-label={t("select.contactsSearch")}
            className="w-full rounded-xl border border-line bg-surface px-3 py-2 text-callout outline-none focus:border-accent"
          />
          <div className="flex gap-3">
            <button
              className="text-caption font-medium text-accent hover:underline disabled:opacity-50"
              disabled={found.length === 0}
              onClick={() => setAlbums("contacts", found.map((e) => e.id))}
            >
              {t("select.contactsOnlyFound", { count: found.length })}
            </button>
            <button className="text-caption font-medium text-accent hover:underline" onClick={() => setAlbums("contacts", [])}>
              {t("select.contactsNone")}
            </button>
          </div>
          {entries === null ? (
            <Skeleton className="h-24 w-full" />
          ) : found.length === 0 ? (
            <p className="text-callout text-text-3">{t("select.contactsNotFound")}</p>
          ) : (
            <div className="max-h-72 overflow-y-auto rounded-xl border border-line">
              {found.slice(0, CONTACTS_SHOWN).map((e) => (
                <label
                  key={e.id}
                  className="flex cursor-default items-center gap-2.5 px-2.5 py-1.5 transition-colors hover:bg-line"
                  onClick={() => toggle(e.id)}
                >
                  <Checkbox checked={isOn(e.id)} label={e.name} size={18} onChange={() => toggle(e.id)} />
                  <span className="min-w-0 flex-1 truncate text-callout">{e.name}</span>
                  <span className="truncate text-caption text-text-3">{e.detail}</span>
                </label>
              ))}
            </div>
          )}
          {found.length > CONTACTS_SHOWN && <p className="text-caption text-text-3">{t("select.contactsMore", { count: CONTACTS_SHOWN })}</p>}
        </div>
      )}
    </div>
  );
}

function ContactsPolicyPicker() {
  const t = useT();
  const policy = useApp((s) => s.selection.contactsPolicy);
  const setPolicy = useApp((s) => s.setContactsPolicy);
  return (
    <div>
      <div className="mb-2 text-caption font-semibold text-text-2">{t("contactsPolicy.title")}</div>
      <Segmented
        label={t("contactsPolicy.title")}
        value={policy}
        onChange={setPolicy}
        options={[
          { value: "merge", label: t("contactsPolicy.merge") },
          { value: "skip", label: t("contactsPolicy.skip") },
          { value: "keepBoth", label: t("contactsPolicy.keepBoth") },
        ]}
      />
      <p className="mt-2 text-caption text-text-2">{t(`contactsPolicy.${policy}Hint`)}</p>
    </div>
  );
}

export function CategoryCard({
  category,
  status,
  note,
  inventory,
  scanning,
  selected,
  onToggle,
  onRescan,
}: {
  category: Category;
  status: CompatStatus;
  note: string | null;
  inventory: CategorySummary | undefined;
  scanning: boolean;
  selected: boolean;
  onToggle: (on: boolean) => void;
  onRescan: () => void;
}) {
  const t = useT();
  const lang = useApp((s) => s.lang);
  const sourceId = useApp((s) => s.snapshot.sourceId);
  const [open, setOpen] = useState(false);
  // If the phone has not answered for a while (prompt dismissed, app closed), offer to ask again.
  const [slowScan, setSlowScan] = useState(false);
  useEffect(() => {
    setSlowScan(false);
    if (!scanning) return;
    const id = setTimeout(() => setSlowScan(true), 20_000);
    return () => clearTimeout(id);
  }, [scanning]);
  const transferable = status === "supported" || status === "action_required";
  const denied = inventory && (inventory.permission === "denied" || inventory.permission === "unavailable");
  const checkable = transferable && !denied;

  let meta: React.ReactNode;
  if (!transferable) meta = <span className="text-text-3">{t(`categories.${category}.desc`)}</span>;
  else if (scanning)
    meta = (
      <span className="flex items-center gap-2 text-accent">
        <Spinner size={12} /> {t("select.scanning")}
        {slowScan && (
          <button
            type="button"
            className="font-medium underline-offset-2 hover:underline"
            onClick={(e) => {
              e.stopPropagation();
              onRescan();
            }}
          >
            {t("select.askAgain")}
          </button>
        )}
      </span>
    );
  else if (inventory?.note === "not_picked") meta = <span className="text-text-3">{t("select.notPicked")}</span>;
  else if (inventory?.permission === "denied") meta = <span className="text-danger">{t("select.denied")}</span>;
  else if (inventory?.permission === "unavailable") meta = <span className="text-text-3">{t("select.unavailable")}</span>;
  else if (inventory)
    meta = (
      <span className="tabular">
        {inventory.count === 0 ? t("select.empty") : `${t(`units.${category}`, { count: inventory.count })} · ${formatBytes(lang, inventory.totalBytes)}`}
      </span>
    );
  else meta = <span className="text-text-3">{t("select.tapToCheck")}</span>;

  const hasDetails = !!note || !transferable || (inventory?.albums.length ?? 0) > 0 || category === "contacts" || !!denied || inventory?.permission === "limited";

  return (
    <motion.div
      layout
      transition={motionTokens.springSoft}
      className={cn(
        "glass overflow-hidden rounded-[18px] transition-[box-shadow,border-color] duration-200",
        selected && "border-accent/60 shadow-[var(--glow)]",
        !transferable && "opacity-80",
      )}
    >
      <div
        className="flex items-center gap-3.5 p-4"
        onClick={() => (checkable ? onToggle(!selected) : setOpen(!open))}
        role="presentation"
      >
        <Checkbox checked={selected} disabled={!checkable} onChange={onToggle} label={t(`categories.${category}.title`)} />
        <CategoryIcon category={category} muted={!transferable} />
        <div className="min-w-0 flex-1">
          <div className="text-headline font-semibold">{t(`categories.${category}.title`)}</div>
          <div className="mt-0.5 truncate text-callout text-text-2">
            {scanning && !inventory ? <Skeleton className="mt-1 h-3.5 w-36" /> : meta}
          </div>
          <Badge tone={STATUS_TONE[status]} dot className="mt-2">
            {t(`compat.${status}`)}
          </Badge>
        </div>
        {hasDetails && (
          <button
            type="button"
            aria-label={t("common.learnMore")}
            aria-expanded={open}
            onClick={(e) => {
              e.stopPropagation();
              setOpen(!open);
            }}
            className="flex h-8 w-8 items-center justify-center rounded-full text-text-2 hover:bg-line hover:text-text"
          >
            <motion.span animate={{ rotate: open ? 180 : 0 }} transition={motionTokens.small} className="flex">
              <ChevronDown size={18} />
            </motion.span>
          </button>
        )}
      </div>
      <AnimatePresence initial={false}>
        {open && hasDetails && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={motionTokens.screen}
            className="overflow-hidden"
          >
            <div className="space-y-4 border-t border-line px-4 pt-3.5 pb-4">
              {note && (
                <p className="flex gap-2 text-callout text-text-2">
                  <Info size={15} className="mt-0.5 shrink-0 text-accent" />
                  <span>{t(note)}</span>
                </p>
              )}
              {denied && inventory?.permission === "denied" && (
                <div className="flex items-center justify-between gap-3 rounded-xl bg-danger-soft p-3">
                  <span className="text-callout text-text">{t("select.deniedHint")}</span>
                  <Button size="sm" variant="secondary" icon={RotateCcw} onClick={onRescan}>
                    {t("select.askAgain")}
                  </Button>
                </div>
              )}
              {inventory?.permission === "limited" && <p className="text-callout text-warning">{t("select.limitedHint")}</p>}
              {transferable && inventory && inventory.albums.length > 0 && <AlbumPicker category={category} albums={inventory.albums} />}
              {transferable && category === "contacts" && sourceId && (inventory?.entryCount ?? 0) > 0 && (
                <ContactPicker deviceId={sourceId} total={inventory!.count} />
              )}
              {transferable && category === "contacts" && <ContactsPolicyPicker />}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
