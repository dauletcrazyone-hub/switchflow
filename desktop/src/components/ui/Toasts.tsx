import { AnimatePresence, motion } from "motion/react";
import { CircleAlert, CircleCheck, Info, X } from "lucide-react";
import { useApp } from "../../state/store";
import { motionTokens } from "../../lib/ui";

const icons = { error: CircleAlert, success: CircleCheck, info: Info };
const colors = { error: "text-danger", success: "text-success", info: "text-accent" };

export function Toasts() {
  const toasts = useApp((s) => s.toasts);
  const dismiss = useApp((s) => s.dismissToast);
  return (
    <div className="pointer-events-none fixed right-5 bottom-5 z-[60] flex w-[360px] flex-col gap-2" aria-live="polite">
      <AnimatePresence initial={false}>
        {toasts.map((t) => {
          const Icon = icons[t.kind];
          return (
            <motion.div
              key={t.id}
              layout
              initial={{ opacity: 0, y: 16, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, x: 24, transition: motionTokens.small }}
              transition={motionTokens.spring}
              className="glass-strong pointer-events-auto flex items-start gap-3 rounded-2xl p-3.5 pr-2"
              role={t.kind === "error" ? "alert" : "status"}
            >
              <Icon size={19} className={`mt-px shrink-0 ${colors[t.kind]}`} />
              <div className="flex-1 text-callout leading-snug">{t.text}</div>
              <button onClick={() => dismiss(t.id)} className="rounded-full p-1 text-text-3 hover:text-text" aria-label="×">
                <X size={14} />
              </button>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
