import clsx from 'clsx';
import { AlertTriangle, CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Badge, Button, Empty, ErrorNote, IconButton, PageHeader, Segmented, Spinner, useAction } from '@/components/ui';
import { put } from '@/lib/api';
import {
  addDays,
  addMonths,
  dayLabel,
  dayList,
  lanes,
  monthGrid,
  monthLabel,
  partOnDay,
  rangeLabel,
  streamColor,
  time,
  weekLabel,
  weekStart,
  weekdayShort,
} from '@/lib/calendar';
import { browserTimeZone } from '@/lib/format';
import { keys, useInvalidate, useMe, useScheduleCalendar } from '@/lib/queries';
import type { CalendarClash, CalendarOccurrence, ScheduleCalendar } from '@/lib/types';
import { localInZone } from './stream/ScheduleTab';

type View = 'week' | 'month';

const HOUR_PX = 40;
const SNAP_MIN = 15;
const KIND: Record<CalendarOccurrence['kind'], string> = { ONCE: 'Once', DAILY: 'Every day', WEEKLY: 'Chosen days' };

/** Wide screens get the grids; phones get a day-by-day list. */
function useWide(): boolean {
  const query = '(min-width: 768px)';
  const [wide, setWide] = useState(() => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const m = window.matchMedia(query);
    const on = () => setWide(m.matches);
    m.addEventListener?.('change', on);
    return () => m.removeEventListener?.('change', on);
  }, []);
  return wide;
}

const scheduleLink = (o: { streamId: number }) => `/studio/streams/${o.streamId}/schedule`;
const occKey = (o: CalendarOccurrence) => `${o.scheduleId}:${o.start}`;

