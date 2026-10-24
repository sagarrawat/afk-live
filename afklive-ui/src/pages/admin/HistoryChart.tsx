import clsx from 'clsx';
import type { HistoryPoint } from './types';

export type Metric = 'cpu' | 'memory';

export interface Summary {
  now: number | null;
  average: number | null;
  peak: number | null;
  peakAt: string | null;
}

/** Now, average and peak over the window: what makes one reading readable (a 96% spike vs a 96% day). */
export function summarize(points: HistoryPoint[], metric: Metric): Summary {
  const vals = points.filter((p) => p[metric] != null);
  if (vals.length === 0) return { now: null, average: null, peak: null, peakAt: null };
  const peakOf = (p: HistoryPoint) => (metric === 'cpu' ? (p.cpuPeak ?? p.cpu) : p.memory) as number;
  let peak = vals[0];
  for (const p of vals) if (peakOf(p) > peakOf(peak)) peak = p;
  const avg = vals.reduce((a, p) => a + (p[metric] as number), 0) / vals.length;
  return { now: vals[vals.length - 1][metric] as number, average: Math.round(avg), peak: Math.round(peakOf(peak)), peakAt: peak.at };
}

const W = 600;

function pct(v: number | null) {
  return v == null ? '–' : `${Math.round(v)}%`;
}

function hhmm(iso: string, days: boolean) {
  const d = new Date(iso);
  return d.toLocaleString(undefined, days ? { weekday: 'short', hour: '2-digit', minute: '2-digit' } : { hour: '2-digit', minute: '2-digit' });
}

/** Line segments, broken where samples are missing (a worker that stopped reporting shows a gap, not a slope). */
function segments(points: HistoryPoint[], metric: Metric, from: number, to: number, h: number, bucketMs: number) {
  const out: string[] = [];
  let cur: string[] = [];
  let last = -Infinity;
  for (const p of points) {
    const v = p[metric];
    const t = new Date(p.at).getTime();
    if (v == null || t - last > bucketMs * 2.5) {
      if (cur.length) out.push(cur.join(' '));
      cur = [];
    }
    if (v != null) {
      const x = ((t - from) / (to - from)) * W;
      const y = h - (Math.max(0, Math.min(100, v)) / 100) * h;
      cur.push(`${cur.length ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`);
      last = t;
    }
  }
  if (cur.length) out.push(cur.join(' '));
  return out;
}

/**
 * A percentage over time as an inline SVG line (0 to 100 %), with a dashed line at 90 % where streams
 * start to suffer. The summary line says the numbers in words, so the chart is never the only carrier.
 */
export function HistoryChart({
  points,
  metric,
  hours,
  bucketSeconds,
  compact,
  label,
  now = Date.now(),
}: {
  points: HistoryPoint[];
  metric: Metric;
  hours: number;
  bucketSeconds: number;
  compact?: boolean;
  label: string;
  now?: number;
}) {
  const h = compact ? 36 : 96;
  const from = now - hours * 3600_000;
  const s = summarize(points, metric);
  const paths = segments(points, metric, from, now, h, bucketSeconds * 1000);
  const words =
    s.now == null
      ? `${label}: no history yet`
      : `${label} over the last ${hours < 48 ? `${hours} hours` : `${Math.round(hours / 24)} days`}: now ${pct(s.now)}, average ${pct(s.average)}, peak ${pct(s.peak)}${s.peakAt ? ` at ${hhmm(s.peakAt, hours > 24)}` : ''}`;
  return (
    <figure className="min-w-0">
      <figcaption className="flex items-baseline justify-between gap-2 text-xs">
        <span className="text-muted">{label}</span>
        <span className="tnum">
          {s.now == null ? (
            <span className="text-muted">No history yet</span>
          ) : (
            <>
              <span className={clsx('font-semibold', s.now >= 90 ? 'text-bad' : s.now >= 75 ? 'text-warn' : 'text-ink')}>{pct(s.now)}</span>
              {!compact && (
                <span className="text-muted">
                  {' '}
                  now, avg {pct(s.average)}, peak {pct(s.peak)}
                </span>
              )}
            </>
          )}
        </span>
      </figcaption>
      <div className={clsx('mt-1', !compact && 'pl-8')}>
        <div className="relative">
          {!compact && (
            <>
              <span aria-hidden className="tnum absolute -left-8 top-0 -translate-y-1/2 text-[10px] text-muted">
                100%
              </span>
              <span aria-hidden className="tnum absolute -left-8 bottom-0 translate-y-1/2 text-[10px] text-muted">
                0
              </span>
            </>
          )}
          <svg role="img" aria-label={words} viewBox={`0 0 ${W} ${h}`} preserveAspectRatio="none" className="block w-full overflow-visible" style={{ height: h }}>
          <rect x={0} y={0} width={W} height={h} className="fill-raised/40" />
          <line x1={0} x2={W} y1={h * 0.1} y2={h * 0.1} className="stroke-bad/50" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
          {!compact && <line x1={0} x2={W} y1={h * 0.5} y2={h * 0.5} className="stroke-line" vectorEffect="non-scaling-stroke" />}
          {paths.map((d, i) => (
            <path key={i} d={d} fill="none" className={metric === 'cpu' ? 'stroke-ink' : 'stroke-muted'} strokeWidth={1.5} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
          ))}
          </svg>
        </div>
        {compact && s.now != null && (
          <p className="tnum mt-1 text-[11px] text-muted">
            avg {pct(s.average)}, peak {pct(s.peak)}
          </p>
        )}
        {!compact && (
          <div aria-hidden className="tnum mt-1 flex justify-between text-[10px] text-muted">
            <span>{hhmm(new Date(from).toISOString(), hours > 24)}</span>
            <span>now</span>
          </div>
        )}
      </div>
    </figure>
  );
}
