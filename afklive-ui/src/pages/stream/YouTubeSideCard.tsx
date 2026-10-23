import { useQueryClient } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import { useEffect, useState } from 'react';
import { HelpLink } from '@/components/HelpLink';
import { isOnAir } from '@/components/status';
import { Button, Panel, buttonClass } from '@/components/ui';
import { patch } from '@/lib/api';
import { HELP, YOUTUBE_STUDIO_LIVE } from '@/lib/help';
import { keys, useDestinations, useMe } from '@/lib/queries';
import type { Destination, LiveState, Me, StreamView } from '@/lib/types';

/** The tip's id in /api/me's dismissedHints (MeController.HINTS). */
export const YOUTUBE_SIDE_HINT = 'youtube-side';
/** After this long on air YouTube has normally caught up, so the card goes (except on a first go-live). */
export const FRESH_SECONDS = 120;

/** How long this run has been on air, by the clock rather than the last poll. */
export function liveSeconds(live: Pick<LiveState, 'startedAt' | 'uptimeSeconds'>, now: number): number {
  if (live.startedAt) {
    const started = Date.parse(live.startedAt);
    if (!Number.isNaN(started)) return Math.max(0, (now - started) / 1000);
  }
  return live.uptimeSeconds ?? 0;
}

/** True when an enabled destination of this stream is YouTube (unknown until destinations load). */
export function sendsToYouTube(s: Pick<StreamView, 'destinations'>, destinations: Pick<Destination, 'id' | 'platform'>[] | undefined): boolean {
  if (!destinations) return false;
  return s.destinations.some((t) => t.enabled && destinations.some((d) => d.id === t.destinationId && d.platform === 'YOUTUBE'));
}

/**
 * GL-6: show "What you'll see on YouTube" while a stream that sends to YouTube is on air, on the
 * account's first go-live (for the whole run) and in the first two minutes of any run, until the
 * creator dismisses it for their account.
 */
export function showYouTubeSide({
  me,
  stream,
  destinations,
  now,
}: {
  me: Pick<Me, 'dismissedHints' | 'goLives'> | undefined;
  stream: Pick<StreamView, 'live' | 'destinations'>;
  destinations: Pick<Destination, 'id' | 'platform'>[] | undefined;
  now: number;
}): boolean {
  if (!me || me.dismissedHints?.includes(YOUTUBE_SIDE_HINT)) return false;
  const state = stream.live.state;
  if (!isOnAir(state) || state === 'STOPPING') return false;
  if (!sendsToYouTube(stream, destinations)) return false;
  const firstGoLive = (me.goLives ?? 0) <= 1;
  return firstGoLive || liveSeconds(stream.live, now) < FRESH_SECONDS;
}

export function YouTubeSideCard({ stream: s }: { stream: StreamView }) {
  const { data: me } = useMe();
  const { data: destinations } = useDestinations();
  const qc = useQueryClient();
  const [hidden, setHidden] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const shown = !hidden && showYouTubeSide({ me, stream: s, destinations, now });
  useEffect(() => {
    if (!shown) return;
    const t = setInterval(() => setNow(Date.now()), 5_000);
    return () => clearInterval(t);
  }, [shown]);
  if (!shown) return null;

  const dismiss = async () => {
    setHidden(true);
    try {
      const updated = await patch<Me>('/api/me', { dismissHint: YOUTUBE_SIDE_HINT });
      qc.setQueryData(keys.me, updated);
    } catch {
      // Not saved: the card comes back next time, which is harmless.
    }
  };

  return (
    <Panel
      title="What you'll see on YouTube"
      action={
        <Button size="sm" variant="ghost" onClick={dismiss}>
          Don't show again
        </Button>
      }
    >
      <ul className="space-y-3">
        <li>
          <span className="font-medium">"Preparing" is normal</span>
          <span className="text-muted"> for up to about 30 seconds after you go live.</span>
        </li>
        <li>
          <span className="font-medium">For 24/7, turn Auto-start on and Auto-stop off</span>
          <span className="text-muted"> in YouTube Studio's stream settings, so YouTube carries on by itself after any restart.</span>
        </li>
        <li>
          <span className="font-medium">Stuck on Preparing?</span>
          <span className="text-muted"> End that live in YouTube Studio and start a fresh one with the same stream key. Your stream here keeps running.</span>
        </li>
      </ul>
      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
        <a href={YOUTUBE_STUDIO_LIVE} target="_blank" rel="noreferrer" className={buttonClass('secondary', 'sm')}>
          Open YouTube Studio
          <ExternalLink className="h-3.5 w-3.5" aria-hidden />
        </a>
        <HelpLink to={HELP.preparing} className="text-sm">
          How to set Auto-start and Auto-stop
        </HelpLink>
      </div>
    </Panel>
  );
}
