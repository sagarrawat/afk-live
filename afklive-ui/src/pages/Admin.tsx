import clsx from 'clsx';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Paperclip } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Badge, Button, Dialog, ErrorNote, Field, Input, Panel, Segmented, Select, Spinner, Textarea, Toggle, useAction } from '@/components/ui';
import { ReasonDialog } from './admin/ReasonDialog';
import { NewSignupLimits, Offers } from './admin/OffersPanel';

import { UserSupport } from './admin/Support';
import { get, post } from '@/lib/api';
import { bytes, date, dateTime, duration, money } from '@/lib/format';
import type { Level, Page, Traffic } from '@/lib/types';
import { gbText } from '@/components/StreamingData';

export interface CostView {
  monthlyServerCost?: number | null;
  currency: string;
  streamHours30d: number;
  costPerStreamHour?: number | null;
}
export interface QuotaView {
  usedToday: number;
  dailyLimit: number;
  percent: number;
  resetsAt: string;
  breakdown: { api: string; units: number }[];
}
type IncidentStage = 'investigating' | 'identified' | 'monitoring' | 'resolved';
interface StatusIncident {
  id: number;
  title: string;
  status: IncidentStage;
  startedAt: string;
  resolvedAt?: string | null;
  updates: { status: IncidentStage; message: string; at: string }[];
}
export interface AdminAction {
  id: number;
  adminEmail: string;
  action: string;
  targetType: string;
  targetId: string;
  detail?: string | null;
  createdAt: string;
}
interface UserDetail {
  id: number;
  email: string;
  name?: string | null;
  plan: string;
  enabled: boolean;
  role: string;
  subscription?: { plan: string; cycle: string; status: string; periodEnd?: string | null; cancelAtPeriodEnd: boolean } | null;
  storageBytes: number;
  storageLimit: number;
  hoursThisMonth: number;
  hoursIncluded: number;
  streams: { id: number; name: string; live: boolean; jobId?: number | null; worker?: string | null }[];
  payments: { orderId: string; amount: number; currency?: string | null; status: string; kind?: string | null; plan?: string | null; createdAt: string }[];
  invoices: { id: number; number: string; total: number; currency: string; issuedAt: string }[];
  history: AdminAction[];
  /** FO-5: streaming data this month against the plan's allowance plus grants. */
  traffic?: Traffic | null;
}

/** FO-5: "1,234 of 2,000 GB (62%)", "+500 GB granted", or "no limit". */
export function trafficText(t: Traffic | null | undefined): string {
  if (!t) return '–';
  if (t.allowanceGb == null) return `${gbText(t.usedGb)} of streaming data (no limit)`;
  const pct = Math.floor(t.percent ?? 0);
  return `${gbText(t.usedGb)} of ${gbText(t.allowanceGb)} streaming data (${pct}%)${t.grantedGb > 0 ? `, ${gbText(t.grantedGb)} granted` : ''}`;
}

/** Health colour for a load percentage: green below 75, amber to 90, red above. */
export function loadLevel(pct: number | null | undefined): Level {
  if (pct == null) return 'green';
  return pct >= 90 ? 'red' : pct >= 75 ? 'amber' : 'green';
}

export function Meter({ label, pct, text }: { label: string; pct: number | null | undefined; text: string }) {
  const level = loadLevel(pct);
  return (
    <div>
      <div className="flex justify-between gap-2 text-xs">
        <span className="text-muted">{label}</span>
        <span className={clsx('tnum', level === 'red' ? 'text-bad' : level === 'amber' ? 'text-warn' : 'text-muted')}>{text}</span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-raised" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct ?? undefined}>
        {pct != null && <div className={clsx('h-full rounded-full', level === 'red' ? 'bg-bad' : level === 'amber' ? 'bg-warn' : 'bg-ok')} style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />}
      </div>
    </div>
  );
}

