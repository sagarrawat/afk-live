import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNote, Panel, Spinner } from '@/components/ui';
import { get } from '@/lib/api';
import { duration } from '@/lib/format';
import { OverviewPanel } from '../Admin';
import { HistoryChart } from './HistoryChart';
import { ActDialog, Actions, useLive, type Act, type RunRef } from './LivePage';
import type { Attention, ConsoleOverview, ServerHistory } from './types';

export const useAttention = () =>
  useQuery({ queryKey: ['admin', 'attention'], queryFn: () => get<ConsoleOverview>('/api/admin/attention'), refetchInterval: 15000 });

export const useHistory = (hours: number) =>
  useQuery({ queryKey: ['admin', 'history', hours], queryFn: () => get<ServerHistory[]>(`/api/admin/servers/history?hours=${hours}`), refetchInterval: 60000 });

const LEVEL_WORD = { red: 'Problem', amber: 'Needs a look' } as const;

/** "for 12 min" from when the problem started; empty when unknown. */
export function sinceText(since: string | null | undefined, now = Date.now()): string {
  if (!since) return '';
  const s = Math.max(0, (now - new Date(since).getTime()) / 1000);
  return s < 60 ? 'just now' : `for ${duration(s)}`;
}

function AttentionItem({ a, run, onAct }: { a: Attention; run?: RunRef; onAct: (x: Act) => void }) {
  const body = (
    <>
      <span className="flex flex-wrap items-baseline gap-x-2">
        <span className={clsx('text-xs font-semibold', a.level === 'red' ? 'text-bad' : 'text-warn')}>{LEVEL_WORD[a.level]}</span>
        <span className="font-medium">{a.title}</span>
        {a.since && <span className="tnum text-xs text-muted">{sinceText(a.since)}</span>}
      </span>
      {a.detail && <span className="mt-0.5 block break-words text-sm text-muted">{a.detail}</span>}
    </>
  );
  return (
    <li className={clsx('flex flex-col gap-2 border-l-[3px] py-2.5 pl-3 pr-3 sm:flex-row sm:items-start', a.level === 'red' ? 'border-l-bad' : 'border-l-warn')}>
      {a.link ? (
        <Link to={a.link} className="min-w-0 flex-1 rounded hover:text-ink">
          {body}
        </Link>
      ) : (
        <span className="min-w-0 flex-1">{body}</span>
      )}
      {run && (
        <span className="shrink-0 sm:w-48">
          <Actions run={run} onAct={onAct} phone />
        </span>
      )}
    </li>
  );
}

