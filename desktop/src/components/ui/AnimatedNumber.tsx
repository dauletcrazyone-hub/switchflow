import { useMotionValue, useSpring, useTransform, motion } from "motion/react";
import { useEffect } from "react";

/** A number that glides to its new value (spring on a motion value, no re-renders per frame). */
export function AnimatedNumber({
  value,
  format,
  className,
  instant,
}: {
  value: number;
  format: (n: number) => string;
  className?: string;
  instant?: boolean;
}) {
  const mv = useMotionValue(value);
  const spring = useSpring(mv, { stiffness: 120, damping: 24, mass: 0.6 });
  const text = useTransform(instant ? mv : spring, (v) => format(v));
  useEffect(() => {
    mv.set(value);
  }, [mv, value]);
  return <motion.span className={className}>{text}</motion.span>;
}
