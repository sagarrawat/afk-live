import { CheckCircle2, CircleAlert, Gauge, Play, Square } from 'lucide-react';
import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, patch, post } from '@/lib/api';
import { appLink } from '@/lib/links';
import { keys, useInvalidate } from '@/lib/queries';
import type { CapacityEstimate, LiveState, Preflight, Quality, StreamSummary, StreamView } from '@/lib/types';
import { Button, Dialog, Field, Input, Segmented, useAction, useToast } from './ui';
import { isOnAir } from './status';

type AnyStream = Pick<StreamView | StreamSummary, 'id' | 'name' | 'live' | 'source'>;

export function PreflightList({ preflight, streamId, onNavigate }: { preflight: Preflight; streamId: number; onNavigate?: () => void }) {
  return (
    <ul className="space-y-2">
      {preflight.checks.map((c) => {
        const to = appLink(c.fix, streamId);
        return (
          <li key={c.id} className="flex items-start gap-2.5">
            {c.ok ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-ok" aria-label="Ready" /> : <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-warn" aria-label="Needs fixing" />}
            <span className="min-w-0">
              <span className={c.ok ? 'text-muted' : undefined}>{c.message}</span>
              {!c.ok && to && (
                <Link to={to} onClick={onNavigate} className="ml-2 text-sm text-accent hover:underline">
                  Fix this
                </Link>
              )}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** GL-7: "1080p 60 fps" for a quality. */
export const qualityLabel = (q: Quality) => `${q.height}p ${q.fps} fps`;

/**
 * GL-7: everything is ready, but by our estimate the servers can't keep up with this quality right
 * now. One tap takes the lighter quality that fits; the creator may also go live anyway.
 */
export function CapacityWarning({
  capacity,
  busy,
  onUseSuggestion,
  onGoAnyway,
  onCancel,
}: {
  capacity: CapacityEstimate;
  busy: boolean;
  onUseSuggestion: (q: Quality) => void;
  onGoAnyway: () => void;
  onCancel: () => void;
}) {
  const s = capacity.suggestion;
  return (
    <Dialog
      open
      onClose={onCancel}
      title="This quality may not keep up right now"
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant="secondary" onClick={onGoAnyway} disabled={busy}>
            Go live anyway
          </Button>
          {s && (
            <Button variant="primary" busy={busy} onClick={() => onUseSuggestion(s)}>
              Use {qualityLabel(s)}
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-3">
        <p>{capacity.message}</p>
        <p className="text-sm text-muted">
          This is an estimate: about {capacity.needCpus.toFixed(1)} processor cores needed
          {capacity.freeCpus != null ? `, ${capacity.freeCpus.toFixed(1)} free on our servers right now` : ''}. Going live anyway can work if
          a server frees up; if it doesn&apos;t, we&apos;ll tell you and you can lower the quality then.
        </p>
      </div>
    </Dialog>
  );
}

/** GL-1: one press goes live; if a pre-flight check fails, say which and where to fix it. */
export function GoLiveButton({ stream, size = 'md', scene }: { stream: AnyStream; size?: 'sm' | 'md'; scene?: string }) {
  const [preflight, setPreflight] = useState<Preflight | null>(null);
  const [capacity, setCapacity] = useState<CapacityEstimate | null>(null);
  const [busy, setBusy] = useState(false);
  // A double tap lands before React re-renders the button as busy: guard the request itself.
  const inFlight = useRef(false);
  const toast = useToast();
  const invalidate = useInvalidate();
  const go = async (opts: { anyway?: boolean; quality?: Quality } = {}) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      if (opts.quality) {
        await patch(`/api/streams/${stream.id}`, { qualityHeight: opts.quality.height, fps: opts.quality.fps });
      }
      await post(`/api/streams/${stream.id}/live`, { ...(scene ? { scene } : {}), ...(opts.anyway ? { anyway: true } : {}) });
      setCapacity(null);
      toast(`${stream.name} is starting. It's usually live within 15 seconds.`, 'ok');
      await invalidate([...keys.streams], [...keys.stream(stream.id)]);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.body && typeof e.body === 'object' && 'preflight' in e.body) {
        const pf = (e.body as { preflight: Preflight }).preflight;
        if (pf.ok && pf.capacity && !pf.capacity.fits) {
          setCapacity(pf.capacity);
        } else {
          setCapacity(null);
          setPreflight(pf);
        }
      } else {
        toast(e instanceof Error ? e.message : String(e), 'bad');
      }
      if (opts.quality) await invalidate([...keys.stream(stream.id)]);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };
  return (
    <>
      <Button variant="live" size={size} busy={busy} onClick={() => go()} icon={<Play className="h-4 w-4" />}>
        Go live
      </Button>
      <Dialog open={!!preflight} onClose={() => setPreflight(null)} title={`${stream.name} can't go live yet`} footer={<Button onClick={() => setPreflight(null)}>Close</Button>}>
        {preflight && <PreflightList preflight={preflight} streamId={stream.id} onNavigate={() => setPreflight(null)} />}
      </Dialog>
      {capacity && (
        <CapacityWarning
          capacity={capacity}
          busy={busy}
          onUseSuggestion={(q) => go({ quality: q })}
          onGoAnyway={() => go({ anyway: true })}
          onCancel={() => setCapacity(null)}
        />
      )}
    </>
  );
}

/** RE-10: the stream has run slower than real time for a minute or more; say so and give the fix. */
export function SlowNotice({ live, streamId }: { live: Pick<LiveState, 'slowSince'>; streamId: number }) {
  if (!live.slowSince) return null;
  return (
    <p role="status" className="mt-2 flex items-start gap-2 text-sm text-warn">
      <Gauge className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <span>
        Running slower than real time, so viewers see it play slowly. The server can&apos;t keep up with this quality.{' '}
        <Link to={`/studio/streams/${streamId}/settings`} className="text-accent hover:underline">
          Lower it to 720p 30 fps
        </Link>{' '}
        (stop, change it, and go live again).
      </span>
    </p>
  );
}

/** GL-3: stop now, at a time, or when the playlist finishes. */
export function StopButton({ stream, size = 'md', timeZone }: { stream: AnyStream; size?: 'sm' | 'md'; timeZone?: string }) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'NOW' | 'AT' | 'AFTER_PLAYLIST'>('NOW');
  const [at, setAt] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  if (!isOnAir(stream.live.state)) return null;
  const tz = timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  const submit = async () => {
    const ok = await run(
      () => post(`/api/streams/${stream.id}/stop`, { mode, at: mode === 'AT' ? at : null, timeZone: tz }),
      mode === 'NOW' ? `Stopping ${stream.name}.` : 'Stop scheduled.',
    );
    if (ok) {
      setOpen(false);
      invalidate([...keys.streams], [...keys.stream(stream.id)]);
    }
  };
  return (
    <>
      <Button variant="secondary" size={size} onClick={() => setOpen(true)} icon={<Square className="h-3.5 w-3.5" />}>
        Stop
      </Button>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title={`Stop ${stream.name}`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Keep streaming
            </Button>
            <Button variant="danger" busy={busy} onClick={submit} disabled={mode === 'AT' && !at}>
              {mode === 'NOW' ? 'Stop now' : 'Schedule stop'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Segmented
            label="When to stop"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'NOW', label: 'Now' },
              { value: 'AT', label: 'At a time' },
              ...(stream.source === 'PLAYLIST' ? [{ value: 'AFTER_PLAYLIST' as const, label: 'After this playlist pass' }] : []),
            ]}
          />
          {mode === 'NOW' && <p className="text-muted">Viewers see the stream end. Destinations disconnect.</p>}
          {mode === 'AT' && (
            <Field label="Stop at" hint={`In your time zone (${tz}).`} htmlFor="stop-at">
              <Input id="stop-at" type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} />
            </Field>
          )}
          {mode === 'AFTER_PLAYLIST' && <p className="text-muted">The current video and the rest of the playlist play out, then the stream ends.</p>}
        </div>
      </Dialog>
    </>
  );
}