/** One bar split by health, with the counts in words beside it. */
export function HealthBar({ health }: { health: ConsoleOverview['health'] }) {
  const total = health.green + health.amber + health.red;
  const parts = [
    { n: health.red, word: 'Problem', bar: 'bg-bad' },
    { n: health.amber, word: 'Needs a look', bar: 'bg-warn' },
    { n: health.green, word: 'Healthy', bar: 'bg-ok' },
  ];
  return (
    <div>
      <div className="flex h-2.5 overflow-hidden rounded-full bg-raised" aria-hidden>
        {total > 0 && parts.map((p) => p.n > 0 && <span key={p.word} className={p.bar} style={{ width: `${(p.n / total) * 100}%` }} />)}
      </div>
      <ul className="mt-2.5 space-y-1 text-sm">
        {parts.map((p) => (
          <li key={p.word} className="flex items-center justify-between gap-3">
            <span className="flex items-center gap-2">
              <span aria-hidden className={clsx('h-2 w-2 rounded-full', p.bar)} />
              {p.word}
            </span>
            <span className="tnum font-medium">{p.n}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ServersGlance() {
  const h = useHistory(24);
  if (h.isLoading) return <Spinner />;
  if (!h.data) return <ErrorNote error={h.error} />;
  if (h.data.length === 0) return <p className="text-sm text-muted">No servers have reported in the last day.</p>;
  return (
    <ul className="space-y-4">
      {h.data.map((s) => (
        <li key={s.worker}>
          <p className="mb-1.5 truncate text-sm font-medium">{s.worker}</p>
          <div className="grid grid-cols-2 gap-3">
            <HistoryChart points={s.points} metric="cpu" hours={24} bucketSeconds={s.bucketSeconds} compact label="CPU" />
            <HistoryChart points={s.points} metric="memory" hours={24} bucketSeconds={s.bucketSeconds} compact label="Memory" />
          </div>
        </li>
      ))}
    </ul>
  );
}

/** The admin console's front page: what's wrong now, then the shape of the platform. */
export function OverviewPage() {
  const o = useAttention();
  const live = useLive();
  const [act, setAct] = useState<Act | null>(null);
  const runs = new Map<number, RunRef>((live.data ?? []).map((r) => [r.jobId, { jobId: r.jobId, name: r.streamName, userEmail: r.userEmail, worker: r.worker }]));
  const d = o.data;
  return (
    <div className="space-y-6">
      <h1 className="font-display text-xl font-bold">Overview</h1>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <section aria-labelledby="attention-h" className="min-w-0 self-start rounded-md border border-line bg-panel">
          <header className="flex items-baseline justify-between gap-3 border-b border-line px-4 py-3">
            <h2 id="attention-h" className="text-md font-semibold">
              Needs attention now
            </h2>
            {d && <span className="tnum text-sm text-muted">{d.attention.length === 0 ? 'All clear' : `${d.attention.length} item${d.attention.length === 1 ? '' : 's'}`}</span>}
          </header>
          {o.isLoading && <Spinner />}
          <ErrorNote error={o.error} />
          {d && d.attention.length === 0 && (
            <div className="px-4 py-8">
              <p className="font-medium">Nothing needs you right now.</p>
              <p className="mt-1 text-sm text-muted">
                {d.liveStreams === 0 ? 'Nothing is live.' : `${d.liveStreams} stream${d.liveStreams === 1 ? '' : 's'} live, every platform receiving, no failed payments.`}
              </p>
              <div aria-hidden className="mt-5 h-px bg-ok/60" />
            </div>
          )}
          {d && d.attention.length > 0 && (
            <ul className="divide-y divide-line">
              {d.attention.map((a, i) => (
                <AttentionItem key={`${a.kind}-${a.jobId ?? a.userId ?? ''}-${i}`} a={a} run={a.jobId != null ? runs.get(a.jobId) : undefined} onAct={setAct} />
              ))}
            </ul>
          )}
        </section>

        <div className="min-w-0 space-y-6">
          <Panel title="Streams by health" action={<Link to="/studio/admin/live" className="text-sm text-muted hover:text-ink">Live</Link>}>
            {d ? <HealthBar health={d.health} /> : <Spinner />}
          </Panel>
          <Panel title="Today (UTC)" action={<Link to="/studio/admin/growth" className="text-sm text-muted hover:text-ink">Growth</Link>}>
            {d ? (
              <dl className="grid grid-cols-2 gap-4">
                <div>
                  <dt className="text-sm text-muted">Sign-ups</dt>
                  <dd className="tnum text-xl font-semibold">{d.signUpsToday.toLocaleString()}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted">First Go live</dt>
                  <dd className="tnum text-xl font-semibold">{d.firstGoLivesToday.toLocaleString()}</dd>
                </div>
              </dl>
            ) : (
              <Spinner />
            )}
          </Panel>
          <Panel title="Servers, last 24 hours" action={<Link to="/studio/admin/servers" className="text-sm text-muted hover:text-ink">Servers</Link>}>
            <ServersGlance />
          </Panel>
        </div>
      </div>
      <OverviewPanel />
      {act && (
        <ActDialog
          act={act}
          onClose={() => setAct(null)}
          onDone={() => {
            o.refetch();
            live.refetch();
          }}
        />
      )}
    </div>
  );
}
