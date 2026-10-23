import clsx from 'clsx';
import { ListVideo, Smartphone, Upload as UploadIcon } from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { destinationUrl } from '@/components/AddDestination';
import { MediaPicker } from '@/components/MediaPicker';
import { GoLiveButton } from '@/components/live-actions';
import { Button, Field, Input, Panel, useAction } from '@/components/ui';
import { patch, post, put } from '@/lib/api';
import { browserTimeZone } from '@/lib/format';
import { keys, useInvalidate, useMe, useStream } from '@/lib/queries';
import type { Destination, StreamView } from '@/lib/types';
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
        <h1 className="text-xl font-semibold tracking-tight">Set up your first stream</h1>
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

function Content({ streamId, onNext }: { streamId: number; onNext: () => void }) {
  const { data: s } = useStream(streamId);
  const uploads = useUploads();
  const input = useRef<HTMLInputElement>(null);
  const [picking, setPicking] = useState(false);
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
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
      <div className="mt-6 flex justify-end">
        <Button
          variant="primary"
          busy={busy}
          disabled={uploading || ids.length === 0}
          onClick={async () => {
            if (await run(() => put(`/api/streams/${s.id}/playlist`, { mediaIds: ids }))) {
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
  const [added, setAdded] = useState(false);
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  if (!s) return null;
  const save = () =>
    run(async () => {
      const d = await post<Destination>('/api/destinations', { name: 'YouTube', key: destinationUrl('YOUTUBE', key), type: 'RTMP', selected: true });
      await put(`/api/streams/${s.id}/destinations`, [{ destinationId: d.id, enabled: true }]);
      await invalidate([...keys.stream(s.id)], [...keys.destinations]);
      setAdded(true);
    }, 'YouTube connected.');
  return (
    <Panel title="Send it to YouTube">
      {!added ? (
        <>
          <ol className="list-decimal space-y-1 pl-5 text-muted">
            <li>
              Open{' '}
              <a href="https://studio.youtube.com/channel/UC/livestreaming" target="_blank" rel="noreferrer" className="text-accent hover:underline">
                YouTube Studio's live dashboard
              </a>{' '}
              and choose Stream.
            </li>
            <li>Copy the stream key and paste it here. No Google sign-in needed.</li>
          </ol>
          <div className="mt-4">
            <Field label="YouTube stream key" htmlFor="yt-key">
              <Input id="yt-key" value={key} autoComplete="off" spellCheck={false} placeholder="xxxx-xxxx-xxxx-xxxx-xxxx" onChange={(e) => setKey(e.target.value)} />
            </Field>
          </div>
          <div className="mt-6 flex flex-wrap justify-between gap-2">
            <Button variant="ghost" onClick={() => onFinish(`/studio/streams/${s.id}/destinations`)}>
              Use another platform
            </Button>
            <Button variant="primary" busy={busy} disabled={!key.trim()} onClick={save}>
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
          <div className="mt-6 flex flex-wrap items-center gap-3">
            <GoLiveButton stream={s} />
            <Button variant="ghost" onClick={() => onFinish(`/studio/streams/${s.id}`)}>
              Not yet, open the stream
            </Button>
          </div>
          {s.live.state !== 'DRAFT' && s.live.state !== 'ENDED' && (
            <Button className="mt-4" onClick={() => onFinish(`/studio/streams/${s.id}`)}>
              Watch it start
            </Button>
          )}
        </>
      )}
    </Panel>
  );
}
