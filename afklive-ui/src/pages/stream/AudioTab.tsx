import { useEffect, useState } from 'react';
import { Panel, Segmented } from '@/components/ui';
import { patch } from '@/lib/api';
import type { StreamView } from '@/lib/types';
import { ItemsEditor } from './PlaylistTab';
import { useStreamUpdate } from './useStreamUpdate';

/** AU-1..AU-3: background music, what happens to the videos' own sound, and levels. */
export function AudioTab({ stream: s }: { stream: StreamView }) {
  const { update } = useStreamUpdate(s.id);
  return (
    <div className="space-y-6">
      {s.source === 'PLAYLIST' && (
        <Panel title="Sound from your videos">
          <Segmented
            label="Sound from your videos"
            value={s.audioMode}
            onChange={(v) => update(() => patch(`/api/streams/${s.id}`, { audioMode: v }))}
            options={[
              { value: 'KEEP', label: 'Keep it' },
              { value: 'MIX', label: 'Keep it, with music under' },
              { value: 'MUTE', label: 'Mute it, music only' },
            ]}
          />
          <div className="mt-6 grid gap-6 sm:grid-cols-2">
            {s.audioMode !== 'MUTE' && <Level label="Video volume" value={s.videoVolume} onCommit={(v) => update(() => patch(`/api/streams/${s.id}`, { videoVolume: v }))} />}
            {s.audioMode !== 'KEEP' && <Level label="Music volume" value={s.musicVolume} onCommit={(v) => update(() => patch(`/api/streams/${s.id}`, { musicVolume: v }))} />}
          </div>
        </Panel>
      )}
      {s.source === 'PHONE' && (
        <Panel title="Music level">
          <p className="mb-4 text-muted">Music plays under your Starting soon, BRB and Ending scenes, and keeps playing if your phone drops.</p>
          <Level label="Music volume" value={s.musicVolume} onCommit={(v) => update(() => patch(`/api/streams/${s.id}`, { musicVolume: v }))} />
        </Panel>
      )}
      <ItemsEditor stream={s} kind="music" />
    </div>
  );
}

function Level({ label, value, onCommit }: { label: string; value: number; onCommit: (v: number) => void }) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  const id = label.replace(/\s/g, '-').toLowerCase();
  return (
    <div>
      <label htmlFor={id} className="flex justify-between text-sm font-medium">
        {label}
        <span className="tnum text-muted">{Math.round(v * 100)}%</span>
      </label>
      <input
        id={id}
        type="range"
        min={0}
        max={1.5}
        step={0.05}
        value={v}
        onChange={(e) => setV(Number(e.target.value))}
        onPointerUp={() => v !== value && onCommit(v)}
        onKeyUp={() => v !== value && onCommit(v)}
        className="mt-2 w-full accent-[rgb(var(--accent))]"
      />
    </div>
  );
}
