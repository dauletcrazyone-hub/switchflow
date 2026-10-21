import { motion } from "motion/react";
import { useId } from "react";
import type { Platform } from "../../backend/types";

/** Original phone illustration: a generic slab with an island (iOS) or punch-hole (Android). */
export function PhoneArt({
  platform,
  width = 64,
  active,
  ghost,
}: {
  platform: Platform | null;
  width?: number;
  active?: boolean;
  ghost?: boolean;
}) {
  const id = useId().replace(/:/g, "");
  const h = width * 2.05;
  return (
    <svg width={width} height={h} viewBox="0 0 100 205" aria-hidden>
      <defs>
        <linearGradient id={`scr${id}`} x1="0" y1="0" x2="0.4" y2="1">
          <stop offset="0" stopColor="var(--accent)" stopOpacity={active ? 0.55 : 0.22} />
          <stop offset="1" stopColor="var(--accent-2)" stopOpacity={active ? 0.3 : 0.06} />
        </linearGradient>
      </defs>
      <rect
        x="3"
        y="3"
        width="94"
        height="199"
        rx="17"
        fill={ghost ? "none" : "var(--bg-2)"}
        stroke={ghost ? "var(--text-3)" : "var(--line-strong)"}
        strokeWidth={ghost ? 2.5 : 3}
        strokeDasharray={ghost ? "6 6" : undefined}
      />
      {!ghost && (
        <>
          <rect x="9" y="9" width="82" height="187" rx="12" fill={`url(#scr${id})`} />
          {platform === "ios" ? (
            <rect x="36" y="15" width="28" height="8" rx="4" fill="var(--bg)" />
          ) : (
            <circle cx="50" cy="19" r="3.6" fill="var(--bg)" />
          )}
          {active && (
            <motion.rect
              x="9"
              y="9"
              width="82"
              height="187"
              rx="12"
              fill="var(--accent-2)"
              initial={{ opacity: 0 }}
              animate={{ opacity: [0, 0.16, 0] }}
              transition={{ duration: 2.2, repeat: Infinity, ease: "easeInOut" }}
            />
          )}
          <rect x="38" y="186" width="24" height="3" rx="1.5" fill="var(--text)" opacity="0.35" />
        </>
      )}
    </svg>
  );
}
