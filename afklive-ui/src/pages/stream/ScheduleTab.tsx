import clsx from 'clsx';
import { CalendarClock, Plus, RotateCcw, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Badge, Button, Dialog, Empty, Field, IconButton, Input, Panel, Segmented, Toggle } from '@/components/ui';
import { del, post, put } from '@/lib/api';
import { dateTime } from '@/lib/format';
import type { ScheduleView, StreamView } from '@/lib/types';
import { MediaPicker } from '@/components/MediaPicker';
import { useStreamUpdate } from './useStreamUpdate';

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;
const DAY_LABEL: Record<string, string> = { MON: 'Mon', TUE: 'Tue', WED: 'Wed', THU: 'Thu', FRI: 'Fri', SAT: 'Sat', SUN: 'Sun' };

/** "2026-10-23T09:30" for an instant, as the wall clock reads in {@code timeZone}. */
export function localInZone(iso: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(iso));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`;
}

export function describe(sc: ScheduleView, tz: string): string {
  if (sc.kind === 'ONCE') return `${dateTime(sc.startAt, tz)} to ${dateTime(sc.endAt, tz)}`;
  const time = `${sc.startTime?.slice(0, 5)} to ${sc.endTime?.slice(0, 5)}`;
  if (sc.kind === 'DAILY') return `Every day, ${time}`;
  const days = sc.daysOfWeek.length === 5 && !sc.daysOfWeek.includes('SAT') && !sc.daysOfWeek.includes('SUN') ? 'Weekdays' : sc.daysOfWeek.map((d) => DAY_LABEL[d] ?? d).join(', ');
  return `${days}, ${time}`;
}

function toRequest(sc: ScheduleView, enabled = sc.enabled) {
  return {
    kind: sc.kind,
    startAt: sc.startAt ? localInZone(sc.startAt, sc.timeZone) : null,
    endAt: sc.endAt ? localInZone(sc.endAt, sc.timeZone) : null,
    daysOfWeek: sc.daysOfWeek,
    startTime: sc.startTime?.slice(0, 5) ?? null,
    endTime: sc.endTime?.slice(0, 5) ?? null,
    timeZone: sc.timeZone,
    skipDates: sc.skipDates,
    enabled,
    playlist: (sc.playlist ?? []).map((m) => m.id),
  };
}

/** SC-1..SC-5: run this stream on a timetable; windows that span midnight are fine. */
export function ScheduleTab({ stream: s }: { stream: StreamView }) {
  const [editing, setEditing] = useState<ScheduleView | 'new' | null>(null);
  const { busy, update } = useStreamUpdate(s.id);
  return (
    <Panel
      title="Schedule"
      pad={false}
      action={
        <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setEditing('new')}>
          Add a time
        </Button>
      }
    >
      {s.schedules.length === 0 ? (
        <div className="p-4">
          <Empty icon={<CalendarClock className="h-8 w-8" />} title="No schedule">
            Go live automatically: once, every day, or on chosen days. We start and stop the stream for you, in {s.timeZone}.
          </Empty>
        </div>
      ) : (
        <ul className="divide-y divide-line">
          {s.schedules.map((sc) => (
            <li key={sc.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <button className={clsx('min-w-0 flex-1 text-left', !sc.enabled && 'opacity-60')} onClick={() => setEditing(sc)}>
                <span className="block font-medium">{describe(sc, sc.timeZone)}</span>
                <span className="block text-sm text-muted">
                  {sc.enabled ? (sc.nextStart ? `Next start ${dateTime(sc.nextStart, sc.timeZone)}` : 'No upcoming start') : 'Paused'}
                  {sc.timeZone !== Intl.DateTimeFormat().resolvedOptions().timeZone && `, ${sc.timeZone} time`}
                  {(sc.playlist?.length ?? 0) > 0 && `. Plays its own ${sc.playlist!.length} video${sc.playlist!.length === 1 ? '' : 's'}`}
                </span>
                {sc.lastError && (
                  <span className="mt-1 block">
                    <Badge tone="warn">{sc.lastError}</Badge>
                  </span>
                )}
              </button>
              {sc.lastError && (
                <Button size="sm" variant="ghost" icon={<RotateCcw className="h-4 w-4" />} busy={busy} onClick={() => update(() => post(`/api/streams/${s.id}/schedules/${sc.id}/retry`), 'Starting now.')}>
                  Try again
                </Button>
              )}
              <Toggle label={sc.enabled ? 'Pause this schedule' : 'Resume this schedule'} checked={sc.enabled} onChange={(v) => update(() => put(`/api/streams/${s.id}/schedules/${sc.id}`, toRequest(sc, v)))} />
              <IconButton label="Delete this schedule" onClick={() => update(() => del(`/api/streams/${s.id}/schedules/${sc.id}`), 'Schedule deleted.')}>
                <Trash2 className="h-4 w-4" />
              </IconButton>
            </li>
          ))}
        </ul>
      )}
      {editing && <ScheduleDialog stream={s} schedule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function ScheduleDialog({ stream: s, schedule, onClose }: { stream: StreamView; schedule: ScheduleView | null; onClose: () => void }) {
  const init = schedule ? toRequest(schedule) : null;
  const [kind, setKind] = useState<'ONCE' | 'DAILY' | 'WEEKLY'>(schedule?.kind ?? 'WEEKLY');
  const [startAt, setStartAt] = useState(init?.startAt ?? '');
  const [endAt, setEndAt] = useState(init?.endAt ?? '');
  const [startTime, setStartTime] = useState(init?.startTime ?? '06:00');
  const [endTime, setEndTime] = useState(init?.endTime ?? '22:00');
  const [days, setDays] = useState<string[]>(init?.daysOfWeek ?? ['MON', 'TUE', 'WED', 'THU', 'FRI']);
  const [tz, setTz] = useState(schedule?.timeZone ?? s.timeZone);
  const [slot, setSlot] = useState<{ id: number; title: string }[]>(schedule?.playlist ?? []);
  const [picking, setPicking] = useState(false);
  const { busy, update } = useStreamUpdate(s.id);
  const save = async () => {
    const body = { kind, startAt: kind === 'ONCE' ? startAt : null, endAt: kind === 'ONCE' ? endAt : null, daysOfWeek: kind === 'WEEKLY' ? days : [], startTime, endTime, timeZone: tz, skipDates: schedule?.skipDates ?? [], enabled: schedule?.enabled ?? true, playlist: slot.map((m) => m.id) };
    const ok = await update(() => (schedule ? put(`/api/streams/${s.id}/schedules/${schedule.id}`, body) : post(`/api/streams/${s.id}/schedules`, body)), 'Schedule saved.');
    if (ok) onClose();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={schedule ? 'Change schedule' : 'Add a time'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} onClick={save}>
            Save schedule
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <Segmented label="Repeat" value={kind} onChange={setKind} options={[{ value: 'ONCE', label: 'Once' }, { value: 'DAILY', label: 'Every day' }, { value: 'WEEKLY', label: 'Chosen days' }]} />
        {kind === 'ONCE' ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Start" htmlFor="sc-start">
              <Input id="sc-start" type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} />
            </Field>
            <Field label="End" htmlFor="sc-end">
              <Input id="sc-end" type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} />
            </Field>
          </div>
        ) : (
          <>
            {kind === 'WEEKLY' && (
              <div role="group" aria-label="Days" className="flex flex-wrap gap-1.5">
                {DAYS.map((d) => {
                  const on = days.includes(d);
                  return (
                    <button
                      key={d}
                      type="button"
                      aria-pressed={on}
                      onClick={() => setDays(on ? days.filter((x) => x !== d) : [...days, d])}
                      className={clsx('h-9 w-12 rounded border text-sm', on ? 'border-accent bg-accent/15 font-medium' : 'border-line text-muted')}
                    >
                      {DAY_LABEL[d]}
                    </button>
                  );
                })}
              </div>
            )}
            <div className="grid grid-cols-2 gap-4">
              <Field label="Go live at" htmlFor="sc-st">
                <Input id="sc-st" type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} />
              </Field>
              <Field label="Stop at" htmlFor="sc-et" hint={endTime <= startTime ? 'Ends the next day.' : undefined}>
                <Input id="sc-et" type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
              </Field>
            </div>
          </>
        )}
        <Field label="Time zone" htmlFor="sc-tz">
          <Input id="sc-tz" value={tz} onChange={(e) => setTz(e.target.value)} />
        </Field>
        {s.source === 'PLAYLIST' && (
          <div>
            <span className="block text-sm font-medium">Plays</span>
            <Segmented
              label="Plays"
              value={slot.length > 0 ? 'OWN' : 'STREAM'}
              onChange={(v) => (v === 'OWN' ? setPicking(true) : setSlot([]))}
              options={[
                { value: 'STREAM', label: "The stream's playlist" },
                { value: 'OWN', label: 'Its own videos' },
              ]}
            />
            {slot.length > 0 && (
              <p className="mt-2 text-sm text-muted">
                {slot.length} video{slot.length === 1 ? '' : 's'}: {slot.map((m) => m.title).slice(0, 3).join(', ')}
                {slot.length > 3 ? ` and ${slot.length - 3} more` : ''}.{' '}
                <button type="button" className="text-accent hover:underline" onClick={() => setPicking(true)}>
                  Change
                </button>
              </p>
            )}
          </div>
        )}
      </div>
      <MediaPicker
        open={picking}
        onClose={() => setPicking(false)}
        type="VIDEO"
        title="Videos for this time"
        onPick={(items) => {
          setSlot(items.map((m) => ({ id: m.id, title: m.title })));
          setPicking(false);
        }}
      />
    </Dialog>
  );
}
