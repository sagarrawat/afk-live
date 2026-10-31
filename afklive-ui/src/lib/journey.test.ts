import type { Me, PublicOffer, StreamView } from './types';
import {
  creatorPrice,
  FINISH_SETUP_HINT,
  hoursResetDay,
  hoursWarning,
  mainStream,
  newestStream,
  nextSteps,
  parseResume,
  setupResume,
  showFinishSetup,
  showWhatsNext,
  WHATS_NEXT_HINT,
} from './journey';
import { pushStateOf } from './push';

type S = Pick<StreamView, 'id' | 'source' | 'playlist' | 'destinations' | 'schedules' | 'live'>;
const video = (id: number) => ({ id, title: `v${id}.mp4`, contentType: 'VIDEO' as const, status: 'READY' as const });
const stream = (over: Partial<S> = {}): S => ({
  id: 7,
  source: 'PLAYLIST',
  playlist: [video(1)],
  destinations: [{ destinationId: 3, name: 'YouTube', type: 'RTMP', enabled: true }],
  schedules: [],
  live: { state: 'DRAFT', uptimeSeconds: 0 },
  ...over,
});

describe('finish setting up (ON-9)', () => {
  it('shows only for accounts that never went live, have nothing on air and did not hide it', () => {
    const off = [{ live: { state: 'DRAFT' as const, uptimeSeconds: 0 } }];
    expect(showFinishSetup({ goLives: 0, dismissedHints: [] }, off)).toBe(true);
    expect(showFinishSetup({ goLives: 0 }, [])).toBe(true);
    expect(showFinishSetup({ goLives: 1, dismissedHints: [] }, off)).toBe(false);
    expect(showFinishSetup({ goLives: 0, dismissedHints: [FINISH_SETUP_HINT] }, off)).toBe(false);
    expect(showFinishSetup({ goLives: 0 }, [{ live: { state: 'STARTING', uptimeSeconds: 0 } }])).toBe(false);
    expect(showFinishSetup(undefined, off)).toBe(false);
    expect(showFinishSetup({ goLives: 0 }, undefined)).toBe(false);
  });

  it('says where setup stopped, from the real stream, and links to exactly that step', () => {
    expect(setupResume(null)).toMatchObject({ step: 'start', to: '/studio/setup', action: 'Start setup' });
    expect(setupResume(stream({ playlist: [] }))).toMatchObject({ step: 'content', to: '/studio/setup?stream=7&step=content', action: 'Add your videos' });
    expect(setupResume(stream({ playlist: [], destinations: [] })).step).toBe('content');
    expect(setupResume(stream({ destinations: [] }))).toMatchObject({ step: 'destination', to: '/studio/setup?stream=7&step=destination', action: 'Connect YouTube' });
    expect(setupResume(stream({ destinations: [{ destinationId: 3, name: 'YouTube', type: 'RTMP', enabled: false }] })).step).toBe('destination');
    expect(setupResume(stream())).toMatchObject({ step: 'golive', to: '/studio/setup?stream=7&step=destination', action: 'Go live for the first time' });
    // A phone stream has no videos to add: it goes on to where it goes.
    expect(setupResume(stream({ source: 'PHONE', playlist: [], destinations: [] })).step).toBe('destination');
    // Plain words only.
    for (const s of [null, stream({ playlist: [] }), stream({ destinations: [] }), stream()]) {
      expect(setupResume(s).text).not.toMatch(/rtmp|bitrate|stream key/i);
    }
  });

  it('reads the resume link back into a setup step, and ignores anything else', () => {
    expect(parseResume(new URLSearchParams('stream=7&step=content'))).toEqual({ streamId: 7, step: 1 });
    expect(parseResume(new URLSearchParams('stream=7&step=destination'))).toEqual({ streamId: 7, step: 2 });
    expect(parseResume(new URLSearchParams('stream=7'))).toEqual({ streamId: 7, step: 1 });
    expect(parseResume(new URLSearchParams(''))).toBeNull();
    expect(parseResume(new URLSearchParams('stream=abc&step=destination'))).toBeNull();
    expect(parseResume(new URLSearchParams('stream=-1'))).toBeNull();
  });

  it('picks the newest stream for setup, and one on air for the checklist', () => {
    const a = { id: 2, live: { state: 'LIVE' as const, uptimeSeconds: 1 } };
    const b = { id: 9, live: { state: 'DRAFT' as const, uptimeSeconds: 0 } };
    expect(newestStream([a, b])?.id).toBe(9);
    expect(mainStream([a, b])?.id).toBe(2);
    expect(mainStream([b])?.id).toBe(9);
    expect(newestStream([])).toBeNull();
  });
});

