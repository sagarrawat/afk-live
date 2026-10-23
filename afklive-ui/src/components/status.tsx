import clsx from 'clsx';
import { useEffect, useState } from 'react';
import { timecode } from '@/lib/format';
import type { Level, LiveState, LiveStateName } from '@/lib/types';

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

/** Red is reserved for "on air"; every other state is quiet. */
export function LiveBadge({ state, size = 'sm' }: { state: LiveStateName; size?: 'sm' | 'lg' }) {
  const live = state === 'LIVE';
  const transitional = state === 'STARTING' || state === 'STOPPING' || state === 'RECONNECTING';
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 rounded-sm font-semibold',
        size === 'lg' ? 'px-2.5 py-1 text-sm' : 'px-1.5 py-0.5 text-xs',
        live && 'bg-live text-white',
        transitional && 'border border-live/50 text-live',
        state === 'ERROR' && 'border border-bad/50 text-bad',
        (state === 'DRAFT' || state === 'ENDED') && 'border border-line text-muted',
      )}
    >
      <span
        aria-hidden
        className={clsx('h-1.5 w-1.5 rounded-full', live ? 'bg-white tally-pulse' : transitional ? 'bg-live tally-pulse' : state === 'ERROR' ? 'bg-bad' : 'bg-muted')}
      />
      {stateLabel[state] ?? state}
    </span>
  );
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
