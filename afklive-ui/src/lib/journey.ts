/**
 * ON-9: the new creator's journey after the one-sitting setup, worked out from real account data (streams,
 * their videos and destinations, /api/me), never from this browser's storage, so it's the same on every device.
 */
import { isOnAir } from '@/components/status';
import { hoursOf } from '@/components/HoursLeft';
import { offerFor } from './offer';
import type { Me, PlanView, PublicOffer, StreamSummary, StreamView } from './types';

/** The tips' ids in /api/me's dismissedHints (MeController.HINTS). */
export const FINISH_SETUP_HINT = 'finish-setup';
export const WHATS_NEXT_HINT = 'whats-next';

/** The newest stream: the one a setup that was left halfway made. */
export function newestStream<T extends Pick<StreamSummary, 'id'>>(streams: T[]): T | null {
  return streams.reduce<T | null>((a, s) => (!a || s.id > a.id ? s : a), null);
}

/** The stream the checklist is about: one on air now, else the newest. */
export function mainStream<T extends Pick<StreamSummary, 'id' | 'live'>>(streams: T[]): T | null {
  return newestStream(streams.filter((s) => isOnAir(s.live.state))) ?? newestStream(streams);
}

/** "Finish setting up": an account that has never gone live, hasn't hidden the card, with nothing on air now. */
export function showFinishSetup(me: Pick<Me, 'goLives' | 'dismissedHints'> | undefined, streams: Pick<StreamSummary, 'live'>[] | undefined): boolean {
  if (!me || !streams) return false;
  if (me.dismissedHints?.includes(FINISH_SETUP_HINT)) return false;
  if ((me.goLives ?? 0) > 0) return false;
  return !streams.some((s) => isOnAir(s.live.state));
}

export type SetupStepId = 'start' | 'content' | 'destination' | 'golive';

export interface SetupResume {
  step: SetupStepId;
  /** Where they got to, in plain words. */
  text: string;
  /** The one button. */
  action: string;
  to: string;
}

/** Where a setup that was skipped or left halfway stands, and the link that resumes it at exactly that step. */
export function setupResume(stream: Pick<StreamView, 'id' | 'source' | 'playlist' | 'destinations'> | null | undefined): SetupResume {
  if (!stream) {
    return { step: 'start', text: "You haven't made a stream yet. It takes about five minutes.", action: 'Start setup', to: '/studio/setup' };
  }
  const at = (step: 'content' | 'destination') => `/studio/setup?stream=${stream.id}&step=${step}`;
  if (stream.source === 'PLAYLIST' && stream.playlist.length === 0) {
    return { step: 'content', text: 'Your stream has no videos yet. Add a few and they play back to back, around the clock.', action: 'Add your videos', to: at('content') };
  }
  if (!stream.destinations.some((d) => d.enabled)) {
    return { step: 'destination', text: "Your stream isn't connected to YouTube or another site yet.", action: 'Connect YouTube', to: at('destination') };
  }
  return { step: 'golive', text: "Your stream is ready, but it hasn't gone live yet.", action: 'Go live for the first time', to: at('destination') };
}

/** The setup step a resume link asks for (0 choose, 1 content, 2 destination), or null for a fresh setup. */
export function parseResume(search: URLSearchParams): { streamId: number; step: 1 | 2 } | null {
  const id = Number(search.get('stream'));
  if (!Number.isInteger(id) || id <= 0) return null;
  return { streamId: id, step: search.get('step') === 'destination' ? 2 : 1 };
}

/** This device's alerts: unavailable (server or browser can't), blocked by the browser, off, or on. */
export type PushState = 'loading' | 'unavailable' | 'blocked' | 'off' | 'on';

export type NextId = 'alerts' | 'schedule' | 'second' | 'title' | 'invite';

export interface NextItem {
  id: NextId;
  label: string;
  done: boolean;
  /** Where to do it; alerts are turned on in place instead. */
  to?: string;
}

/** Title flags of the stream check (GR-9); while one is open the title can be better. */
const TITLE_FLAGS = ['title-no-phrase', 'title-live'];

export interface NextInput {
  stream: Pick<StreamView, 'id' | 'source' | 'playlist' | 'schedules' | 'live'> | null | undefined;
  push: PushState;
  /** The stream check (undefined while loading: the item waits rather than flickering). */
  check?: { youtubeTitle?: string | null; flags: { id: string; dismissed: boolean }[] } | null;
  /** GR-18 invites (undefined while loading or when there's no invite programme). */
  referrals?: { joined: number; payoutsLive: boolean } | null;
}