describe("what's next after the first live (ON-9)", () => {
  const check = (youtubeTitle: string | null, flags: { id: string; dismissed: boolean }[] = []) => ({ youtubeTitle, flags });

  it('lists alerts, schedule, a second video, the title and invites, each ticking itself', () => {
    const items = nextSteps({ stream: stream(), push: 'off', check: check(null), referrals: { joined: 0, payoutsLive: true } });
    expect(items.map((i) => [i.id, i.done])).toEqual([
      ['alerts', false],
      ['schedule', false],
      ['second', false],
      ['title', false],
      ['invite', false],
    ]);
    expect(items.find((i) => i.id === 'schedule')?.to).toBe('/studio/streams/7/schedule');
    expect(items.find((i) => i.id === 'second')?.to).toBe('/studio/streams/7/playlist');
    expect(items.find((i) => i.id === 'title')?.to).toBe('/studio/streams/7?title=write');
    expect(items.find((i) => i.id === 'invite')?.to).toBe('/studio/settings/invite');

    const done = nextSteps({
      stream: stream({ playlist: [video(1), video(2)], schedules: [{ id: 1 } as unknown as StreamView['schedules'][number]] }),
      push: 'on',
      check: check('Lofi beats to study to, 24/7'),
      referrals: { joined: 1, payoutsLive: true },
    });
    expect(done.every((i) => i.done)).toBe(true);
  });

  it('counts an always-on stream as needing no schedule', () => {
    const live = stream({ live: { state: 'LIVE', uptimeSeconds: 60 } });
    expect(nextSteps({ stream: live, push: 'unavailable' }).find((i) => i.id === 'schedule')).toMatchObject({ done: true, label: 'It runs 24/7, no schedule needed' });
    const stopping = stream({ live: { state: 'LIVE', uptimeSeconds: 60, stopAt: '2026-11-01T00:00:00Z' } });
    expect(nextSteps({ stream: stopping, push: 'unavailable' }).find((i) => i.id === 'schedule')?.done).toBe(false);
  });

  it('a title the check still flags is not done; a kept flag is', () => {
    const flagged = nextSteps({ stream: stream(), push: 'unavailable', check: check('LIVE LIVE', [{ id: 'title-live', dismissed: false }]) });
    expect(flagged.find((i) => i.id === 'title')?.done).toBe(false);
    const kept = nextSteps({ stream: stream(), push: 'unavailable', check: check('LIVE LIVE', [{ id: 'title-live', dismissed: true }]) });
    expect(kept.find((i) => i.id === 'title')?.done).toBe(true);
  });

  it('leaves out what this device, stream or account can not do', () => {
    const items = nextSteps({ stream: stream({ source: 'PHONE', playlist: [] }), push: 'unavailable' });
    expect(items.map((i) => i.id)).toEqual(['schedule']);
    expect(nextSteps({ stream: stream(), push: 'blocked' }).some((i) => i.id === 'alerts')).toBe(false);
  });

  it('shows after a first go-live until everything is done or it is hidden', () => {
    const todo = [{ id: 'schedule' as const, label: '', done: false }];
    const allDone = [{ id: 'schedule' as const, label: '', done: true }];
    expect(showWhatsNext({ goLives: 1 }, todo)).toBe(true);
    expect(showWhatsNext({ goLives: 0 }, todo)).toBe(false);
    expect(showWhatsNext({ goLives: 3 }, allDone)).toBe(false);
    expect(showWhatsNext({ goLives: 3, dismissedHints: [WHATS_NEXT_HINT] }, todo)).toBe(false);
    expect(showWhatsNext({ goLives: 3 }, [])).toBe(false);
  });

  it("reads this device's alerts", () => {
    expect(pushStateOf(false, 'default', { available: true, publicKey: 'k' }, false)).toBe('unavailable');
    expect(pushStateOf(true, 'default', { available: false, publicKey: null }, false)).toBe('unavailable');
    expect(pushStateOf(true, 'denied', { available: true, publicKey: 'k' }, false)).toBe('blocked');
    expect(pushStateOf(true, 'granted', { available: true, publicKey: 'k' }, true)).toBe('on');
    expect(pushStateOf(true, 'default', { available: true, publicKey: 'k' }, false)).toBe('off');
  });
});

