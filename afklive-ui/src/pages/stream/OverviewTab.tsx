import clsx from 'clsx';
import { SkipForward, Smartphone, WifiOff } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { HealthDot, isOnAir } from '@/components/status';
import { Badge, Button, Panel, Toggle } from '@/components/ui';
import { post } from '@/lib/api';
import { clock, dateTime, duration } from '@/lib/format';
import { useRuns, useUptime } from '@/lib/queries';
import type { StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

const SCENES = [
  ['STARTING', 'Starting soon'],
  ['LIVE', 'Live'],
  ['BRB', 'Be right back'],
  ['ENDING', 'Ending'],
] as const;

export function OverviewTab({ stream: s }: { stream: StreamView }) {
  const onAir = isOnAir(s.live.state);
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <div className="space-y-6">
        {s.source === 'PHONE' ? <PhoneControl stream={s} /> : <NowPlaying stream={s} />}
        {onAir && s.overlays.length > 0 && <OverlayToggles stream={s} />}
        <UptimePanel stream={s} />
        <RecentRuns stream={s} />
      </div>
      <div className="space-y-6">
        <HealthPanel stream={s} />
        <DestinationsPanel stream={s} />
      </div>
    </div>
  );
}

/** Elapsed time that keeps moving between polls. */
function useElapsed(base: number | undefined, running: boolean) {
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    setOffset(0);
    if (!running) return;
    const started = Date.now();
    const t = setInterval(() => setOffset(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => clearInterval(t);
  }, [base, running]);
  return (base ?? 0) + offset;
}

function NowPlaying({ stream: s }: { stream: StreamView }) {
  const onAir = isOnAir(s.live.state);
  const np = s.live.nowPlaying;
  const elapsed = useElapsed(np?.elapsed, onAir && !!np);
  const { busy, update } = useStreamUpdate(s.id);
  const total = np?.duration ?? 0;
  const pct = total > 0 ? Math.min(100, (elapsed / total) * 100) : 0;
  const next = Array.isArray(np?.next) ? np!.next! : [];

  if (!onAir) {
    return (
      <Panel title="Playlist">
        {s.playlist.length === 0 ? (
          <p className="text-muted">
            No videos yet. <Link to={`/studio/streams/${s.id}/playlist`} className="text-accent hover:underline">Add videos to the playlist</Link> to go live.
          </p>
        ) : (
          <p className="text-muted">
            {s.playlist.length} video{s.playlist.length === 1 ? '' : 's'}, {duration(s.totalRuntimeSeconds)} per pass,{' '}
            {s.playbackOrder === 'SHUFFLE' ? 'shuffled' : 'in order'}
            {s.loopCount > 0 ? `, ${s.loopCount} pass${s.loopCount === 1 ? '' : 'es'} then stop` : ', repeating forever'}.
          </p>
        )}
      </Panel>
    );
  }
  return (
    <Panel
      title="Now playing"
      action={
        <Button size="sm" variant="ghost" busy={busy} icon={<SkipForward className="h-4 w-4" />} onClick={() => update(() => post(`/api/streams/${s.id}/skip`), 'Skipping to the next video.')}>
          Skip
        </Button>
      }
    >
      {np?.title ? (
        <>
          <p className="truncate text-lg font-semibold">{np.title}</p>
          <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-raised" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label="Progress through this video">
            <div className="h-full bg-accent transition-[width] duration-1000 ease-linear" style={{ width: `${pct}%` }} />
          </div>
          <p className="tnum mt-1.5 flex justify-between text-sm text-muted">
            <span>{clock(elapsed)}</span>
            <span>{clock(total)}</span>
          </p>
          {next.length > 0 && (
            <div className="mt-4">
              <p className="text-sm font-medium text-muted">Up next</p>
              <ol className="mt-1.5 space-y-1">
                {next.slice(0, 3).map((n, i) => (
                  <li key={`${n.mediaId}-${i}`} className="truncate text-sm">
                    {n.title}
                  </li>
                ))}
              </ol>
            </div>
          )}
        </>
      ) : (
        <p className="text-muted">Starting the first video…</p>
      )}
    </Panel>
  );
}

