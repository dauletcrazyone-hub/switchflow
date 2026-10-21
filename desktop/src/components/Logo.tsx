import { useId } from "react";

/** The SwitchFlow mark: two phones joined by a flowing line of light. */
export function Logo({ size = 24, glow }: { size?: number; glow?: boolean }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg width={size} height={size} viewBox="0 0 1024 1024" aria-hidden style={glow ? { filter: "drop-shadow(0 8px 24px rgba(71,139,255,0.45))" } : undefined}>
      <defs>
        <linearGradient id={`bg${id}`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#1B2538" />
          <stop offset="1" stopColor="#070A11" />
        </linearGradient>
        <linearGradient id={`fl${id}`} x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#478BFF" />
          <stop offset="1" stopColor="#75C8FF" />
        </linearGradient>
      </defs>
      <rect x="40" y="40" width="944" height="944" rx="220" fill={`url(#bg${id})`} />
      <rect x="206" y="250" width="232" height="420" rx="56" fill="#111722" stroke="#9CAAC0" strokeOpacity="0.55" strokeWidth="22" />
      <rect x="586" y="354" width="232" height="420" rx="56" fill="#111722" stroke="#9CAAC0" strokeOpacity="0.55" strokeWidth="22" />
      <path d="M322 470 C 420 760, 604 250, 702 560" fill="none" stroke={`url(#fl${id})`} strokeWidth="58" strokeLinecap="round" />
      <circle cx="322" cy="470" r="44" fill="#478BFF" />
      <circle cx="702" cy="560" r="44" fill="#75C8FF" />
    </svg>
  );
}
