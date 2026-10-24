import { CheckCircle2, CircleAlert, Loader2, Play, Square } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, get, post } from '@/lib/api';
import { appLink } from '@/lib/links';
import { keys, useInvalidate } from '@/lib/queries';
import type { DestinationChecks, Preflight, StreamSummary, StreamView } from '@/lib/types';
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

/** The dialog's title: what is happening, or which platform can't be reached. */
export function checksTitle(checks: DestinationChecks): string {
  if (checks.checks.some((c) => c.state === 'TESTING')) return 'Checking your destinations';
  const failed = checks.checks.filter((c) => c.state === 'FAILED');
  if (failed.length === 1) return `Not reaching ${failed[0].platform ?? failed[0].name}`;
  return "Some destinations can't be reached";
}

/** How often the dialog asks how the tests are going. */
export const CHECK_POLL_MS = 1500;

/**
 * GL-1: one press goes live. First each destination that hasn't passed a test in 24 hours (or whose
 * key changed) gets a 10-second test; a failure blocks with the reason, "Fix key" and "Go live anyway".
 * If a pre-flight check fails, say which and where to fix it.
 */
export function GoLiveButton({ stream, size = 'md', scene }: { stream: AnyStream; size?: 'sm' | 'md'; scene?: string }) {
  const [preflight, setPreflight] = useState<Preflight | null>(null);
  const [checks, setChecks] = useState<DestinationChecks | null>(null);
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const invalidate = useInvalidate();
  const open = useRef(false);

  const goLive = async () => {
    setBusy(true);
    try {
      await post(`/api/streams/${stream.id}/live`, scene ? { scene } : {});
      toast(`${stream.name} is starting. It's usually live within 15 seconds.`, 'ok');
      await invalidate([...keys.streams], [...keys.stream(stream.id)]);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.body && typeof e.body === 'object' && 'preflight' in e.body) {
        setPreflight((e.body as { preflight: Preflight }).preflight);
      } else {
        toast(e instanceof Error ? e.message : String(e), 'bad');
      }
    } finally {
      setBusy(false);
    }
  };

  const close = () => {
    open.current = false;
    setChecks(null);
  };

  const press = async () => {
    setBusy(true);
    let c: DestinationChecks | null = null;
    try {
      c = await post<DestinationChecks>(`/api/streams/${stream.id}/destination-checks`);
    } catch {
      c = null; // the check itself couldn't run: don't stand between the creator and going live
    }
    setBusy(false);
    if (c && !c.ready) {
      open.current = true;
      setChecks(c);
      return;
    }
    await goLive();
  };

  // While a test runs, ask again; when everything passed, go live without another press.
  useEffect(() => {
    if (!checks || !checks.checks.some((c) => c.state === 'TESTING')) return;
    const t = setTimeout(async () => {
      try {
        const next = await get<DestinationChecks>(`/api/streams/${stream.id}/destination-checks`);
        if (!open.current) return;
        if (next.ready) {
          close();
          await goLive();
        } else {
          setChecks(next);
        }
      } catch {
        if (open.current) setChecks({ ...checks });
      }
    }, CHECK_POLL_MS);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checks, stream.id]);

  const testing = !!checks?.checks.some((c) => c.state === 'TESTING');
  return (
    <>
      <Button variant="live" size={size} busy={busy} onClick={press} icon={<Play className="h-4 w-4" />}>
        Go live
      </Button>
      <Dialog
        open={!!checks}
        onClose={close}
        title={checks ? checksTitle(checks) : ''}
        footer={
          testing ? (
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
          ) : (
            <>
              <Button
                variant="secondary"
                onClick={async () => {
                  close();
                  await goLive();
                }}
              >
                Go live anyway
              </Button>
              <Link to={`/studio/streams/${stream.id}/destinations`} onClick={close} className={buttonClass('primary')}>
                Fix key
              </Link>
            </>
          )
        }
      >
        {checks && (
          <div className="space-y-3">
            <DestinationChecksList checks={checks} />
            {testing ? (
              <p className="text-sm text-muted">We send a short test to each destination that hasn't been checked in the last day. It goes live by itself when they pass.</p>
            ) : (
              <p className="text-sm text-muted">If you go live anyway, the stream starts but this destination won't show it until the key is fixed.</p>
            )}
          </div>
        )}
      </Dialog>
      <Dialog open={!!preflight} onClose={() => setPreflight(null)} title={`${stream.name} can't go live yet`} footer={<Button onClick={() => setPreflight(null)}>Close</Button>}>
        {preflight && <PreflightList preflight={preflight} streamId={stream.id} onNavigate={() => setPreflight(null)} />}
      </Dialog>
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
