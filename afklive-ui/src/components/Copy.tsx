import { Check, Copy as CopyIcon, Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';

/** A small "Copy" button for text the creator pastes somewhere else; says "Copied" for a moment. */
export function CopyButton({ text, label, className }: { text: string; label: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setFailed(false);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setFailed(true);
    }
  };
  return (
    <button
      type="button"
      aria-label={`Copy ${label}`}
      disabled={!text}
      onClick={copy}
      className={`inline-flex h-8 shrink-0 items-center gap-1.5 rounded px-2.5 text-sm text-muted hover:bg-raised hover:text-ink disabled:opacity-40 ${className ?? ''}`}
    >
      {copied ? <Check className="h-4 w-4 text-ok" aria-hidden /> : <CopyIcon className="h-4 w-4" aria-hidden />}
      <span aria-live="polite">{copied ? 'Copied' : failed ? 'Select and copy' : 'Copy'}</span>
    </button>
  );
}

/** A value with a copy button; secrets stay hidden until asked. */
export function CopyField({ label, value, secret }: { label: string; value: string; secret?: boolean }) {
  const [shown, setShown] = useState(!secret);
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setShown(true);
    }
  };
  return (
    <div className="space-y-1.5">
      <span className="block text-sm font-medium">{label}</span>
      <div className="flex items-center gap-1 rounded border border-line bg-bg pl-3">
        <code className="min-w-0 flex-1 truncate py-2 text-sm">{shown ? value : '•'.repeat(Math.min(24, value.length))}</code>
        {secret && (
          <button type="button" className="p-2 text-muted hover:text-ink" aria-label={shown ? `Hide ${label}` : `Show ${label}`} onClick={() => setShown((s) => !s)}>
            {shown ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        )}
        <button type="button" className="p-2 text-muted hover:text-ink" aria-label={`Copy ${label}`} onClick={copy}>
          {copied ? <Check className="h-4 w-4 text-ok" /> : <CopyIcon className="h-4 w-4" />}
        </button>
      </div>
    </div>
  );
}