describe('Free hours warning (BI-1)', () => {
  const free = (hoursUsed: number): Pick<Me, 'plan' | 'hoursUsed'> => ({
    plan: { id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours: 10, destinations: 1, watermark: true },
    hoursUsed,
  });
  const offer: PublicOffer = {
    name: 'Launch offer',
    percentOff: 10,
    monthlyPeriods: 3,
    includeYearly: false,
    startsAt: '2026-10-01T00:00:00Z',
    endsAt: '2026-12-31T00:00:00Z',
    currency: 'INR',
    eligible: true,
    plans: [{ id: 'ESSENTIALS', name: 'Creator', listMonthly: 499, offerMonthly: 449, listMonthlyLabel: '₹499', offerMonthlyLabel: '₹449', listYearly: 4990, offerYearly: null, listYearlyLabel: '₹4,990', offerYearlyLabel: null }],
  };
  const plans = [{ id: 'ESSENTIALS' as const, monthlyLabel: '₹499' }];

  it('uses the real offer price, and the list price when there is no offer or the account cannot take it', () => {
    expect(creatorPrice(offer, plans, 'INR')).toEqual({ price: '₹449', offer: true });
    expect(creatorPrice(null, plans, 'INR')).toEqual({ price: '₹499', offer: false });
    expect(creatorPrice({ ...offer, eligible: false }, plans, 'INR')).toEqual({ price: '₹499', offer: false });
    expect(creatorPrice({ ...offer, plans: [] }, plans, 'INR')).toEqual({ price: '₹499', offer: false });
    expect(creatorPrice(null, undefined, 'INR')).toBeNull();
  });

  it('starts at 70% with the hours left, and says nothing before it or on paid plans', () => {
    expect(hoursWarning(free(6.9), { price: '₹449', offer: true })).toBeNull();
    expect(hoursWarning(free(7), { price: '₹449', offer: true })).toBe('3 hours left this month. Creator keeps you live 24/7 for ₹449 this month (launch offer).');
    expect(hoursWarning(free(9), { price: '₹499', offer: false })).toBe('1 hour left this month. Creator keeps you live 24/7 for ₹499 a month.');
    expect(hoursWarning(free(8.5), null)).toBe('1.5 hours left this month. Creator keeps you live 24/7 with no hour limit.');
    const paid = { ...free(200), plan: { ...free(0).plan, id: 'ESSENTIALS' as const, monthlyHours: 0 } };
    expect(hoursWarning(paid, { price: '₹449', offer: true })).toBeNull();
  });

  it('says when they come back once the hours are used up', () => {
    const now = new Date('2026-10-31T12:00:00Z');
    expect(hoursResetDay(now)).toBe('1 Nov');
    expect(hoursWarning(free(10), { price: '₹449', offer: true }, now)).toBe(
      "This month's free hours are used up, so streams stay off until 1 Nov. Creator keeps you live 24/7 for ₹449 this month (launch offer).",
    );
  });
});
