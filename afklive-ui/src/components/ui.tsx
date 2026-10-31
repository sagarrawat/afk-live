import clsx from 'clsx';
import { Loader2, X } from 'lucide-react';
import {
  createContext,
  forwardRef,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { useLocation } from 'react-router-dom';

// ------------------------------------------------------------------ buttons

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'live';

const variants: Record<Variant, string> = {
  primary: 'bg-accent text-accent-ink hover:bg-accent/90',
  secondary: 'bg-raised text-ink border border-line hover:border-muted/60',
  ghost: 'text-muted hover:text-ink hover:bg-raised',
  danger: 'bg-bad/10 text-bad border border-bad/40 hover:bg-bad/20',
  live: 'bg-live text-white hover:bg-live/90',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: 'sm' | 'md';
  busy?: boolean;
  icon?: ReactNode;
}

/** A button's look, for links that act as buttons (e.g. "Open YouTube Studio"). */
export function buttonClass(variant: Variant = 'secondary', size: 'sm' | 'md' = 'md', className?: string) {
  return clsx(
    'inline-flex items-center justify-center gap-2 rounded font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none whitespace-nowrap',
    size === 'sm' ? 'h-8 px-3 text-sm' : 'h-10 px-4',
    variants[variant],
    className,
  );
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', busy, icon, className, children, disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      className={buttonClass(variant, size, className)}
      disabled={disabled || busy}
      {...rest}
    >
      {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  );
});

export function IconButton({ label, className, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button
      aria-label={label}
      title={label}
      className={clsx('inline-flex h-9 w-9 items-center justify-center rounded text-muted hover:bg-raised hover:text-ink disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent', className)}
      {...rest}
    >
      {children}
    </button>
  );
}

// ------------------------------------------------------------------ surfaces

export function Panel({ title, action, children, className, pad = true }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string; pad?: boolean }) {
  return (
    <section className={clsx('rounded-md border border-line bg-panel', className)}>
      {(title || action) && (
        // Wraps: on a phone, wide actions (the Plans toggles) go under the title instead of squeezing it.
        <header className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-line px-4 py-3">
          <h2 className="text-md font-semibold">{title}</h2>
          {action}
        </header>
      )}
      <div className={pad ? 'p-4' : undefined}>{children}</div>
    </section>
  );
}

export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
        {description && <p className="mt-1 max-w-prose text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Empty({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-md border border-dashed border-line px-6 py-12 text-center">
      {icon && <div className="mb-3 text-muted">{icon}</div>}
      <p className="text-md font-semibold">{title}</p>
      {children && <p className="mt-1 max-w-md text-muted">{children}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-10 text-muted" role="status">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      <span>{label}…</span>
    </div>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <p role="alert" className="rounded border border-bad/40 bg-bad/10 px-3 py-2 text-sm text-bad">
      {msg}
    </p>
  );
}

export function Badge({ tone = 'neutral', children, className }: { tone?: 'neutral' | 'ok' | 'warn' | 'bad' | 'accent' | 'live'; children: ReactNode; className?: string }) {
  const tones = {
    neutral: 'bg-raised text-muted border-line',
    ok: 'bg-ok/10 text-ok border-ok/30',
    warn: 'bg-warn/10 text-warn border-warn/30',
    bad: 'bg-bad/10 text-bad border-bad/30',
    accent: 'bg-accent/10 text-accent border-accent/30',
    live: 'bg-live text-white border-live',
  };
  return <span className={clsx('inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-xs font-medium', tones[tone], className)}>{children}</span>;
}

// ------------------------------------------------------------------ form

export function Field({ label, hint, error, children, htmlFor }: { label: ReactNode; hint?: ReactNode; error?: string | null; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium">
        {label}
      </label>
      {children}
      {hint && !error && <p className="text-xs text-muted">{hint}</p>}
      {error && <p className="text-xs text-bad">{error}</p>}
    </div>
  );
}

const control = 'rounded border border-line bg-bg px-3 text-base text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...rest }, ref) {
  return <input ref={ref} className={clsx(control, 'h-10', !/\bw-/.test(className ?? '') && 'w-full', className)} {...rest} />;
});

export function Textarea({ className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={clsx(control, 'min-h-[80px] w-full py-2', className)} {...rest} />;
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={clsx(control, 'h-10 pr-8', !/\bw-/.test(className ?? '') && 'w-full', className)} {...rest}>
      {children}
    </select>
  );
}

