import { Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { MediaPicker, StatusBadge } from '@/components/MediaPicker';
import { isOnAir } from '@/components/status';
import { Button, Empty, Field, IconButton, Input, Panel, Segmented } from '@/components/ui';
import { patch, put } from '@/lib/api';
import { clock, duration } from '@/lib/format';
import type { MediaRef, StreamView } from '@/lib/types';
import { SortableList } from './SortableList';
import { useStreamUpdate } from './useStreamUpdate';
import { resetVerticalHint, ShapeNotes, VerticalHint } from './VerticalHint';

/** PL-1..PL-4: what plays, in what order, how many times. Changes apply live between videos. */
export function PlaylistTab({ stream: s }: { stream: StreamView }) {
  return <ItemsEditor stream={s} kind="playlist" />;
}

export function ItemsEditor({ stream: s, kind }: { stream: StreamView; kind: 'playlist' | 'music' }) {
  const source = kind === 'playlist' ? s.playlist : s.music;
  const [items, setItems] = useState<MediaRef[]>(source);
  const [picking, setPicking] = useState(false);
  const [hintKey, setHintKey] = useState(0);
  const { busy, update } = useStreamUpdate(s.id);
  useEffect(() => setItems(source), [source]);
  const dirty = items.map((i) => i.id).join(',') !== source.map((i) => i.id).join(',');
  const total = items.reduce((t, i) => t + (i.durationSeconds ?? 0), 0);
  const onAir = isOnAir(s.live.state);
  const save = (next: MediaRef[]) =>
    update(() => put(`/api/streams/${s.id}/${kind}`, { mediaIds: next.map((i) => i.id) }), onAir ? 'Saved. The change applies after the current track.' : 'Saved.');

  const order = kind === 'playlist' ? s.playbackOrder : s.musicOrder;
  return (
    <div className="space-y-6">
      {kind === 'playlist' && <VerticalHint key={hintKey} stream={s} />}
      {kind === 'playlist' && <ShapeNotes stream={s} />}
      <Panel
        title={kind === 'playlist' ? 'Videos' : 'Music'}
        pad={false}
        action={
          <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setPicking(true)}>
            Add from library
          </Button>
        }
      >
        {items.length === 0 ? (
          <div className="p-4">
            <Empty title={kind === 'playlist' ? 'No videos yet' : 'No music yet'}>
              {kind === 'playlist' ? 'Add videos from your library. They play back to back with no gaps.' : 'Add tracks to play under your videos or scenes.'}
            </Empty>
          </div>
        ) : (
          <>
            <SortableList
              items={items}
              keyOf={(m, i) => `${m.id}-${i}`}
              onReorder={setItems}
              render={(m, i) => (
                <>
                  <span className="tnum w-6 text-right text-sm text-muted">{i + 1}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{m.title}</span>
                    {m.status !== 'READY' && (
                      <span className="mt-0.5 block">
                        <StatusBadge item={m} />
                      </span>
                    )}
                  </span>
                  {kind === 'playlist' && (
                    <select
                      aria-label={`How ${m.title} fills the frame`}
                      title={dirty ? 'Save the order first' : 'How this video fills the frame'}
                      disabled={dirty || busy}
                      value={m.fit ?? ''}
                      onChange={(e) => update(() => put(`/api/streams/${s.id}/playlist/${i}/fit`, { fit: e.target.value || null }), 'Saved.')}
                      className="hidden h-8 rounded border border-line bg-bg px-2 text-sm text-muted sm:block"
                    >
                      <option value="">Like the stream</option>
                      <option value="BLUR">Blurred background</option>
                      <option value="FIT">Bars</option>
                      <option value="FILL">Fill and crop</option>
                    </select>
                  )}
                  <span className="tnum text-sm text-muted">{clock(m.durationSeconds)}</span>
                  <IconButton label={`Remove ${m.title}`} onClick={() => setItems(items.filter((_, j) => j !== i))}>
                    <Trash2 className="h-4 w-4" />
                  </IconButton>
                </>
              )}
            />
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line px-4 py-3">
              <span className="tnum text-sm text-muted">
                {items.length} {kind === 'playlist' ? 'video' : 'track'}
                {items.length === 1 ? '' : 's'}, {duration(total)}
              </span>
              <div className="flex gap-2">
                {dirty && (
                  <Button size="sm" variant="ghost" onClick={() => setItems(source)}>
                    Undo changes
                  </Button>
                )}
                <Button size="sm" variant="primary" disabled={!dirty} busy={busy} onClick={() => save(items)}>
                  Save order
                </Button>
              </div>
            </div>
          </>
        )}
      </Panel>

      <Panel title="Playback">
        <div className="flex flex-wrap gap-8">
          <div className="space-y-1.5">
            <span className="block text-sm font-medium">Order</span>
            <Segmented
              label="Order"
              value={order}
              onChange={(v) => update(() => patch(`/api/streams/${s.id}`, kind === 'playlist' ? { playbackOrder: v } : { musicOrder: v }))}
              options={[
                { value: 'IN_ORDER', label: 'In order' },
                { value: 'SHUFFLE', label: 'Shuffle' },
              ]}
            />
          </div>
          {kind === 'playlist' && <LoopField stream={s} />}
        </div>
      </Panel>

      <MediaPicker
        open={picking}
        onClose={() => setPicking(false)}
        type={kind === 'playlist' ? 'VIDEO' : 'AUDIO'}
        title={kind === 'playlist' ? 'Add videos' : 'Add music'}
        onPick={(picked) => {
          const next = [
            ...items,
            ...picked.map((p) => ({ id: p.id, title: p.title, durationSeconds: p.durationSeconds, width: p.width, height: p.height, contentType: p.type, status: p.status, fps: p.fps })),
          ];
          if (kind === 'playlist' && items.length === 0) {
            // ST-1: the first videos decide the shape; offer Vertical again if they're mostly vertical.
            resetVerticalHint(s.id);
            setHintKey((k) => k + 1);
          }
          setItems(next);
          save(next);
        }}
      />
    </div>
  );
}

function LoopField({ stream: s }: { stream: StreamView }) {
  const [value, setValue] = useState(String(s.loopCount));
  const { update } = useStreamUpdate(s.id);
  useEffect(() => setValue(String(s.loopCount)), [s.loopCount]);
  return (
    <Field label="Repeat" htmlFor="loops" hint="0 repeats forever, so the channel never goes offline.">
      <div className="flex items-center gap-2">
        <Input
          id="loops"
          type="number"
          min={0}
          max={1000}
          className="w-24"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onBlur={() => {
            const n = Math.max(0, Math.min(1000, Math.round(Number(value) || 0)));
            if (n !== s.loopCount) update(() => patch(`/api/streams/${s.id}`, { loopCount: n }), 'Saved.');
          }}
        />
        <span className="text-muted">{Number(value) === 0 ? 'times (forever)' : Number(value) === 1 ? 'pass' : 'passes'}</span>
      </div>
    </Field>
  );
}
