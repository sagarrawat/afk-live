import clsx from 'clsx';
import { Clock, Image as ImageIcon, Music, Plus, Timer, Trash2, Type, Rss } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { MediaPicker } from '@/components/MediaPicker';
import { isOnAir } from '@/components/status';
import { Button, Field, IconButton, Input, Panel, Segmented, Select, Toggle } from '@/components/ui';
import { put } from '@/lib/api';
import { browserTimeZone } from '@/lib/format';
import type { Overlay, Scene, StreamView } from '@/lib/types';
import { overlayName } from './OverviewTab';
import { TemplatePicker } from './Templates';
import { useStreamUpdate } from './useStreamUpdate';

const SCENE_LABEL: Record<string, string> = { STARTING: 'Starting soon', LIVE: 'Live', BRB: 'Be right back', ENDING: 'Ending' };
const SAFE = 0.05;

function newOverlay(type: Overlay['type']): Overlay {
  const id = `${type.toLowerCase()}-${Math.random().toString(36).slice(2, 7)}`;
  const base: Overlay = { id, type, visible: true, x: SAFE, y: SAFE, width: 0, size: 0.045, opacity: 1, speed: 0, color: '#ffffff', background: null };
  if (type === 'TEXT') return { ...base, text: 'Your text', background: '#99000000' };
  if (type === 'TICKER') return { ...base, y: 0.9, x: 0, width: 1, text: 'Thanks for watching! Subscribe for more.', background: '#cc000000', speed: 120 };
  if (type === 'CLOCK') return { ...base, x: 0.82, timeZone: browserTimeZone(), background: '#99000000' };
  if (type === 'COUNTDOWN') return { ...base, x: 0.4, y: 0.45, size: 0.08, target: new Date(Date.now() + 3600_000).toISOString(), text: 'Starting in' };
  return { ...base, width: 0.15 };
}