/** "What's next" after the first live: each item from real state, so it ticks itself when done anywhere. */
export function nextSteps({ stream, push, check, referrals }: NextInput): NextItem[] {
  const items: NextItem[] = [];
  if (push === 'off' || push === 'on') {
    items.push({ id: 'alerts', label: push === 'on' ? 'Alerts are on for this device' : 'Get alerts on this device if your stream stops', done: push === 'on' });
  }
  if (stream) {
    const base = `/studio/streams/${stream.id}`;
    const scheduled = stream.schedules.length > 0;
    const allDay = stream.source === 'PLAYLIST' && isOnAir(stream.live.state) && !stream.live.stopAt;
    items.push({
      id: 'schedule',
      label: scheduled ? "It's on a schedule" : allDay ? 'It runs 24/7, no schedule needed' : 'Schedule when it goes live',
      done: scheduled || allDay,
      to: `${base}/schedule`,
    });
    if (stream.source === 'PLAYLIST') {
      const two = stream.playlist.length >= 2;
      items.push({ id: 'second', label: two ? `${stream.playlist.length} videos in the playlist` : 'Add a second video, so it isn’t one file on repeat', done: two, to: `${base}/playlist` });
    }
    if (check) {
      const good = !!check.youtubeTitle && !check.flags.some((f) => !f.dismissed && TITLE_FLAGS.includes(f.id));
      items.push({ id: 'title', label: good ? 'Your YouTube title is checked' : 'Write a YouTube title people search for', done: good, to: `${base}?title=write` });
    }
  }
  if (referrals) {
    const joined = referrals.joined > 0;
    items.push({
      id: 'invite',
      label: joined ? 'A creator joined from your invite' : referrals.payoutsLive ? 'Invite a creator: you each get a free month' : 'Invite a creator friend',
      done: joined,
      to: '/studio/settings/invite',
    });
  }
  return items.slice(0, 5);
}

/** "What's next": after the first go-live, until every item is done or the creator hides it. */
export function showWhatsNext(me: Pick<Me, 'goLives' | 'dismissedHints'> | undefined, items: NextItem[]): boolean {
  if (!me || (me.goLives ?? 0) < 1 || me.dismissedHints?.includes(WHATS_NEXT_HINT)) return false;
  return items.length > 0 && items.some((i) => !i.done);
}

/** The Creator plan's price for this account this month: the launch offer when it can take one, else the list price. */
export function creatorPrice(offer: PublicOffer | null | undefined, plans: Pick<PlanView, 'id' | 'monthlyLabel'>[] | undefined, currency: string): { price: string; offer: boolean } | null {
  const deal = offerFor(offer, 'ESSENTIALS', 'MONTHLY', currency);
  if (deal) return { price: deal.price, offer: true };
  const plan = plans?.find((p) => p.id === 'ESSENTIALS');
  return plan ? { price: plan.monthlyLabel, offer: false } : null;
}

function hoursText(n: number): string {
  const r = Math.round(n * 10) / 10;
  const v = Number.isInteger(r) ? String(r) : r.toFixed(1);
  return `${v} hour${r === 1 ? '' : 's'}`;
}

/** "1 Nov": when free hours start over (the 1st, UTC). */
export function hoursResetDay(now: Date): string {
  const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(next);
}

/** BI-1: the calm Free-plan warning from 70% of the month's hours; null under it or on a plan without an hour limit. */
export function hoursWarning(me: Pick<Me, 'plan' | 'hoursUsed'>, price: { price: string; offer: boolean } | null, now = new Date()): string | null {
  const h = hoursOf(me);
  if (!h || h.used < h.included * 0.7) return null;
  const pitch = price
    ? price.offer
      ? `Creator keeps you live 24/7 for ${price.price} this month (launch offer).`
      : `Creator keeps you live 24/7 for ${price.price} a month.`
    : 'Creator keeps you live 24/7 with no hour limit.';
  if (h.left <= 0) return `This month's free hours are used up, so streams stay off until ${hoursResetDay(now)}. ${pitch}`;
  return `${hoursText(h.left)} left this month. ${pitch}`;
}
