import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { Link, NavLink, Navigate, useParams } from 'react-router-dom';
import { HealthDot, LiveBadge } from '@/components/status';
import { Badge, Button, Dialog, ErrorNote, Field, Input, PageHeader, Panel, Select, Spinner, Textarea, useAction } from '@/components/ui';
import { get, post } from '@/lib/api';
import { bytes, date, dateTime, duration, money, timecode } from '@/lib/format';
import { useMe } from '@/lib/queries';
import type { Level, LiveStateName, Page } from '@/lib/types';

interface LiveRow {
  jobId: number;
  streamId?: number | null;
  streamName: string;
  userEmail: string;
  userId: number;
  plan: string;
  worker?: string | null;
  heartbeatAgeSeconds?: number | null;
  status: string;
  health: Level;
  uptimeSeconds: number;
  nowPlaying?: string | null;
  restarts: number;
  bitrateKbps?: number | null;
  lastError?: string | null;
}
interface WorkerRow {
  worker: string;
  streams: number;
  lastHeartbeatAgeSeconds: number;
  healthy: boolean;
}
interface UserRow {
  id: number;
  email: string;
  name?: string | null;
  plan: string;
  enabled: boolean;
  liveStreams: number;
  storageBytes: number;
}
interface AdminAction {
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
}

/** AD-1 / AD-3: the operator console. */
export function Admin() {
  const { data: me } = useMe();
  const { section = 'live', id } = useParams();
  if (me && !me.admin) return <Navigate to="/studio" replace />;
  return (
    <>
      <PageHeader title="Admin" description="Every action asks for a reason and is written to the audit log." />
      <nav className="mb-6 flex gap-1 border-b border-line">
        {[
          ['live', 'Live streams'],
          ['users', 'Users'],
          ['audit', 'Audit log'],
        ].map(([s, label]) => (
          <NavLink
            key={s}
            to={`/studio/admin/${s}`}
            className={() => clsx('-mb-px border-b-2 px-3 py-2.5 text-sm', section === s ? 'border-accent font-medium' : 'border-transparent text-muted hover:text-ink')}
          >
            {label}
          </NavLink>
        ))}
      </nav>
      {section === 'live' && <LiveStreams />}
      {section === 'users' && (id ? <UserPage id={Number(id)} /> : <Users />)}
      {section === 'audit' && <Audit />}
    </>
  );
}

