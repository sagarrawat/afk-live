import { useQuery, useQueryClient } from '@tanstack/react-query';
import clsx from 'clsx';
import { Circle, CircleCheck, Clock, Rocket } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { hoursOf } from '@/components/HoursLeft';
import { Button, buttonClass } from '@/components/ui';
import { get, patch } from '@/lib/api';
import {
  creatorPrice,
  FINISH_SETUP_HINT,
  hoursWarning,
  mainStream,
  newestStream,
  nextSteps,
  type NextItem,
  type PushState,
  setupResume,
  showWhatsNext,
  WHATS_NEXT_HINT,
} from '@/lib/journey';
import { currentSubscription, enablePush, pushInfo, pushStateOf, pushSupported } from '@/lib/push';
import { keys, useOffer, usePlans } from '@/lib/queries';
import type { Me, StreamSummary, StreamView } from '@/lib/types';

/** Hides a Home card for this account on every device (MeController.HINTS). */
function useDismiss(hint: string) {
  const qc = useQueryClient();
  const [hidden, setHidden] = useState(false);
  const dismiss = async () => {
    setHidden(true);
    try {
      qc.setQueryData(keys.me, await patch<Me>('/api/me', { dismissHint: hint }));
    } catch {
      // Not saved: the card comes back next time, which is harmless.
    }
  };
  return { hidden, dismiss };
}

function useStreamView(id: number | undefined) {
  return useQuery({ queryKey: keys.stream(id ?? 0), queryFn: () => get<StreamView>(`/api/streams/${id}`), enabled: id != null });
}

/** ON-9: "Finish setting up" for an account that started or skipped setup and never went live. */
export function FinishSetupCard({ streams }: { streams: StreamSummary[] }) {
  const newest = newestStream(streams);
  const { data: stream, isLoading } = useStreamView(newest?.id);
  const { hidden, dismiss } = useDismiss(FINISH_SETUP_HINT);
  if (hidden || (newest && (isLoading || !stream))) return null;
  const resume = setupResume(newest ? stream : null);
  return (
    <section aria-labelledby="finish-setup" className="mb-4 rounded-md border border-accent/40 bg-panel p-4">
      <div className="flex items-start gap-3">
        <Rocket className="mt-0.5 h-5 w-5 shrink-0 text-accent" aria-hidden />
        <div className="min-w-0 flex-1">
          <h2 id="finish-setup" className="text-md font-semibold">
            Finish setting up
          </h2>
          <p className="mt-1 text-muted">{resume.text}</p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Link to={resume.to} className={buttonClass('primary')}>
              {resume.action}
            </Link>
            <Button variant="ghost" onClick={dismiss}>
              Don't show again
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}

/** This device's alerts, re-read when asked. */
function usePushState(): { state: PushState; publicKey: string | null; set: (s: PushState) => void } {
  const [state, setState] = useState<PushState>('loading');
  const [publicKey, setPublicKey] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    const supported = pushSupported();
    if (!supported) {
      setState('unavailable');
      return;
    }
    Promise.all([pushInfo(), currentSubscription()])
      .then(([info, sub]) => {
        if (!live) return;
        setPublicKey(info.publicKey);
        setState(pushStateOf(true, Notification.permission, info, !!sub));
      })
      .catch(() => live && setState('unavailable'));
    return () => {
      live = false;
    };
  }, []);
  return { state, publicKey, set: setState };
}

