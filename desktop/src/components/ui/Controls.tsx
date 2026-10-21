import { motion } from "motion/react";
import { useId, type ReactNode } from "react";
import { cn, motionTokens } from "../../lib/ui";

export function Card({ className, children, strong }: { className?: string; children: ReactNode; strong?: boolean }) {
  return <div className={cn(strong ? "glass-strong" : "glass", "rounded-[var(--radius-card)]", className)}>{children}</div>;
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative h-[26px] w-[44px] shrink-0 rounded-full transition-colors duration-200 disabled:opacity-40",
        checked ? "bg-accent" : "bg-line-strong",
      )}
    >
      <motion.span
        className="absolute top-[3px] left-[3px] h-5 w-5 rounded-full bg-white shadow-[0_2px_6px_rgba(0,0,0,0.25)]"
        animate={{ x: checked ? 18 : 0 }}
        transition={motionTokens.spring}
      />
    </button>
  );
}

export function Checkbox({
  checked,
  indeterminate,
  onChange,
  label,
  disabled,
  size = 22,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange?: (v: boolean) => void;
  label: string;
  disabled?: boolean;
  size?: number;
}) {
  const on = checked || indeterminate;
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={indeterminate ? "mixed" : checked}
      aria-label={label}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation();
        onChange?.(!checked);
      }}
      style={{ width: size, height: size }}
      className={cn(
        "relative inline-flex shrink-0 items-center justify-center rounded-[7px] border transition-colors duration-150 disabled:opacity-35",
        on ? "border-accent bg-accent" : "border-line-strong bg-transparent hover:border-text-3",
      )}
    >
      <svg viewBox="0 0 24 24" width={size - 6} height={size - 6} aria-hidden>
        {indeterminate ? (
          <motion.path d="M6 12h12" stroke="white" strokeWidth={3} strokeLinecap="round" initial={false} animate={{ pathLength: 1 }} />
        ) : (
          <motion.path
            d="M5 12.5l4.5 4.5L19 7.5"
            fill="none"
            stroke="white"
            strokeWidth={3}
            strokeLinecap="round"
            strokeLinejoin="round"
            initial={false}
            animate={{ pathLength: checked ? 1 : 0, opacity: checked ? 1 : 0 }}
            transition={motionTokens.small}
          />
        )}
      </svg>
    </button>
  );
}

type Tone = "neutral" | "accent" | "success" | "warning" | "danger";
const tones: Record<Tone, string> = {
  neutral: "bg-line text-text-2",
  accent: "bg-accent-soft text-accent",
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  danger: "bg-danger-soft text-danger",
};

export function Badge({ tone = "neutral", children, dot, className }: { tone?: Tone; children: ReactNode; dot?: boolean; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full px-2.5 py-[3px] text-caption font-medium whitespace-nowrap", tones[tone], className)}>
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("skeleton", className)} aria-hidden />;
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: Array<{ value: T; label: string }>;
  onChange: (v: T) => void;
  label: string;
}) {
  const id = useId();
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-[11px] bg-line p-[3px]">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              "relative h-8 rounded-[8px] px-3.5 text-callout font-medium transition-colors duration-150",
              active ? "text-text" : "text-text-2 hover:text-text",
            )}
          >
            {active && (
              <motion.span
                layoutId={`seg-${id}`}
                className="absolute inset-0 rounded-[8px] bg-surface shadow-[var(--shadow-sm)] dark:bg-surface-2"
                transition={motionTokens.spring}
              />
            )}
            <span className="relative">{o.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/** Progress uses scaleX (transform-only) so it never triggers layout. */
export function ProgressBar({ value, className, tone = "accent" }: { value: number; className?: string; tone?: "accent" | "success" | "warning" }) {
  const color =
    tone === "success" ? "bg-success" : tone === "warning" ? "bg-warning" : "bg-[linear-gradient(90deg,var(--accent),var(--accent-2))]";
  return (
    <div
      className={cn("relative h-1.5 overflow-hidden rounded-full bg-line", className)}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(value)}
    >
      <motion.div
        className={cn("absolute inset-0 origin-left rounded-full", color)}
        initial={false}
        animate={{ scaleX: Math.max(0, Math.min(1, value / 100)) }}
        transition={motionTokens.springSoft}
      />
    </div>
  );
}

export function SectionTitle({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <div className="mb-4 flex items-end justify-between gap-4">
      <div>
        <h2 className="font-display text-headline font-semibold">{title}</h2>
        {subtitle && <p className="mt-0.5 text-callout text-text-2">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export function EmptyState({ icon, title, hint, action }: { icon: ReactNode; title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
      <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-[20px] bg-accent-soft text-accent">{icon}</div>
      <div className="font-display text-headline font-semibold">{title}</div>
      {hint && <p className="mt-1 max-w-sm text-callout text-text-2">{hint}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
