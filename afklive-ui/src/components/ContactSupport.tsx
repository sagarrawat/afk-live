import { Paperclip, X } from 'lucide-react';
import { useRef, useState } from 'react';
import { api } from '@/lib/api';
import { Button, Dialog, Field, IconButton, Select, Textarea, useAction } from './ui';

/** What the server files tickets under (SupportController.CATEGORIES). */
export const SUPPORT_TOPICS: [string, string][] = [
  ['Streaming', 'My stream'],
  ['Destinations', 'YouTube or another platform'],
  ['Uploads', 'Uploading videos'],
  ['Billing', 'Plans and payments'],
  ['Account', 'Signing in or my account'],
  ['Other', 'Something else'],
];

const MAX_BYTES = 25 * 1024 * 1024;
const ACCEPT = '.png,.jpg,.jpeg,.pdf,.mp4,image/png,image/jpeg,application/pdf,video/mp4';

/** Why a file can't be attached, or null when it can. Mirrors the server's checks. */
export function attachmentProblem(file: File): string | null {
  const ext = file.name.toLowerCase().split('.').pop() ?? '';
  if (!['png', 'jpg', 'jpeg', 'pdf', 'mp4'].includes(ext)) return 'Attach a screenshot (PNG or JPG), a PDF or an MP4 recording.';
  if (file.size > MAX_BYTES) return 'The attachment is too big. Keep it under 25 MB.';
  return null;
}

/**
 * UX audit #23: "Contact support" files a ticket (POST /api/support/ticket) that admins see under
 * Support, with an optional screenshot, instead of opening the mail app.
 */
export function ContactSupportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [topic, setTopic] = useState('Streaming');
  const [message, setMessage] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const { busy, run } = useAction();

  const reset = () => {
    setTopic('Streaming');
    setMessage('');
    setFile(null);
    setFileError(null);
    setSent(false);
  };
  const close = () => {
    reset();
    onClose();
  };

  if (sent) {
    return (
      <Dialog
        open={open}
        onClose={close}
        title="Message sent"
        footer={
          <Button variant="primary" onClick={close}>
            Done
          </Button>
        }
      >
        <p>We got it and usually reply within a day, by email. Your streams keep running in the meantime.</p>
      </Dialog>
    );
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Contact support"
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button type="submit" form="support-form" variant="primary" busy={busy} disabled={!message.trim() || !!fileError}>
            Send
          </Button>
        </>
      }
    >
      <form
        id="support-form"
        className="space-y-4"
        onSubmit={async (e) => {
          e.preventDefault();
          const body = new FormData();
          body.append('category', topic);
          body.append('message', message.trim());
          if (file) body.append('file', file);
          if (await run(() => api('/api/support/ticket', { method: 'POST', body }))) setSent(true);
        }}
      >
        <Field label="What's it about?" htmlFor="sup-topic">
          <Select id="sup-topic" value={topic} onChange={(e) => setTopic(e.target.value)}>
            {SUPPORT_TOPICS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="What happened?" htmlFor="sup-message" hint="Which stream, what you expected, and what you saw instead.">
          <Textarea id="sup-message" value={message} maxLength={5000} rows={5} onChange={(e) => setMessage(e.target.value)} required />
        </Field>
        <div>
          <input
            ref={input}
            type="file"
            accept={ACCEPT}
            className="hidden"
            aria-label="Attach a screenshot"
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null;
              e.target.value = '';
              setFile(f);
              setFileError(f ? attachmentProblem(f) : null);
            }}
          />
          {file ? (
            <div className="flex items-center gap-2 text-sm">
              <Paperclip className="h-4 w-4 shrink-0 text-muted" aria-hidden />
              <span className="min-w-0 flex-1 truncate">{file.name}</span>
              <IconButton
                label={`Remove ${file.name}`}
                onClick={() => {
                  setFile(null);
                  setFileError(null);
                }}
              >
                <X className="h-4 w-4" />
              </IconButton>
            </div>
          ) : (
            <Button type="button" size="sm" variant="ghost" icon={<Paperclip className="h-4 w-4" />} onClick={() => input.current?.click()}>
              Attach a screenshot (optional)
            </Button>
          )}
          {fileError && <p className="mt-1 text-xs text-bad">{fileError}</p>}
        </div>
      </form>
    </Dialog>
  );
}
