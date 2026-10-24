import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { nowPlayingTitle } from '../../lib/now-playing';
import { MailWarning } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { HealthDot, LiveBadge } from '@/components/status';
import { Badge, Button, ErrorNote, Input, Panel, Spinner, useToast } from '@/components/ui';
import { get, post } from '@/lib/api';
import { dateTime, relative, timecode } from '@/lib/format';
import type { Level, LiveStateName } from '@/lib/types';
import { ReasonDialog } from './ReasonDialog';

// ------------------------------------------------------------------ OPS-1: email

export interface EmailRow {
  at: string;
  kind: string;
  ok: boolean;
  error?: string | null;
}

export interface EmailStatus {
  configured: boolean;
  sent24h: number;
  failed24h: number;
  lastSent?: EmailRow | null;
  lastFailure?: EmailRow | null;
}

const KIND: Record<string, string> = {
  VERIFY: 'Verify email',
  RESET: 'Password reset',
  WELCOME: 'Welcome',
  UPGRADE: 'Plan upgrade',
  UPLOAD: 'Upload status',
  DELETION: 'Account deletion',
  ALERT: 'Alert',
  SUPPORT: 'Support ticket',
  TEST: 'Test email',
};
export const emailKind = (k: string) => KIND[k] ?? k;

const useEmailStatus = () => useQuery({ queryKey: ['admin', 'email'], queryFn: () => get<EmailStatus>('/api/admin/email'), refetchInterval: 60000 });

/** "Send a test email" to the signed-in admin; says plainly whether it went. */
function TestEmailButton({ variant = 'secondary' }: { variant?: 'primary' | 'secondary' }) {
  const [open, setOpen] = useState(false);
  const toast = useToast();
  const status = useEmailStatus();
  return (
    <>
      <Button size="sm" variant={variant} onClick={() => setOpen(true)}>
        Send a test email
      </Button>
      {open && (
        <ReasonDialog
          title="Send a test email"
          action="Send"
          success=""
          onClose={() => setOpen(false)}
          onSubmit={(reason) =>
            post<{ ok: boolean; error?: string | null }>('/api/admin/email/test', { reason }).then((r) => {
              status.refetch();
              if (!r.ok) throw new Error(`Not sent: ${r.error ?? 'the mail service refused it'}`);
              toast('Sent. Check your inbox.', 'ok');
            })
          }
        >
          <p className="text-muted">It goes to your own address.</p>
        </ReasonDialog>
      )}
    </>
  );
}

/** OPS-1: on every admin page while email is off. */
export function EmailBanner() {
  const status = useEmailStatus();
  if (!status.data || status.data.configured) return null;
  return (
    <div role="alert" className="mb-6 flex flex-wrap items-start gap-3 rounded-md border border-bad/50 bg-bad/10 px-4 py-3 text-sm">
      <MailWarning className="mt-0.5 h-5 w-5 shrink-0 text-bad" aria-hidden />
      <div className="min-w-0 flex-1 basis-60">
        <p className="font-medium">Email is off. Nothing is being sent.</p>
        <p className="text-muted">
          Verification links, password resets, alerts and payment reminders don't go out. Meanwhile new password sign-ups are verified automatically and “Forgot password” sends people to
          support. Set <code>ZEPTOMAIL_URL</code> and <code>ZEPTOMAIL_TOKEN</code> on the web server to turn it on.
        </p>
      </div>
      <TestEmailButton />
    </div>
  );
}

/** OPS-1: email on the Platform page. */
export function EmailPanel() {
  const status = useEmailStatus();
  if (status.isLoading) return <Spinner />;
  if (!status.data) return <ErrorNote error={status.error} />;
  const d = status.data;
  return (
    <Panel title="Email" action={<TestEmailButton />}>
      <p className="flex items-center gap-2">
        <HealthDot level={!d.configured ? 'red' : d.failed24h > 0 ? 'amber' : 'green'} />
        <span className="font-medium">{d.configured ? 'Set up' : 'Off: not set up'}</span>
      </p>
      <p className="tnum mt-1 text-sm text-muted">
        Last 24 hours: {d.sent24h} sent, {d.failed24h} not sent.
        {d.lastSent ? ` Last sent ${relative(d.lastSent.at)}.` : ' Nothing sent yet.'}
      </p>
      {d.lastFailure && (
        <p className="mt-1 break-words text-sm text-muted">
          Last not sent: {emailKind(d.lastFailure.kind)}, {dateTime(d.lastFailure.at)}: {d.lastFailure.error}
        </p>
      )}
    </Panel>
  );
}

