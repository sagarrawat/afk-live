import { Download } from 'lucide-react';
import { useState } from 'react';
import { Button, Dialog, Field, Input, Panel, Spinner, useAction } from '@/components/ui';
import { post } from '@/lib/api';
import { useMe } from '@/lib/queries';

/** ON-7: take your data with you, or delete the account (with 7 days to change your mind). */
export function DataSettings() {
  const { data: me } = useMe();
  const [deleting, setDeleting] = useState(false);
  if (!me) return <Spinner />;
  return (
    <div className="space-y-6">
      <Panel title="Download your data">
        <div className="max-w-xl space-y-4">
          <p>A zip with your profile, streams and their settings, schedules, destinations, library file details and invoices. Stream keys are left out to keep your channels safe.</p>
          <a
            href="/api/account/export"
            download
            className="inline-flex h-10 items-center justify-center gap-2 rounded border border-line bg-raised px-4 font-medium text-ink hover:border-muted/60"
          >
            <Download className="h-4 w-4" aria-hidden />
            Download my data
          </a>
        </div>
      </Panel>
      <Panel title="Delete account">
        <div className="max-w-xl space-y-4">
          <p>
            Your streams stop and you're signed out straight away. After 7 days we delete your streams, videos and settings for good. Until then, sign in to keep your account.
          </p>
          <Button variant="danger" onClick={() => setDeleting(true)}>
            Delete my account
          </Button>
        </div>
      </Panel>
      <DeleteAccountDialog email={me.email} open={deleting} onClose={() => setDeleting(false)} />
    </div>
  );
}

export function DeleteAccountDialog({ email, open, onClose, onDeleted }: { email: string; open: boolean; onClose: () => void; onDeleted?: () => void }) {
  const [typed, setTyped] = useState('');
  const { busy, run } = useAction();
  const matches = typed.trim().toLowerCase() === email.toLowerCase();
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Delete your account?"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="delete-account" variant="danger" busy={busy} disabled={!matches}>
            Delete my account
          </Button>
        </>
      }
    >
      <form
        id="delete-account"
        className="space-y-4"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!matches) return;
          if (await run(() => post('/api/account/delete', { email: typed.trim() }))) {
            if (onDeleted) onDeleted();
            else window.location.href = '/login';
          }
        }}
      >
        <ul className="list-disc space-y-1 pl-5 text-sm">
          <li>Live streams stop now and schedules won't start them.</li>
          <li>You're signed out. Signing in during the next 7 days lets you keep the account.</li>
          <li>After 7 days, your streams, videos and settings are deleted. Invoices are kept for tax records.</li>
        </ul>
        <Field label={<>Type <strong className="break-all">{email}</strong> to confirm</>} htmlFor="delete-email">
          <Input id="delete-email" type="email" autoComplete="off" value={typed} onChange={(e) => setTyped(e.target.value)} className="w-full" />
        </Field>
      </form>
    </Dialog>
  );
}
