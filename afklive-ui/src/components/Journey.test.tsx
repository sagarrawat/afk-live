import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me, PublicOffer, StreamSummary, StreamView } from '@/lib/types';

const base: Me = {
  id: 1,
  email: 'meera@example.com',
  name: 'Meera',
  admin: false,
  plan: { id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours: 10, destinations: 1, watermark: true },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
  dismissedHints: [],
  goLives: 0,
  hoursUsed: 1,
};

const summary = (over: Partial<StreamSummary> = {}): StreamSummary => ({
  id: 7,
  name: 'My 24/7 stream',
  source: 'PLAYLIST',
  orientation: 'LANDSCAPE',
  qualityHeight: 720,
  playlistCount: 0,
  live: { state: 'DRAFT', uptimeSeconds: 0 },
  ...over,
});

const view = (over: Partial<StreamView> = {}): StreamView =>
  ({
    id: 7,
    name: 'My 24/7 stream',
    source: 'PLAYLIST',
    orientation: 'LANDSCAPE',
    qualityHeight: 720,
    fps: 30,
    playbackOrder: 'IN_ORDER',
    loopCount: 0,
    musicOrder: 'IN_ORDER',
    audioMode: 'KEEP',
    videoVolume: 100,
    musicVolume: 50,
    timeZone: 'Asia/Kolkata',
    overlays: [],
    scenes: [],
    playlist: [],
    music: [],
    totalRuntimeSeconds: 0,
    destinations: [],
    schedules: [],
    live: { state: 'DRAFT', uptimeSeconds: 0 },
    fit: 'BLUR',
    crossfadeSeconds: 0,
    ...over,
  }) as StreamView;

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

const calls: { method: string; path: string; body?: string }[] = [];

function mockApi(routes: Record<string, unknown>) {
  calls.length = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
      const method = init?.method ?? 'GET';
      calls.push({ method, path, body: typeof init?.body === 'string' ? init.body : undefined });
      const body = routes[`${method} ${path}`] ?? routes[path];
      return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const common = { '/api/notifications': { unread: 0, emailAlerts: true, items: [] } };

describe('Finish setting up (ON-9)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('says the stream has no videos yet and resumes setup at that step; hiding it is saved for the account', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      ...common,
      '/api/me': base,
      '/api/streams': [summary({ id: 3 }), summary({ id: 7 })],
      '/api/streams/7': view(),
      'PATCH /api/me': { ...base, dismissedHints: ['finish-setup'] },
    });
    render(<App client={makeQueryClient()} />);
    const card = (await screen.findByRole('heading', { name: 'Finish setting up' })).closest('section')!;
    expect(within(card).getByText(/Your stream has no videos yet/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Add your videos' })).toHaveAttribute('href', '/studio/setup?stream=7&step=content');
    // Its button is the screen's one primary action.
    expect(screen.getByRole('button', { name: 'New stream' }).className).not.toMatch(/bg-accent\b/);

    fireEvent.click(within(card).getByRole('button', { name: "Don't show again" }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Finish setting up' })).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'PATCH' && c.path === '/api/me')?.body).toBe(JSON.stringify({ dismissHint: 'finish-setup' }));
  });

  it('a stream with videos and a destination is one step from going live', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      ...common,
      '/api/me': base,
      '/api/streams': [summary({ playlistCount: 2 })],
      '/api/streams/7': view({ playlist: [{ id: 1, title: 'a.mp4', contentType: 'VIDEO', status: 'READY' }], destinations: [{ destinationId: 4, name: 'YouTube', type: 'RTMP', enabled: true }] }),
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText("Your stream is ready, but it hasn't gone live yet.")).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go live for the first time' })).toHaveAttribute('href', '/studio/setup?stream=7&step=destination');
  });

  it('skipped setup with no stream starts setup over, and never shows once dismissed or after a go-live', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({ ...common, '/api/me': base, '/api/streams': [] });
    const { unmount } = render(<App client={makeQueryClient()} />);
    expect(await screen.findByRole('link', { name: 'Start setup' })).toHaveAttribute('href', '/studio/setup');
    expect(screen.queryByText('Use the setup guide')).not.toBeInTheDocument();
    unmount();

    mockApi({ ...common, '/api/me': { ...base, dismissedHints: ['finish-setup'] }, '/api/streams': [] });
    const second = render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('No streams yet')).toBeInTheDocument();
    expect(screen.queryByText('Finish setting up')).not.toBeInTheDocument();
    second.unmount();

    mockApi({ ...common, '/api/me': { ...base, goLives: 1 }, '/api/streams': [summary()], '/api/streams/7': view() });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('My 24/7 stream')).toBeInTheDocument();
    expect(screen.queryByText('Finish setting up')).not.toBeInTheDocument();
  });
});

