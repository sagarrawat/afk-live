import { useState } from 'react';
import { Button, Dialog, Field, Textarea, useAction } from '@/components/ui';

/** An admin action that asks why: the reason goes in the audit log. */
export function ReasonDialog({
  title,
  action,
  onClose,
  onSubmit,
  danger,
  blocked,
  wide,
  success = 'Done.',
  children,
}: {
  title: string;
  action: string;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<unknown>;
  danger?: boolean;
  /** Keeps the action disabled, e.g. while the form above the reason isn't valid. */
  blocked?: boolean;
  wide?: boolean;
  /** Toast after it worked (none when empty). */
  success?: string;
  children?: React.ReactNode;
}) {
  const [reason, setReason] = useState('');
  const { busy, run } = useAction();
  return (
    <Dialog
      open
      onClose={onClose}
      title={title}
      wide={wide}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} busy={busy} disabled={blocked || reason.trim().length < 3} onClick={async () => (await run(() => onSubmit(reason.trim()), success || undefined)) && onClose()}>
            {action}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {children}
        <Field label="Reason" htmlFor="reason" hint="Goes in the audit log.">
          <Textarea id="reason" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
        </Field>
      </div>
    </Dialog>
  );
}
