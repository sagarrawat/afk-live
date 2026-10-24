import clsx from 'clsx';
import { ListVideo, Plus, Smartphone } from 'lucide-react';
import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { GoLiveButton, StopButton } from '@/components/live-actions';
import { HealthDot, isOnAir, LiveBadge, reachProblem, Uptime } from '@/components/status';
import { Button, Dialog, Empty, ErrorNote, Field, Input, PageHeader, Segmented, Select, Spinner, useAction } from '@/components/ui';
import { post } from '@/lib/api';
import { keys, useInvalidate, useMe, useQualityOptions, useStreams } from '@/lib/queries';
import type { StreamSummary, StreamView } from '@/lib/types';

/** ON-4: every stream with its state, uptime and what's playing, problems first. */
export function Home() {
  const { data: streams, isLoading, error } = useStreams();
  const { data: me } = useMe();
  const [creating, setCreating] = useState(false);

  if (isLoading) return <Spinner label="Loading your streams" />;
  if (me && !me.onboarded && streams && streams.length === 0) return <Navigate to="/studio/setup" replace />;

  const sorted = [...(streams ?? [])].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
  const live = sorted.filter((s) => isOnAir(s.live.state)).length;

  return (
    <>
      <PageHeader
        title="Streams"
        description={streams && streams.length > 0 ? (live > 0 ? `${live} of ${streams.length} on air right now.` : 'Nothing is on air right now.') : undefined}
        actions={
          <Button variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
            New stream
          </Button>
        }
      />
      <ErrorNote error={error} />
      {streams && streams.length === 0 ? (
        <Empty
          icon={<ListVideo className="h-8 w-8" />}
          title="No streams yet"
          action={
            <div className="flex flex-wrap justify-center gap-2">
              <Button variant="primary" onClick={() => setCreating(true)}>
                Create a stream
              </Button>
              <Link to="/studio/setup" className="inline-flex h-10 items-center rounded px-4 text-muted hover:bg-raised hover:text-ink">
                Use the setup guide
              </Link>
            </div>
          }
        >
          A stream is a playlist of your videos that runs 24/7, or your phone's camera with overlays added in the cloud.
        </Empty>
      ) : (
        <ul className="divide-y divide-line overflow-hidden rounded-md border border-line bg-panel">
          {sorted.map((s) => (
            <StreamRow key={s.id} stream={s} />
          ))}
        </ul>
      )}
      <NewStreamDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function rank(s: StreamSummary): number {
  const h = s.live.health?.overall;
  if (isOnAir(s.live.state)) return h === 'red' ? 0 : h === 'amber' ? 1 : 2;
  if (s.live.state === 'ERROR') return 3;
  return 4;
}

function StreamRow({ stream: s }: { stream: StreamSummary }) {
  const onAir = isOnAir(s.live.state);
  const np = s.live.nowPlaying;
  return (
    <li className="relative">
      {/* The tally: a red edge on every stream that's on air. */}
      <span aria-hidden className={clsx('absolute inset-y-0 left-0 w-1', onAir ? 'bg-live' : s.live.state === 'ERROR' ? 'bg-bad/60' : 'bg-transparent')} />
      <div className="flex flex-col gap-3 py-4 pl-5 pr-4 sm:flex-row sm:items-center">
        <Link to={`/studio/streams/${s.id}`} className="min-w-0 flex-1 group">
          <span className="flex items-center gap-2">
            {s.source === 'PHONE' ? <Smartphone className="h-4 w-4 shrink-0 text-muted" aria-label="Phone stream" /> : <ListVideo className="h-4 w-4 shrink-0 text-muted" aria-label="Playlist stream" />}
            <span className="truncate font-semibold group-hover:underline">{s.name}</span>
            <LiveBadge state={s.live.state} reach={s.live.reach} />
            {onAir && <HealthDot level={s.live.health?.overall} />}
          </span>
          <span className={clsx('mt-1 block truncate text-sm', onAir && reachProblem(s.live) ? 'text-bad' : 'text-muted')}>
            {onAir && reachProblem(s.live)
              ? reachProblem(s.live)
              : onAir
              ? s.source === 'PHONE'
                ? s.live.inputLive === false
                  ? 'Phone disconnected: viewers see your BRB scene'
                  : `Phone connected${s.live.scene ? `, ${s.live.scene.toLowerCase()} scene` : ''}`
                : np?.title
                  ? `Now playing ${np.title}`
                  : 'Starting the playlist'
              : s.live.state === 'ERROR'
                ? (s.live.endReason ?? 'Stopped with a problem')
                : s.source === 'PHONE'
                  ? `Phone stream, ${s.qualityHeight}p`
                  : `${s.playlistCount} video${s.playlistCount === 1 ? '' : 's'}, ${s.qualityHeight}p`}
          </span>
        </Link>
        <div className="flex items-center gap-4">
          {onAir && <Uptime live={s.live} className="text-md font-medium" />}
          {onAir ? <StopButton stream={s} size="sm" /> : <GoLiveButton stream={s} size="sm" />}
        </div>
      </div>
    </li>
  );
}

export function NewStreamDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [name, setName] = useState('');
  const [source, setSource] = useState<'PLAYLIST' | 'PHONE'>('PLAYLIST');
  const [orientation, setOrientation] = useState<'LANDSCAPE' | 'VERTICAL'>('LANDSCAPE');
  const [quality, setQuality] = useState('');
  const { data: options } = useQualityOptions();
  const { busy, run } = useAction();
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  const chosen = quality || defaultQuality(options ?? []);
  const [height, fps] = chosen.split('x').map(Number);

  const create = async () => {
    let created: StreamView | undefined;
    const ok = await run(async () => {
      created = await post<StreamView>('/api/streams', { name: name.trim() || 'My stream', source, orientation, qualityHeight: height, fps });
    });
    if (ok && created) {
      await invalidate([...keys.streams]);
      onClose();
      navigate(`/studio/streams/${created.id}/${source === 'PHONE' ? 'phone' : 'playlist'}`);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="New stream"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} onClick={create}>
            Create stream
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <Field label="Name" htmlFor="ns-name" hint="Only you see this.">
          <Input id="ns-name" value={name} placeholder="Lofi beats 24/7" maxLength={100} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <div className="space-y-1.5">
          <span className="block text-sm font-medium">What viewers see</span>
          <div className="grid gap-2 sm:grid-cols-2">
            <SourceOption active={source === 'PLAYLIST'} onClick={() => setSource('PLAYLIST')} icon={<ListVideo className="h-5 w-5" />} title="My videos, on repeat" text="A playlist from your library that keeps your channel live around the clock." />
            <SourceOption active={source === 'PHONE'} onClick={() => setSource('PHONE')} icon={<Smartphone className="h-5 w-5" />} title="My phone, live" text="Stream from a phone app; we add overlays and keep the stream up if your signal drops." />
          </div>
        </div>
        <div className="flex flex-wrap gap-6">
          <div className="space-y-1.5">
            <span className="block text-sm font-medium">Shape</span>
            <Segmented label="Shape" value={orientation} onChange={setOrientation} options={[{ value: 'LANDSCAPE', label: 'Landscape 16:9' }, { value: 'VERTICAL', label: 'Vertical 9:16' }]} />
          </div>
          <Field label="Quality" htmlFor="ns-quality">
            <Select id="ns-quality" value={chosen} onChange={(e) => setQuality(e.target.value)} className="w-44">
              {(options ?? []).map((o) => (
                <option key={`${o.height}x${o.fps}`} value={`${o.height}x${o.fps}`} disabled={!o.allowed}>
                  {o.height}p {o.fps} fps{o.allowed ? '' : ' (upgrade)'}
                </option>
              ))}
            </Select>
          </Field>
        </div>
      </div>
    </Dialog>
  );
}

/** 1080p30 when the plan allows it, otherwise the best allowed 30 fps option. */
export function defaultQuality(options: { height: number; fps: number; allowed: boolean }[]): string {
  const allowed = options.filter((o) => o.allowed);
  const pick =
    allowed.find((o) => o.height === 1080 && o.fps === 30) ??
    [...allowed].filter((o) => o.fps === 30).sort((a, b) => b.height - a.height)[0] ??
    allowed[0];
  return pick ? `${pick.height}x${pick.fps}` : '720x30';
}

function SourceOption({ active, onClick, icon, title, text }: { active: boolean; onClick: () => void; icon: React.ReactNode; title: string; text: string }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={clsx('rounded-md border p-3 text-left transition-colors', active ? 'border-accent bg-accent/10' : 'border-line hover:border-muted/60')}
    >
      <span className={clsx('flex items-center gap-2 font-medium', active && 'text-ink')}>
        {icon}
        {title}
      </span>
      <span className="mt-1 block text-sm text-muted">{text}</span>
    </button>
  );
}
