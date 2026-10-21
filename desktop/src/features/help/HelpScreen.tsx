import { AnimatePresence, motion } from "motion/react";
import { Ban, ChevronDown, KeyRound, Rocket, Usb, Wifi } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useState } from "react";
import { translateRaw } from "../../i18n";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

const SECTIONS: Array<{ id: string; icon: LucideIcon }> = [
  { id: "start", icon: Rocket },
  { id: "usb", icon: Usb },
  { id: "network", icon: Wifi },
  { id: "permissions", icon: KeyRound },
  { id: "limits", icon: Ban },
];

function Item({ q, a }: { q: string; a: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="border-t border-line first:border-t-0">
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="flex w-full items-center gap-3 px-5 py-3.5 text-left">
        <span className="flex-1 text-body font-medium">{q}</span>
        <motion.span animate={{ rotate: open ? 180 : 0 }} transition={motionTokens.small} className="flex text-text-3">
          <ChevronDown size={17} />
        </motion.span>
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={motionTokens.screen} className="overflow-hidden">
            <p className="selectable px-5 pb-4 text-callout leading-relaxed text-text-2">{a}</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

export function HelpScreen() {
  const t = useT();
  const lang = useApp((s) => s.lang);
  return (
    <div className="mx-auto max-w-[760px] pb-10">
      <h1 className="font-display text-display font-semibold">{t("help.title")}</h1>
      <p className="mt-1 text-body text-text-2">{t("help.subtitle")}</p>
      {SECTIONS.map(({ id, icon: Icon }) => {
        const items = translateRaw<string[][]>(lang, `help.sections.${id}.items`) ?? [];
        return (
          <section key={id} className="mt-7">
            <h2 className="mb-2 flex items-center gap-2 px-1 text-headline font-semibold">
              <Icon size={18} className="text-accent" /> {t(`help.sections.${id}.title`)}
            </h2>
            <div className="glass rounded-[var(--radius-card)]">
              {items.map(([q, a]) => (
                <Item key={q} q={q ?? ""} a={a ?? ""} />
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}
