import { useQueries } from '@tanstack/react-query';
import clsx from 'clsx';
import { CircleCheck, ListVideo, Loader2, Smartphone, TriangleAlert, Upload as UploadIcon } from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { destinationUrl, readKey } from '@/components/AddDestination';
import { TestNote, useDestinationTests } from '@/components/DestinationTest';
import { MediaPicker } from '@/components/MediaPicker';
import { GoLiveButton } from '@/components/live-actions';
import { HelpLink } from '@/components/HelpLink';
import { Button, Field, Input, Panel, Segmented, useAction } from '@/components/ui';
import { get, patch, post, put } from '@/lib/api';
import { HELP, YOUTUBE_STUDIO_LIVE } from '@/lib/help';
import { fitNotes, frameRateNote, suggestedShape, VERTICAL_ON_YOUTUBE, type Orientation } from '@/lib/shape';
import { browserTimeZone } from '@/lib/format';
import { keys, useInvalidate, useMe, useStream } from '@/lib/queries';
import type { Destination, LiveState, MediaItem, StreamView } from '@/lib/types';
import { useUploads } from '@/lib/uploads-context';
import { PhoneTab } from './stream/PhoneTab';

/** ON-2: live in under five minutes. Three steps, each skippable; ends live or as a saved draft. */
export function Setup() {
  const [step, setStep] = useState(0);
  const [streamId, setStreamId] = useState<number | null>(null);
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  const finish = async (to: string) => {
    await patch('/api/me', { onboarded: true }).catch(() => {});
    await invalidate([...keys.me], [...keys.streams]);
    navigate(to);
  };
  const steps = ['What you stream', 'Your content', 'Where it goes'];
  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-8 flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold tracking-tight">Set up your first stream</h1>
        <button className="text-sm text-muted hover:text-ink" onClick={() => finish(streamId ? `/studio/streams/${streamId}` : '/studio')}>
          Skip setup
        </button>
      </div>
      <ol className="mb-8 grid grid-cols-3 gap-2" aria-label="Setup progress">
        {steps.map((label, i) => (
          <li key={label} aria-current={i === step ? 'step' : undefined}>
            <span className={clsx('block h-1 rounded-full', i <= step ? 'bg-accent' : 'bg-raised')} />
            <span className={clsx('mt-2 block text-sm', i === step ? 'font-medium' : 'text-muted')}>
              {i + 1}. {label}
            </span>
          </li>
        ))}
      </ol>
      {step === 0 && (
        <ChooseSource
          onCreated={(id) => {
            setStreamId(id);
            setStep(1);
          }}
        />
      )}
      {step === 1 && streamId && <Content streamId={streamId} onNext={() => setStep(2)} />}
      {step === 2 && streamId && <Destination streamId={streamId} onFinish={finish} />}
    </div>
  );
}

function ChooseSource({ onCreated }: { onCreated: (id: number) => void }) {
  const { busy, run } = useAction();
  const create = (source: 'PLAYLIST' | 'PHONE') =>
    run(async () => {
      const s = await post<StreamView>('/api/streams', {
        name: source === 'PHONE' ? 'Live from my phone' : 'My 24/7 stream',
        source,
        orientation: 'LANDSCAPE',
        qualityHeight: 720,
        fps: 30,
      });
      await patch(`/api/streams/${s.id}`, { timeZone: browserTimeZone() }).catch(() => {});
      onCreated(s.id);
    });
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Choice disabled={busy} icon={<ListVideo className="h-6 w-6" />} title="My videos, 24/7" onClick={() => create('PLAYLIST')}>
        Upload videos once and keep your channel live around the clock, even while you sleep.
      </Choice>
      <Choice disabled={busy} icon={<Smartphone className="h-6 w-6" />} title="Live from my phone" onClick={() => create('PHONE')}>
        Stream from a free phone app. We add overlays and keep you on air when your signal drops. No subscriber minimum.
      </Choice>
    </div>
  );
}

function Choice({ icon, title, children, onClick, disabled }: { icon: React.ReactNode; title: string; children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button disabled={disabled} onClick={onClick} className="rounded-md border border-line bg-panel p-5 text-left hover:border-accent disabled:opacity-60">
      <span className="text-accent">{icon}</span>
      <span className="mt-3 block text-md font-semibold">{title}</span>
      <span className="mt-1 block text-muted">{children}</span>
    </button>
  );
}

