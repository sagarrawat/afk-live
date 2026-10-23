import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { isOnAir } from '@/components/status';
import { Button, Dialog, Field, Input, Panel, Segmented, Select, useAction } from '@/components/ui';
import { del, patch, post } from '@/lib/api';
import { keys, useInvalidate, useQualityOptions } from '@/lib/queries';
import type { StreamView } from '@/lib/types';
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

      <DuplicateStream stream={s} />
      <DangerZone stream={s} />
    </div>
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
