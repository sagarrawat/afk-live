import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { Search, X } from 'lucide-react';
import { useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { HealthDot } from '@/components/status';
import { Button, ErrorNote, Input, Select } from '@/components/ui';
import { get, post } from '@/lib/api';
import { timecode } from '@/lib/format';
import { ReasonDialog } from './ReasonDialog';
import { destinationName, reachReason, reachWord, SLOW_SPEED, speedText, targetFps, worstFirst } from './live-logic';
import type { DestinationHealth, LiveRow } from './types';

export const useLive = (q = '', health = '', worker = '') =>
  useQuery({
    queryKey: ['admin', 'live', q, health, worker],
    queryFn: () => get<LiveRow[]>(`/api/admin/live?q=${encodeURIComponent(q)}&health=${health}${worker ? `&worker=${encodeURIComponent(worker)}` : ''}`),
    refetchInterval: 5000,
  });

/** One destination: its platform and whether it's receiving, in words; the reason when it isn't. */
export function Reach({ d, compact }: { d: DestinationHealth; compact?: boolean }) {
  const word = reachWord(d);
  const why = reachReason(d);
  return (
    <span className="block min-w-0">
      <span className="flex items-center gap-1.5">
        <span aria-hidden className={clsx('h-1.5 w-1.5 shrink-0 rounded-full', word === 'On' ? 'bg-ok' : word === 'Not reaching' ? 'bg-bad' : word === 'Connecting' ? 'bg-warn' : 'bg-muted')} />
        <span className="truncate">{destinationName(d)}</span>
        <span className={clsx('shrink-0', word === 'Not reaching' ? 'font-medium text-bad' : word === 'On' ? 'text-ok' : 'text-muted')}>{word}</span>
      </span>
      {why && (
        <span className={clsx('block text-xs text-muted', compact ? 'line-clamp-2' : 'max-w-[10rem] truncate')} title={why}>
          {why}
        </span>
      )}
    </span>
  );
}

function Speed({ r }: { r: LiveRow }) {
  const slow = r.speed != null && r.speed < SLOW_SPEED;
  return (
    <span className={clsx('tnum', slow && 'text-warn')}>
      {speedText(r.speed)}
      {slow && <span className="ml-1 text-xs">slow</span>}
    </span>
  );
}

function Fps({ r }: { r: LiveRow }) {
  const target = targetFps(r.quality);
  const low = r.fps != null && target != null && r.fps < target * 0.9;
  return (
    <span className={clsx('tnum', low && 'text-warn')}>
      {r.fps == null ? '–' : Math.round(r.fps)}
      {low && <span className="ml-1 text-xs">low</span>}
    </span>
  );
}

/** What Stop or Move acts on. */
export interface RunRef {
  jobId: number;
  name: string;
  userEmail?: string | null;
  worker?: string | null;
}
export type Act = { run: RunRef; kind: 'stop' | 'move' };

/** Stop and Move, apart: Move is routine, Stop ends a customer's stream. Both still ask for a reason. */
export function Actions({ run, onAct, phone }: { run: RunRef; onAct: (a: Act) => void; phone?: boolean }) {
  const stop = (e: MouseEvent) => (e.stopPropagation(), e.preventDefault(), onAct({ run, kind: 'stop' }));
  const move = (e: MouseEvent) => (e.stopPropagation(), e.preventDefault(), onAct({ run, kind: 'move' }));
  return (
    <span className={clsx('flex items-center', phone ? 'justify-between' : 'justify-end gap-2')}>
      <Button size="sm" variant="secondary" onClick={move} disabled={!run.worker} aria-label={`Move ${run.name}`}>
        Move
      </Button>
      {!phone && <span aria-hidden className="h-6 w-px bg-line" />}
      <Button size="sm" variant="danger" onClick={stop} aria-label={`Stop ${run.name}`}>
        Stop
      </Button>
    </span>
  );
}

/** The existing stop / move flows (AD-1): a reason, written to the audit log; Stop also tells the customer. */
export function ActDialog({ act, onClose, onDone }: { act: Act; onClose: () => void; onDone: () => void }) {
  const { run, kind } = act;
  return (
    <ReasonDialog
      title={kind === 'stop' ? `Stop ${run.name}?` : `Move ${run.name} to another worker?`}
      action={kind === 'stop' ? 'Stop stream' : 'Move stream'}
      danger={kind === 'stop'}
      onClose={onClose}
      onSubmit={(reason) => post(`/api/admin/jobs/${run.jobId}/${kind}`, { reason }).then(onDone)}
    >
      <p className="text-muted">
        {kind === 'stop'
          ? `${run.userEmail ?? 'The customer'} is notified with your reason. Their viewers see the stream end.`
          : 'Another worker picks it up within a few seconds; destinations reconnect, so viewers may see a short gap.'}
      </p>
    </ReasonDialog>
  );
}

const ref = (r: LiveRow): RunRef => ({ jobId: r.jobId, name: r.streamName, userEmail: r.userEmail, worker: r.worker });

/** AD-1: every live stream, worst first, with what a viewer would notice (speed, frames, reach). */
export function LivePage() {
  const [q, setQ] = useState('');
  const [health, setHealth] = useState('');
  const [params, setParams] = useSearchParams();
  const worker = params.get('worker') ?? '';
  const live = useLive(q, health, worker);
  const [act, setAct] = useState<Act | null>(null);
  const navigate = useNavigate();
  const rows = worstFirst(live.data ?? []);
  const open = (r: LiveRow) => navigate(`/studio/admin/runs/${r.jobId}`);
  const onKey = (r: LiveRow) => (e: KeyboardEvent) => {
    if (e.key === 'Enter' && e.target === e.currentTarget) open(r);
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-display text-xl font-bold">
          Live <span className="tnum font-sans text-md font-normal text-muted">{live.data ? rows.length : ''}</span>
        </h1>
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
          <Input className="pl-9" placeholder="Email, stream or run id" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter live streams" />
        </div>
        <Select className="w-full sm:w-40" value={health} onChange={(e) => setHealth(e.target.value)} aria-label="Health">
          <option value="">Any health</option>
          <option value="red">Problem</option>
          <option value="amber">Needs a look</option>
          <option value="green">Healthy</option>
        </Select>
      </div>
      {worker && (
        <p className="flex items-center gap-2 text-sm">
          <span className="text-muted">On worker</span>
          <span className="font-medium">{worker}</span>
          <button type="button" aria-label="Show every worker" onClick={() => setParams({})} className="rounded p-1 text-muted hover:bg-raised hover:text-ink">
            <X className="h-4 w-4" />
          </button>
        </p>
      )}
      <ErrorNote error={live.error} />

      {/* Wide screens: one dense table. */}
      <div className="hidden overflow-x-auto rounded-md border border-line bg-panel md:block">
        <table className="w-full text-sm">
          <thead className="border-b border-line text-left text-xs text-muted">
            <tr>
              <th className="px-2.5 py-2 font-medium">Health</th>
              <th className="px-2.5 py-2 font-medium">Stream</th>
              <th className="px-2.5 py-2 text-right font-medium">Up</th>
              <th className="px-2.5 py-2 text-right font-medium">Speed</th>
              <th className="px-2.5 py-2 text-right font-medium">FPS</th>
              <th className="px-2.5 py-2 font-medium">Quality</th>
              <th className="px-2.5 py-2 font-medium">Plan</th>
              <th className="px-2.5 py-2 font-medium">Platforms</th>
              <th className="px-2.5 py-2 font-medium">Worker</th>
              <th className="sticky right-0 bg-panel px-2.5 py-2">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((r) => (
              <tr key={r.jobId} tabIndex={0} onClick={() => open(r)} onKeyDown={onKey(r)} className="cursor-pointer align-top hover:bg-raised/60 focus-visible:bg-raised/60">
                <td className="whitespace-nowrap px-2.5 py-2">
                  <HealthDot level={r.health} withText />
                </td>
                <td className="max-w-[14rem] px-2.5 py-2">
                  <Link to={`/studio/admin/runs/${r.jobId}`} onClick={(e) => e.stopPropagation()} className="block truncate font-medium hover:underline">
                    {r.streamName}
                  </Link>
                  <Link to={`/studio/admin/users/${r.userId}`} onClick={(e) => e.stopPropagation()} className="block truncate text-xs text-muted hover:underline">
                    {r.userEmail}
                  </Link>
                  {r.problem && (
                    <span className="mt-0.5 line-clamp-2 text-xs text-warn" title={r.problem}>
                      {r.problem}
                    </span>
                  )}
                  {!r.problem && r.lastError && <span className="mt-0.5 block truncate text-xs text-muted" title={r.lastError}>{r.lastError}</span>}
                </td>
                <td className="tnum whitespace-nowrap px-2.5 py-2 text-right">{timecode(r.uptimeSeconds)}</td>
                <td className="whitespace-nowrap px-2.5 py-2 text-right">
                  <Speed r={r} />
                </td>
                <td className="whitespace-nowrap px-2.5 py-2 text-right">
                  <Fps r={r} />
                </td>
                <td className="tnum whitespace-nowrap px-2.5 py-2">{r.quality ?? '–'}</td>
                <td className="whitespace-nowrap px-2.5 py-2 text-xs text-muted">{r.plan}</td>
                <td className="px-2.5 py-2">
                  <span className="flex min-w-[8rem] flex-col gap-1">
                    {(r.destinationHealth ?? []).map((d, i) => (
                      <Reach key={i} d={d} />
                    ))}
                    {(r.destinationHealth ?? []).length === 0 && <span className="text-muted">None</span>}
                  </span>
                </td>
                <td className="whitespace-nowrap px-2.5 py-2">
                  <span className="block">{r.worker ?? 'Unassigned'}</span>
                  <span className="tnum text-xs text-muted">{r.heartbeatAgeSeconds == null ? '' : `${r.heartbeatAgeSeconds}s ago`}</span>
                </td>
                <td className="sticky right-0 whitespace-nowrap bg-panel px-2.5 py-2 shadow-[-8px_0_8px_-8px_rgb(0_0_0/0.5)]">
                  <Actions run={ref(r)} onAct={setAct} />
                </td>
              </tr>
            ))}
            {live.data?.length === 0 && (
              <tr>
                <td colSpan={10} className="px-3 py-8 text-center text-muted">
                  {q || health || worker ? 'No live streams match.' : 'Nothing is live right now.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phones (on call): compact rows, Move and Stop at opposite ends. */}
      <ul className="divide-y divide-line rounded-md border border-line bg-panel md:hidden" aria-label="Live streams">
        {rows.map((r) => (
          <li key={r.jobId} className="space-y-2 px-3 py-3">
            <Link to={`/studio/admin/runs/${r.jobId}`} className="block">
              <span className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate font-medium">{r.streamName}</span>
                <HealthDot level={r.health} withText />
              </span>
              <span className="block truncate text-xs text-muted">{r.userEmail}</span>
              <span className="tnum mt-1 flex flex-wrap gap-x-3 text-xs text-muted">
                <span>Up {timecode(r.uptimeSeconds)}</span>
                <span>
                  Speed <Speed r={r} />
                </span>
                <span>
                  FPS <Fps r={r} />
                </span>
                <span>{r.plan}</span>
              </span>
            </Link>
            {(r.destinationHealth ?? []).length > 0 && (
              <span className="flex flex-col gap-1 text-sm">
                {(r.destinationHealth ?? []).map((d, i) => (
                  <Reach key={i} d={d} compact />
                ))}
              </span>
            )}
            <Actions run={ref(r)} onAct={setAct} phone />
          </li>
        ))}
        {live.data?.length === 0 && <li className="px-3 py-6 text-center text-muted">{q || health || worker ? 'No live streams match.' : 'Nothing is live right now.'}</li>}
      </ul>

      {act && <ActDialog act={act} onClose={() => setAct(null)} onDone={() => live.refetch()} />}
    </div>
  );
}
