import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { CopyField } from '@/components/Copy';
import { Badge, Button, Dialog, Field, Input, Panel, Spinner, useAction } from '@/components/ui';
import { get, post } from '@/lib/api';

export interface SecurityStatus {
  twoFactorEnabled: boolean;
  recoveryCodesLeft: number;
  twoFactorSince: string | null;
}

interface Setup {
  secret: string;
  uri: string;
}

const securityKey = ['account', 'security'] as const;

/** Groups the key in fours so it's easier to type into an app. */
const grouped = (secret: string) => secret.replace(/(.{4})/g, '$1 ').trim();

/** ON-6: two-step sign-in with an authenticator app, and recovery codes. */
export function SecuritySettings() {
  const { data } = useQuery({ queryKey: securityKey, queryFn: () => get<SecurityStatus>('/api/account/security') });
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: securityKey });
  const [setup, setSetup] = useState<Setup | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [asking, setAsking] = useState<'off' | 'codes' | null>(null);
  const { busy, run } = useAction();

  if (!data) return <Spinner />;

  if (codes) return <RecoveryCodes codes={codes} onDone={() => setCodes(null)} />;

  if (setup)
    return (
      <SetupPanel
        setup={setup}
        onCancel={() => setSetup(null)}
        onEnabled={(c) => {
          setSetup(null);
          setCodes(c);
          refresh();
        }}
      />
    );

  return (
    <Panel title="Two-step sign-in" action={data.twoFactorEnabled ? <Badge tone="ok">On</Badge> : <Badge>Off</Badge>}>
      <div className="max-w-xl space-y-4">
        {data.twoFactorEnabled ? (
          <>
            <p>
              Signing in needs your password and a code from your authenticator app, so nobody can take over your channels with your password alone.
            </p>
            <p className="text-sm text-muted">
              {data.recoveryCodesLeft} of 10 recovery codes left. Use one if you lose your phone.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setAsking('codes')}>New recovery codes</Button>
              <Button variant="danger" onClick={() => setAsking('off')}>
                Turn off
              </Button>
            </div>
          </>
        ) : (
          <>
            <p>Protect your channels: after your password, sign-in also asks for a code from an authenticator app on your phone (Google Authenticator, Microsoft Authenticator, 1Password and others).</p>
            <Button
              variant="primary"
              icon={<ShieldCheck className="h-4 w-4" />}
              busy={busy}
              onClick={() => run(async () => setSetup(await post<Setup>('/api/account/2fa/setup')))}
            >
              Turn on two-step sign-in
            </Button>
          </>
        )}
      </div>
      <CodeDialog
        open={asking === 'off'}
        title="Turn off two-step sign-in"
        action="Turn off"
        danger
        description="Enter a code from your authenticator app (or a recovery code). Sign-in will only need your password."
        onClose={() => setAsking(null)}
        onSubmit={async (code) => {
          await post('/api/account/2fa/disable', { code });
          setAsking(null);
          refresh();
        }}
        success="Two-step sign-in is off."
      />
      <CodeDialog
        open={asking === 'codes'}
        title="New recovery codes"
        action="Make new codes"
        description="Your old recovery codes stop working. Enter a code from your authenticator app."
        onClose={() => setAsking(null)}
        onSubmit={async (code) => {
          const r = await post<{ recoveryCodes: string[] }>('/api/account/2fa/recovery-codes', { code });
          setAsking(null);
          setCodes(r.recoveryCodes);
          refresh();
        }}
      />
    </Panel>
  );
}

function SetupPanel({ setup, onCancel, onEnabled }: { setup: Setup; onCancel: () => void; onEnabled: (codes: string[]) => void }) {
  const [code, setCode] = useState('');
  const { busy, run } = useAction();
  return (
    <Panel title="Turn on two-step sign-in">
      <form
        className="max-w-xl space-y-5"
        onSubmit={async (e) => {
          e.preventDefault();
          let codes: string[] = [];
          if (await run(async () => (codes = (await post<{ recoveryCodes: string[] }>('/api/account/2fa/enable', { code })).recoveryCodes))) onEnabled(codes);
        }}
      >
        <ol className="list-decimal space-y-2 pl-5">
          <li>Open your authenticator app and add an account.</li>
          <li>Scan this code with it. On this phone? Tap the link below or type the key in.</li>
        </ol>
        <img src="/api/account/2fa/qr.png" alt="QR code to add AFK Live to your authenticator app" width={200} height={200} className="h-[200px] w-[200px] rounded bg-white p-2" />
        <a href={setup.uri} className="block text-sm text-accent underline-offset-2 hover:underline">
          Open in my authenticator app
        </a>
        <CopyField label="Key to type in" value={grouped(setup.secret)} />
        <Field label="Enter the 6-digit code the app shows" htmlFor="tf-code">
          <Input
            id="tf-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            placeholder="123456"
            className="w-40 tracking-[0.2em]"
            required
          />
        </Field>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="primary" busy={busy} disabled={code.replace(/\D/g, '').length !== 6}>
            Confirm and turn on
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </form>
    </Panel>
  );
}

/** Shown once, right after they're made: only hashes are kept, so they can't be shown again. */
export function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const text = codes.join('\n');
  const download = () => {
    const url = URL.createObjectURL(new Blob([`AFK Live recovery codes\nEach code works once.\n\n${text}\n`], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'afk-live-recovery-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <Panel title="Save your recovery codes">
      <div className="max-w-xl space-y-4">
        <p>
          If you lose your phone, sign in with one of these instead of a code. Each works once. <strong>We can't show them again</strong>, so save them somewhere safe now.
        </p>
        <ul aria-label="Recovery codes" className="grid grid-cols-2 gap-2 rounded border border-line bg-bg p-3 font-mono text-sm sm:grid-cols-2">
          {codes.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-2">
          <Button onClick={download}>Download</Button>
          <Button onClick={() => navigator.clipboard?.writeText(text).catch(() => {})}>Copy</Button>
          <Button variant="primary" onClick={onDone}>
            I've saved them
          </Button>
        </div>
      </div>
    </Panel>
  );
}

function CodeDialog({
  open,
  title,
  action,
  description,
  danger,
  success,
  onClose,
  onSubmit,
}: {
  open: boolean;
  title: string;
  action: string;
  description: string;
  danger?: boolean;
  success?: string;
  onClose: () => void;
  onSubmit: (code: string) => Promise<void>;
}) {
  const [code, setCode] = useState('');
  const { busy, run } = useAction();
  const formId = `code-${action.replace(/\s/g, '-')}`;
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form={formId} variant={danger ? 'danger' : 'primary'} busy={busy} disabled={!code.trim()}>
            {action}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        className="space-y-4"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await run(() => onSubmit(code), success)) setCode('');
        }}
      >
        <p className="text-sm text-muted">{description}</p>
        <Field label="Code" htmlFor={`${formId}-input`}>
          <Input id={`${formId}-input`} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" maxLength={16} className="w-48" />
        </Field>
      </form>
    </Dialog>
  );
}