function PhoneControl({ stream: s }: { stream: StreamView }) {
  const onAir = isOnAir(s.live.state);
  const { busy, update } = useStreamUpdate(s.id);
  const connected = s.live.inputLive === true;
  return (
    <Panel title="Your phone">
      <div className="flex items-center gap-3">
        <span className={clsx('grid h-10 w-10 place-items-center rounded-full', onAir ? (connected ? 'bg-ok/15 text-ok' : 'bg-warn/15 text-warn') : 'bg-raised text-muted')}>
          {onAir && !connected ? <WifiOff className="h-5 w-5" /> : <Smartphone className="h-5 w-5" />}
        </span>
        <div className="min-w-0">
          <p className="font-medium">
            {!onAir ? 'Not live' : connected ? 'Connected and on air' : 'Waiting for your phone'}
          </p>
          <p className="text-sm text-muted">
            {!onAir
              ? s.phone?.lastConnectedAt
                ? `Last connected ${dateTime(s.phone.lastConnectedAt, s.timeZone)}.`
                : 'Go live here, then start streaming in your phone app.'
              : connected
                ? 'Viewers see your phone with your overlays.'
                : 'Viewers see your BRB scene with music until your phone reconnects.'}
          </p>
        </div>
      </div>
      {onAir && (
        <div className="mt-5">
          <p className="mb-2 text-sm font-medium">Scene</p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {SCENES.map(([id, label]) => {
              const active = (s.live.scene ?? 'LIVE') === id;
              return (
                <button
                  key={id}
                  disabled={busy}
                  aria-pressed={active}
                  onClick={() => update(() => post(`/api/streams/${s.id}/scene`, { scene: id }))}
                  className={clsx('h-12 rounded border text-sm font-medium', active ? 'border-accent bg-accent/15 text-ink' : 'border-line text-muted hover:border-muted/60 hover:text-ink')}
                >
                  {label}
                </button>
              );
            })}
          </div>
          <p className="mt-3 text-sm text-muted">
            On another device? Open the <a className="text-accent hover:underline" href={`/remote.html?stream=${s.id}`}>remote control</a>.
          </p>
        </div>
      )}
      {!onAir && (
        <p className="mt-4 text-sm">
          <Link to={`/studio/streams/${s.id}/phone`} className="text-accent hover:underline">
            Set up your phone app
          </Link>
        </p>
      )}
    </Panel>
  );
}

function OverlayToggles({ stream: s }: { stream: StreamView }) {
  const { update } = useStreamUpdate(s.id);
  return (
    <Panel title="Show on stream">
      <ul className="divide-y divide-line">
        {s.overlays
          .filter((o) => o.id !== 'afk-watermark')
          .map((o) => (
            <li key={o.id} className="flex items-center justify-between gap-3 py-2.5">
              <span className="min-w-0 truncate">{overlayName(o)}</span>
              <Toggle label={`Show ${overlayName(o)}`} checked={o.visible} onChange={(v) => update(() => post(`/api/streams/${s.id}/overlays/${encodeURIComponent(o.id)}/visible`, { visible: v }))} />
            </li>
          ))}
      </ul>
    </Panel>
  );
}

export function overlayName(o: { type: string; text?: string | null; id: string }): string {
  if (o.type === 'TEXT' || o.type === 'TICKER') return o.text?.trim() ? `${o.type === 'TICKER' ? 'Ticker' : 'Text'}: ${o.text}` : 'Text';
  if (o.type === 'CLOCK') return 'Clock';
  if (o.type === 'COUNTDOWN') return 'Countdown';
  if (o.type === 'IMAGE') return 'Image';
  return o.id;
}

