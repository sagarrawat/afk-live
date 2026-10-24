import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { HealthDot } from '@/components/status';
import { ErrorNote, Segmented, Spinner } from '@/components/ui';
import { get } from '@/lib/api';
import { bytes } from '@/lib/format';
import { CostLine, Incidents, Quota, type CostView } from '../Admin';
import { HistoryChart } from './HistoryChart';
import { useHistory } from './OverviewPage';
import { EmailPanel } from './Support';
import type { ServerHistory, WorkerRow } from './types';

const RANGES = [
  { value: '6', label: '6 hours' },
  { value: '24', label: '24 hours' },
  { value: '168', label: '7 days' },
] as const;

function WorkerCard({ w, history, hours }: { w: WorkerRow; history?: ServerHistory; hours: number }) {
  const memPct = w.memoryUsedBytes != null && w.memoryTotalBytes ? Math.round((w.memoryUsedBytes / w.memoryTotalBytes) * 100) : null;
  return (
    <li className="min-w-0 rounded-md border border-line bg-panel">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line px-4 py-3">
        <HealthDot level={w.healthy ? 'green' : 'red'} withText />
        <span className="min-w-0 truncate font-medium">{w.worker}</span>
        {w.draining ? <span className="shrink-0 rounded bg-raised px-1.5 py-0.5 text-xs text-muted">Being replaced</span> : null}
        <span className="tnum ml-auto text-sm text-muted">
          <Link to={`/studio/admin/live?worker=${encodeURIComponent(w.worker)}`} className="hover:text-ink">
            {w.streams}
            {w.capacity ? ` of ${w.capacity}` : ''} stream{w.streams === 1 && !w.capacity ? '' : 's'}
          </Link>
          , last report {w.lastHeartbeatAgeSeconds < 0 ? 'never' : `${w.lastHeartbeatAgeSeconds}s ago`}
        </span>
      </div>
      <div className="grid gap-5 px-4 py-4 sm:grid-cols-2">
        {history && history.points.length > 0 ? (
          <>
            <HistoryChart points={history.points} metric="cpu" hours={hours} bucketSeconds={history.bucketSeconds} label="CPU" />
            <HistoryChart points={history.points} metric="memory" hours={hours} bucketSeconds={history.bucketSeconds} label="Memory" />
          </>
        ) : (
          <p className="text-sm text-muted sm:col-span-2">
            No history yet: samples start a minute after this version is deployed.
            {w.cpuPercent != null && <span className="tnum"> Now: CPU {Math.round(w.cpuPercent)}%{memPct != null ? `, memory ${memPct}%` : ''}.</span>}
          </p>
        )}
      </div>
      {w.memoryTotalBytes ? (
        <p className="tnum border-t border-line px-4 py-2 text-xs text-muted">
          Memory now {bytes(w.memoryUsedBytes)} of {bytes(w.memoryTotalBytes)}
        </p>
      ) : null}
    </li>
  );
}

/** AD-2: every worker with its CPU and memory over time, then the platform's running costs and services. */
export function ServersPage() {
  const [range, setRange] = useState<(typeof RANGES)[number]['value']>('24');
  const hours = Number(range);
  const workers = useQuery({ queryKey: ['admin', 'workers'], queryFn: () => get<WorkerRow[]>('/api/admin/workers'), refetchInterval: 10000 });
  const history = useHistory(hours);
  const cost = useQuery({ queryKey: ['admin', 'cost'], queryFn: () => get<CostView>('/api/admin/cost'), refetchInterval: 300000 });
  const byWorker = new Map((history.data ?? []).map((h) => [h.worker, h]));
  const list = (workers.data ?? []).filter((w) => w.worker !== '(unassigned)');
  const unassigned = (workers.data ?? []).find((w) => w.worker === '(unassigned)');
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="mr-auto font-display text-xl font-bold">Servers</h1>
        <Segmented label="Time range" value={range} onChange={setRange} options={RANGES.map((r) => ({ ...r }))} />
      </div>
      {workers.isLoading && <Spinner />}
      <ErrorNote error={workers.error ?? history.error} />
      {unassigned && unassigned.streams > 0 && (
        <p className="rounded-md border border-warn/40 bg-warn/10 px-4 py-2.5 text-sm">
          <span className="font-medium text-warn">Needs a look: </span>
          {unassigned.streams} stream{unassigned.streams === 1 ? '' : 's'} waiting for a worker.
        </p>
      )}
      <ul className={clsx('grid gap-4', list.length > 1 && '2xl:grid-cols-2')}>
        {list.map((w) => (
          <WorkerCard key={w.worker} w={w} history={byWorker.get(w.worker)} hours={hours} />
        ))}
        {workers.data && list.length === 0 && <li className="rounded-md border border-line bg-panel px-4 py-3 text-muted">No workers have reported in the last hour.</li>}
      </ul>
      {cost.data && (
        <p className="text-sm">
          <CostLine cost={cost.data} />
        </p>
      )}
      <div className="grid gap-6 xl:grid-cols-2">
        <div id="email" className="min-w-0">
          <EmailPanel />
        </div>
        <div className="min-w-0">
          <Quota />
        </div>
      </div>
      <Incidents />
    </div>
  );
}