/** Fresh uploads, re-read until they've been checked (probing gives their size and frame rate). */
function useUploadedMedia(ids: number[]): MediaItem[] {
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['media', 'item', id],
      queryFn: () => get<MediaItem>(`/api/media/${id}`),
      refetchInterval: (q: { state: { data?: MediaItem } }) => (q.state.data?.status === 'PROCESSING' ? 4_000 : false),
    })),
  });
  return results.flatMap((r) => (r.data ? [r.data] : []));
}

/**
 * ST-1: the stream's shape, preselected from the videos (Vertical when most are vertical), with what
 * YouTube does with vertical lives, how videos of the other shape come out, and 60 fps clips.
 */
export function ShapeChoice({
  videos,
  value,
  suggested,
  onChange,
  fit,
  fps,
  planMaxFps,
  checking = false,
}: {
  videos: Parameters<typeof fitNotes>[0];
  value: Orientation;
  suggested: Orientation | null;
  onChange: (v: Orientation) => void;
  fit: StreamView['fit'];
  fps: number;
  planMaxFps?: number;
  /** Videos still being checked: their shape isn't known yet. */
  checking?: boolean;
}) {
  if (!suggested) return checking ? <p className="mt-4 text-sm text-muted">Checking your videos. We'll suggest the stream's shape in a moment.</p> : null;
  const notes = [...fitNotes(videos, value, fit), frameRateNote(videos, fps, planMaxFps)].filter((n): n is string => !!n);
  return (
    <div className="mt-5 rounded-md border border-line p-4">
      <p className="mb-2 text-sm font-medium">Stream shape</p>
      <Segmented
        label="Stream shape"
        value={value}
        onChange={onChange}
        options={[
          { value: 'LANDSCAPE', label: 'Landscape 16:9' },
          { value: 'VERTICAL', label: 'Vertical 9:16' },
        ]}
      />
      {suggested === 'VERTICAL' && (
        <p className="mt-3 text-sm">
          Most of your videos are vertical, so we picked Vertical 9:16. <span className="text-muted">{VERTICAL_ON_YOUTUBE}</span>
        </p>
      )}
      {notes.map((n) => (
        <p key={n} className="mt-2 text-sm text-muted">
          {n}
        </p>
      ))}
      <p className="mt-2 text-sm">
        <HelpLink to={HELP.shape}>Vertical or landscape?</HelpLink>
      </p>
    </div>
  );
}

function Content({ streamId, onNext }: { streamId: number; onNext: () => void }) {
  const { data: s } = useStream(streamId);
  const { data: me } = useMe();
  const uploads = useUploads();
  const input = useRef<HTMLInputElement>(null);
  const [picking, setPicking] = useState(false);
  const [shape, setShape] = useState<Orientation | null>(null);
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const uploadedIds = uploads.entries.flatMap((e) => (e.progress.state === 'done' && e.progress.media ? [e.progress.media.id] : []));
  const uploaded = useUploadedMedia(uploadedIds);
  if (!s) return null;
  if (s.source === 'PHONE') {
    return (
      <div className="space-y-6">
        <PhoneTab stream={s} />
        <div className="flex justify-end">
          <Button variant="primary" onClick={onNext}>
            Next: where it goes
          </Button>
        </div>
      </div>
    );
  }
  const mine = uploads.entries.filter((e) => e.progress.state === 'done' && e.progress.media).map((e) => e.progress.media!);
  const ids = Array.from(new Set([...s.playlist.map((p) => p.id), ...mine.map((m) => m.id)]));
  const uploading = uploads.entries.some((e) => e.progress.state === 'uploading' || e.progress.state === 'finishing');
  const videos = [...s.playlist, ...uploaded.filter((m) => !s.playlist.some((p) => p.id === m.id))];
  const suggested = suggestedShape(videos);
  const chosen = shape ?? suggested ?? s.orientation;
  return (
    <Panel title="Add your videos">
      <p className="text-muted">Upload a few videos, or choose ones already in your library. They play back to back and repeat.</p>
      <div className="mt-4 flex flex-wrap gap-2">
        <input ref={input} type="file" multiple accept="video/*,.mkv,.flv" className="hidden" onChange={(e) => e.target.files && (uploads.add(e.target.files), (e.target.value = ''))} />
        <Button variant="primary" icon={<UploadIcon className="h-4 w-4" />} onClick={() => input.current?.click()}>
          Upload videos
        </Button>
        <Button onClick={() => setPicking(true)}>Choose from library</Button>
      </div>
      {uploads.entries.length > 0 && (
        <ul className="mt-4 space-y-1.5 text-sm">
          {uploads.entries.map((e) => (
            <li key={e.key} className="flex justify-between gap-3">
              <span className="truncate">{e.file.name}</span>
              <span className="tnum text-muted">{e.progress.state === 'done' ? 'Uploaded' : e.progress.state === 'failed' ? 'Failed' : `${Math.round((e.progress.sent / Math.max(1, e.progress.total)) * 100)}%`}</span>
            </li>
          ))}
        </ul>
      )}
      {s.playlist.length > 0 && <p className="mt-4 text-sm">{s.playlist.length} video{s.playlist.length === 1 ? '' : 's'} in the playlist.</p>}
      <ShapeChoice videos={videos} value={chosen} suggested={suggested} onChange={setShape} fit={s.fit ?? 'BLUR'} fps={s.fps} planMaxFps={me?.plan.maxFps} checking={uploaded.some((m) => m.status === 'PROCESSING')} />
      <div className="mt-6 flex justify-end">
        <Button
          variant="primary"
          busy={busy}
          disabled={uploading || ids.length === 0}
          onClick={async () => {
            const saved = await run(async () => {
              if (chosen !== s.orientation) await patch(`/api/streams/${s.id}`, { orientation: chosen });
              await put(`/api/streams/${s.id}/playlist`, { mediaIds: ids });
            });
            if (saved) {
              await invalidate([...keys.stream(s.id)]);
              onNext();
            }
          }}
        >
          {uploading ? 'Waiting for uploads' : 'Next: where it goes'}
        </Button>
      </div>
      <MediaPicker
        open={picking}
        onClose={() => setPicking(false)}
        type="VIDEO"
        title="Choose videos"
        onPick={(items) => run(() => put(`/api/streams/${s.id}/playlist`, { mediaIds: [...ids, ...items.map((i) => i.id)] })).then(() => invalidate([...keys.stream(s.id)]))}
      />
    </Panel>
  );
}

