import { motion, type HTMLMotionProps } from "motion/react";
import type { LucideIcon } from "lucide-react";
import { forwardRef, type ReactNode } from "react";
import { cn, motionTokens } from "../../lib/ui";
import { Spinner } from "./Spinner";

type Variant = "primary" | "secondary" | "ghost" | "danger" | "subtle";
type Size = "sm" | "md" | "lg" | "xl";

const variants: Record<Variant, string> = {
  primary:
    "bg-accent text-white shadow-[0_1px_0_rgba(255,255,255,0.18)_inset,0_8px_22px_-8px_var(--accent)] hover:brightness-110 disabled:shadow-none",
  secondary: "glass text-text hover:bg-surface-2",
  ghost: "text-text-2 hover:text-text hover:bg-line",
  danger: "bg-danger-soft text-danger hover:bg-danger hover:text-white",
  subtle: "bg-accent-soft text-accent hover:brightness-110",
};

const sizes: Record<Size, string> = {
  sm: "h-8 px-3 text-callout gap-1.5 rounded-[10px]",
  md: "h-10 px-4 text-body gap-2 rounded-[var(--radius-control)]",
  lg: "h-12 px-5 text-headline gap-2.5 rounded-[14px]",
  xl: "h-14 px-8 text-headline gap-3 rounded-2xl font-semibold",
};

export interface ButtonProps extends Omit<HTMLMotionProps<"button">, "children"> {
  variant?: Variant;
  size?: Size;
  icon?: LucideIcon;
  iconRight?: LucideIcon;
  loading?: boolean;
  children?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "secondary", size = "md", icon: Icon, iconRight: IconRight, loading, disabled, className, children, ...rest },
  ref,
) {
  const iconSize = size === "sm" ? 15 : size === "md" ? 17 : 19;
  return (
    <motion.button
      ref={ref}
      type="button"
      disabled={disabled || loading}
      whileTap={disabled || loading ? undefined : { scale: 0.97 }}
      transition={motionTokens.micro}
      className={cn(
        "relative inline-flex select-none items-center justify-center font-medium whitespace-nowrap",
        "transition-[background-color,color,filter,box-shadow,opacity] duration-150",
        "disabled:opacity-45",
        variants[variant],
        sizes[size],
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner size={iconSize} /> : Icon ? <Icon size={iconSize} strokeWidth={2} aria-hidden /> : null}
      {children}
      {IconRight && !loading ? <IconRight size={iconSize} strokeWidth={2} aria-hidden /> : null}
    </motion.button>
  );
});

export function IconButton({
  icon: Icon,
  label,
  className,
  size = 36,
  ...rest
}: Omit<HTMLMotionProps<"button">, "children"> & { icon: LucideIcon; label: string; size?: number }) {
  return (
    <motion.button
      type="button"
      aria-label={label}
      title={label}
      whileTap={{ scale: 0.92 }}
      transition={motionTokens.micro}
      style={{ width: size, height: size }}
      className={cn(
        "inline-flex items-center justify-center rounded-full text-text-2 transition-colors duration-150 hover:bg-line hover:text-text disabled:opacity-40",
        className,
      )}
      {...rest}
    >
      <Icon size={Math.round(size * 0.47)} strokeWidth={2} aria-hidden />
    </motion.button>
  );
}