function major(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(currency === 'INR' ? 'en-IN' : 'en-US', { style: 'currency', currency, maximumFractionDigits: 2 }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

/** AD-2: the monthly server bill over the last 30 days' stream hours. */
export function CostLine({ cost }: { cost: CostView }) {
  const hours = `${cost.streamHours30d.toLocaleString(undefined, { maximumFractionDigits: 1 })} stream hours in the last 30 days`;
  if (cost.monthlyServerCost == null) {
    return (
      <span className="text-muted">
        {hours}. Set <code>APP_MONTHLY_SERVER_COST</code> to see the server cost per stream hour.
      </span>
    );
  }
  return (
    <span className="text-muted">
      <span className="tnum font-medium text-ink">{cost.costPerStreamHour == null ? '–' : major(cost.costPerStreamHour, cost.currency)}</span> per stream hour: {major(cost.monthlyServerCost, cost.currency)} a month over {hours}.
    </span>
  );
}

export interface FunnelDay {
  day: string;
  views: number;
  startClicks: number;
  signUps: number;
  firstGoLives: number;
  firstReached: number;
}

/** Share of the previous step, or a dash when there's nothing to divide by. */
export function rate(part: number, whole: number): string {
  return whole > 0 ? `${Math.round((part / whole) * 100)}%` : '–';
}

/**
 * The homepage funnel: views and Start free clicks from the site's beacon (no cookies, bots dropped),
 * sign-ups and first lives from accounts and runs. Days are UTC.
 */
export function FunnelPanel() {
  const f = useQuery({ queryKey: ['admin', 'funnel'], queryFn: () => get<FunnelDay[]>('/api/admin/funnel?days=30') });
  if (f.isLoading) return <Spinner />;
  if (!f.data) return <ErrorNote error={f.error} />;
  const days = f.data;
  const t = days.reduce(
    (a, d) => ({ views: a.views + d.views, startClicks: a.startClicks + d.startClicks, signUps: a.signUps + d.signUps, firstGoLives: a.firstGoLives + d.firstGoLives, firstReached: a.firstReached + d.firstReached }),
    { views: 0, startClicks: 0, signUps: 0, firstGoLives: 0, firstReached: 0 },
  );
  const cols: [keyof typeof t, string][] = [
    ['views', 'Homepage views'],
    ['startClicks', 'Start free clicks'],
    ['signUps', 'Sign-ups'],
    ['firstGoLives', 'First Go live'],
    ['firstReached', 'First reached a platform'],
  ];
  return (
    <Panel title="Homepage to first live, last 30 days">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
        {cols.map(([k, label], i) => (
          <Stat key={k} label={label}>
            {t[k].toLocaleString()}
            {i > 0 && <span className="ml-1.5 text-sm font-normal text-muted">{rate(t[k], t[cols[i - 1][0]])}</span>}
          </Stat>
        ))}
      </div>
      <div className="mt-4 overflow-x-auto border-t border-line pt-3">
        <table className="w-full min-w-[520px] text-sm">
          <thead className="text-left text-muted">
            <tr>
              <th className="py-1.5 pr-3 font-medium">Day (UTC)</th>
              {cols.map(([k, label]) => (
                <th key={k} className="py-1.5 pr-3 text-right font-medium">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="tnum">
            {days
              .filter((d) => cols.some(([k]) => d[k] > 0))
              .map((d) => (
                <tr key={d.day} className="border-t border-line">
                  <td className="py-1.5 pr-3">{d.day}</td>
                  {cols.map(([k]) => (
                    <td key={k} className="py-1.5 pr-3 text-right">
                      {d[k].toLocaleString()}
                    </td>
                  ))}
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      <p className="mt-3 text-xs text-muted">Views and clicks are counted without cookies or visitor ids; bots and scripts are left out roughly. Accounts made before this was added have no sign-up day.</p>
    </Panel>
  );
}

/** YT-4: YouTube Data API units spent today. */
export function Quota() {
  const q = useQuery({ queryKey: ['admin', 'quota'], queryFn: () => get<QuotaView>('/api/admin/quota'), refetchInterval: 60000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const d = q.data;
  const level = loadLevel(d.percent);
  const resetsIn = Math.max(0, (new Date(d.resetsAt).getTime() - Date.now()) / 1000);
  return (
    <Panel title="YouTube API quota today">
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
        <p>
          <span className="tnum text-2xl font-semibold">{d.usedToday.toLocaleString()}</span>
          <span className="tnum text-muted"> of {d.dailyLimit.toLocaleString()} units</span>
        </p>
        <p className={clsx('tnum text-sm', level === 'red' ? 'text-bad' : level === 'amber' ? 'text-warn' : 'text-muted')}>
          {d.percent}% used{level === 'red' ? ': uploads and live comment tools may start failing' : ''}
        </p>
      </div>
      <div className="mt-3">
        <Meter label="Quota used" pct={d.percent} text={`resets in ${duration(resetsIn)} (midnight Pacific)`} />
      </div>
      {d.breakdown.length > 0 ? (
        <ul className="mt-4 divide-y divide-line text-sm">
          {d.breakdown.map((b) => (
            <li key={b.api} className="flex justify-between gap-3 py-2">
              <span className="truncate">{b.api}</span>
              <span className="tnum text-muted">{b.units.toLocaleString()}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-sm text-muted">No YouTube API calls yet today.</p>
      )}
    </Panel>
  );
}

const STAGES: [IncidentStage, string][] = [
  ['investigating', 'Investigating'],
  ['identified', 'Cause found'],
  ['monitoring', 'Fix in place, watching'],
  ['resolved', 'Resolved'],
];
const stageLabel = (s: IncidentStage) => STAGES.find(([v]) => v === s)?.[1] ?? s;

/** RE-7: incidents on the public status page. */
export function Incidents() {
  const list = useQuery({ queryKey: ['admin', 'incidents'], queryFn: () => get<{ incident: StatusIncident; createdBy: string }[]>('/api/admin/status/incidents') });
  const [dialog, setDialog] = useState<{ incident?: StatusIncident } | null>(null);
  return (
    <Panel
      title="Status page incidents"
      pad={false}
      action={
        <Button size="sm" variant="primary" onClick={() => setDialog({})}>
          Post an incident
        </Button>
      }
    >
      <p className="border-b border-line px-4 py-2.5 text-sm text-muted">
        Shown publicly at{' '}
        <a href="/status" target="_blank" rel="noreferrer" className="underline">
          /status
        </a>
        . Never name customers or their streams.
      </p>
      <ErrorNote error={list.error} />
      <ul className="divide-y divide-line">
        {(list.data ?? []).map(({ incident: i, createdBy }) => (
          <li key={i.id} className="px-4 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="min-w-0 flex-1 font-medium">{i.title}</span>
              <Badge tone={i.resolvedAt ? 'ok' : 'warn'}>{stageLabel(i.status)}</Badge>
              {!i.resolvedAt && (
                <Button size="sm" onClick={() => setDialog({ incident: i })}>
                  Post update
                </Button>
              )}
            </div>
            <p className="text-xs text-muted">
              Started {dateTime(i.startedAt)} by {createdBy}
              {i.resolvedAt ? `, resolved ${dateTime(i.resolvedAt)}` : ''}
            </p>
            <ol className="mt-2 space-y-1.5 border-l border-line pl-3 text-sm">
              {i.updates.map((u, n) => (
                <li key={n}>
                  <span className="font-medium">{stageLabel(u.status)}</span> <span className="text-muted">{dateTime(u.at)}</span>
                  <span className="block whitespace-pre-line text-muted">{u.message}</span>
                </li>
              ))}
            </ol>
          </li>
        ))}
        {list.data?.length === 0 && <li className="px-4 py-3 text-muted">No incidents in the last 90 days.</li>}
      </ul>
      {dialog && <IncidentDialog incident={dialog.incident} onClose={() => setDialog(null)} onDone={() => list.refetch()} />}
    </Panel>
  );
}

function IncidentDialog({ incident, onClose, onDone }: { incident?: StatusIncident; onClose: () => void; onDone: () => void }) {
  const [title, setTitle] = useState('');
  const [stage, setStage] = useState<IncidentStage>(incident ? incident.status : 'investigating');
  const [message, setMessage] = useState('');
  const { busy, run } = useAction();
  const ok = message.trim().length > 0 && (incident || title.trim().length >= 3);
  const submit = () =>
    run(
      () =>
        (incident ? post(`/api/admin/status/incidents/${incident.id}/updates`, { status: stage, message: message.trim() }) : post('/api/admin/status/incidents', { title: title.trim(), status: stage, message: message.trim() })).then(onDone),
      'Posted to the status page.',
    );
  return (
    <Dialog
      open
      onClose={onClose}
      title={incident ? `Update: ${incident.title}` : 'Post an incident'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} disabled={!ok} onClick={async () => (await submit()) && onClose()}>
            {incident ? 'Post update' : 'Post incident'}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {!incident && (
          <Field label="Title" htmlFor="it" hint="What viewers of the status page notice, e.g. “Streams reconnecting in India”.">
            <Input id="it" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} autoFocus />
          </Field>
        )}
        <Field label="Stage" htmlFor="is">
          <Select id="is" value={stage} onChange={(e) => setStage(e.target.value as IncidentStage)}>
            {STAGES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Update" htmlFor="im" hint="Public. Plain words, no customer or stream names.">
          <Textarea id="im" value={message} maxLength={2000} onChange={(e) => setMessage(e.target.value)} autoFocus={!!incident} />
        </Field>
      </div>
    </Dialog>
  );
}

export interface AdminOverview {
  users: number;
  liveStreams: number;
  storageBytes: number;
  openTickets: number;
  freeStreamLimit: number;
  revenue: { currency: string; allTime: number; last30Days: number }[];
}

export function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p className="tnum truncate text-xl font-semibold">{children}</p>
    </div>
  );
}

/** Payments received (completed ones, per currency) and the server cost per stream hour (AD-2). */
export function RevenuePanel() {
  const o = useQuery({ queryKey: ['admin', 'overview'], queryFn: () => get<AdminOverview>('/api/admin/overview'), refetchInterval: 30000 });
  const cost = useQuery({ queryKey: ['admin', 'cost'], queryFn: () => get<CostView>('/api/admin/cost'), refetchInterval: 300000 });
  if (o.isLoading) return <Spinner />;
  if (!o.data) return <ErrorNote error={o.error} />;
  const d = o.data;
  return (
    <Panel title="Payments received">
      <div className="text-sm">
        {d.revenue.length === 0 ? (
          <p className="text-muted">No completed payments yet.</p>
        ) : (
          <ul className="space-y-1">
            {d.revenue.map((r) => (
              <li key={r.currency} className="tnum text-muted">
                <span className="font-medium text-ink">{money(r.last30Days, r.currency)}</span> in the last 30 days, {money(r.allTime, r.currency)} in all
              </li>
            ))}
          </ul>
        )}
        <p className="mt-1 text-xs text-muted">Completed payments only; refunded ones aren't counted.</p>
      </div>
      {cost.data && (
        <div className="mt-4 border-t border-line pt-3 text-sm">
          <CostLine cost={cost.data} />
        </div>
      )}
    </Panel>
  );
}

/** The platform at a glance, and the free-plan capacity setting. */
export function OverviewPanel() {
  const qc = useQueryClient();
  const o = useQuery({ queryKey: ['admin', 'overview'], queryFn: () => get<AdminOverview>('/api/admin/overview'), refetchInterval: 30000 });
  const [editing, setEditing] = useState(false);
  const [limit, setLimit] = useState('');
  if (o.isLoading) return <Spinner />;
  if (!o.data) return <ErrorNote error={o.error} />;
  const d = o.data;
  const n = Number(limit);
  const limitOk = limit.trim() !== '' && Number.isInteger(n) && n >= 0 && n <= 10000;
  return (
    <Panel title="Platform at a glance">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Accounts">{d.users.toLocaleString()}</Stat>
        <Stat label="Live now">{d.liveStreams.toLocaleString()}</Stat>
        <Stat label="Storage used">{bytes(d.storageBytes)}</Stat>
        <Stat label="Open tickets">
          <Link to="/studio/admin/support" className="hover:underline">
            {d.openTickets.toLocaleString()}
          </Link>
        </Stat>
      </div>
      <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-line pt-4 text-sm">
        <p className="min-w-0 flex-1">
          <span className="font-medium">Free plan capacity: </span>
          <span className="text-muted">
            free accounts can't go live while <span className="tnum font-medium text-ink">{d.freeStreamLimit.toLocaleString()}</span> or more streams (all plans) are live.
          </span>
        </p>
        <Button size="sm" onClick={() => (setLimit(String(d.freeStreamLimit)), setEditing(true))}>
          Change
        </Button>
      </div>
      {editing && (
        <ReasonDialog
          title="Free plan capacity"
          action="Save"
          blocked={!limitOk}
          onClose={() => setEditing(false)}
          onSubmit={(reason) => post<AdminOverview>('/api/admin/settings/free-stream-limit', { limit: n, reason }).then((next) => qc.setQueryData(['admin', 'overview'], next))}
        >
          <Field label="Live streams before free accounts wait" htmlFor="fl" hint="0 to 10,000. Applies to the next go-live; streams already live keep running." error={limitOk ? null : 'Enter a whole number from 0 to 10,000.'}>
            <Input id="fl" type="number" min={0} max={10000} value={limit} onChange={(e) => setLimit(e.target.value)} />
          </Field>
        </ReasonDialog>
      )}
    </Panel>
  );
}

// ------------------------------------------------------------------ support tickets

type TicketStatus = 'OPEN' | 'RESOLVED' | 'CLOSED';
export interface Ticket {
  id: number;
  userEmail?: string | null;
  userId?: number | null;
  category: string;
  message: string;
  status: TicketStatus;
  createdAt: string;
  attachmentName?: string | null;
  hasAttachment: boolean;
}
const TICKET_LABEL: Record<TicketStatus, string> = { OPEN: 'Open', RESOLVED: 'Resolved', CLOSED: 'Closed' };
const TICKET_TONE: Record<TicketStatus, 'warn' | 'ok' | 'neutral'> = { OPEN: 'warn', RESOLVED: 'ok', CLOSED: 'neutral' };

export function Tickets() {
  const [status, setStatus] = useState<TicketStatus | 'ALL'>('OPEN');
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: ['admin', 'tickets', status, page],
    queryFn: () => get<Page<Ticket>>(`/api/admin/tickets?status=${status === 'ALL' ? '' : status}&page=${page}`),
  });
  return (
    <div className="space-y-4">
      <Segmented
        label="Ticket status"
        value={status}
        onChange={(v) => (setStatus(v), setPage(0))}
        options={[
          { value: 'OPEN', label: 'Open' },
          { value: 'RESOLVED', label: 'Resolved' },
          { value: 'CLOSED', label: 'Closed' },
          { value: 'ALL', label: 'All' },
        ]}
      />
      <ErrorNote error={list.error} />
      <ul className="divide-y divide-line rounded-md border border-line bg-panel">
        {(list.data?.content ?? []).map((t) => (
          <li key={t.id}>
            <Link to={`/studio/admin/support/${t.id}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 hover:bg-raised">
              <span className="min-w-0 flex-1 basis-56">
                <span className="block truncate font-medium">{t.userEmail ?? 'Unknown account'}</span>
                <span className="line-clamp-2 break-words text-sm text-muted">{t.message}</span>
              </span>
              <Badge>{t.category}</Badge>
              {t.hasAttachment && <Paperclip className="h-4 w-4 text-muted" aria-label="Has an attachment" />}
              <Badge tone={TICKET_TONE[t.status] ?? 'neutral'}>{TICKET_LABEL[t.status] ?? t.status}</Badge>
              <span className="tnum text-sm text-muted">{dateTime(t.createdAt)}</span>
            </Link>
          </li>
        ))}
        {list.data?.content.length === 0 && <li className="px-4 py-6 text-center text-muted">{status === 'ALL' ? 'No tickets yet.' : `No ${TICKET_LABEL[status].toLowerCase()} tickets.`}</li>}
      </ul>
      {list.data && list.data.totalPages > 1 && (
        <div className="flex justify-center gap-3">
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </Button>
          <Button size="sm" disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>
            Older
          </Button>
        </div>
      )}
    </div>
  );
}

/** How an attachment can be previewed in the page, from its file name. */
export function attachmentKind(name: string | null | undefined): 'image' | 'video' | 'file' {
  const n = (name ?? '').toLowerCase();
  if (/\.(png|jpe?g)$/.test(n)) return 'image';
  if (/\.mp4$/.test(n)) return 'video';
  return 'file';
}

export function TicketPage({ id }: { id: number }) {
  const qc = useQueryClient();
  const t = useQuery({ queryKey: ['admin', 'ticket', id], queryFn: () => get<Ticket>(`/api/admin/tickets/${id}`) });
  const [next, setNext] = useState<TicketStatus | null>(null);
  if (t.isLoading) return <Spinner />;
  if (!t.data) return <ErrorNote error={t.error} />;
  const d = t.data;
  const url = `/api/admin/tickets/${d.id}/attachment`;
  const kind = attachmentKind(d.attachmentName);
  const verbs: Record<TicketStatus, string> = { OPEN: 'Reopen', RESOLVED: 'Mark resolved', CLOSED: 'Close' };
  return (
    <div className="space-y-6">
      <Link to="/studio/admin/support" className="text-sm text-muted hover:text-ink">
        All tickets
      </Link>
      <Panel title={`${d.category} ticket`} action={<Badge tone={TICKET_TONE[d.status] ?? 'neutral'}>{TICKET_LABEL[d.status] ?? d.status}</Badge>}>
        <p className="text-sm text-muted">
          From{' '}
          {d.userId ? (
            <Link to={`/studio/admin/users/${d.userId}`} className="text-ink hover:underline">
              {d.userEmail}
            </Link>
          ) : (
            (d.userEmail ?? 'an unknown account')
          )}
          , {dateTime(d.createdAt)}
        </p>
        <p className="mt-3 whitespace-pre-wrap break-words">{d.message}</p>
        <div className="mt-5 flex flex-wrap gap-2">
          {d.status === 'OPEN' ? (
            <>
              <Button size="sm" variant="primary" onClick={() => setNext('RESOLVED')}>
                Mark resolved
              </Button>
              <Button size="sm" onClick={() => setNext('CLOSED')}>
                Close
              </Button>
            </>
          ) : (
            <Button size="sm" onClick={() => setNext('OPEN')}>
              Reopen
            </Button>
          )}
        </div>
      </Panel>
      {d.hasAttachment && (
        <Panel
          title="Attachment"
          action={
            <a href={url} download={d.attachmentName ?? true} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
              <Download className="h-4 w-4" aria-hidden />
              Download
            </a>
          }
        >
          <p className="mb-3 truncate text-sm text-muted">{d.attachmentName}</p>
          {kind === 'image' && <img src={url} alt={`Attachment: ${d.attachmentName}`} className="max-h-96 max-w-full rounded border border-line object-contain" />}
          {kind === 'video' && <video src={url} controls className="max-h-96 w-full rounded border border-line" />}
          {kind === 'file' && <p className="text-sm text-muted">No preview for this kind of file. Download it to open it.</p>}
        </Panel>
      )}
      {next && (
        <ReasonDialog
          title={`${verbs[next]} this ticket?`}
          action={verbs[next]}
          onClose={() => setNext(null)}
          onSubmit={(reason) =>
            post<Ticket>(`/api/admin/tickets/${d.id}/status`, { status: next, reason }).then((saved) => {
              qc.setQueryData(['admin', 'ticket', id], saved);
              qc.invalidateQueries({ queryKey: ['admin', 'tickets'] });
              qc.invalidateQueries({ queryKey: ['admin', 'overview'] });
            })
          }
        >
          <p className="text-muted">The customer isn't emailed; reply to them from the support inbox.</p>
        </ReasonDialog>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ plans

export interface PlanSettings {
  plan: string;
  displayName: string;
  maxStorageBytes: number;
  maxActiveStreams: number;
  maxDestinations: number;
  maxChannels: number;
  maxResolution: number;
  maxFps: number;
  monthlyHours: number;
  watermark: boolean;
  maxScheduledPosts: number;
  priceInrMonth: number;
  priceInrYear: number;
  priceUsdMonth: number;
  priceUsdYear: number;
  /** FO-5: streaming data included a month, in GB; null = no limit. */
  monthlyTrafficGb?: number | null;
}

/** The plan editor's fields, as typed (prices in rupees / dollars, storage in GB). */
export interface PlanForm {
  displayName: string;
  storageGb: string;
  streams: string;
  destinations: string;
  resolution: string;
  fps: string;
  monthlyHours: string;
  watermark: boolean;
  inrMonth: string;
  inrYear: string;
  usdMonth: string;
  usdYear: string;
  /** Empty: no limit. */
  trafficGb: string;
}

const GB = 1024 ** 3;
const RESOLUTIONS: [number, string][] = [
  [720, '720p'],
  [1080, '1080p'],
  [1440, '1440p'],
  [2160, '4K (2160p)'],
];

export function planForm(p: PlanSettings): PlanForm {
  const major = (minor: number) => String(minor / 100);
  return {
    displayName: p.displayName,
    storageGb: String(Math.round((p.maxStorageBytes / GB) * 100) / 100),
    streams: String(p.maxActiveStreams),
    destinations: String(p.maxDestinations),
    resolution: String(p.maxResolution),
    fps: String(p.maxFps),
    monthlyHours: String(p.monthlyHours),
    watermark: p.watermark,
    inrMonth: major(p.priceInrMonth),
    inrYear: major(p.priceInrYear),
    usdMonth: major(p.priceUsdMonth),
    usdYear: major(p.priceUsdYear),
    trafficGb: p.monthlyTrafficGb == null ? '' : String(p.monthlyTrafficGb),
  };
}

/** What's wrong with the form, in words (null: it can be saved). The server checks the same rules. */
export function planProblem(plan: string, f: PlanForm): string | null {
  const num = (s: string) => (s.trim() === '' ? NaN : Number(s));
  const whole = (s: string, min: number, max: number) => Number.isInteger(num(s)) && num(s) >= min && num(s) <= max;
  if (!f.displayName.trim() || f.displayName.trim().length > 40) return 'Give the plan a name of up to 40 characters.';
  if (!(num(f.storageGb) > 0 && num(f.storageGb) <= 10240)) return 'Storage is more than 0 and at most 10,240 GB.';
  if (!whole(f.streams, 1, 1000)) return 'Streams at a time is a whole number from 1 to 1,000.';
  if (!whole(f.destinations, 1, 20)) return 'Destinations per stream is a whole number from 1 to 20.';
  if (!whole(f.monthlyHours, 0, 744)) return 'Monthly hours is a whole number from 0 (no limit) to 744.';
  if (f.trafficGb.trim() !== '' && !whole(f.trafficGb, 1, 1000000)) return 'Streaming data a month is a whole number of GB from 1 to 1,000,000, or empty for no limit.';
  const prices = [f.inrMonth, f.inrYear, f.usdMonth, f.usdYear].map(num);
  if (prices.some((p) => !Number.isFinite(p) || p < 0)) return "Prices can't be empty or negative.";
  if (prices.some((p) => Math.round(p * 100) !== Math.round(p * 100 * 1000) / 1000)) return 'Prices have at most two decimals.';
  const [inrMonth, inrYear, usdMonth, usdYear] = prices;
  if (plan === 'FREE') {
    if (prices.some((p) => p !== 0)) return "The Free plan's prices stay 0.";
  } else {
    if (prices.some((p) => p === 0)) return 'A paid plan needs every price above 0.';
    if (inrYear > inrMonth * 12 || usdYear > usdMonth * 12) return "A yearly price can't be more than 12 monthly ones.";
  }
  return null;
}

function planBody(f: PlanForm) {
  const minor = (s: string) => Math.round(Number(s) * 100);
  return {
    displayName: f.displayName.trim(),
    maxStorageBytes: Math.round(Number(f.storageGb) * GB),
    maxActiveStreams: Number(f.streams),
    maxDestinations: Number(f.destinations),
    maxResolution: Number(f.resolution),
    maxFps: Number(f.fps),
    monthlyHours: Number(f.monthlyHours),
    watermark: f.watermark,
    priceInrMonth: minor(f.inrMonth),
    priceInrYear: minor(f.inrYear),
    priceUsdMonth: minor(f.usdMonth),
    priceUsdYear: minor(f.usdYear),
    monthlyTrafficGb: f.trafficGb.trim() === '' ? null : Number(f.trafficGb),
  };
}

export function Plans() {
  const plans = useQuery({ queryKey: ['admin', 'plans'], queryFn: () => get<PlanSettings[]>('/api/admin/plans') });
  const [editing, setEditing] = useState<PlanSettings | null>(null);
  if (plans.isLoading) return <Spinner />;
  if (!plans.data) return <ErrorNote error={plans.error} />;
  const price = (month: number, year: number, cur: string) => (month === 0 && year === 0 ? 'Free' : `${money(month, cur)} a month, ${money(year, cur)} a year`);
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">
        Changes apply right away: prices to every checkout and renewal from then on, limits to new sign-ups and to anyone whose own recorded limits are smaller. Paying subscribers never get less than the limits they bought (raising a limit reaches them too). Prices include GST.
      </p>
      <NewSignupLimits onApplied={() => plans.refetch()} />
      <Offers />
      <ul className="grid gap-4 sm:grid-cols-2">
        {plans.data.map((p) => (
          <li key={p.plan}>
            <Panel
              title={
                <span className="flex items-center gap-2">
                  {p.displayName} <Badge>{p.plan}</Badge>
                </span>
              }
              action={
                <Button size="sm" onClick={() => setEditing(p)}>
                  Edit
                </Button>
              }
            >
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
                <dt className="text-muted">India</dt>
                <dd className="tnum">{price(p.priceInrMonth, p.priceInrYear, 'INR')}</dd>
                <dt className="text-muted">Elsewhere</dt>
                <dd className="tnum">{price(p.priceUsdMonth, p.priceUsdYear, 'USD')}</dd>
                <dt className="text-muted">Streams</dt>
                <dd className="tnum">
                  {p.maxActiveStreams} at a time, {p.maxDestinations} destination{p.maxDestinations === 1 ? '' : 's'} each
                </dd>
                <dt className="text-muted">Quality</dt>
                <dd className="tnum">
                  Up to {p.maxResolution}p at {p.maxFps} fps{p.watermark ? ', with watermark' : ''}
                </dd>
                <dt className="text-muted">Storage</dt>
                <dd className="tnum">{bytes(p.maxStorageBytes)}</dd>
                <dt className="text-muted">Hours</dt>
                <dd className="tnum">{p.monthlyHours === 0 ? 'No monthly limit' : `${p.monthlyHours} a month`}</dd>
                <dt className="text-muted">Streaming data</dt>
                <dd className="tnum">{p.monthlyTrafficGb == null ? 'No monthly limit' : `${p.monthlyTrafficGb.toLocaleString('en-US')} GB a month`}</dd>
              </dl>
            </Panel>
          </li>
        ))}
      </ul>
      {editing && <PlanDialog plan={editing} onClose={() => setEditing(null)} onSaved={() => plans.refetch()} />}
    </div>
  );
}

function PlanDialog({ plan, onClose, onSaved }: { plan: PlanSettings; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<PlanForm>(() => planForm(plan));
  const set = (k: keyof PlanForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });
  const problem = planProblem(plan.plan, f);
  const paid = plan.plan !== 'FREE';
  return (
    <ReasonDialog
      wide
      title={`Edit ${plan.displayName}`}
      action="Save plan"
      blocked={problem != null}
      onClose={onClose}
      onSubmit={(reason) => post(`/api/admin/plans/${plan.plan}`, { plan: planBody(f), reason }).then(onSaved)}
    >
      <p className="text-sm text-muted">
        Saving applies at once: prices for every new purchase and renewal; limits for new sign-ups on {plan.displayName}. Paying subscribers keep the limits they bought if those are larger (streams already live keep running).
      </p>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" htmlFor="pn">
          <Input id="pn" value={f.displayName} maxLength={40} onChange={set('displayName')} />
        </Field>
        <Field label="Storage (GB)" htmlFor="ps">
          <Input id="ps" type="number" min={0} step="any" value={f.storageGb} onChange={set('storageGb')} />
        </Field>
        <Field label="Streams at a time" htmlFor="pc">
          <Input id="pc" type="number" min={1} max={1000} value={f.streams} onChange={set('streams')} />
        </Field>
        <Field label="Destinations per stream" htmlFor="pd">
          <Input id="pd" type="number" min={1} max={20} value={f.destinations} onChange={set('destinations')} />
        </Field>
        <Field label="Top quality" htmlFor="pr">
          <Select id="pr" value={f.resolution} onChange={set('resolution')}>
            {RESOLUTIONS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Frame rate" htmlFor="pf">
          <Select id="pf" value={f.fps} onChange={set('fps')}>
            <option value="30">30 fps</option>
            <option value="60">60 fps</option>
          </Select>
        </Field>
        <Field label="Streaming hours a month" htmlFor="ph" hint="0 means no limit.">
          <Input id="ph" type="number" min={0} max={744} value={f.monthlyHours} onChange={set('monthlyHours')} />
        </Field>
        <Field label="Streaming data a month (GB)" htmlFor="pt" hint="Empty means no limit. Past it, streams keep their first destination only; past 110%, new streams start in 720p.">
          <Input id="pt" type="number" min={1} max={1000000} value={f.trafficGb} onChange={set('trafficGb')} placeholder="No limit" />
        </Field>
        <div className="flex items-center justify-between gap-3 self-start pt-7">
          <span className="text-sm font-medium">AFK Live watermark on the stream</span>
          <Toggle label="Watermark" checked={f.watermark} onChange={(v) => setF({ ...f, watermark: v })} />
        </div>
        <Field label="Price a month (₹)" htmlFor="pim" hint={paid ? 'GST included.' : undefined}>
          <Input id="pim" type="number" min={0} step="0.01" value={f.inrMonth} onChange={set('inrMonth')} disabled={!paid} />
        </Field>
        <Field label="Price a year (₹)" htmlFor="piy">
          <Input id="piy" type="number" min={0} step="0.01" value={f.inrYear} onChange={set('inrYear')} disabled={!paid} />
        </Field>
        <Field label="Price a month ($)" htmlFor="pum">
          <Input id="pum" type="number" min={0} step="0.01" value={f.usdMonth} onChange={set('usdMonth')} disabled={!paid} />
        </Field>
        <Field label="Price a year ($)" htmlFor="puy">
          <Input id="puy" type="number" min={0} step="0.01" value={f.usdYear} onChange={set('usdYear')} disabled={!paid} />
        </Field>
      </div>
      {problem && (
        <p role="alert" className="text-sm text-bad">
          {problem}
        </p>
      )}
    </ReasonDialog>
  );
}

export function UserPage({ id }: { id: number }) {
  const u = useQuery({ queryKey: ['admin', 'user', id], queryFn: () => get<UserDetail>(`/api/admin/users/${id}`) });
  const [act, setAct] = useState<'plan' | 'extend' | 'enable' | 'traffic' | { refund: string; amount: number } | null>(null);
  const [grantGb, setGrantGb] = useState('100');
  const [plan, setPlan] = useState('PRO');
  const [until, setUntil] = useState('');
  const [days, setDays] = useState('7');
  if (u.isLoading) return <Spinner />;
  if (!u.data) return <ErrorNote error={u.error} />;
  const d = u.data;
  const done = () => u.refetch();
  return (
    <div className="space-y-6">
      <Link to="/studio/admin/users" className="text-sm text-muted hover:text-ink">
        All users
      </Link>
      <Panel title={d.email}>
        <div className="grid gap-4 sm:grid-cols-3">
          <div>
            <p className="text-sm text-muted">Plan</p>
            <p className="font-medium">
              {d.plan} {d.subscription && <span className="text-sm font-normal text-muted">({d.subscription.status.toLowerCase()}, ends {date(d.subscription.periodEnd)})</span>}
            </p>
          </div>
          <div>
            <p className="text-sm text-muted">This month</p>
            <p className="tnum">
              {d.hoursThisMonth} h{d.hoursIncluded > 0 ? ` of ${d.hoursIncluded}` : ''}, {bytes(d.storageBytes)} of {bytes(d.storageLimit)}
            </p>
            <p className={clsx('tnum text-sm', d.traffic && d.traffic.step !== 'NONE' ? 'text-warn' : 'text-muted')}>{trafficText(d.traffic)}</p>
          </div>
          <div>
            <p className="text-sm text-muted">Account</p>
            <p>{d.enabled ? 'Active' : 'Disabled'}</p>
          </div>
        </div>
        <div className="mt-5 flex flex-wrap gap-2">
          <Button size="sm" onClick={() => setAct('plan')}>
            Change plan
          </Button>
          <Button size="sm" onClick={() => setAct('extend')} disabled={!d.subscription || d.subscription.status === 'CANCELED'}>
            Extend period
          </Button>
          <Button size="sm" onClick={() => setAct('traffic')} disabled={d.traffic?.allowanceGb == null}>
            Grant streaming data
          </Button>
          <Button size="sm" variant={d.enabled ? 'danger' : 'secondary'} onClick={() => setAct('enable')}>
            {d.enabled ? 'Disable account' : 'Enable account'}
          </Button>
        </div>
      </Panel>
      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="Streams" pad={false}>
          <ul className="divide-y divide-line">
            {d.streams.map((s) => (
              <li key={s.id} className="flex justify-between gap-3 px-4 py-2.5 text-sm">
                <span className="truncate">{s.name}</span>
                <span className="text-muted">{s.live ? `Live on ${s.worker ?? 'unassigned'}` : 'Off air'}</span>
              </li>
            ))}
            {d.streams.length === 0 && <li className="px-4 py-3 text-muted">No streams.</li>}
          </ul>
        </Panel>
        <Panel title="Payments" pad={false}>
          <ul className="divide-y divide-line">
            {d.payments.map((p) => (
              <li key={p.orderId} className="flex flex-wrap items-center gap-3 px-4 py-2.5 text-sm">
                <span className="min-w-0 flex-1">
                  <span className="block truncate">{p.orderId}</span>
                  <span className="text-muted">
                    {p.kind ?? 'Payment'} {p.plan ?? ''}, {dateTime(p.createdAt)}
                  </span>
                </span>
                <span className="tnum">{money(p.amount, p.currency ?? 'INR')}</span>
                <Badge tone={p.status === 'COMPLETED' ? 'ok' : p.status.includes('REFUND') ? 'warn' : 'neutral'}>{p.status}</Badge>
                {p.status === 'COMPLETED' && (
                  <Button size="sm" variant="ghost" onClick={() => setAct({ refund: p.orderId, amount: p.amount })}>
                    Record refund
                  </Button>
                )}
              </li>
            ))}
            {d.payments.length === 0 && <li className="px-4 py-3 text-muted">No payments.</li>}
          </ul>
        </Panel>
      </div>
      <UserSupport id={d.id} />
      <Panel title="Admin history" pad={false}>
        <AuditList items={d.history} />
      </Panel>

      {act === 'plan' && (
        <ReasonDialog title="Change plan" action="Change plan" onClose={() => setAct(null)} onSubmit={(reason) => post(`/api/admin/users/${id}/plan`, { plan, until: plan === 'FREE' ? null : new Date(until).toISOString(), reason }).then(done)}>
          <Field label="Plan" htmlFor="ap">
            <Select id="ap" value={plan} onChange={(e) => setPlan(e.target.value)}>
              {['FREE', 'ESSENTIALS', 'PRO', 'AGENCY'].map((p) => (
                <option key={p}>{p}</option>
              ))}
            </Select>
          </Field>
          {plan !== 'FREE' && (
            <Field label="Until" htmlFor="au">
              <Input id="au" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
            </Field>
          )}
        </ReasonDialog>
      )}
      {act === 'extend' && (
        <ReasonDialog title="Extend the current period" action="Extend" onClose={() => setAct(null)} onSubmit={(reason) => post(`/api/admin/users/${id}/extend`, { days: Number(days), reason }).then(done)}>
          <Field label="Days" htmlFor="ad">
            <Input id="ad" type="number" min={1} max={366} value={days} onChange={(e) => setDays(e.target.value)} />
          </Field>
        </ReasonDialog>
      )}
      {act === 'traffic' && (
        <ReasonDialog title="Grant extra streaming data" action="Grant" onClose={() => setAct(null)} onSubmit={(reason) => post(`/api/admin/users/${id}/traffic-grant`, { gb: Number(grantGb), reason }).then(done)}>
          <p className="text-muted">For this calendar month (UTC) only, on top of the plan's allowance. If that takes them back under it, paused destinations come back on within seconds.</p>
          <Field label="Extra GB" htmlFor="ag">
            <Input id="ag" type="number" min={1} max={100000} value={grantGb} onChange={(e) => setGrantGb(e.target.value)} />
          </Field>
        </ReasonDialog>
      )}
      {act === 'enable' && (
        <ReasonDialog title={d.enabled ? 'Disable this account?' : 'Enable this account?'} action={d.enabled ? 'Disable' : 'Enable'} danger={d.enabled} onClose={() => setAct(null)} onSubmit={(reason) => post(`/api/admin/users/${id}/enabled`, { enabled: !d.enabled, reason }).then(done)}>
          {d.enabled && <p className="text-muted">Their live streams stop and they can't sign in.</p>}
        </ReasonDialog>
      )}
      {act && typeof act === 'object' && (
        <ReasonDialog title="Record a refund" action="Record refund" onClose={() => setAct(null)} onSubmit={(reason) => post(`/api/admin/payments/${act.refund}/refund`, { amount: act.amount, endPlan: false, reason }).then(done)}>
          <p className="text-muted">Refund the money in the payment provider's dashboard first; this records it here. Full amount: {money(act.amount, 'INR')}.</p>
        </ReasonDialog>
      )}
    </div>
  );
}

export function AuditList({ items }: { items: AdminAction[] }) {
  if (items.length === 0) return <p className="px-4 py-3 text-muted">Nothing yet.</p>;
  return (
    <ul className="divide-y divide-line">
      {items.map((a) => (
        <li key={a.id} className="px-4 py-2.5 text-sm">
          <span className="font-medium">{a.action.replace(/_/g, ' ').toLowerCase()}</span>{' '}
          <span className="text-muted">
            {a.targetType} {a.targetId} by {a.adminEmail}, {dateTime(a.createdAt)} ({duration((Date.now() - new Date(a.createdAt).getTime()) / 1000)} ago)
          </span>
          {a.detail && <span className="block text-muted">{a.detail}</span>}
        </li>
      ))}
    </ul>
  );
}

export function Audit() {
  const [page, setPage] = useState(0);
  const log = useQuery({ queryKey: ['admin', 'audit', page], queryFn: () => get<Page<AdminAction>>(`/api/admin/audit?page=${page}`) });
  return (
    <Panel title="Audit log" pad={false}>
      <AuditList items={log.data?.content ?? []} />
      {log.data && log.data.totalPages > 1 && (
        <div className="flex justify-center gap-3 border-t border-line py-3">
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </Button>
          <Button size="sm" disabled={page + 1 >= log.data.totalPages} onClick={() => setPage(page + 1)}>
            Older
          </Button>
        </div>
      )}
    </Panel>
  );
}