/** OPS-1: emails we tried to send one user (no bodies). */
export function EmailList({ items }: { items: EmailRow[] }) {
  if (items.length === 0) return <p className="px-4 py-3 text-muted">No emails yet.</p>;
  return (
    <ul className="divide-y divide-line">
      {items.map((e, i) => (
        <li key={i} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm">
          <span className="min-w-0 flex-1 basis-40">
            <span className="block">{emailKind(e.kind)}</span>
            <span className="tnum text-xs text-muted">{dateTime(e.at)}</span>
          </span>
          {e.ok ? <Badge tone="ok">Sent</Badge> : <Badge tone="bad">Not sent</Badge>}
          {!e.ok && e.error && <span className="block w-full break-words text-xs text-muted">{e.error}</span>}
        </li>
      ))}
    </ul>
  );
}

interface UserSupportData {
  awaitingVerification: boolean;
  hasPassword: boolean;
  streams: { streamId: number; name: string; live: boolean; lastJobId?: number | null }[];
  emails: EmailRow[];
}

/** On a user's admin page: each stream's last run (AD-6), emails sent (OPS-1), and a reset link for someone locked out. */
export function UserSupport({ id }: { id: number }) {
  const q = useQuery({ queryKey: ['admin', 'user-support', id], queryFn: () => get<UserSupportData>(`/api/admin/users/${id}/support`) });
  const [asking, setAsking] = useState(false);
  const [link, setLink] = useState<string | null>(null);
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const d = q.data;
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Panel title="Stream runs" pad={false}>
        <ul className="divide-y divide-line">
          {d.streams.map((s) => (
            <li key={s.streamId}>
              {s.lastJobId ? (
                <Link to={`/studio/admin/runs/${s.lastJobId}`} className="flex justify-between gap-3 px-4 py-2.5 text-sm hover:bg-raised">
                  <span className="truncate">{s.name}</span>
                  <span className="shrink-0 text-muted">{s.live ? 'Live run' : 'Last run'}</span>
                </Link>
              ) : (
                <span className="flex justify-between gap-3 px-4 py-2.5 text-sm">
                  <span className="truncate">{s.name}</span>
                  <span className="shrink-0 text-muted">Never live</span>
                </span>
              )}
            </li>
          ))}
          {d.streams.length === 0 && <li className="px-4 py-3 text-muted">No streams.</li>}
        </ul>
      </Panel>
      <Panel
        title="Emails"
        pad={false}
        action={
          d.hasPassword ? (
            <Button size="sm" onClick={() => setAsking(true)}>
              Password reset link
            </Button>
          ) : undefined
        }
      >
        {d.awaitingVerification && <p className="border-b border-line px-4 py-2.5 text-sm text-warn">Waiting for email verification.</p>}
        {link && (
          <div className="space-y-2 border-b border-line px-4 py-3 text-sm">
            <p>Give this link only to the account's owner. It works once.</p>
            <Input readOnly value={link} aria-label="Password reset link" onFocus={(e) => e.currentTarget.select()} />
          </div>
        )}
        <EmailList items={d.emails} />
      </Panel>
      {asking && (
        <ReasonDialog
          title="Make a password reset link?"
          action="Make link"
          success=""
          onClose={() => setAsking(false)}
          onSubmit={(reason) => post<{ link: string }>(`/api/admin/users/${id}/reset-link`, { reason }).then((r) => setLink(r.link))}
        >
          <p className="text-muted">For someone locked out while email is off. Check it's really them first (the email they write from, a payment id).</p>
        </ReasonDialog>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ AD-6: one run

interface SourceMedia {
  id?: number | null;
  title: string;
  width?: number | null;
  height?: number | null;
  fps?: number | null;
  shape?: string | null;
  codec?: string | null;
  hasAudio?: boolean | null;
  status?: string | null;
  problem?: string | null;
}
interface RunDestination {
  position: number;
  name: string;
  off: boolean;
  server: string;
  keyMasked: string;
  keyLength: number;
  checkLevel: 'OK' | 'WARN' | 'BAD';
  check: string;
  status?: string | null;
  restarts: number;
  lastError?: string | null;
  updatedAt?: string | null;
  /** RE-8, when the stream server reports it: is the platform receiving? */
  reach?: 'ON' | 'CONNECTING' | 'NOT_REACHING' | null;
  reachReason?: string | null;
}
export interface RunDetail {
  jobId: number;
  streamId?: number | null;
  streamName: string;
  userId: number;
  userEmail: string;
  plan: string;
  live: boolean;
  status?: string | null;
  desiredState?: string | null;
  health?: Level | null;
  worker?: string | null;
  heartbeatAgeSeconds?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  endReason?: string | null;
  restarts: number;
  bitrateKbps?: number | null;
  fps?: number | null;
  speed?: number | null;
  droppedFrames?: number | null;
  lastError?: string | null;
  nowPlaying?: string | null;
  inputLive?: boolean | null;
  scene?: string | null;
  source: { kind: string; count: number; media: SourceMedia[]; musicCount: number };
  output?: { width: number; height: number; fps: number; shape: string; videoKbps?: number | null; audio: string; keyframes: string } | null;
  destinations: RunDestination[];
  log: string;
  logUpdatedAt?: string | null;
  otherRuns: { jobId: number; startTime?: string | null; endTime?: string | null; status?: string | null; endReason?: string | null }[];
  history: { id: number; adminEmail: string; action: string; detail?: string | null; createdAt: string }[];
}
interface DestinationTestResult {
  id: number;
  status: 'PENDING' | 'RUNNING' | 'OK' | 'FAILED';
  message?: string | null;
}

const CHECK_TONE = { OK: 'ok', WARN: 'warn', BAD: 'bad' } as const;
const REACH = {
  ON: { label: 'Platform receiving', tone: 'ok' },
  CONNECTING: { label: 'Connecting', tone: 'warn' },
  NOT_REACHING: { label: 'Not reaching the platform', tone: 'bad' },
} as const;
const SOURCE_KIND: Record<string, string> = { PLAYLIST: 'Playlist', PHONE: 'Phone (live input)', FILE: 'One file', UNKNOWN: 'Unknown' };

/** One line about a source file: size, frame rate, shape, codec, sound. */
export function describeMedia(m: SourceMedia): string {
  const parts: string[] = [];
  if (m.width && m.height) parts.push(`${m.width}×${m.height}`);
  if (m.fps) parts.push(`${Math.round(m.fps * 100) / 100} fps`);
  if (m.shape) parts.push(m.shape.toLowerCase());
  if (m.codec) parts.push(m.codec);
  if (m.hasAudio != null) parts.push(m.hasAudio ? 'sound' : 'no sound');
  return parts.length ? parts.join(', ') : 'Not probed yet';
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  );
}

/** AD-6: everything support needs about one run of a stream. Keys are only ever masked. */
export function RunPage({ id }: { id: number }) {
  const run = useQuery({
    queryKey: ['admin', 'run', id],
    queryFn: () => get<RunDetail>(`/api/admin/jobs/${id}`),
    refetchInterval: (q) => (q.state.data?.live ? 10000 : false),
  });
  const [testing, setTesting] = useState<RunDestination | null>(null);
  const [tests, setTests] = useState<Record<number, number>>({});
  if (run.isLoading) return <Spinner />;
  if (!run.data) return <ErrorNote error={run.error} />;
  const d = run.data;
  return (
    <div className="space-y-6">
      <Link to={`/studio/admin/users/${d.userId}`} className="text-sm text-muted hover:text-ink">
        {d.userEmail}
      </Link>
      <Panel
        title={<span className="break-words">{d.streamName}</span>}
        action={
          d.live ? (
            <span className="flex items-center gap-2">
              <LiveBadge state={(d.status ?? 'STARTING') as LiveStateName} />
              <HealthDot level={d.health ?? undefined} />
            </span>
          ) : (
            <Badge>{d.status === 'FAILED' ? 'Failed' : 'Ended'}</Badge>
          )
        }
      >
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
          <Row label="Run">
            #{d.jobId}, {d.plan} plan
          </Row>
          <Row label="Worker">
            {d.worker ?? 'Unassigned'}
            {d.heartbeatAgeSeconds != null && <span className="tnum text-muted"> (reported {d.heartbeatAgeSeconds}s ago)</span>}
          </Row>
          <Row label="Started">{d.startTime ? dateTime(d.startTime) : 'Not yet'}</Row>
          {!d.live && (
            <Row label="Ended">
              {dateTime(d.endTime)}
              {d.endReason ? `: ${d.endReason}` : ''}
            </Row>
          )}
          {d.live && d.startTime && <Row label="Up">{timecode(Math.max(0, (Date.now() - new Date(d.startTime).getTime()) / 1000))}</Row>}
          <Row label="Encoder">
            <span className="tnum">
              {d.bitrateKbps ? `${Math.round(d.bitrateKbps)} kbps` : '–'}
              {d.fps ? `, ${Math.round(d.fps)} fps` : ''}
              {d.speed ? `, speed ${d.speed.toFixed(2)}×` : ''}
              {d.droppedFrames ? `, ${d.droppedFrames} frames dropped` : ''}, {d.restarts} restart{d.restarts === 1 ? '' : 's'}
            </span>
          </Row>
          {nowPlayingTitle(d.nowPlaying) && <Row label="Now playing">{nowPlayingTitle(d.nowPlaying)}</Row>}
          {d.inputLive != null && <Row label="Phone input">{d.inputLive ? 'Receiving' : 'Not receiving'}</Row>}
          {d.lastError && (
            <Row label="Last error">
              <span className="text-bad">{d.lastError}</span>
            </Row>
          )}
        </dl>
      </Panel>

      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="What goes in" pad={false}>
          <p className="border-b border-line px-4 py-2.5 text-sm">
            {SOURCE_KIND[d.source.kind] ?? d.source.kind}
            {d.source.kind === 'PLAYLIST' && `: ${d.source.count} video${d.source.count === 1 ? '' : 's'}`}
            {d.source.musicCount > 0 && `, ${d.source.musicCount} music track${d.source.musicCount === 1 ? '' : 's'}`}
          </p>
          <ul className="divide-y divide-line">
            {d.source.media.map((m, i) => (
              <li key={i} className="px-4 py-2.5 text-sm">
                <span className="block truncate">{m.title}</span>
                <span className="tnum block text-muted">{describeMedia(m)}</span>
                {m.problem && <span className="block text-warn">{m.problem}</span>}
              </li>
            ))}
            {d.source.kind === 'PLAYLIST' && d.source.count > d.source.media.length && <li className="px-4 py-2.5 text-sm text-muted">…and {d.source.count - d.source.media.length} more.</li>}
          </ul>
        </Panel>
        <Panel title="What comes out">
          {d.output ? (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
              <Row label="Picture">
                <span className="tnum">
                  {d.output.width}×{d.output.height}, {d.output.shape.toLowerCase()}, {d.output.fps} fps
                </span>
              </Row>
              <Row label="Video">{d.output.videoKbps ? <span className="tnum">{d.output.videoKbps.toLocaleString()} kbps</span> : 'Set by the file'}</Row>
              <Row label="Sound">{d.output.audio}</Row>
              <Row label="Keyframes">{d.output.keyframes}</Row>
            </dl>
          ) : (
            <p className="text-sm text-muted">This run's settings can't be read.</p>
          )}
        </Panel>
      </div>

      <Panel title="Where it goes" pad={false}>
        <ul className="divide-y divide-line">
          {d.destinations.map((dest) => {
            const blocked = d.live && !dest.off;
            return (
              <li key={dest.position} className="space-y-1.5 px-4 py-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="min-w-0 flex-1 font-medium">
                    {dest.position + 1}. {dest.name}
                  </span>
                  {dest.off && <Badge>Turned off</Badge>}
                  {dest.reach && REACH[dest.reach] ? (
                    <Badge tone={REACH[dest.reach].tone}>{REACH[dest.reach].label}</Badge>
                  ) : (
                    dest.status && <Badge tone={dest.status === 'LIVE' ? 'ok' : dest.status === 'RECONNECTING' ? 'warn' : 'neutral'}>{dest.status.toLowerCase()}</Badge>
                  )}
                  <Button size="sm" disabled={blocked} title={blocked ? 'Live right now: test it when the stream has stopped' : undefined} onClick={() => setTesting(dest)}>
                    Send a test
                  </Button>
                </div>
                <p className="break-all font-mono text-xs text-muted">{dest.server}</p>
                <p className="flex flex-wrap items-center gap-2">
                  <span className="text-muted">Key</span>
                  <span className="font-mono text-xs">{dest.keyMasked || '(none)'}</span>
                  {dest.keyLength > 0 && <span className="tnum text-xs text-muted">{dest.keyLength} characters</span>}
                  <Badge tone={CHECK_TONE[dest.checkLevel]}>{dest.check}</Badge>
                </p>
                <p className="tnum text-muted">
                  {dest.restarts} reconnect{dest.restarts === 1 ? '' : 's'}
                  {dest.updatedAt ? `, last change ${relative(dest.updatedAt)}` : ''}
                </p>
                {dest.reach === 'NOT_REACHING' && dest.reachReason && <p className="break-words text-bad">Why: {dest.reachReason}</p>}
                {dest.lastError && <p className="break-words text-bad">Platform said: {dest.lastError}</p>}
                {tests[dest.position] && <TestResult id={tests[dest.position]} />}
              </li>
            );
          })}
          {d.destinations.length === 0 && <li className="px-4 py-3 text-muted">No destinations.</li>}
        </ul>
      </Panel>

      <Panel title="FFmpeg log" pad={false} action={d.logUpdatedAt ? <span className="text-xs text-muted">Saved {relative(d.logUpdatedAt)}</span> : undefined}>
        {d.log ? (
          <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words px-4 py-3 font-mono text-xs leading-relaxed">{d.log}</pre>
        ) : (
          <p className="px-4 py-3 text-sm text-muted">Nothing logged for this run. Stream servers save FFmpeg's last 200 messages every few seconds, with keys removed; a run with no problems may have none.</p>
        )}
      </Panel>

      {(d.otherRuns.length > 0 || d.history.length > 0) && (
        <div className="grid gap-6 lg:grid-cols-2">
          {d.otherRuns.length > 0 && (
            <Panel title="Other runs of this stream" pad={false}>
              <ul className="divide-y divide-line">
                {d.otherRuns.map((r) => (
                  <li key={r.jobId}>
                    <Link to={`/studio/admin/runs/${r.jobId}`} className="block px-4 py-2.5 text-sm hover:bg-raised">
                      <span className="tnum">{r.startTime ? dateTime(r.startTime) : `Run #${r.jobId}`}</span>
                      <span className="block truncate text-muted">{r.endTime ? r.endReason || 'Ended' : r.status?.toLowerCase() || 'Not started'}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
          {d.history.length > 0 && (
            <Panel title="Admin history" pad={false}>
              <ul className="divide-y divide-line">
                {d.history.map((a) => (
                  <li key={a.id} className="px-4 py-2.5 text-sm">
                    <span className="font-medium">{a.action.replace(/_/g, ' ').toLowerCase()}</span> <span className="text-muted">by {a.adminEmail}, {dateTime(a.createdAt)}</span>
                    {a.detail && <span className="block break-words text-muted">{a.detail}</span>}
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      )}

      {testing && (
        <ReasonDialog
          title={`Send a test to ${testing.name}?`}
          action="Send test"
          success="Test started."
          onClose={() => setTesting(null)}
          onSubmit={(reason) =>
            post<DestinationTestResult>(`/api/admin/jobs/${d.jobId}/destinations/${testing.position}/test`, { reason }).then((t) => {
              setTests((all) => ({ ...all, [testing.position]: t.id }));
              run.refetch();
            })
          }
        >
          <p className="text-muted">A stream server sends a 10-second test picture to this address with the user's key. On YouTube it shows in their live dashboard as receiving.</p>
        </ReasonDialog>
      )}
    </div>
  );
}

function TestResult({ id }: { id: number }) {
  const t = useQuery({
    queryKey: ['admin', 'destination-test', id],
    queryFn: () => get<DestinationTestResult>(`/api/admin/destination-tests/${id}`),
    refetchInterval: (q) => (q.state.data && (q.state.data.status === 'OK' || q.state.data.status === 'FAILED') ? false : 2000),
  });
  if (!t.data) return <ErrorNote error={t.error} />;
  const done = t.data.status === 'OK' || t.data.status === 'FAILED';
  return (
    <p role="status" className={clsx('break-words', t.data.status === 'OK' ? 'text-ok' : t.data.status === 'FAILED' ? 'text-bad' : 'text-muted')}>
      {done ? `Test ${t.data.status === 'OK' ? 'worked' : 'failed'}: ${t.data.message ?? ''}` : 'Test running…'}
    </p>
  );
}
