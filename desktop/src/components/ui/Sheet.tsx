import { AnimatePresence, motion } from "motion/react";
import { X } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";
import { cn, motionTokens } from "../../lib/ui";
import { useT } from "../../state/store";
import { Button, IconButton } from "./Button";

/** Modal sheet with a frosted panel. Esc and the scrim close it unless `locked`. */
export function Sheet({
  open,
  onClose,
  children,
  width = 520,
  locked,
  label,
}: {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  width?: number;
  locked?: boolean;
  label: string;
}) {
  const t = useT();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !locked) onClose();
    };
    window.addEventListener("keydown", onKey);
    const prev = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    return () => {
      window.removeEventListener("keydown", onKey);
      prev?.focus?.();
    };
  }, [open, locked, onClose]);

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-50 flex items-center justify-center p-6"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={motionTokens.small}
        >
          <div className="absolute inset-0 bg-[var(--scrim)] backdrop-blur-[3px]" onClick={() => !locked && onClose()} />
          <motion.div
            ref={panel}
            role="dialog"
            aria-modal="true"
            aria-label={label}
            tabIndex={-1}
            className="glass-strong relative max-h-[calc(100vh-80px)] w-full overflow-hidden rounded-[24px] outline-none"
            style={{ maxWidth: width }}
            initial={{ opacity: 0, scale: 0.96, y: 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97, y: 8 }}
            transition={motionTokens.sheet}
          >
            {!locked && (
              <IconButton icon={X} label={t("common.close")} onClick={onClose} className="absolute top-3 right-3 z-10" size={32} />
            )}
            <div className="scroll-area max-h-[calc(100vh-80px)]">{children}</div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel,
  danger,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  return (
    <Sheet open={open} onClose={onCancel} width={420} label={title}>
      <div className="p-6">
        <h3 className="font-display text-headline font-semibold">{title}</h3>
        <p className="mt-2 text-body text-text-2">{body}</p>
        <div className={cn("mt-6 flex justify-end gap-2")}>
          <Button variant="ghost" onClick={onCancel}>
            {cancelLabel ?? t("common.cancel")}
          </Button>
          <Button variant={danger ? "danger" : "primary"} onClick={onConfirm} autoFocus>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </Sheet>
  );
}
