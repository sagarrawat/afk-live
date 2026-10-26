import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { CopyField } from '@/components/Copy';
import { Badge, Button, Dialog, Field, Input, Panel, Spinner, useAction, useToast } from '@/components/ui';
import { del, get, post } from '@/lib/api';
import { useMe } from '@/lib/queries';

export interface SecurityStatus {
  twoFactorEnabled: boolean;
  recoveryCodesLeft: number;
  twoFactorSince: string | null;
  /** False for accounts that only sign in with Google. */
  hasPassword?: boolean;
  /** Whether a confirmation email can be sent (email is set up on the server). */
  emailChangeAvailable?: boolean;
  /** A new address waiting for its confirmation link. */
  pendingEmail?: string | null;
}

interface Setup {
  secret: string;
  uri: string;
}

const securityKey = ['account', 'security'] as const;

/** Groups the key in fours so it's easier to type into an app. */
const grouped = (secret: string) => secret.replace(/(.{4})/g, '$1 ').trim();

/** ON-6: password, two-step sign-in, email and other devices. */
export function SecuritySettings() {
  const { data } = useQuery({ queryKey: securityKey, queryFn: () => get<SecurityStatus>('/api/account/security') });
  if (!data) return <Spinner />;
  return (
    <div className="space-y-6">
      <PasswordPanel status={data} />
      <TwoStepPanel />
      <EmailPanel status={data} />
      <DevicesPanel />
    </div>
  );
}

/** ON-6 / UX audit #14: needs the current password; other devices are signed out. */
function PasswordPanel({ status }: { status: SecurityStatus }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const { busy, run } = useAction();
  if (status.hasPassword === false) {
    return (
      <Panel title="Password">
        <p className="max-w-xl text-muted">You sign in with Google, so there&apos;s no AFK Live password to change. Your Google account&apos;s own security protects your sign-in.</p>
      </Panel>
    );
  }
  const mismatch = again.length > 0 && again !== next;
  return (
    <Panel title="Password">
      <form
        className="max-w-md space-y-4"
        onSubmit={async (e) => {
          e.preventDefault();
          const ok = await run(
            () => post('/api/account/password', { currentPassword: current, newPassword: next }),
            'Password changed. Other devices are signed out; this one stays signed in.',
          );
          if (ok) {
            setCurrent('');
            setNext('');
            setAgain('');
          }
        }}
      >
        <Field label="Current password" htmlFor="pw-current">
          <Input id="pw-current" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        </Field>
        <Field label="New password" htmlFor="pw-new" hint="At least 8 characters with an uppercase letter, a lowercase letter, a number and a symbol.">
          <Input id="pw-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required />
        </Field>
        <Field label="New password again" htmlFor="pw-again" error={mismatch ? "The two new passwords don't match." : null}>
          <Input id="pw-again" type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} required />
        </Field>
        <p className="text-sm text-muted">Changing it signs you out on every other device.</p>
        <Button type="submit" variant="primary" busy={busy} disabled={!current || !next || next !== again}>
          Change password
        </Button>
      </form>
    </Panel>
  );
}

