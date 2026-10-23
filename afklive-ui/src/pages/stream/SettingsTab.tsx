import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { isOnAir } from '@/components/status';
import { Button, Dialog, Field, Input, Panel, Segmented, Select, useAction } from '@/components/ui';
import clsx from 'clsx';
import { del, patch, post, put } from '@/lib/api';
import { keys, useInvalidate, useQualityOptions } from '@/lib/queries';
import type { Fit, StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

export function SettingsTab({ stream: s }: { stream: StreamView }) {
  const [name, setName] = useState(s.name);
  const [tz, setTz] = useState(s.timeZone);
  const { data: options } = useQualityOptions();
  const { busy, update } = useStreamUpdate(s.id);
  const onAir = isOnAir(s.live.state);
  useEffect(() => {
    setName(s.name);
    setTz(s.timeZone);
  }, [s.name, s.timeZone]);

  return (
    <div className="space-y-6">
      <Panel title="General">
        <form
          className="space-y-5"
          onSubmit={(e) => {
            e.preventDefault();
            update(() => patch(`/api/streams/${s.id}`, { name: name.trim(), timeZone: tz.trim() }), 'Saved.');
          }}
        >
          <Field label="Name" htmlFor="st-name">
            <Input id="st-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Time zone" htmlFor="st-tz" hint="Schedules, the clock overlay and stop times use this.">
            <Input id="st-tz" value={tz} onChange={(e) => setTz(e.target.value)} />
          </Field>
          <Button type="submit" variant="primary" busy={busy} disabled={name.trim() === s.name && tz.trim() === s.timeZone}>
            Save changes
          </Button>
        </form>
      </Panel>

      <Panel title="Picture">
        {onAir && <p className="mb-4 text-sm text-muted">Shape and quality change the encoder, so they can be changed once the stream is off air.</p>}
        <div className="flex flex-wrap gap-8">
          <div className="space-y-1.5">
            <span className="block text-sm font-medium">Shape</span>
            <Segmented
              label="Shape"
              value={s.orientation}
              onChange={(v) => update(() => patch(`/api/streams/${s.id}`, { orientation: v }))}
              options={[
                { value: 'LANDSCAPE', label: 'Landscape 16:9', disabled: onAir },
                { value: 'VERTICAL', label: 'Vertical 9:16', disabled: onAir },
              ]}
            />
          </div>
          <Field label="Quality" htmlFor="st-q">
            <Select
              id="st-q"
              className="w-48"
              disabled={onAir}
              value={`${s.qualityHeight}x${s.fps}`}
              onChange={(e) => {
                const [h, f] = e.target.value.split('x').map(Number);
                update(() => patch(`/api/streams/${s.id}`, { qualityHeight: h, fps: f }), 'Quality saved.');
              }}
            >
              {(options ?? [{ height: s.qualityHeight, fps: s.fps, allowed: true }]).map((o) => (
                <option key={`${o.height}x${o.fps}`} value={`${o.height}x${o.fps}`} disabled={!o.allowed}>
                  {o.height}p {o.fps} fps{o.allowed ? '' : ' (upgrade)'}
                </option>
              ))}
            </Select>
          </Field>
        </div>
      </Panel>

      <FitPanel stream={s} />
      <DuplicateStream stream={s} />
      <DangerZone stream={s} />
    </div>
  );
}

const FIT_OPTIONS: { value: Fit; label: string; hint: string }[] = [
  { value: 'BLUR', label: 'Blurred background', hint: 'The whole picture over a soft, blurred copy of itself. Best for phone videos.' },
  { value: 'FIT', label: 'Bars', hint: 'The whole picture, with black bars at the sides or top.' },
  { value: 'FILL', label: 'Fill and crop', hint: 'Fills the frame; the edges of the picture are cut off.' },
];

/** A picture of each choice: a tall video on a wide frame. */
export function FitPreview({ fit }: { fit: Fit }) {
  return (
    <svg viewBox="0 0 64 36" className="h-9 w-16 rounded-sm" aria-hidden="true">
      <rect width="64" height="36" fill={fit === 'BLUR' ? 'rgb(var(--sun) / 0.35)' : '#000'} />
      {fit === 'BLUR' && <rect width="64" height="36" fill="rgb(var(--raised))" opacity="0.5" />}
      {fit === 'FILL' ? <rect width="64" height="36" fill="rgb(var(--sun))" /> : <rect x="22" width="20" height="36" fill="rgb(var(--sun))" />}
    </svg>
  );
}

/** PL-6 / MS-10: how videos that aren't the stream's shape fill the frame. */
function FitPanel({ stream: s }: { stream: StreamView }) {
  const { busy, update } = useStreamUpdate(s.id);
  const onAir = isOnAir(s.live.state);
  return (
    <Panel title="Videos that aren't the stream's shape">
      <div role="radiogroup" aria-label="Fit" className="grid gap-2 sm:grid-cols-3">
        {FIT_OPTIONS.map((o) => (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={s.fit === o.value}
            disabled={busy}
            onClick={() => update(() => put(`/api/streams/${s.id}/presentation`, { fit: o.value }), onAir ? 'Saved. It applies from the next video.' : 'Saved.')}
            className={clsx('flex items-start gap-3 rounded-md border p-3 text-left', s.fit === o.value ? 'border-accent bg-accent/10' : 'border-line hover:border-muted/60')}
          >
            <FitPreview fit={o.value} />
            <span>
              <span className="block text-sm font-medium">{o.label}</span>
              <span className="block text-xs text-muted">{o.hint}</span>
            </span>
          </button>
        ))}
      </div>
      <p className="mt-3 text-sm text-muted">You can choose differently for a single video in the playlist.</p>
    </Panel>
  );
}

/** ST-4: start a similar channel from this one. */
function DuplicateStream({ stream: s }: { stream: StreamView }) {
  const { busy, run } = useAction();
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  return (
    <Panel title="Duplicate stream">
      <p className="mb-4 text-muted">Makes a copy with the same playlist, music, overlays and settings. Destinations start switched off and schedules aren't copied.</p>
      <Button
        busy={busy}
        onClick={async () => {
          let copy: StreamView | undefined;
          if (await run(async () => { copy = await post<StreamView>(`/api/streams/${s.id}/duplicate`); }, 'Copy made.')) {
            await invalidate([...keys.streams]);
            if (copy) navigate(`/studio/streams/${copy.id}`);
          }
        }}
      >
        Duplicate {s.name}
      </Button>
    </Panel>
  );
}

function DangerZone({ stream: s }: { stream: StreamView }) {
  const [confirm, setConfirm] = useState(false);
  const { busy, run } = useAction();
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  const onAir = isOnAir(s.live.state);
  return (
    <Panel title="Delete stream">
      <p className="mb-4 text-muted">Deletes the stream's playlist, overlays and schedule. Your library files stay.</p>
      <Button variant="danger" disabled={onAir} title={onAir ? 'Stop the stream first' : undefined} onClick={() => setConfirm(true)}>
        Delete {s.name}
      </Button>
      <Dialog
        open={confirm}
        onClose={() => setConfirm(false)}
        title={`Delete ${s.name}?`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirm(false)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              busy={busy}
              onClick={async () => {
                if (await run(() => del(`/api/streams/${s.id}`), `${s.name} deleted.`)) {
                  await invalidate([...keys.streams]);
                  navigate('/studio');
                }
              }}
            >
              Delete stream
            </Button>
          </>
        }
      >
        <p>This can't be undone.</p>
      </Dialog>
    </Panel>
  );
}