function ReasonDialog({ title, action, onClose, onSubmit, danger, children }: { title: string; action: string; onClose: () => void; onSubmit: (reason: string) => Promise<unknown>; danger?: boolean; children?: React.ReactNode }) {
  const [reason, setReason] = useState('');
  const { busy, run } = useAction();
  return (
    <Dialog
      open
      onClose={onClose}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} busy={busy} disabled={reason.trim().length < 3} onClick={async () => (await run(() => onSubmit(reason.trim()), 'Done.')) && onClose()}>
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

function LiveStreams() {
  const [q, setQ] = useState('');
  const [health, setHealth] = useState('');
  const live = useQuery({ queryKey: ['admin', 'live', q, health], queryFn: () => get<LiveRow[]>(`/api/admin/live?q=${encodeURIComponent(q)}&health=${health}`), refetchInterval: 5000 });
  const workers = useQuery({ queryKey: ['admin', 'workers'], queryFn: () => get<WorkerRow[]>('/api/admin/workers'), refetchInterval: 10000 });
  const [act, setAct] = useState<{ row: LiveRow; kind: 'stop' | 'move' } | null>(null);
  return (
    <div className="space-y-6">
      <Panel title="Workers" pad={false}>
        <ul className="grid gap-px bg-line sm:grid-cols-2 lg:grid-cols-4">
          {(workers.data ?? []).map((w) => (
            <li key={w.worker} className="bg-panel px-4 py-3">
              <span className="flex items-center gap-2 font-medium">
                <HealthDot level={w.healthy ? 'green' : 'red'} />
                <span className="truncate">{w.worker}</span>
              </span>
              <span className="tnum text-sm text-muted">
                {w.streams} stream{w.streams === 1 ? '' : 's'}, last report {w.lastHeartbeatAgeSeconds < 0 ? 'never' : `${w.lastHeartbeatAgeSeconds}s ago`}
              </span>
            </li>
          ))}
          {workers.data?.length === 0 && <li className="bg-panel px-4 py-3 text-muted">No streams running.</li>}
        </ul>
      </Panel>
      <div className="flex flex-wrap gap-3">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
          <Input className="pl-9" placeholder="Email, stream name or run id" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search live streams" />
        </div>
        <Select className="w-40" value={health} onChange={(e) => setHealth(e.target.value)} aria-label="Health">
          <option value="">Any health</option>
          <option value="red">Problem</option>
          <option value="amber">Needs a look</option>
          <option value="green">Healthy</option>
        </Select>
      </div>
      <ErrorNote error={live.error} />
      <div className="overflow-x-auto rounded-md border border-line bg-panel">
        <table className="w-full text-sm">
          <thead className="border-b border-line text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">Stream</th>
              <th className="px-3 py-2 font-medium">State</th>
              <th className="px-3 py-2 font-medium">Up</th>
              <th className="px-3 py-2 font-medium">Worker</th>
              <th className="px-3 py-2 font-medium">Detail</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {(live.data ?? []).map((r) => (
              <tr key={r.jobId}>
                <td className="max-w-[220px] px-3 py-2">
                  <span className="block truncate font-medium">{r.streamName}</span>
                  <Link to={`/studio/admin/users/${r.userId}`} className="block truncate text-muted hover:underline">
                    {r.userEmail}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  <span className="flex items-center gap-2">
                    <LiveBadge state={r.status as LiveStateName} />
                    <HealthDot level={r.health} />
                  </span>
                </td>
                <td className="tnum px-3 py-2">{timecode(r.uptimeSeconds)}</td>
                <td className="px-3 py-2">
                  <span className="block truncate">{r.worker ?? 'Unassigned'}</span>
                  <span className="tnum text-xs text-muted">{r.heartbeatAgeSeconds == null ? '' : `${r.heartbeatAgeSeconds}s ago`}</span>
                </td>
                <td className="max-w-[240px] px-3 py-2 text-muted">
                  <span className="block truncate">{r.lastError || r.nowPlaying || '–'}</span>
                  <span className="tnum text-xs">
                    {r.bitrateKbps ? `${Math.round(r.bitrateKbps)} kbps` : ''} {r.restarts ? `${r.restarts} restarts` : ''}
                  </span>
                </td>
                <td className="whitespace-nowrap px-3 py-2 text-right">
                  <Button size="sm" variant="ghost" onClick={() => setAct({ row: r, kind: 'move' })} disabled={!r.worker}>
                    Move
                  </Button>
                  <Button size="sm" variant="danger" onClick={() => setAct({ row: r, kind: 'stop' })}>
                    Stop
                  </Button>
                </td>
              </tr>
            ))}
            {live.data?.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-muted">
                  No live streams match.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {act && (
        <ReasonDialog
          title={act.kind === 'stop' ? `Stop ${act.row.streamName}?` : `Move ${act.row.streamName} to another worker?`}
          action={act.kind === 'stop' ? 'Stop stream' : 'Move stream'}
          danger={act.kind === 'stop'}
          onClose={() => setAct(null)}
          onSubmit={(reason) => post(`/api/admin/jobs/${act.row.jobId}/${act.kind}`, { reason }).then(() => live.refetch())}
        >
          <p className="text-muted">
            {act.kind === 'stop'
              ? `${act.row.userEmail} is notified with your reason.`
              : 'Another worker picks it up within a few seconds; destinations reconnect, so viewers may see a short gap.'}
          </p>
        </ReasonDialog>
      )}
    </div>
  );
}

function Users() {
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const users = useQuery({ queryKey: ['admin', 'users', q, page], queryFn: () => get<Page<UserRow>>(`/api/admin/users?q=${encodeURIComponent(q)}&page=${page}`) });
  return (
    <div className="space-y-4">
      <Input placeholder="Search by email or name" value={q} onChange={(e) => (setQ(e.target.value), setPage(0))} aria-label="Search users" />
      <ul className="divide-y divide-line rounded-md border border-line bg-panel">
        {(users.data?.content ?? []).map((u) => (
          <li key={u.id}>
            <Link to={`/studio/admin/users/${u.id}`} className="flex flex-wrap items-center gap-3 px-4 py-3 hover:bg-raised">
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{u.email}</span>
                <span className="block truncate text-sm text-muted">{u.name}</span>
              </span>
              <Badge>{u.plan}</Badge>
              {u.liveStreams > 0 && <Badge tone="live">{u.liveStreams} live</Badge>}
              {!u.enabled && <Badge tone="bad">Disabled</Badge>}
              <span className="tnum text-sm text-muted">{bytes(u.storageBytes)}</span>
            </Link>
          </li>
        ))}
      </ul>
      {users.data && users.data.totalPages > 1 && (
        <div className="flex justify-center gap-3">
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button size="sm" disabled={page + 1 >= users.data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}
    </div>
  );
}

function UserPage({ id }: { id: number }) {
  const u = useQuery({ queryKey: ['admin', 'user', id], queryFn: () => get<UserDetail>(`/api/admin/users/${id}`) });
  const [act, setAct] = useState<'plan' | 'extend' | 'enable' | { refund: string; amount: number } | null>(null);
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

function AuditList({ items }: { items: AdminAction[] }) {
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

function Audit() {
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
