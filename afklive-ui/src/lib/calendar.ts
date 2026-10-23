// SC-3: date arithmetic for the schedule calendar. Everything works on wall-clock strings in the
// account's time zone ("2026-11-02", "2026-11-02T22:00") so the browser's own zone never matters.

const DAY_MS = 86_400_000;

function toUtc(date: string): Date {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function fromUtc(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function addDays(date: string, n: number): string {
  return fromUtc(new Date(toUtc(date).getTime() + n * DAY_MS));
}

export function daysBetween(a: string, b: string): number {
  return Math.round((toUtc(b).getTime() - toUtc(a).getTime()) / DAY_MS);
}

/** Monday of the week holding {@code date}. */
export function weekStart(date: string): string {
  const dow = (toUtc(date).getUTCDay() + 6) % 7; // Monday = 0
  return addDays(date, -dow);
}

export function monthStart(date: string): string {
  return `${date.slice(0, 7)}-01`;
}

export function addMonths(date: string, n: number): string {
  const d = toUtc(monthStart(date));
  d.setUTCMonth(d.getUTCMonth() + n);
  return fromUtc(d);
}

/** The Monday-first grid for the month holding {@code date}: five weeks, or six when the month needs them. */
export function monthGrid(date: string): { from: string; days: number } {
  const first = monthStart(date);
  const from = weekStart(first);
  const last = addDays(addMonths(first, 1), -1);
  return { from, days: daysBetween(from, last) < 35 ? 35 : 42 };
}

export function dayList(from: string, days: number): string[] {
  return Array.from({ length: days }, (_, i) => addDays(from, i));
}

export function minutesOf(local: string): number {
  return Number(local.slice(11, 13)) * 60 + Number(local.slice(14, 16));
}

export function time(local: string): string {
  return local.slice(11, 16);
}

export function weekdayShort(date: string): string {
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', timeZone: 'UTC' }).format(toUtc(date));
}

export function dayLabel(date: string): string {
  return new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(toUtc(date));
}

export function shortDate(date: string): string {
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(toUtc(date));
}

export function monthLabel(date: string): string {
  return new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(toUtc(date));
}

export function weekLabel(from: string): string {
  const to = addDays(from, 6);
  const f = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' });
  return `${f.format(toUtc(from))} – ${f.format(toUtc(to))} ${to.slice(0, 4)}`;
}

/** "Mon 3 Nov, 11:00 to 12:00" (the end's date only when it's another day). */
export function rangeLabel(startLocal: string, endLocal?: string | null): string {
  const start = `${shortDate(startLocal.slice(0, 10))}, ${time(startLocal)}`;
  if (!endLocal) return `${start}, until stopped`;
  const sameDay = endLocal.slice(0, 10) === startLocal.slice(0, 10);
  return `${start} to ${sameDay ? '' : `${shortDate(endLocal.slice(0, 10))}, `}${time(endLocal)}`;
}

export interface Span {
  startLocal: string;
  endLocal?: string | null;
}

/** The part of a span that falls on {@code day}, in minutes from midnight; null when it doesn't. */
export function partOnDay(span: Span, day: string): { from: number; to: number } | null {
  const startDay = span.startLocal.slice(0, 10);
  const endLocal = span.endLocal ?? `${day}T24:00`; // until stopped: draw to the end of each day it reaches
  const endDay = endLocal.slice(0, 10);
  if (day < startDay || day > endDay) return null;
  const from = day === startDay ? minutesOf(span.startLocal) : 0;
  const to = day === endDay ? (endLocal.slice(11) === '24:00' ? 1440 : minutesOf(endLocal)) : 1440;
  return to > from ? { from, to } : null;
}

/**
 * Side-by-side lanes for overlapping blocks in one day: each block's lane and how many lanes its
 * group of overlapping blocks needs (a block that overlaps nothing keeps the full width).
 */
export function lanes<T extends { from: number; to: number }>(items: T[]): (T & { lane: number; lanes: number })[] {
  const sorted = [...items].sort((a, b) => a.from - b.from || b.to - a.to);
  const out: (T & { lane: number; lanes: number })[] = [];
  let group: (T & { lane: number; lanes: number })[] = [];
  let ends: number[] = [];
  let groupEnd = -1;
  const close = () => {
    group.forEach((g) => (g.lanes = ends.length));
    out.push(...group);
    group = [];
    ends = [];
  };
  for (const it of sorted) {
    if (it.from >= groupEnd) close();
    let lane = ends.findIndex((end) => end <= it.from);
    if (lane < 0) {
      lane = ends.length;
      ends.push(it.to);
    } else {
      ends[lane] = it.to;
    }
    groupEnd = Math.max(groupEnd, it.to);
    group.push({ ...it, lane, lanes: 1 });
  }
  close();
  return out;
}

/** Stream colour token (by the stream's position in the account), as a CSS colour. */
export function streamColor(index: number, alpha = 1): string {
  return `rgb(var(--stream-${(Math.max(index, 0) % 6) + 1}) / ${alpha})`;
}
