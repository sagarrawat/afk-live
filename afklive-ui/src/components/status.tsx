import clsx from 'clsx';
import { useEffect, useState } from 'react';
import { timecode } from '@/lib/format';
import type { Level, LiveState, LiveStateName, StreamReach } from '@/lib/types';

/** Words for each state, as a creator would say them. */
export const stateLabel: Record<LiveStateName, string> = {
  DRAFT: 'Off air',
  STARTING: 'Starting',
  LIVE: 'Live',
  RECONNECTING: 'Reconnecting',
  STOPPING: 'Stopping',
  ENDED: 'Off air',
  ERROR: 'Stopped with a problem',
};

export function isOnAir(state: LiveStateName | undefined): boolean {
  return state === 'LIVE' || state === 'STARTING' || state === 'RECONNECTING' || state === 'STOPPING';
}

/**
 * RE-8: what the badge says. "Live" only while a platform is really receiving the stream; our encoder
 * running is not enough. Without a reach (an older server) the state alone decides, as before.
 */
export function badgeLabel(state: LiveStateName, reach?: StreamReach | null): string {
  if (state === 'LIVE' && reach) {
    return { ALL: 'Live', SOME: 'Live with problems', NONE: 'Not reaching anyone', CONNECTING: 'Connecting' }[reach];
  }
  return stateLabel[state] ?? state;
}

/** Red is reserved for "on air"; every other state is quiet. A stream nobody receives isn't on air. */
export function LiveBadge({ state, reach, size = 'sm' }: { state: LiveStateName; reach?: StreamReach | null; size?: 'sm' | 'lg' }) {
  const unreached = state === 'LIVE' && reach === 'NONE';
  const connecting = state === 'LIVE' && reach === 'CONNECTING';
  const live = state === 'LIVE' && !unreached && !connecting;
  const transitional = state === 'STARTING' || state === 'STOPPING' || state === 'RECONNECTING' || connecting;
  const bad = state === 'ERROR' || unreached;
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 rounded-sm font-semibold',
        size === 'lg' ? 'px-2.5 py-1 text-sm' : 'px-1.5 py-0.5 text-xs',
        live && 'bg-live text-white',
        transitional && 'border border-live/50 text-live',
        bad && 'border border-bad/50 text-bad',
        (state === 'DRAFT' || state === 'ENDED') && 'border border-line text-muted',
      )}
    >
      <span
        aria-hidden
        className={clsx('h-1.5 w-1.5 rounded-full', live ? 'bg-white tally-pulse' : transitional ? 'bg-live tally-pulse' : bad ? 'bg-bad' : 'bg-muted')}
      />
      {badgeLabel(state, reach)}
    </span>
  );
}

/** RE-8: the first destination that isn't reaching its platform, in plain words ("Not reaching YouTube. ..."). */
export function reachProblem(live: LiveState | undefined): string | null {
  if (!live || live.state !== 'LIVE' || !live.reach || live.reach === 'ALL' || live.reach === 'CONNECTING') return null;
  const d = live.health?.destinations.find((x) => x.reach === 'NOT_REACHING');
  return d?.message ?? null;
}

export const levelText: Record<Level, string> = { green: 'Healthy', amber: 'Needs a look', red: 'Problem' };

export function HealthDot({ level, withText }: { level: Level | undefined | null; withText?: boolean }) {
  if (!level) return null;
  return (
    <span className="inline-flex items-center gap-1.5 text-sm">
      <span aria-hidden className={clsx('h-2 w-2 rounded-full', level === 'green' && 'bg-ok', level === 'amber' && 'bg-warn', level === 'red' && 'bg-bad')} />
      <span className={withText ? undefined : 'sr-only'}>{levelText[level]}</span>
    </span>
  );
}

/** Uptime that keeps ticking between polls, from the server's start time. */
export function useUptime(live: LiveState | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  const running = live && isOnAir(live.state) && live.startedAt;
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);
  if (!live) return 0;
  if (running && live.startedAt) {
    return Math.max(live.uptimeSeconds, Math.floor((now - new Date(live.startedAt).getTime()) / 1000));
  }
  return live.uptimeSeconds;
}

export function Uptime({ live, className }: { live: LiveState; className?: string }) {
  const s = useUptime(live);
  return (
    <span className={clsx('tnum', className)} aria-label={`Up ${timecode(s)}`}>
      {timecode(s)}
    </span>
  );
}
