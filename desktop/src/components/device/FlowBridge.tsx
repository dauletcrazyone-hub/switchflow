import { AnimatePresence, motion } from "motion/react";
import { ArrowLeftRight, ChevronRight } from "lucide-react";
import { useId } from "react";
import { useReducedMotion, useT } from "../../state/store";
import { motionTokens } from "../../lib/ui";

const PATH = "M 8 60 C 60 60, 70 30, 100 30 S 140 60, 192 60";

/**
 * The connection between the two phones. Idle: a calm line with a direction arrow.
 * Transferring: light travels along the line from source to target.
 */
export function FlowBridge({
  connected,
  active,
  onSwap,
  swapDisabled,
  height = 120,
}: {
  connected: boolean;
  active: boolean;
  onSwap?: () => void;
  swapDisabled?: boolean;
  height?: number;
}) {
  const t = useT();
  const reduce = useReducedMotion();
  const id = useId().replace(/:/g, "");
  return (
    <div className="relative flex w-[200px] shrink-0 items-center justify-center" style={{ height }}>
      <svg viewBox="0 0 200 90" width={200} height={90} className="absolute top-1/2 left-0 -translate-y-1/2" aria-hidden>
        <defs>
          <linearGradient id={`g${id}`} x1="0" x2="1">
            <stop offset="0" stopColor="var(--accent)" />
            <stop offset="1" stopColor="var(--accent-2)" />
          </linearGradient>
          <filter id={`b${id}`} x="-20%" y="-50%" width="140%" height="200%">
            <feGaussianBlur stdDeviation="3" />
          </filter>
        </defs>
        <path d={PATH} fill="none" stroke="var(--line-strong)" strokeWidth="2" strokeDasharray={connected ? undefined : "3 6"} />
        <motion.path
          d={PATH}
          fill="none"
          stroke={`url(#g${id})`}
          strokeWidth="2.5"
          strokeLinecap="round"
         
          initial={false}
          animate={{ pathLength: connected ? 1 : 0, opacity: connected ? 1 : 0 }}
          transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
        />
        {active && !reduce && (
          <>
            <path
              d={PATH}
              fill="none"
              stroke={`url(#g${id})`}
              strokeWidth="6"
              strokeDasharray="10 22"
              strokeLinecap="round"
              opacity="0.55"
              filter={`url(#b${id})`}
              className="flow-dash"
             
            />
            <path
              d={PATH}
              fill="none"
              stroke="var(--accent-2)"
              strokeWidth="2.5"
              strokeDasharray="6 26"
              strokeLinecap="round"
              className="flow-dash"
             
            />
          </>
        )}
      </svg>

      {active && !reduce && (
        <div className="pointer-events-none absolute top-1/2 left-0 h-[90px] w-[200px] -translate-y-1/2" aria-hidden>
          {[0, 0.6, 1.2].map((delay) => (
            <span
              key={delay}
              className="particle absolute top-0 left-0 -mt-1 -ml-1 h-2 w-2 rounded-full bg-accent-2 shadow-[0_0_12px_4px_var(--accent)]"
              style={{ offsetPath: `path("${PATH}")`, animationDelay: `${delay}s` }}
            />
          ))}
        </div>
      )}

      <AnimatePresence mode="wait">
        {onSwap && !active ? (
          <motion.button
            key="swap"
            type="button"
            onClick={onSwap}
            disabled={swapDisabled}
            aria-label={t("home.swap")}
            title={t("home.swap")}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.8 }}
            whileHover={swapDisabled ? undefined : { rotate: 180 }}
            whileTap={swapDisabled ? undefined : { scale: 0.9 }}
            transition={motionTokens.spring}
            className="glass-strong relative -mt-[30px] flex h-11 w-11 items-center justify-center rounded-full text-accent disabled:text-text-3"
          >
            <ArrowLeftRight size={19} strokeWidth={2} />
          </motion.button>
        ) : (
          <motion.div
            key="arrow"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="relative -mt-[30px] flex h-9 w-9 items-center justify-center rounded-full bg-accent text-white shadow-[var(--glow)]"
            aria-hidden
          >
            <ChevronRight size={20} strokeWidth={2.4} />
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
