import { CheckCircle2, CircleAlert, Gauge, Loader2, Play, Square } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, del, get, patch, post } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { appLink } from '@/lib/links';
import { keys, useInvalidate } from '@/lib/queries';
import type { CapacityEstimate, DestinationChecks, LiveState, Preflight, Quality, StreamSummary, StreamView } from '@/lib/types';
import { Button, buttonClass, Dialog, Field, Input, Segmented, useAction, useToast } from './ui';
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

/** GL-1: each destination's test before going live, in plain words. */
export function DestinationChecksList({ checks }: { checks: DestinationChecks }) {
  return (
    <ul className="space-y-2.5">
      {checks.checks.map((c) => (
        <li key={c.destinationId} className="flex items-start gap-2.5">
          {c.state === 'TESTING' ? (
            <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin text-muted" aria-label="Testing" />
          ) : c.state === 'FAILED' ? (
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-bad" aria-label="Not reaching" />
          ) : (
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-ok" aria-label="Ready" />
          )}
          <span className="min-w-0">
            <span className="block font-medium">{c.name}</span>
            <span className={c.state === 'FAILED' ? 'block text-sm text-bad' : 'block text-sm text-muted'}>{c.message}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}

/** The destinations part of the dialog's title: what is happening, or which platform can't be reached. */
export function checksTitle(checks: DestinationChecks): string {
  if (checks.checks.some((c) => c.state === 'TESTING')) return 'Checking your destinations';
  const failed = checks.checks.filter((c) => c.state === 'FAILED');
  if (failed.length === 1) return `Not reaching ${failed[0].platform ?? failed[0].name}`;
  return "Some destinations can't be reached";
}

/** How often the dialog asks how the tests are going. */
export const CHECK_POLL_MS = 1500;

const CAPACITY_TITLE = 'This quality may not keep up right now';

/** A checks answer we can use (an older server, or a failed request, gives none: nothing to wait for). */
function usable(c: unknown): DestinationChecks | null {
  return c && typeof c === 'object' && Array.isArray((c as DestinationChecks).checks) ? (c as DestinationChecks) : null;
}

/** GL-7: the capacity part of the dialog, in plain words and labelled as an estimate. */
function CapacityText({ capacity }: { capacity: CapacityEstimate }) {
  return (
    <div className="space-y-3">
      <p>{capacity.message}</p>
      <p className="text-sm text-muted">
        This is an estimate: about {capacity.needCpus.toFixed(1)} processor cores needed
        {capacity.freeCpus != null ? `, ${capacity.freeCpus.toFixed(1)} free on our servers right now` : ''}. Going live anyway can work if a
        server frees up; if it doesn&apos;t, we&apos;ll tell you and you can lower the quality then.
      </p>
    </div>
  );
}

/**
 * One press goes live. Before that, in one dialog:
 * <ul>
 *   <li>GL-1: each destination that hasn't passed a test in 24 hours (or whose key changed) gets a
 *       10-second test; a failure shows the reason, "Fix key" and "Go live anyway";</li>
 *   <li>GL-7: a quality the servers can't keep up with right now shows the estimate, the lighter quality
 *       that fits, and "Go live anyway".</li>
 * </ul>
 * Neither check overrides the other: passing the tests still respects the capacity warning, and the
 * creator's "anyway" (or the lighter quality) after seeing both goes live. A failed pre-flight check
 * (nothing to play, plan limits) shows the list and where to fix it, as before.
 */
export function GoLiveButton({ stream, size = 'md', scene }: { stream: AnyStream; size?: 'sm' | 'md'; scene?: string }) {
  const [preflight, setPreflight] = useState<Preflight | null>(null);
  const [checks, setChecks] = useState<DestinationChecks | null>(null);
  const [capacity, setCapacity] = useState<CapacityEstimate | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  // A double tap lands before React re-renders the button as busy: guard the request itself.
  const inFlight = useRef(false);
  const openRef = useRef(false);
  const toast = useToast();
  const invalidate = useInvalidate();

  const close = () => {
    openRef.current = false;
    setOpen(false);
    setChecks(null);
    setCapacity(null);
  };

  const go = async (opts: { anyway?: boolean; quality?: Quality } = {}) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      if (opts.quality) {
        await patch(`/api/streams/${stream.id}`, { qualityHeight: opts.quality.height, fps: opts.quality.fps });
      }
      await post(`/api/streams/${stream.id}/live`, { ...(scene ? { scene } : {}), ...(opts.anyway ? { anyway: true } : {}) });
      close();
      toast(`${stream.name} is starting. It's usually live within 15 seconds.`, 'ok');
      await invalidate([...keys.streams], [...keys.stream(stream.id)]);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.body && typeof e.body === 'object' && 'preflight' in e.body) {
        const pf = (e.body as { preflight: Preflight }).preflight;
        if (pf.ok && pf.capacity && !pf.capacity.fits) {
          // The capacity warning joins the same dialog (with what the destination tests said, if anything).
          setCapacity(pf.capacity);
          openRef.current = true;
          setOpen(true);
        } else {
          close();
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

  const press = async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    let c: DestinationChecks | null = null;
    let pf: Preflight | null = null;
    try {
      // Both at once: the destination tests (GL-1) and the pre-flight with its capacity estimate (GL-7).
      const [checked, preflighted] = await Promise.allSettled([
        post<DestinationChecks>(`/api/streams/${stream.id}/destination-checks`),
        get<Preflight>(`/api/streams/${stream.id}/preflight`),
      ]);
      c = checked.status === 'fulfilled' ? usable(checked.value) : null; // can't check: never stand in the way
      pf = preflighted.status === 'fulfilled' && preflighted.value && typeof preflighted.value === 'object' ? preflighted.value : null;
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
    if (pf && pf.ok === false && Array.isArray(pf.checks)) {
      setPreflight(pf);
      return;
    }
    const cap = pf?.ok && pf.capacity && !pf.capacity.fits ? pf.capacity : null;
    if ((c && !c.ready) || cap) {
      setChecks(c && !c.ready ? c : null);
      setCapacity(cap);
      openRef.current = true;
      setOpen(true);
      return;
    }
    await go();
  };

  // While a test runs, ask again. When every test passed: go live by itself, unless the capacity
  // warning still needs the creator's answer (then the dialog stays, with that part only).
  useEffect(() => {
    if (!open || !checks || !checks.checks.some((c) => c.state === 'TESTING')) return;
    const t = setTimeout(async () => {
      try {
        const next = usable(await get<DestinationChecks>(`/api/streams/${stream.id}/destination-checks`));
        if (!openRef.current) return;
        if (!next || next.ready) {
          setChecks(null);
          if (!capacity) await go();
        } else {
          setChecks(next);
        }
      } catch {
        if (openRef.current) setChecks({ ...checks });
      }
    }, CHECK_POLL_MS);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, checks, capacity, stream.id]);

  const testing = !!checks?.checks.some((c) => c.state === 'TESTING');
  const destFailed = !!checks && !testing && !checks.ready;
  const suggestion = capacity?.suggestion;
  // DS-8: "Fix key" opens the edit for the destination that failed, not a list of switches.
  const failedId = checks?.checks.find((c) => c.state === 'FAILED')?.destinationId;
  const title = testing ? 'Checking your destinations' : destFailed && capacity ? 'Before you go live' : destFailed && checks ? checksTitle(checks) : CAPACITY_TITLE;
  return (
    <>
      <Button variant="live" size={size} busy={busy} onClick={press} icon={<Play className="h-4 w-4" />}>
        Go live
      </Button>
      <Dialog
        open={open}
        onClose={close}
        title={title}
        footer={
          testing ? (
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
          ) : (
            <>
              <Button variant="ghost" onClick={close} disabled={busy}>
                Cancel
              </Button>
              <Button variant="secondary" onClick={() => go({ anyway: !!capacity })} disabled={busy}>
                Go live anyway
              </Button>
              {suggestion && (
                <Button variant={destFailed ? 'secondary' : 'primary'} busy={busy} onClick={() => go({ quality: suggestion })}>
                  Use {qualityLabel(suggestion)}
                </Button>
              )}
              {destFailed && (
                <Link to={failedId != null ? `/studio/destinations?edit=${failedId}` : `/studio/streams/${stream.id}/destinations`} onClick={close} className={buttonClass('primary')}>
                  Fix key
                </Link>
              )}
            </>
          )
        }
      >
        <div className="space-y-4">
          {checks && (
            <div className="space-y-3">
              <DestinationChecksList checks={checks} />
              {testing ? (
                <p className="text-sm text-muted">We send a short test to each destination that hasn&apos;t been checked in the last day. It goes live by itself when they pass.</p>
              ) : (
                <p className="text-sm text-muted">If you go live anyway, the stream starts but this destination won&apos;t show it until the key is fixed.</p>
              )}
            </div>
          )}
          {capacity && (
            <div className={checks ? 'border-t border-line pt-4' : undefined}>
              {checks && <p className="mb-2 font-medium">{CAPACITY_TITLE}</p>}
              <CapacityText capacity={capacity} />
            </div>
          )}
        </div>
      </Dialog>
      <Dialog open={!!preflight} onClose={() => setPreflight(null)} title={`${stream.name} can't go live yet`} footer={<Button onClick={() => setPreflight(null)}>Close</Button>}>
        {preflight && <PreflightList preflight={preflight} streamId={stream.id} onNavigate={() => setPreflight(null)} />}
      </Dialog>
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

/**
 * GL-3: a planned stop, shown on the stream with a way to take it back: "Stops 23:00" (a stop time) and
 * "Ends after this playlist pass". Cancelling keeps the stream on air; nothing reconnects.
 */
export function PlannedStop({ stream, timeZone }: { stream: Pick<StreamView, 'id' | 'live'>; timeZone?: string }) {
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const live = stream.live;
  if (!isOnAir(live.state) || live.state === 'STOPPING' || (!live.stopAt && !live.finishAfterPass)) return null;
  const cancel = async (path: string, done: string) => {
    if (await run(() => del(`/api/streams/${stream.id}/${path}`), done)) {
      invalidate([...keys.streams], [...keys.stream(stream.id)]);
    }
  };
  const row = (text: string, label: string, onCancel: () => void) => (
    <p className="mt-1 flex flex-wrap items-baseline gap-x-3 text-sm text-muted">
      <span>{text}</span>
      <button type="button" disabled={busy} aria-label={label} onClick={onCancel} className="text-accent hover:underline disabled:opacity-50">
        Cancel
      </button>
    </p>
  );
  return (
    <>
      {live.stopAt && row(`Stops ${dateTime(live.stopAt, timeZone)}`, 'Cancel the scheduled stop', () => void cancel('stop-at', 'Scheduled stop cancelled. The stream keeps going.'))}
      {live.finishAfterPass &&
        row('Ends after this playlist pass', 'Cancel ending after this playlist pass', () => void cancel('finish-after-pass', 'It will keep playing the playlist. The stream keeps going.'))}
    </>
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