function HealthPanel({ stream: s }: { stream: StreamView }) {
  const onAir = isOnAir(s.live.state);
  const h = s.live.health;
  return (
    <Panel title="Health" action={onAir && h ? <HealthDot level={h.overall} withText /> : undefined}>
      {!onAir || !h ? (
        <p className="text-muted">Health shows here while the stream is on air: picture, sound, speed and every destination.</p>
      ) : h.metrics.length === 0 ? (
        <p className="text-muted">Everything looks good.</p>
      ) : (
        <ul className="space-y-3">
          {h.metrics.map((m) => (
            <li key={m.name} className="flex items-start gap-2.5">
              <span className="mt-0.5">
                <HealthDot level={m.level} />
              </span>
              <span className="min-w-0">
                <span className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium capitalize">{m.name}</span>
                  <span className="tnum text-sm text-muted">{m.value}</span>
                </span>
                {m.level !== 'green' && <span className="block text-sm text-muted">{m.explanation}</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function DestinationsPanel({ stream: s }: { stream: StreamView }) {
  const onAir = isOnAir(s.live.state);
  const health = s.live.health?.destinations ?? [];
  const enabled = s.destinations.filter((d) => d.enabled);
  return (
    <Panel title="Destinations" action={<Link to={`/studio/streams/${s.id}/destinations`} className="text-sm text-accent hover:underline">Change</Link>}>
      {onAir && health.length > 0 ? (
        <ul className="space-y-2.5">
          {health.map((d) => (
            <li key={d.name} className="flex items-start justify-between gap-3">
              <span className="min-w-0">
                <span className="block truncate font-medium">{d.name}</span>
                {(d.error || d.restarts > 0) && (
                  <span className="block text-sm text-muted">
                    {d.error}
                    {d.restarts > 0 && <span className="tnum"> {d.restarts} reconnect{d.restarts === 1 ? '' : 's'}</span>}
                  </span>
                )}
              </span>
              <span className="shrink-0 whitespace-nowrap">
                <HealthDot level={d.level} withText />
              </span>
            </li>
          ))}
        </ul>
      ) : enabled.length === 0 ? (
        <p className="text-muted">
          No destination yet. <Link to={`/studio/streams/${s.id}/destinations`} className="text-accent hover:underline">Add YouTube or another platform</Link>.
        </p>
      ) : (
        <ul className="space-y-1.5">
          {enabled.map((d) => (
            <li key={d.destinationId} className="flex items-center justify-between">
              <span className="truncate">{d.name}</span>
              <Badge>{onAir ? 'Connecting' : 'Ready'}</Badge>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** RE-4: how reliably this stream stays up, in numbers a creator can show a client. */
export function UptimePanel({ stream: s }: { stream: StreamView }) {
  const { data } = useUptime(s.id);
  if (!data || data.windows.every((w) => w.percent === null)) return null;
  const last = data.outages[0];
  return (
    <Panel title="Uptime">
      <dl className="grid grid-cols-3 gap-3">
        {data.windows.map((w) => (
          <div key={w.label}>
            <dt className="text-sm text-muted">{w.label}</dt>
            <dd className="tnum mt-0.5 text-xl font-semibold">{w.percent === null ? '–' : `${w.percent}%`}</dd>
            {w.percent !== null && <dd className="tnum text-xs text-muted">on air {duration(w.onAirSeconds)}</dd>}
          </div>
        ))}
      </dl>
      <p className="mt-3 text-sm text-muted">
        {last
          ? `Last outage ${dateTime(last.startedAt, s.timeZone)}${last.resolvedAt ? `, back after ${duration((new Date(last.resolvedAt).getTime() - new Date(last.startedAt).getTime()) / 1000)}` : ', still going'}.`
          : 'No outages in the last 30 days.'}{' '}
        Counts time on air; an outage is a minute or more down.
      </p>
    </Panel>
  );
}

function RecentRuns({ stream: s }: { stream: StreamView }) {
  const { data } = useRuns(s.id);
  if (!data || data.length === 0) return null;
  return (
    <Panel title="Recent runs" pad={false}>
      <ul className="divide-y divide-line">
        {data.slice(0, 6).map((r) => {
          const end = r.endedAt ? new Date(r.endedAt).getTime() : Date.now();
          const secs = (end - new Date(r.startedAt).getTime()) / 1000;
          return (
            <li key={r.jobId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 text-sm">
              <span>{dateTime(r.startedAt, s.timeZone)}</span>
              <span className="tnum text-muted">{duration(secs)}</span>
              <span className="min-w-0 flex-1 truncate text-right text-muted">{r.endedAt ? r.endReason || 'Ended' : 'On air'}</span>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