function Destination({ streamId, onFinish }: { streamId: number; onFinish: (to: string) => void }) {
  const { data: s } = useStream(streamId);
  const { data: me } = useMe();
  const [key, setKey] = useState('');
  const [added, setAdded] = useState<Destination | null>(null);
  const { busy, run } = useAction();
  const { tests, test } = useDestinationTests();
  const invalidate = useInvalidate();
  const { problem } = readKey('YOUTUBE', key);
  if (!s) return null;
  const save = () =>
    run(async () => {
      const d = await post<Destination>('/api/destinations', { name: 'YouTube', key: destinationUrl('YOUTUBE', key), type: 'RTMP', selected: true });
      await put(`/api/streams/${s.id}/destinations`, [{ destinationId: d.id, enabled: true }]);
      await invalidate([...keys.stream(s.id)], [...keys.destinations]);
      setAdded(d);
      void test(d); // MS-6: check the key now, without holding up the next step
    }, 'YouTube stream key saved.');
  return (
    <Panel title="Send it to YouTube">
      {!added ? (
        <>
          <ol className="list-decimal space-y-1 pl-5 text-muted">
            <li>
              Open{' '}
              <a href={YOUTUBE_STUDIO_LIVE} target="_blank" rel="noreferrer" className="text-accent hover:underline">
                YouTube Studio's live dashboard
              </a>{' '}
              and choose Stream.
            </li>
            <li>Copy the stream key and paste it here. No Google sign-in needed.</li>
          </ol>
          <p className="mt-2 text-sm">
            <HelpLink to={HELP.streamKey}>Can't find it? Step by step, with the usual mix-ups</HelpLink>
          </p>
          <div className="mt-4">
            <Field label="YouTube stream key" htmlFor="yt-key" error={problem}>
              <Input id="yt-key" value={key} autoComplete="off" spellCheck={false} aria-invalid={!!problem} placeholder="xxxx-xxxx-xxxx-xxxx-xxxx" onChange={(e) => setKey(e.target.value)} />
            </Field>
          </div>
          <div className="mt-6 flex flex-wrap justify-between gap-2">
            <Button variant="ghost" onClick={() => onFinish(`/studio/streams/${s.id}/destinations`)}>
              Use another platform
            </Button>
            <Button variant="primary" busy={busy} disabled={!key.trim() || !!problem} onClick={save}>
              Connect YouTube
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-md font-semibold">You're ready to go live.</p>
          <p className="mt-1 text-muted">
            {s.source === 'PHONE' ? 'Press Go live, then start streaming in your phone app. Until your phone connects, viewers see your Starting soon scene.' : 'Press Go live. Your playlist is usually on YouTube within 15 seconds.'}
            {me?.plan.id === 'FREE' && ' The Free plan includes 10 hours a month at 720p.'}
          </p>
          <TestNote state={tests[added.id]} platform="YOUTUBE" className="mt-3" />
          {firstLiveStep(s.live).step === 'off' ? (
            <div className="mt-6 flex flex-wrap items-center gap-3">
              <GoLiveButton stream={s} />
              <Button variant="ghost" onClick={() => onFinish(`/studio/streams/${s.id}`)}>
                Not yet, open the stream
              </Button>
            </div>
          ) : (
            <FirstLiveProof live={s.live} onFinish={onFinish} streamId={s.id} />
          )}
        </>
      )}
    </Panel>
  );
}