/** ON-6 / UX audit #14: the new address gets a link; nothing changes until it's opened. */
function EmailPanel({ status }: { status: SecurityStatus }) {
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [address, setAddress] = useState('');
  const [password, setPassword] = useState('');
  const { busy, run } = useAction();
  const refresh = () => qc.invalidateQueries({ queryKey: securityKey });
  let body: ReactNode;
  if (status.pendingEmail) {
    body = (
      <>
        <p>
          We sent a link to <strong className="break-all">{status.pendingEmail}</strong>. Open it within 24 hours to switch. Until then you keep signing in with your current email.
        </p>
        <Button
          className="mt-3"
          busy={busy}
          onClick={() =>
            run(async () => {
              await del('/api/account/email');
              await refresh();
            }, 'Email change cancelled.')
          }
        >
          Cancel the change
        </Button>
      </>
    );
  } else if (status.hasPassword === false) {
    body = <p className="text-muted">Your account is tied to this Google address, so it can&apos;t be changed here. Contact support if you need to move your account to another address.</p>;
  } else if (status.emailChangeAvailable === false) {
    body = <p className="text-muted">Changing your email isn&apos;t available yet: we can&apos;t send the confirmation email right now. Contact support if you need it changed.</p>;
  } else {
    body = <Button onClick={() => setOpen(true)}>Change email</Button>;
  }
  return (
    <Panel title="Email">
      <div className="max-w-xl space-y-3">
        <p>
          You sign in with <strong className="break-all">{me?.email}</strong>.
        </p>
        <div>{body}</div>
      </div>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Change email"
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" form="email-change" variant="primary" busy={busy} disabled={!address.trim() || !password}>
              Send the link
            </Button>
          </>
        }
      >
        <form
          id="email-change"
          className="space-y-4"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await run(async () => {
              await post('/api/account/email', { newEmail: address, password });
              await refresh();
            }, 'Link sent. Open it from your new inbox to finish.');
            if (ok) {
              setOpen(false);
              setAddress('');
              setPassword('');
            }
          }}
        >
          <p className="text-sm text-muted">We&apos;ll email a link to the new address. Your email changes when you open it, and then you sign in again with the new address on every device.</p>
          <Field label="New email" htmlFor="em-new">
            <Input id="em-new" type="email" autoComplete="email" value={address} onChange={(e) => setAddress(e.target.value)} required />
          </Field>
          <Field label="Your password" htmlFor="em-pw" hint="To make sure it's you.">
            <Input id="em-pw" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </Field>
        </form>
      </Dialog>
    </Panel>
  );
}

/** ON-6 / UX audit #14: a lost phone or a shared computer. This device stays signed in. */
function DevicesPanel() {
  const [asking, setAsking] = useState(false);
  const { busy, run } = useAction();
  const toast = useToast();
  return (
    <Panel title="Other devices">
      <div className="max-w-xl space-y-3">
        <p>Lost a phone, or signed in on a computer that isn&apos;t yours? Sign out everywhere except here.</p>
        <Button onClick={() => setAsking(true)}>Sign out other devices</Button>
      </div>
      <Dialog
        open={asking}
        onClose={() => setAsking(false)}
        title="Sign out other devices?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setAsking(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              busy={busy}
              onClick={async () => {
                let ended = 0;
                const ok = await run(async () => {
                  ended = (await post<{ otherSessions: number }>('/api/account/sign-out-others')).otherSessions;
                });
                if (ok) {
                  setAsking(false);
                  toast(ended > 0 ? `Signed out ${ended} other sign-in${ended === 1 ? '' : 's'}. This device stays signed in.` : 'Signed out everywhere else. This device stays signed in.', 'ok');
                }
              }}
            >
              Sign out other devices
            </Button>
          </>
        }
      >
        <p>Every other browser and phone signed in to your account is signed out, including &ldquo;Keep me signed in&rdquo;. Your streams keep running.</p>
      </Dialog>
    </Panel>
  );
}

/** ON-6: two-step sign-in with an authenticator app, and recovery codes. */
function TwoStepPanel() {
  const { data } = useQuery({ queryKey: securityKey, queryFn: () => get<SecurityStatus>('/api/account/security') });
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: securityKey });
  const [setup, setSetup] = useState<Setup | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [asking, setAsking] = useState<'off' | 'codes' | null>(null);
  const { busy, run } = useAction();

  if (!data) return <Spinner />;
  /** UX audit #15: Google sign-in accounts have no password; the copy mustn't assume one. */
  const google = data.hasPassword === false;

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
              {google
                ? 'Signing in with Google also needs a code from your authenticator app, so nobody can take over your channels with your Google account alone.'
                : 'Signing in needs your password and a code from your authenticator app, so nobody can take over your channels with your password alone.'}
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
            <p>
              Protect your channels: after you sign in{google ? ' with Google' : ' with your password'}, we also ask for a code from an authenticator app on your phone (Google
              Authenticator, Microsoft Authenticator, 1Password and others).
            </p>
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
        description={`Enter a code from your authenticator app (or a recovery code). Sign-in will only need ${google ? 'your Google account' : 'your password'}.`}
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