/** OV-1..OV-5 and MS-3: a canvas preview with draggable layers, safe-area guides and scenes. */
export function OverlaysTab({ stream: s }: { stream: StreamView }) {
  const [layers, setLayers] = useState<Overlay[]>(s.overlays);
  const [selected, setSelected] = useState<string | null>(s.overlays[0]?.id ?? null);
  const [scene, setScene] = useState<string>(s.source === 'PHONE' ? 'LIVE' : 'ALL');
  const [picking, setPicking] = useState<'image' | null>(null);
  const [imageThumbs, setImageTitles] = useState<Record<number, string>>({});
  const { busy, update } = useStreamUpdate(s.id);
  useEffect(() => setLayers(s.overlays), [s.overlays]);
  const dirty = JSON.stringify(layers) !== JSON.stringify(s.overlays);
  const current = layers.find((l) => l.id === selected) ?? null;
  const vertical = s.orientation === 'VERTICAL';

  const visibleIn = (o: Overlay) => scene === 'ALL' || !o.scene || o.scene === scene;
  const set = (id: string, patch: Partial<Overlay>) => setLayers((ls) => ls.map((l) => (l.id === id ? { ...l, ...patch } : l)));
  const add = (type: AddKind) => {
    if (type === 'IMAGE') {
      setPicking('image');
      return;
    }
    const made = type === 'NOW_PLAYING' ? nowPlayingOverlay(s.music.length > 0) : newOverlay(type);
    const o = { ...made, scene: scene === 'ALL' ? null : scene };
    setLayers((ls) => [...ls, o]);
    setSelected(o.id);
  };

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Segmented
              label="Preview scene"
              value={scene}
              onChange={setScene}
              options={[...(s.source === 'PLAYLIST' ? [{ value: 'ALL', label: 'All' }] : []), ...Object.entries(SCENE_LABEL).map(([value, label]) => ({ value, label }))]}
            />
            <div className="flex flex-wrap gap-2">
              <TemplatePicker stream={s} disabled={dirty} />
              {dirty && (
                <Button size="sm" variant="ghost" onClick={() => setLayers(s.overlays)}>
                  Undo changes
                </Button>
              )}
              <Button size="sm" variant="primary" busy={busy} disabled={!dirty} onClick={() => update(() => put(`/api/streams/${s.id}/overlays`, layers), isOnAir(s.live.state) ? 'Saved. Viewers see it within a second.' : 'Saved.')}>
                Save overlays
              </Button>
            </div>
          </div>
          <Canvas
            vertical={vertical}
            background={sceneColor(s.scenes, scene)}
            layers={layers.filter(visibleIn)}
            selected={selected}
            onSelect={setSelected}
            onMove={(id, x, y) => set(id, { x, y })}
            imageThumbs={imageThumbs}
          />
          <p className="text-sm text-muted">Drag a layer to move it. The dashed line is the safe area: keep text inside it so phones and TVs don't crop it.</p>
        </div>

        <div className="space-y-6">
          <Panel title="Layers" pad={false} action={<AddMenu onAdd={add} />}>
            {layers.length === 0 ? (
              <p className="p-4 text-muted">No overlays yet. Add your logo, a ticker with your socials, or a clock.</p>
            ) : (
              <ul className="divide-y divide-line">
                {layers.map((o) => (
                  <li key={o.id}>
                    <div className={clsx('flex items-center gap-2 px-3 py-2', selected === o.id && 'bg-raised')}>
                      <button className="min-w-0 flex-1 truncate text-left" onClick={() => setSelected(o.id)} aria-pressed={selected === o.id}>
                        {overlayName(o)}
                        {o.scene && <span className="ml-2 text-xs text-muted">{SCENE_LABEL[o.scene]} only</span>}
                      </button>
                      <Toggle label={`Show ${overlayName(o)}`} checked={o.visible} onChange={(v) => set(o.id, { visible: v })} />
                      <IconButton label={`Delete ${overlayName(o)}`} onClick={() => setLayers((ls) => ls.filter((l) => l.id !== o.id))}>
                        <Trash2 className="h-4 w-4" />
                      </IconButton>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          {current && <LayerEditor overlay={current} onChange={(p) => set(current.id, p)} onPickImage={() => setPicking('image')} />}
        </div>
      </div>

      <ScenesPanel stream={s} />

      <MediaPicker
        open={picking === 'image'}
        onClose={() => setPicking(null)}
        type="IMAGE"
        multiple={false}
        title="Choose an image"
        onPick={([img]) => {
          if (!img) return;
          setImageTitles((t) => ({ ...t, [img.id]: img.thumbnailUrl ?? '' }));
          if (current?.type === 'IMAGE' && picking === 'image' && layers.some((l) => l.id === current.id && !l.imageMediaId)) {
            set(current.id, { imageMediaId: img.id });
            return;
          }
          const o = { ...newOverlay('IMAGE'), imageMediaId: img.id, scene: scene === 'ALL' ? null : scene };
          setLayers((ls) => [...ls, o]);
          setSelected(o.id);
        }}
      />
    </div>
  );
}

function sceneColor(scenes: Scene[], scene: string): string {
  const sc = scenes.find((x) => x.name === scene);
  return sc?.color && scene !== 'LIVE' ? sc.color : '#1b2230';
}

export type AddKind = Overlay['type'] | 'NOW_PLAYING';

/** OV-2: a ready-made "now playing" line: the music track when there's music, else the video. */
export function nowPlayingOverlay(hasMusic: boolean): Overlay {
  return { ...newOverlay('TEXT'), id: `now-${Math.random().toString(36).slice(2, 7)}`, y: 0.86, size: 0.04, text: hasMusic ? '♪ {track}' : 'Now playing: {title}' };
}

function AddMenu({ onAdd }: { onAdd: (t: AddKind) => void }) {
  const [open, setOpen] = useState(false);
  const items: [AddKind, string, typeof Type][] = [
    ['IMAGE', 'Image or logo', ImageIcon],
    ['NOW_PLAYING', 'Now playing', Music],
    ['TEXT', 'Text', Type],
    ['TICKER', 'Scrolling ticker', Rss],
    ['CLOCK', 'Clock', Clock],
    ['COUNTDOWN', 'Countdown', Timer],
  ];
  return (
    <div className="relative">
      <Button size="sm" variant="secondary" icon={<Plus className="h-4 w-4" />} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        Add
      </Button>
      {open && (
        <ul className="absolute right-0 z-20 mt-1 w-48 overflow-hidden rounded-md border border-line bg-panel py-1 shadow-xl">
          {items.map(([t, label, Icon]) => (
            <li key={t}>
              <button
                className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm hover:bg-raised"
                onClick={() => {
                  setOpen(false);
                  onAdd(t);
                }}
              >
                <Icon className="h-4 w-4 text-muted" aria-hidden />
                {label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** ARGB "#AARRGGBB" → CSS rgba(); "#RRGGBB" passes through. */
export function cssColor(c: string | null | undefined, fallback = 'transparent'): string {
  if (!c) return fallback;
  if (/^#[0-9a-fA-F]{8}$/.test(c)) {
    const a = parseInt(c.slice(1, 3), 16) / 255;
    const r = parseInt(c.slice(3, 5), 16);
    const g = parseInt(c.slice(5, 7), 16);
    const b = parseInt(c.slice(7, 9), 16);
    return `rgba(${r}, ${g}, ${b}, ${a.toFixed(2)})`;
  }
  return c;
}

function Canvas({
  vertical,
  background,
  layers,
  selected,
  onSelect,
  onMove,
  imageThumbs,
}: {
  vertical: boolean;
  background: string;
  layers: Overlay[];
  selected: string | null;
  onSelect: (id: string) => void;
  onMove: (id: string, x: number, y: number) => void;
  imageThumbs: Record<number, string>;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(0);
  const drag = useRef<{ id: string; dx: number; dy: number } | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setHeight(el.clientHeight));
    ro.observe(el);
    setHeight(el.clientHeight);
    return () => ro.disconnect();
  }, []);
  const now = useMemo(() => new Date(), []);

  const down = (e: ReactPointerEvent, o: Overlay) => {
    const rect = ref.current!.getBoundingClientRect();
    onSelect(o.id);
    drag.current = { id: o.id, dx: (e.clientX - rect.left) / rect.width - o.x, dy: (e.clientY - rect.top) / rect.height - o.y };
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
  };
  const move = (e: ReactPointerEvent) => {
    if (!drag.current || !ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const x = Math.max(0, Math.min(0.98, (e.clientX - rect.left) / rect.width - drag.current.dx));
    const y = Math.max(0, Math.min(0.98, (e.clientY - rect.top) / rect.height - drag.current.dy));
    onMove(drag.current.id, Math.round(x * 1000) / 1000, Math.round(y * 1000) / 1000);
  };

  return (
    <div
      ref={ref}
      onPointerMove={move}
      onPointerUp={() => (drag.current = null)}
      className={clsx('relative mx-auto w-full select-none overflow-hidden rounded-md border border-line', vertical ? 'aspect-[9/16] max-w-xs' : 'aspect-video')}
      style={{ background }}
      aria-label="Overlay preview"
    >
      <div aria-hidden className="pointer-events-none absolute border border-dashed border-white/25" style={{ inset: `${SAFE * 100}%` }} />
      {layers.map((o) => {
        const fontPx = Math.max(8, o.size * height);
        const style: React.CSSProperties = {
          left: `${o.x * 100}%`,
          top: `${o.y * 100}%`,
          opacity: o.visible ? o.opacity : 0.35,
          color: cssColor(o.color, '#fff'),
          background: cssColor(o.background),
          fontSize: fontPx,
          lineHeight: 1.2,
        };
        let content: React.ReactNode;
        if (o.type === 'IMAGE') {
          const thumb = o.imageMediaId ? imageThumbs[o.imageMediaId] || `/api/media/${o.imageMediaId}/thumbnail` : null;
          style.width = `${o.width * 100}%`;
          content = thumb ? <img src={thumb} alt="" className="block w-full" draggable={false} /> : <span className="block p-2 text-xs">Choose an image</span>;
        } else if (o.type === 'CLOCK') {
          content = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', timeZone: o.timeZone || undefined }).format(now);
        } else if (o.type === 'COUNTDOWN') {
          content = `${o.text ? o.text + ' ' : ''}00:59:59`;
        } else if (o.type === 'TICKER') {
          style.width = `${(o.width || 1) * 100}%`;
          content = <span className="block truncate">{o.text}</span>;
        } else {
          content = o.text;
        }
        return (
          <div
            key={o.id}
            role="button"
            tabIndex={0}
            aria-label={`${overlayName(o)}, drag to move`}
            onPointerDown={(e) => down(e, o)}
            onKeyDown={(e) => {
              const step = e.shiftKey ? 0.05 : 0.01;
              const moves: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
              const m = moves[e.key];
              if (m) {
                e.preventDefault();
                onMove(o.id, Math.max(0, Math.min(0.98, o.x + m[0])), Math.max(0, Math.min(0.98, o.y + m[1])));
              }
            }}
            className={clsx('absolute cursor-move whitespace-pre px-[0.3em] py-[0.1em] font-semibold', selected === o.id && 'outline outline-2 outline-accent')}
            style={style}
          >
            {content}
          </div>
        );
      })}
    </div>
  );
}

function LayerEditor({ overlay: o, onChange, onPickImage }: { overlay: Overlay; onChange: (p: Partial<Overlay>) => void; onPickImage: () => void }) {
  const hasText = o.type === 'TEXT' || o.type === 'TICKER' || o.type === 'COUNTDOWN';
  return (
    <Panel title={`Edit ${overlayName(o).toLowerCase()}`}>
      <div className="space-y-4">
        {hasText && (
          <Field
            label={o.type === 'COUNTDOWN' ? 'Label' : 'Text'}
            htmlFor="ov-text"
            hint={o.type === 'COUNTDOWN' ? undefined : 'Fills in as it plays: {title} the video, {next} the one after, {track} the music.'}
          >
            <Input id="ov-text" value={o.text ?? ''} maxLength={300} onChange={(e) => onChange({ text: e.target.value })} />
          </Field>
        )}
        {o.type === 'IMAGE' && (
          <>
            <Button size="sm" onClick={onPickImage}>
              {o.imageMediaId ? 'Change image' : 'Choose image'}
            </Button>
            <Slider label="Width" value={o.width} min={0.03} max={1} onChange={(v) => onChange({ width: v })} />
          </>
        )}
        {o.type !== 'IMAGE' && <Slider label="Text size" value={o.size} min={0.015} max={0.2} onChange={(v) => onChange({ size: v })} />}
        {o.type === 'CLOCK' && (
          <Field label="Time zone" htmlFor="ov-tz">
            <Input id="ov-tz" value={o.timeZone ?? ''} placeholder="Asia/Kolkata" onChange={(e) => onChange({ timeZone: e.target.value })} />
          </Field>
        )}
        {o.type === 'COUNTDOWN' && (
          <Field label="Counts down to" htmlFor="ov-target">
            <Input
              id="ov-target"
              type="datetime-local"
              value={o.target ? toLocalInput(o.target) : ''}
              onChange={(e) => onChange({ target: e.target.value ? new Date(e.target.value).toISOString() : null })}
            />
          </Field>
        )}
        {o.type === 'TICKER' && <Slider label="Scroll speed" value={o.speed} min={20} max={400} step={10} format={(v) => `${Math.round(v)} px/s`} onChange={(v) => onChange({ speed: v })} />}
        {o.type !== 'IMAGE' && (
          <div className="grid grid-cols-2 gap-4">
            <ColorField label="Text colour" value={o.color ?? '#ffffff'} onChange={(c) => onChange({ color: c })} />
            <ColorField label="Background" value={o.background ?? ''} allowNone onChange={(c) => onChange({ background: c })} />
          </div>
        )}
        <Slider label="Opacity" value={o.opacity} min={0.1} max={1} onChange={(v) => onChange({ opacity: v })} />
        <Field label="Show in" htmlFor="ov-scene">
          <Select id="ov-scene" value={o.scene ?? ''} onChange={(e) => onChange({ scene: e.target.value || null })}>
            <option value="">Every scene</option>
            {Object.entries(SCENE_LABEL).map(([v, l]) => (
              <option key={v} value={v}>
                {l} only
              </option>
            ))}
          </Select>
        </Field>
      </div>
    </Panel>
  );
}

function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function Slider({ label, value, min, max, step = 0.005, onChange, format }: { label: string; value: number; min: number; max: number; step?: number; onChange: (v: number) => void; format?: (v: number) => string }) {
  const id = `sl-${label.replace(/\s/g, '-').toLowerCase()}`;
  return (
    <div>
      <label htmlFor={id} className="flex justify-between text-sm font-medium">
        {label}
        <span className="tnum text-muted">{format ? format(value) : `${Math.round(value * 100)}%`}</span>
      </label>
      <input id={id} type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} className="mt-2 w-full accent-[rgb(var(--accent))]" />
    </div>
  );
}

function ColorField({ label, value, onChange, allowNone }: { label: string; value: string; onChange: (c: string | null) => void; allowNone?: boolean }) {
  const rgb = value && value.length === 9 ? '#' + value.slice(3) : value || '#000000';
  const alpha = value && value.length === 9 ? value.slice(1, 3) : 'ff';
  const id = `c-${label.replace(/\s/g, '-').toLowerCase()}`;
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      <div className="flex items-center gap-2">
        <input id={id} type="color" value={rgb} onChange={(e) => onChange(alpha === 'ff' ? e.target.value : `#${alpha}${e.target.value.slice(1)}`)} className="h-9 w-12 cursor-pointer rounded border border-line bg-bg" />
        {allowNone && (
          <button type="button" className={clsx('text-sm', value ? 'text-muted hover:text-ink' : 'text-ink')} onClick={() => onChange(value ? null : '#99000000')}>
            {value ? 'None' : 'Add'}
          </button>
        )}
      </div>
    </div>
  );
}

function ScenesPanel({ stream: s }: { stream: StreamView }) {
  const [scenes, setScenes] = useState<Scene[]>(s.scenes);
  const [picking, setPicking] = useState<string | null>(null);
  const { busy, update } = useStreamUpdate(s.id);
  useEffect(() => setScenes(s.scenes), [s.scenes]);
  const dirty = JSON.stringify(scenes) !== JSON.stringify(s.scenes);
  const set = (name: string, p: Partial<Scene>) => setScenes((sc) => sc.map((x) => (x.name === name ? { ...x, ...p } : x)));
  return (
    <Panel
      title="Scenes"
      action={
        <Button size="sm" variant="primary" disabled={!dirty} busy={busy} onClick={() => update(() => put(`/api/streams/${s.id}/scenes`, scenes), 'Scenes saved.')}>
          Save scenes
        </Button>
      }
    >
      <p className="mb-4 text-muted">
        {s.source === 'PHONE'
          ? 'Switch scenes while live from the Overview tab or the remote. BRB shows automatically when your phone drops.'
          : 'Scenes are used when you start in a scene other than Live.'}
      </p>
      <ul className="grid gap-3 sm:grid-cols-2">
        {scenes
          .filter((sc) => SCENE_LABEL[sc.name])
          .map((sc) => (
            <li key={sc.name} className="rounded border border-line p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium">{SCENE_LABEL[sc.name]}</span>
                {sc.name !== 'LIVE' && (
                  <label className="flex items-center gap-2 text-sm text-muted">
                    Music
                    <Toggle label={`Music in ${SCENE_LABEL[sc.name]}`} checked={sc.music} onChange={(v) => set(sc.name, { music: v })} />
                  </label>
                )}
              </div>
              {sc.name !== 'LIVE' && (
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <input type="color" aria-label={`${SCENE_LABEL[sc.name]} background colour`} value={sc.color && sc.color.length === 7 ? sc.color : '#1b2230'} onChange={(e) => set(sc.name, { color: e.target.value })} className="h-8 w-10 rounded border border-line bg-bg" />
                  <Button size="sm" variant="ghost" onClick={() => setPicking(sc.name)}>
                    {sc.backgroundMediaId ? 'Change background' : 'Use an image or video'}
                  </Button>
                  {sc.backgroundMediaId && (
                    <Button size="sm" variant="ghost" onClick={() => set(sc.name, { backgroundMediaId: null, backgroundKey: null, backgroundType: null })}>
                      Remove
                    </Button>
                  )}
                </div>
              )}
              {sc.name === 'LIVE' && <p className="mt-2 text-sm text-muted">{s.source === 'PHONE' ? 'Your phone, full screen.' : 'Your playlist.'}</p>}
            </li>
          ))}
      </ul>
      <MediaPicker
        open={!!picking}
        onClose={() => setPicking(null)}
        type="VIDEO_OR_IMAGE"
        multiple={false}
        title="Choose a background"
        onPick={([m]) => m && picking && set(picking, { backgroundMediaId: m.id, backgroundType: m.type })}
      />
    </Panel>
  );
}