/**
 * ON-8: where the first go-live is, in the setup's terms. "on" once YouTube has been receiving the
 * stream steadily (RE-8 "On YouTube"); "problem" with the reason in plain words while it isn't.
 */
export function firstLiveStep(live: LiveState): { step: 'off' | 'waiting' | 'on' | 'problem' | 'stopped'; message?: string | null } {
  if (live.state === 'DRAFT') return { step: 'off' };
  if (live.state === 'ENDED' || live.state === 'ERROR') return live.endReason ? { step: 'stopped', message: live.endReason } : { step: 'off' };
  const destinations = live.health?.destinations ?? [];
  const youtube = destinations.find((d) => d.platform === 'YouTube') ?? destinations[0];
  if (youtube?.reach === 'ON') return { step: 'on' };
  if (youtube?.reach === 'NOT_REACHING') return { step: 'problem', message: youtube.message };
  return { step: 'waiting' };
}

/** ON-8: after Go live, the setup waits for proof that YouTube receives the stream, then says so. */
export function FirstLiveProof({ live, streamId, onFinish }: { live: LiveState; streamId: number; onFinish: (to: string) => void }) {
  const { step, message } = firstLiveStep(live);
  const open = () => onFinish(`/studio/streams/${streamId}`);
  if (step === 'on') {
    return (
      <div role="status" className="mt-6 rounded-md border border-ok/40 bg-ok/10 p-4">
        <p className="flex items-center gap-2 text-md font-semibold">
          <CircleCheck className="h-5 w-5 shrink-0 text-ok" aria-hidden /> You're live on YouTube
        </p>
        <p className="mt-1 text-muted">YouTube is receiving your stream. It can take a minute before viewers see it. You're all set.</p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button variant="primary" onClick={open}>
            Open my stream
          </Button>
          <a href={YOUTUBE_STUDIO_LIVE} target="_blank" rel="noreferrer" className="text-accent hover:underline">
            See it in YouTube Studio
          </a>
        </div>
      </div>
    );
  }
  if (step === 'problem' || step === 'stopped') {
    return (
      <div role="alert" className="mt-6 rounded-md border border-warn/40 bg-warn/10 p-4">
        <p className="flex items-center gap-2 text-md font-semibold">
          <TriangleAlert className="h-5 w-5 shrink-0 text-warn" aria-hidden /> {step === 'stopped' ? 'The stream stopped' : "YouTube isn't receiving it yet"}
        </p>
        {message && <p className="mt-1 break-words">{message}</p>}
        <p className="mt-1 text-muted">{step === 'stopped' ? 'Fix the problem, then go live again.' : "We keep trying. Most often the stream key is wrong or YouTube's live dashboard isn't open yet."}</p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button variant="primary" onClick={() => onFinish(`/studio/streams/${streamId}/destinations`)}>
            Check the stream key
          </Button>
          <Button variant="ghost" onClick={open}>
            Open my stream
          </Button>
        </div>
      </div>
    );
  }
  return (
    <div role="status" className="mt-6 rounded-md border border-line p-4">
      <p className="flex items-center gap-2 font-medium">
        <Loader2 className="h-5 w-5 shrink-0 animate-spin text-accent" aria-hidden /> Checking that YouTube is receiving your stream
      </p>
      <p className="mt-1 text-sm text-muted">This usually takes under a minute. You can leave this page; the stream keeps going.</p>
      <Button className="mt-4" variant="ghost" onClick={open}>
        Open my stream
      </Button>
    </div>
  );
}