describe('Setup resumes where it was left (ON-9)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('opens at the videos step for the stream in the link', async () => {
    window.history.pushState({}, '', '/studio/setup?stream=7&step=content');
    mockApi({ ...common, '/api/me': base, '/api/streams/7': view() });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Add your videos')).toBeInTheDocument();
    expect(screen.getByText('2. Your content').closest('li')).toHaveAttribute('aria-current', 'step');
  });

  it('opens at go live when the stream already has a destination', async () => {
    window.history.pushState({}, '', '/studio/setup?stream=7&step=destination');
    mockApi({
      ...common,
      '/api/me': base,
      '/api/streams/7': view({ playlist: [{ id: 1, title: 'a.mp4', contentType: 'VIDEO', status: 'READY' }], destinations: [{ destinationId: 4, name: 'YouTube', type: 'RTMP', enabled: true }] }),
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText("You're ready to go live.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Go live/ })).toBeInTheDocument();
  });

  it('asks where it goes when the stream has none yet', async () => {
    window.history.pushState({}, '', '/studio/setup?stream=7&step=destination');
    mockApi({ ...common, '/api/me': base, '/api/streams/7': view({ playlist: [{ id: 1, title: 'a.mp4', contentType: 'VIDEO', status: 'READY' }] }) });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Send it to YouTube')).toBeInTheDocument();
  });

  it('starts over when the stream in the link is not there', async () => {
    window.history.pushState({}, '', '/studio/setup?stream=99&step=destination');
    mockApi({ ...common, '/api/me': base });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('My videos, 24/7')).toBeInTheDocument();
  });
});

describe("What's next (ON-9)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lists next steps from the real stream, with links to where each is done, and hides for good', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      ...common,
      '/api/me': { ...base, goLives: 1, plan: { ...base.plan, id: 'ESSENTIALS', monthlyHours: 0 } },
      '/api/streams': [summary({ playlistCount: 1, live: { state: 'LIVE', uptimeSeconds: 60, startedAt: new Date().toISOString() } })],
      '/api/streams/7': view({ playlist: [{ id: 1, title: 'a.mp4', contentType: 'VIDEO', status: 'READY' }], live: { state: 'LIVE', uptimeSeconds: 60 } }),
      '/api/streams/7/check': { flags: [], open: 0, checking: false, musicHelp: '', youtubeTitle: null },
      '/api/referrals': { joined: 0, payoutsLive: true },
      'PATCH /api/me': { ...base, goLives: 1, dismissedHints: ['whats-next'] },
    });
    render(<App client={makeQueryClient()} />);
    const card = (await screen.findByRole('heading', { name: /What's next/ })).closest('section')!;
    await within(card).findByRole('link', { name: 'Invite a creator: you each get a free month' });
    expect(within(card).getByText('It runs 24/7, no schedule needed')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: /Add a second video/ })).toHaveAttribute('href', '/studio/streams/7/playlist');
    expect(within(card).getByRole('link', { name: 'Write a YouTube title people search for' })).toHaveAttribute('href', '/studio/streams/7?title=write');
    expect(within(card).getByRole('link', { name: 'Invite a creator: you each get a free month' })).toHaveAttribute('href', '/studio/settings/invite');
    expect(within(card).getByText(/1 of 4 done/)).toBeInTheDocument();

    fireEvent.click(within(card).getByRole('button', { name: 'Hide' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: /What's next/ })).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'PATCH')?.body).toBe(JSON.stringify({ dismissHint: 'whats-next' }));
  });

  it('is gone when every step is done', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      ...common,
      '/api/me': { ...base, goLives: 2, plan: { ...base.plan, id: 'ESSENTIALS', monthlyHours: 0 } },
      '/api/streams': [summary({ playlistCount: 2 })],
      '/api/streams/7': view({
        playlist: [
          { id: 1, title: 'a.mp4', contentType: 'VIDEO', status: 'READY' },
          { id: 2, title: 'b.mp4', contentType: 'VIDEO', status: 'READY' },
        ],
        schedules: [{ id: 1 } as unknown as StreamView['schedules'][number]],
      }),
      '/api/streams/7/check': { flags: [], open: 0, checking: false, musicHelp: '', youtubeTitle: 'Lofi beats to study to' },
      '/api/referrals': { joined: 2, payoutsLive: true },
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('My 24/7 stream')).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.path === '/api/referrals')).toBe(true));
    expect(screen.queryByRole('heading', { name: /What's next/ })).not.toBeInTheDocument();
  });
});

describe('Free hours warning (BI-1)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('at 70% says how many hours are left and the real launch offer price, linking to billing', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({ ...common, '/api/me': { ...base, goLives: 1, hoursUsed: 7 }, '/api/streams': [summary()], '/api/streams/7': view(), '/api/billing/offer': offer, '/api/billing/plans': [] });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('3 hours left this month. Creator keeps you live 24/7 for ₹449 this month (launch offer).')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See Creator plan' })).toHaveAttribute('href', '/studio/settings/billing');
  });

  it('falls back to the list price without an offer, and shows on the stream page too', async () => {
    window.history.pushState({}, '', '/studio/streams/7');
    mockApi({
      ...common,
      '/api/me': { ...base, goLives: 1, hoursUsed: 8 },
      '/api/streams/7': view(),
      '/api/billing/plans': [{ id: 'ESSENTIALS', monthlyLabel: '₹499' }],
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('2 hours left this month. Creator keeps you live 24/7 for ₹499 a month.')).toBeInTheDocument();
  });

  it('says nothing under 70%', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({ ...common, '/api/me': { ...base, goLives: 1, hoursUsed: 6.5 }, '/api/streams': [summary()], '/api/streams/7': view(), '/api/billing/offer': offer });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('My 24/7 stream')).toBeInTheDocument();
    expect(screen.queryByText(/left this month\. Creator/)).not.toBeInTheDocument();
  });
});
