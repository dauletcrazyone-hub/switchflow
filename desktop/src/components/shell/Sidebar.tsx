import { motion } from "motion/react";
import { ArrowLeftRight, HardDriveDownload, History, Info, LifeBuoy, Lock, Settings } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useApp, useT, type Screen } from "../../state/store";
import { cn, motionTokens } from "../../lib/ui";

const NAV: Array<{ id: Screen; icon: LucideIcon }> = [
  { id: "home", icon: ArrowLeftRight },
  { id: "history", icon: History },
  { id: "backups", icon: HardDriveDownload },
  { id: "settings", icon: Settings },
  { id: "help", icon: LifeBuoy },
  { id: "about", icon: Info },
];

export function Sidebar() {
  const t = useT();
  const screen = useApp((s) => s.screen);
  const setScreen = useApp((s) => s.setScreen);
  const session = useApp((s) => s.snapshot.session);
  const busy = session && ["preparing", "running", "paused", "waiting_reconnect"].includes(session.state);

  return (
    <nav className="relative z-10 flex w-[232px] shrink-0 flex-col px-3 pt-2 pb-4" aria-label="SwitchFlow">
      <ul className="flex flex-col gap-0.5">
        {NAV.map(({ id, icon: Icon }) => {
          const active = screen === id;
          return (
            <li key={id}>
              <button
                type="button"
                onClick={() => setScreen(id)}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "relative flex h-10 w-full items-center gap-3 rounded-[11px] px-3 text-body font-medium transition-colors duration-150",
                  active ? "text-text" : "text-text-2 hover:bg-line/60 hover:text-text",
                )}
              >
                {active && (
                  <motion.span
                    layoutId="nav-active"
                    className="absolute inset-0 rounded-[11px] bg-surface shadow-[var(--shadow-sm)] ring-1 ring-line"
                    transition={motionTokens.spring}
                  />
                )}
                {active && (
                  <motion.span
                    layoutId="nav-bar"
                    className="absolute top-2.5 bottom-2.5 left-0 w-[3px] rounded-full bg-accent"
                    transition={motionTokens.spring}
                  />
                )}
                <Icon size={18} strokeWidth={1.9} className={cn("relative", active && "text-accent")} />
                <span className="relative">{t(`nav.${id}`)}</span>
                {id === "home" && busy && (
                  <span className="relative ml-auto h-2 w-2 rounded-full bg-accent shadow-[0_0_0_4px_var(--accent-soft)]" />
                )}
              </button>
            </li>
          );
        })}
      </ul>
      <div className="mt-auto rounded-[14px] p-3 text-caption text-text-2" title={t("privacy.tooltip")}>
        <div className="flex items-center gap-2 font-medium text-text">
          <Lock size={14} className="text-success" />
          {t("privacy.badge")}
        </div>
        <p className="mt-1 leading-snug text-text-3">{t("privacy.tooltip")}</p>
      </div>
    </nav>
  );
}