/** ON-9: after the first live, 3 to 5 next steps, each ticking itself from the account's real state. */
export function WhatsNextCard({ me, streams }: { me: Me; streams: StreamSummary[] }) {
  const main = mainStream(streams);
  const { data: stream } = useStreamView(main?.id);
  const { data: check } = useQuery({
    queryKey: [...keys.stream(main?.id ?? 0), 'check'],
    queryFn: () => get<{ youtubeTitle?: string | null; flags: { id: string; dismissed: boolean }[] }>(`/api/streams/${main?.id}/check`),
    enabled: main != null,
    retry: false,
  });
  const { data: referrals } = useQuery({
    queryKey: ['referrals'],
    queryFn: () => get<{ joined: number; payoutsLive: boolean }>('/api/referrals'),
    retry: false,
  });
  const push = usePushState();
  const [pushBusy, setPushBusy] = useState(false);
  const [pushRefused, setPushRefused] = useState(false);
  const { hidden, dismiss } = useDismiss(WHATS_NEXT_HINT);

  const items = nextSteps({ stream: main ? stream : null, push: push.state, check, referrals });
  if (hidden || (main && !stream) || !showWhatsNext(me, items)) return null;

  const turnOn = async () => {
    if (!push.publicKey) return;
    setPushBusy(true);
    try {
      if (await enablePush(push.publicKey)) push.set('on');
      else setPushRefused(true);
    } catch {
      setPushRefused(true);
    } finally {
      setPushBusy(false);
    }
  };
  const done = items.filter((i) => i.done).length;

  return (
    <section aria-labelledby="whats-next" className="mt-6 rounded-md border border-line bg-panel">
      <header className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
        <h2 id="whats-next" className="text-md font-semibold">
          What's next <span className="tnum text-sm font-normal text-muted">{done} of {items.length} done</span>
        </h2>
        <Button size="sm" variant="ghost" onClick={dismiss}>
          Hide
        </Button>
      </header>
      <ul className="divide-y divide-line">
        {items.map((item) => (
          <NextRow key={item.id} item={item} onTurnOn={item.id === 'alerts' ? turnOn : undefined} busy={pushBusy} refused={pushRefused} />
        ))}
      </ul>
    </section>
  );
}

function NextRow({ item, onTurnOn, busy, refused }: { item: NextItem; onTurnOn?: () => void; busy: boolean; refused: boolean }) {
  const Icon = item.done ? CircleCheck : Circle;
  return (
    <li className="flex items-center gap-3 px-4 py-2.5 text-sm">
      <Icon className={clsx('h-4 w-4 shrink-0', item.done ? 'text-ok' : 'text-muted')} aria-hidden />
      <span className="sr-only">{item.done ? 'Done:' : 'To do:'}</span>
      {item.done ? (
        <span className="min-w-0 flex-1 text-muted">{item.label}</span>
      ) : onTurnOn ? (
        <>
          <span className="min-w-0 flex-1">{refused ? "Your browser didn't allow alerts. You can turn them on in Settings, Notifications." : item.label}</span>
          {!refused && (
            <Button size="sm" busy={busy} onClick={onTurnOn}>
              Turn on
            </Button>
          )}
        </>
      ) : (
        <Link to={item.to!} className="min-w-0 flex-1 text-accent hover:underline">
          {item.label}
        </Link>
      )}
    </li>
  );
}

/** BI-1: a calm note from 70% of the Free plan's monthly hours, with the Creator price this account would pay. */
export function FreeHoursBanner({ me, className }: { me: Me; className?: string }) {
  const h = hoursOf(me);
  if (!h || h.used < h.included * 0.7) return null;
  return <FreeHoursNote me={me} className={className} />;
}

function FreeHoursNote({ me, className }: { me: Me; className?: string }) {
  const currency = me.currency ?? 'INR';
  const { data: offer } = useOffer(currency);
  const { data: plans } = usePlans(currency);
  const text = hoursWarning(me, creatorPrice(offer, plans, currency));
  if (!text) return null;
  return (
    <div role="status" className={clsx('flex flex-col gap-3 rounded-md border border-accent/40 bg-accent/5 px-4 py-3 text-sm sm:flex-row sm:items-center', className)}>
      <p className="flex min-w-0 flex-1 items-start gap-2">
        <Clock className="mt-0.5 h-4 w-4 shrink-0 text-accent" aria-hidden />
        <span>{text}</span>
      </p>
      <Link to="/studio/settings/billing" className={buttonClass('secondary', 'sm', 'self-start sm:self-auto')}>
        See Creator plan
      </Link>
    </div>
  );
}