/** SC-3: every scheduled run across streams, a week or a month at a time, with clashes against the plan's limit. */
export function Schedule() {
  const { data: me } = useMe();
  const tz = me?.timeZone || browserTimeZone();
  const today = localInZone(new Date().toISOString(), tz).slice(0, 10);
  const [view, setView] = useState<View>('week');
  const [anchor, setAnchor] = useState<string | null>(null);
  const at = anchor ?? today;
  const range = view === 'week' ? { from: weekStart(at), days: 7 } : monthGrid(at);
  const { data, error, isLoading } = useScheduleCalendar(range.from, range.days);
  const wide = useWide();

  const step = (n: number) => setAnchor(view === 'week' ? addDays(at, 7 * n) : addMonths(at, n));
  const colour = useMemo(() => {
    const index = new Map((data?.streams ?? []).map((s, i) => [s.id, i]));
    return (streamId: number, alpha = 1) => streamColor(index.get(streamId) ?? 0, alpha);
  }, [data?.streams]);

  return (
    <>
      <PageHeader
        title="Schedule"
        description={`Every scheduled run across your streams, in ${data?.timeZone ?? tz} time.`}
      />
      {data && <ClashBanner calendar={data} />}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Segmented label="Calendar view" value={view} onChange={setView} options={[{ value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]} />
        <div className="flex items-center gap-1">
          <IconButton label={view === 'week' ? 'Previous week' : 'Previous month'} onClick={() => step(-1)}>
            <ChevronLeft className="h-4 w-4" />
          </IconButton>
          <Button size="sm" variant="ghost" onClick={() => setAnchor(null)}>
            Today
          </Button>
          <IconButton label={view === 'week' ? 'Next week' : 'Next month'} onClick={() => step(1)}>
            <ChevronRight className="h-4 w-4" />
          </IconButton>
        </div>
        <h2 className="text-md font-semibold tnum" aria-live="polite">
          {view === 'week' ? weekLabel(range.from) : monthLabel(at)}
        </h2>
      </div>
      <ErrorNote error={error} />
      {isLoading && !data ? (
        <Spinner />
      ) : data && data.streams.length === 0 ? (
        <Empty icon={<CalendarDays className="h-8 w-8" />} title="No streams yet" action={<Link to="/studio" className="text-accent hover:underline">Go to your streams</Link>}>
          Create a stream and give it a schedule. Its runs show up here.
        </Empty>
      ) : data ? (
        <>
          {wide && view === 'week' && <WeekGrid calendar={data} today={today} colour={colour} />}
          {wide && view === 'month' && (
            <MonthGrid
              calendar={data}
              month={at.slice(0, 7)}
              today={today}
              colour={colour}
              onOpenDay={(d) => {
                setAnchor(d);
                setView('week');
              }}
            />
          )}
          {!wide && <Agenda calendar={data} month={view === 'month' ? at.slice(0, 7) : null} colour={colour} />}
          <Legend calendar={data} colour={colour} />
        </>
      ) : null}
    </>
  );
}

function ClashBanner({ calendar: c }: { calendar: ScheduleCalendar }) {
  if (c.clashes.length === 0) return null;
  return (
    <section aria-label="Clashes" className="mb-4 rounded-md border border-warn/40 bg-warn/10 px-4 py-3">
      <p className="flex items-center gap-2 font-semibold text-warn">
        <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden />
        {c.clashes.length === 1 ? '1 clash' : `${c.clashes.length} clashes`}: more streams than your {c.planName} plan runs at once
      </p>
      <ul className="mt-2 space-y-1.5 text-sm">
        {c.clashes.map((x) => (
          <li key={x.start}>
            <span className="font-medium tnum">{rangeLabel(x.startLocal, x.endLocal)}</span>
            <span className="text-muted">: {x.streamNames.join(', ')}. </span>
            <span>{x.message}</span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-sm">
        <Link to="/studio/settings/billing" className="text-accent hover:underline">
          See plans with more streams at once
        </Link>
      </p>
    </section>
  );
}

function Legend({ calendar: c, colour }: { calendar: ScheduleCalendar; colour: (id: number, a?: number) => string }) {
  const shown = c.streams.filter((s) => c.occurrences.some((o) => o.streamId === s.id));
  if (shown.length === 0) return null;
  return (
    <ul aria-label="Streams" className="mt-4 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
      {shown.map((s) => (
        <li key={s.id} className="flex items-center gap-1.5">
          <span aria-hidden className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: colour(s.id) }} />
          {s.name}
        </li>
      ))}
    </ul>
  );
}

function OccurrenceText({ o }: { o: CalendarOccurrence }) {
  const overnight = o.endLocal && o.endLocal.slice(0, 10) !== o.startLocal.slice(0, 10);
  return (
    <>
      <span className="block truncate font-medium">{o.streamName}</span>
      <span className="block truncate text-xs text-muted tnum">
        {time(o.startLocal)} to {o.endLocal ? `${time(o.endLocal)}${overnight ? ' next day' : ''}` : 'until stopped'} · {KIND[o.kind]}
      </span>
    </>
  );
}

// ------------------------------------------------------------------ week

interface Drag {
  key: string;
  x: number;
  y: number;
  days: number;
  minutes: number;
  moved: boolean;
}

function WeekGrid({ calendar: c, today, colour }: { calendar: ScheduleCalendar; today: string; colour: (id: number, a?: number) => string }) {
  const days = dayList(c.from, 7);
  const scroller = useRef<HTMLDivElement>(null);
  const columns = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  const { run } = useAction();
  const [drag, setDrag] = useState<Drag | null>(null);
  const justDragged = useRef(false);

  useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 6 * HOUR_PX;
  }, []);

  const move = (o: CalendarOccurrence, minutes: number) => {
    const shift = (iso: string) => localInZone(new Date(Date.parse(iso) + minutes * 60_000).toISOString(), o.scheduleTimeZone);
    const body = { kind: 'ONCE', startAt: shift(o.start), endAt: o.end ? shift(o.end) : null, timeZone: o.scheduleTimeZone, skipDates: [], enabled: true };
    run(async () => {
      await put(`/api/streams/${o.streamId}/schedules/${o.scheduleId}`, body);
      await invalidate(['schedules'], [...keys.stream(o.streamId)]);
    }, `Moved ${o.streamName}.`);
  };

  const startDrag = (e: ReactPointerEvent, o: CalendarOccurrence) => {
    if (!o.movable || e.button > 0) return;
    const colWidth = (columns.current?.getBoundingClientRect().width ?? 0) / 7;
    let current: Drag = { key: occKey(o), x: e.clientX, y: e.clientY, days: 0, minutes: 0, moved: false };
    const onMove = (ev: PointerEvent) => {
      const dx = ev.clientX - current.x;
      const dy = ev.clientY - current.y;
      const moved = current.moved || Math.abs(dx) > 4 || Math.abs(dy) > 4;
      const minutes = Math.round(((dy / HOUR_PX) * 60) / SNAP_MIN) * SNAP_MIN;
      const dayShift = colWidth > 0 ? Math.round(dx / colWidth) : 0;
      current = { ...current, moved, minutes, days: dayShift };
      if (moved) setDrag(current);
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      setDrag(null);
      if (current.moved) {
        justDragged.current = true;
        setTimeout(() => (justDragged.current = false), 0);
        const total = current.days * 1440 + current.minutes;
        if (total !== 0) move(o, total);
      }
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const clashParts = (day: string) => c.clashes.map((x) => ({ x, part: partOnDay(x, day) })).filter((p) => p.part) as { x: CalendarClash; part: { from: number; to: number } }[];

  return (
    <div className="overflow-hidden rounded-md border border-line bg-panel">
      <div className="grid grid-cols-[3rem_1fr] border-b border-line">
        <div />
        <div className="grid grid-cols-7">
          {days.map((d) => (
            <div key={d} className={clsx('border-l border-line px-2 py-2 text-center text-sm', d === today ? 'font-semibold text-ink' : 'text-muted')}>
              {weekdayShort(d)} <span className="tnum">{Number(d.slice(8))}</span>
            </div>
          ))}
        </div>
      </div>
      <div ref={scroller} className="max-h-[70vh] overflow-y-auto">
        <div className="grid grid-cols-[3rem_1fr]">
          <div className="relative" style={{ height: 24 * HOUR_PX }}>
            {Array.from({ length: 23 }, (_, h) => (
              <span key={h} className="absolute right-1.5 -translate-y-1/2 text-xs text-muted tnum" style={{ top: (h + 1) * HOUR_PX }}>
                {String(h + 1).padStart(2, '0')}:00
              </span>
            ))}
          </div>
          <div ref={columns} className="relative grid grid-cols-7" style={{ height: 24 * HOUR_PX }}>
            {days.map((d) => {
              const blocks = lanes(
                c.occurrences.flatMap((o) => {
                  const part = partOnDay(o, d);
                  return part ? [{ ...part, o }] : [];
                }),
              );
              return (
                <div
                  key={d}
                  data-day={d}
                  className={clsx('relative border-l border-line', d === today && 'bg-accent/5')}
                  style={{ backgroundImage: `repeating-linear-gradient(to bottom, transparent 0, transparent ${HOUR_PX - 1}px, rgb(var(--line) / 0.6) ${HOUR_PX - 1}px, rgb(var(--line) / 0.6) ${HOUR_PX}px)` }}
                >
                  {clashParts(d).map(({ x, part }) => (
                    <div
                      key={x.start}
                      className="pointer-events-none absolute inset-x-0 border-y border-warn/50 bg-warn/10"
                      style={{ top: (part.from / 60) * HOUR_PX, height: ((part.to - part.from) / 60) * HOUR_PX }}
                    >
                      <span className="absolute bottom-0.5 right-1 rounded-sm bg-panel/90 px-1 text-[10px] font-semibold text-warn">Clash</span>
                    </div>
                  ))}
                  {blocks.map(({ o, from, to, lane, lanes: n }) => {
                    const dragging = drag?.key === occKey(o);
                    const top = (from / 60) * HOUR_PX;
                    const height = Math.max(((to - from) / 60) * HOUR_PX, 18);
                    return (
                      <button
                        key={occKey(o)}
                        type="button"
                        aria-label={`${o.streamName}, ${rangeLabel(o.startLocal, o.endLocal)}, ${KIND[o.kind]}${o.movable ? '. Drag to move' : ''}`}
                        onPointerDown={(e) => startDrag(e, o)}
                        onClick={() => {
                          if (!justDragged.current) navigate(scheduleLink(o));
                        }}
                        className={clsx(
                          'absolute overflow-hidden rounded-sm border-l-[3px] px-1.5 py-0.5 text-left text-xs leading-4 text-ink',
                          o.movable ? 'cursor-grab touch-none' : 'cursor-pointer',
                          dragging && 'z-20 cursor-grabbing shadow-lg ring-2 ring-accent',
                        )}
                        style={{
                          top,
                          height,
                          left: `calc(${(lane / n) * 100}% + 2px)`,
                          width: `calc(${100 / n}% - 4px)`,
                          backgroundColor: colour(o.streamId, 0.22),
                          borderLeftColor: colour(o.streamId),
                          transform: dragging ? `translate(${drag.days * 100 * n}%, ${(drag.minutes / 60) * HOUR_PX}px)` : undefined,
                        }}
                      >
                        <OccurrenceText o={o} />
                      </button>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      </div>
      <p className="border-t border-line px-3 py-2 text-xs text-muted">Drag a one-time run to move it. Repeating runs open their stream's schedule.</p>
    </div>
  );
}

// ------------------------------------------------------------------ month

function MonthGrid({
  calendar: c,
  month,
  today,
  colour,
  onOpenDay,
}: {
  calendar: ScheduleCalendar;
  month: string;
  today: string;
  colour: (id: number, a?: number) => string;
  onOpenDay: (day: string) => void;
}) {
  const days = dayList(c.from, c.days);
  const navigate = useNavigate();
  return (
    <div className="overflow-hidden rounded-md border border-line bg-panel">
      <div className="grid grid-cols-7 border-b border-line">
        {days.slice(0, 7).map((d) => (
          <div key={d} className="px-2 py-2 text-center text-sm text-muted">
            {weekdayShort(d)}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {days.map((d) => {
          const runs = c.occurrences.filter((o) => o.startLocal.slice(0, 10) === d);
          const clash = c.clashes.some((x) => partOnDay(x, d));
          return (
            <div key={d} data-day={d} className={clsx('min-h-28 border-b border-l border-line p-1.5', d.slice(0, 7) !== month && 'bg-bg/40 text-muted')}>
              <div className="mb-1 flex items-center justify-between gap-1">
                <button
                  type="button"
                  aria-label={`Show the week of ${dayLabel(d)}`}
                  onClick={() => onOpenDay(d)}
                  className={clsx('grid h-6 min-w-6 place-items-center rounded-full px-1 text-xs tnum hover:bg-raised', d === today && 'bg-accent font-semibold text-accent-ink hover:bg-accent')}
                >
                  {Number(d.slice(8))}
                </button>
                {clash && <Badge tone="warn">Clash</Badge>}
              </div>
              <ul className="space-y-0.5">
                {runs.slice(0, 3).map((o) => (
                  <li key={occKey(o)}>
                    <button
                      type="button"
                      onClick={() => navigate(scheduleLink(o))}
                      className="flex w-full items-center gap-1 truncate rounded-sm px-1 text-left text-xs hover:bg-raised"
                      title={`${o.streamName}, ${rangeLabel(o.startLocal, o.endLocal)}`}
                    >
                      <span aria-hidden className="h-2 w-2 shrink-0 rounded-sm" style={{ backgroundColor: colour(o.streamId) }} />
                      <span className="tnum text-muted">{time(o.startLocal)}</span>
                      <span className="truncate">{o.streamName}</span>
                    </button>
                  </li>
                ))}
                {runs.length > 3 && (
                  <li>
                    <button type="button" onClick={() => onOpenDay(d)} className="px-1 text-xs text-muted hover:text-ink">
                      {runs.length - 3} more
                    </button>
                  </li>
                )}
              </ul>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ phone

function Agenda({ calendar: c, month, colour }: { calendar: ScheduleCalendar; month: string | null; colour: (id: number, a?: number) => string }) {
  const days = dayList(c.from, c.days).filter((d) => !month || d.slice(0, 7) === month);
  const groups = days
    .map((d) => ({
      d,
      runs: c.occurrences.filter((o) => (o.startLocal.slice(0, 10) < c.from ? c.from : o.startLocal.slice(0, 10)) === d),
      clashes: c.clashes.filter((x) => x.startLocal.slice(0, 10) === d),
    }))
    .filter((g) => g.runs.length > 0 || g.clashes.length > 0);
  if (groups.length === 0) {
    return (
      <Empty icon={<CalendarDays className="h-8 w-8" />} title="Nothing scheduled">
        Add a time on a stream's Schedule tab and it shows up here.
      </Empty>
    );
  }
  return (
    <div className="space-y-4">
      {groups.map((g) => (
        <section key={g.d} aria-label={dayLabel(g.d)}>
          <h3 className="mb-1.5 flex items-center gap-2 text-sm font-semibold">
            {dayLabel(g.d)}
            {g.clashes.length > 0 && <Badge tone="warn">Clash</Badge>}
          </h3>
          <ul className="divide-y divide-line overflow-hidden rounded-md border border-line bg-panel">
            {g.runs.map((o) => (
              <li key={occKey(o)}>
                <Link to={scheduleLink(o)} className="flex items-center gap-3 px-3 py-2.5 hover:bg-raised">
                  <span aria-hidden className="h-9 w-1 shrink-0 rounded-full" style={{ backgroundColor: colour(o.streamId) }} />
                  <span className="min-w-0 flex-1">
                    <OccurrenceText o={o} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