export function Toggle({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={clsx('relative inline-flex h-6 w-10 shrink-0 items-center rounded-full border transition-colors disabled:opacity-50', checked ? 'border-accent bg-accent' : 'border-line bg-raised')}
    >
      <span className={clsx('inline-block h-4 w-4 rounded-full bg-white shadow transition-transform', checked ? 'translate-x-5' : 'translate-x-1')} />
    </button>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string; disabled?: boolean; title?: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap rounded border border-line bg-bg p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          disabled={o.disabled}
          title={o.title}
          onClick={() => onChange(o.value)}
          // grow: when a phone is too narrow for one row, each wrapped row fills the width evenly instead of
          // leaving one option alone at the start of the last row.
          className={clsx('h-8 grow rounded-sm px-3 text-sm disabled:opacity-40', value === o.value ? 'bg-raised font-medium text-ink' : 'text-muted hover:text-ink')}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ section tabs

/**
 * A row of section links that scrolls sideways when it doesn't fit (a phone). The current section (the
 * link with aria-current) is scrolled into view, so it's never cut off at the edge, and a soft fade marks
 * the side that has more links, so a half-visible label reads as "scroll for more" rather than a mistake.
 */
export function ScrollTabs({ label, className, navClassName, children }: { label: string; className?: string; navClassName?: string; children: ReactNode }) {
  const ref = useRef<HTMLElement>(null);
  const [more, setMore] = useState({ left: false, right: false });
  const { pathname } = useLocation();
  const measure = useCallback(() => {
    const n = ref.current;
    if (!n) return;
    const left = n.scrollLeft > 1;
    const right = n.scrollLeft + n.clientWidth < n.scrollWidth - 1;
    setMore((m) => (m.left === left && m.right === right ? m : { left, right }));
  }, []);
  useEffect(() => {
    const n = ref.current;
    const current = n?.querySelector<HTMLElement>('[aria-current="page"]');
    if (n && current) {
      const nav = n.getBoundingClientRect();
      const tab = current.getBoundingClientRect();
      const pad = 32; // clear of the fade
      if (tab.left < nav.left) n.scrollLeft = Math.max(0, n.scrollLeft - (nav.left + pad - tab.left));
      else if (tab.right > nav.right) n.scrollLeft += tab.right - (nav.right - pad);
    }
    measure();
  }, [pathname, measure]);
  useEffect(() => {
    const n = ref.current;
    if (!n || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(n);
    return () => ro.disconnect();
  }, [measure]);
  return (
    <div className={clsx('relative', className)}>
      <nav ref={ref} aria-label={label} onScroll={measure} className={clsx('overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden', navClassName)}>
        {children}
      </nav>
      <span aria-hidden data-fade="left" className={clsx('pointer-events-none absolute inset-y-0 left-0 w-8 bg-gradient-to-r from-bg to-transparent transition-opacity', more.left ? 'opacity-100' : 'opacity-0')} />
      <span aria-hidden data-fade="right" className={clsx('pointer-events-none absolute inset-y-0 right-0 w-8 bg-gradient-to-l from-bg to-transparent transition-opacity', more.right ? 'opacity-100' : 'opacity-0')} />
    </div>
  );
}

// ------------------------------------------------------------------ dialog

export function Dialog({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal?.();
    if (!open && d.open) d.close?.();
  }, [open]);
  if (!open) return null;
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      className={clsx(
        'm-auto w-[calc(100%-2rem)] max-h-[calc(100dvh-2rem)] overflow-hidden rounded-lg border border-line bg-panel p-0 text-ink backdrop:bg-black/60',
        wide ? 'max-w-3xl' : 'max-w-lg',
      )}
      open={typeof HTMLDialogElement === 'undefined' || !HTMLDialogElement.prototype.showModal ? true : undefined}
    >
      <div className="flex max-h-[calc(100dvh-2rem)] flex-col">
        <header className="flex items-center justify-between border-b border-line px-5 py-3">
          <h2 id={titleId} className="text-md font-semibold">
            {title}
          </h2>
          <IconButton label="Close" onClick={onClose}>
            <X className="h-4 w-4" />
          </IconButton>
        </header>
        <div className="overflow-y-auto px-5 py-4">{children}</div>
        {footer && <footer className="flex flex-wrap justify-end gap-2 border-t border-line px-5 py-3">{footer}</footer>}
      </div>
    </dialog>
  );
}

// ------------------------------------------------------------------ toasts

interface Toast {
  id: number;
  text: string;
  tone: 'ok' | 'bad' | 'neutral';
}

const ToastContext = createContext<(text: string, tone?: Toast['tone']) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((text: string, tone: Toast['tone'] = 'neutral') => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, text, tone }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 5000);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-20 z-50 flex flex-col items-center gap-2 px-4 md:bottom-6 md:items-end">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={clsx(
              'pointer-events-auto max-w-sm rounded border px-4 py-3 text-sm shadow-lg',
              t.tone === 'ok' && 'border-ok/40 bg-panel text-ink',
              t.tone === 'bad' && 'border-bad/50 bg-panel text-ink',
              t.tone === 'neutral' && 'border-line bg-panel text-ink',
            )}
          >
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

/** Runs an action, toasting its error message; returns whether it worked. */
export function useAction() {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const run = useCallback(
    async (fn: () => Promise<unknown>, success?: string) => {
      setBusy(true);
      try {
        await fn();
        if (success) toast(success, 'ok');
        return true;
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), 'bad');
        return false;
      } finally {
        setBusy(false);
      }
    },
    [toast],
  );
  return { busy, run };
}
