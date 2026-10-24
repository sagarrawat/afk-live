import type { DestinationHealth, LiveRow } from './types';

/** Below this the encoder runs slower than real time (RE-10, same as the server's alert). */
export const SLOW_SPEED = 0.95;

const HEALTH_RANK = { red: 0, amber: 1, green: 2 } as const;

/** How bad a run is, smaller is worse: health first, then not reaching a platform, then slow. */
export function severity(r: LiveRow): number {
  const health = HEALTH_RANK[r.health] ?? 2;
  const unreached = (r.destinationHealth ?? []).some((d) => d.reach === 'NOT_REACHING') ? 0 : 1;
  const slow = r.speed != null && r.speed < SLOW_SPEED ? 0 : 1;
  return health * 4 + unreached * 2 + slow;
}

/** Worst first; among equals, the longest-running first. */
export function worstFirst(rows: LiveRow[]): LiveRow[] {
  return [...rows].sort((a, b) => severity(a) - severity(b) || b.uptimeSeconds - a.uptimeSeconds);
}

/** "YouTube" for a known platform, otherwise the creator's own name for the destination. */
export function destinationName(d: DestinationHealth): string {
  return d.platform || d.name || 'Destination';
}

export type ReachWord = 'On' | 'Connecting' | 'Not reaching' | 'Off';

export function reachWord(d: DestinationHealth): ReachWord {
  if (d.reach === 'ON') return 'On';
  if (d.reach === 'NOT_REACHING') return 'Not reaching';
  if (d.reach === 'CONNECTING') return 'Connecting';
  return d.status === 'OFF' || d.status === 'STOPPED' ? 'Off' : 'Connecting';
}

/** Why a destination isn't reaching, without the "Not reaching YouTube." lead the message starts with. */
export function reachReason(d: DestinationHealth): string | null {
  if (d.reach !== 'NOT_REACHING') return null;
  const m = (d.message ?? '').replace(/^Not reaching [^.]*\.\s*/, '').trim();
  return m || d.error || null;
}

/** The frame rate the stream asked for, from its quality ("1080p 30 fps" → 30). */
export function targetFps(quality: string | null | undefined): number | null {
  const m = /(\d+)\s*fps/.exec(quality ?? '');
  return m ? Number(m[1]) : null;
}

export function speedText(speed: number | null | undefined): string {
  return speed == null ? '–' : `${speed.toFixed(2)}×`;
}
