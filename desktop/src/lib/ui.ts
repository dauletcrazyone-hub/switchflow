import type { Transition } from "motion/react";

export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}

const easeOut = [0.22, 1, 0.36, 1] as const;

/** Motion tokens: small actions 120–220 ms, transitions 280–420 ms, springs for physical moves. */
export const motionTokens = {
  micro: { duration: 0.16, ease: easeOut } satisfies Transition,
  small: { duration: 0.22, ease: easeOut } satisfies Transition,
  screen: { duration: 0.34, ease: easeOut } satisfies Transition,
  sheet: { duration: 0.3, ease: easeOut } satisfies Transition,
  spring: { type: "spring", stiffness: 420, damping: 34, mass: 0.7 } satisfies Transition,
  springSoft: { type: "spring", stiffness: 240, damping: 30 } satisfies Transition,
};
