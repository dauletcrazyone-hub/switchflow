import { AnimatePresence, MotionConfig, motion } from "motion/react";
import { TriangleAlert } from "lucide-react";
import { useEffect } from "react";
import { Sidebar } from "./components/shell/Sidebar";
import { TitleBar } from "./components/shell/TitleBar";
import { Toasts } from "./components/ui/Toasts";
import { AboutScreen } from "./features/about/AboutScreen";
import { BackupsScreen } from "./features/backups/BackupsScreen";
import { HelpScreen } from "./features/help/HelpScreen";
import { HistoryScreen } from "./features/history/HistoryScreen";
import { HomeScreen } from "./features/home/HomeScreen";
import { SettingsScreen } from "./features/settings/SettingsScreen";
import { motionTokens } from "./lib/ui";
import { useApp, useReducedMotion, useT } from "./state/store";

const SCREENS = {
  home: HomeScreen,
  history: HistoryScreen,
  backups: BackupsScreen,
  settings: SettingsScreen,
  help: HelpScreen,
  about: AboutScreen,
};

function DemoBanner() {
  const t = useT();
  const demo = useApp((s) => s.demo);
  const realAvailable = useApp((s) => s.realAvailable);
  return (
    <AnimatePresence>
      {demo && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: "auto", opacity: 1 }}
          exit={{ height: 0, opacity: 0 }}
          className="overflow-hidden"
        >
          <div className="mx-6 mt-1 mb-2 flex items-center gap-2.5 rounded-xl bg-warning-soft px-4 py-2 text-callout text-text">
            <TriangleAlert size={16} className="shrink-0 text-warning" />
            {realAvailable ? t("app.demoBanner") : `${t("app.demoBanner")} ${t("app.outsideTauri")}`}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export default function App() {
  const ready = useApp((s) => s.ready);
  const screen = useApp((s) => s.screen);
  const theme = useApp((s) => s.theme);
  const lang = useApp((s) => s.lang);
  const reduce = useReducedMotion();
  const Screen = SCREENS[screen];

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.dataset.reduceMotion = String(reduce);
    document.documentElement.lang = lang;
  }, [theme, reduce, lang]);

  useEffect(() => {
    document.getElementById("sf-scroll")?.scrollTo({ top: 0 });
  }, [screen]);

  return (
    <MotionConfig reducedMotion={reduce ? "always" : "user"}>
      <motion.div
        className="ambient relative flex h-full flex-col bg-bg text-text"
        initial={{ opacity: 0 }}
        animate={{ opacity: ready ? 1 : 0 }}
        transition={{ duration: 0.42, ease: [0.22, 1, 0.36, 1] }}
      >
        <TitleBar />
        <div className="relative z-10 flex min-h-0 flex-1">
          <Sidebar />
          <main className="relative flex min-w-0 flex-1 flex-col overflow-hidden rounded-tl-[22px] border-t border-l border-line bg-bg-2/55">
            <DemoBanner />
            <div id="sf-scroll" className="scroll-area flex-1 px-8 pt-6 pb-8">
              <AnimatePresence mode="wait">
                <motion.div
                  key={screen}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -6 }}
                  transition={motionTokens.screen}
                >
                  <Screen />
                </motion.div>
              </AnimatePresence>
            </div>
          </main>
        </div>
        <Toasts />
      </motion.div>
    </MotionConfig>
  );
}
