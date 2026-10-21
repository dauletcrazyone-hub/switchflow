import { Copy, Minus, Square, X } from "lucide-react";
import { useEffect, useState } from "react";
import { isTauri } from "../../backend/api";
import { useApp, useT } from "../../state/store";
import { Logo } from "../Logo";

type Win = Awaited<ReturnType<typeof getWindow>>;
async function getWindow() {
  const { getCurrentWindow } = await import("@tauri-apps/api/window");
  return getCurrentWindow();
}

function WindowButton({ label, onClick, danger, children }: { label: string; onClick: () => void; danger?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className={`flex h-10 w-[46px] items-center justify-center text-text-2 transition-colors duration-100 ${
        danger ? "hover:bg-[#C42B1C] hover:text-white" : "hover:bg-line hover:text-text"
      }`}
    >
      {children}
    </button>
  );
}

export function TitleBar() {
  const t = useT();
  const demo = useApp((s) => s.demo);
  const [win, setWin] = useState<Win | null>(null);
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!isTauri()) return;
    let unlisten: (() => void) | undefined;
    void getWindow().then(async (w) => {
      setWin(w);
      setMaximized(await w.isMaximized());
      unlisten = await w.onResized(async () => setMaximized(await w.isMaximized()));
    });
    return () => unlisten?.();
  }, []);

  return (
    <header data-tauri-drag-region className="relative z-40 flex h-10 shrink-0 items-center justify-between pl-4">
      <div data-tauri-drag-region className="flex items-center gap-2.5">
        <Logo size={18} />
        <span data-tauri-drag-region className="text-callout font-semibold tracking-tight">
          SwitchFlow
        </span>
        {demo && (
          <span className="rounded-full bg-warning-soft px-2 py-[1px] text-micro font-semibold tracking-wide text-warning uppercase">
            {t("app.demoBadge")}
          </span>
        )}
      </div>
      {win && (
        <div className="flex">
          <WindowButton label={t("window.minimize")} onClick={() => void win.minimize()}>
            <Minus size={16} strokeWidth={1.5} />
          </WindowButton>
          <WindowButton label={t(maximized ? "window.restore" : "window.maximize")} onClick={() => void win.toggleMaximize()}>
            {maximized ? <Copy size={13} strokeWidth={1.5} className="scale-x-[-1]" /> : <Square size={13} strokeWidth={1.5} />}
          </WindowButton>
          <WindowButton label={t("window.close")} onClick={() => void win.close()} danger>
            <X size={17} strokeWidth={1.5} />
          </WindowButton>
        </div>
      )}
    </header>
  );
}
