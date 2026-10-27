import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
import { destinationSummary } from '@/components/AddDestination';
import { DestinationsTab } from './DestinationsTab';

const me = { id: 1, email: 'a@example.com', name: 'A', admin: false, plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 5, watermark: false }, timeZone: 'Asia/Kolkata', currency: 'INR', onboarded: true, emailAlerts: true };
const dests = [
  { id: 1, name: 'YouTube main', type: 'RTMP', platform: 'YOUTUBE', server: 'rtmp://a.rtmp.youtube.com/live2', keyHint: '••••8f2k' },
  { id: 2, name: 'Facebook page', type: 'RTMP', platform: 'FACEBOOK', server: 'rtmps://live-api-s.facebook.com:443/rtmp', keyHint: '••••a91c' },
];

function stream(enabledIds: number[]): StreamView {
  return {
    id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', qualityHeight: 1080, fps: 30, playbackOrder: 'IN_ORDER', loopCount: 0,
    musicOrder: 'IN_ORDER', audioMode: 'KEEP', videoVolume: 1, musicVolume: 0.5, timeZone: 'Asia/Kolkata', overlays: [], scenes: [], playlist: [], music: [],
    totalRuntimeSeconds: 0, schedules: [], phone: null,
    destinations: enabledIds.map((id) => ({ destinationId: id, name: dests[id - 1].name, type: 'RTMP', enabled: true })),
    live: { state: 'LIVE', uptimeSeconds: 60 },
  } as unknown as StreamView;
}

describe('DestinationsTab while live (DS-5)', () => {
  let puts: unknown[] = [];
  beforeEach(() => {
    puts = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = (typeof input === 'string' ? input : input.toString()).split('?')[0];
      if (init?.method === 'PUT') puts.push(JSON.parse(String(init.body)));
      const body = path === '/api/me' ? me : path === '/api/destinations' ? dests : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = (s: StreamView) =>
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><DestinationsTab stream={s} /></MemoryRouter></QueryClientProvider>);

  it("won't turn off the only destination of a live stream, and says why on the page (UX audit #13)", async () => {
    show(stream([1]));
    expect(await screen.findByRole('switch', { name: 'Send to YouTube main' })).toBeDisabled();
    expect(screen.getByText('A live stream needs one destination. Stop the stream to turn this off.')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Send to Facebook page' })).toBeEnabled();
    expect(screen.getByText(/other destinations stay connected/)).toBeInTheDocument();
  });

  it('adds a destination to the live stream straight away', async () => {
    show(stream([1]));
    await userEvent.click(await screen.findByRole('switch', { name: 'Send to Facebook page' }));
    expect(puts).toEqual([[{ destinationId: 1, enabled: true }, { destinationId: 2, enabled: true }]]);
  });
});

describe('DestinationsTab: a new destination is tested (MS-6)', () => {
  let posts: string[] = [];
  beforeEach(() => {
    posts = [];
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (init?.method === 'POST') posts.push(path);
      const body = path === '/api/me' ? me
        : path === '/api/destinations' ? (init?.method === 'POST' ? dests[1] : dests)
        : path.endsWith('/test') ? { testId: 3, status: 'PENDING' } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  const addFacebook = async (s: StreamView) => {
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><DestinationsTab stream={s} /></MemoryRouter></QueryClientProvider>);
    await userEvent.click(await screen.findByRole('button', { name: 'Add destination' }));
    await userEvent.click(screen.getByRole('radio', { name: /Facebook/ }));
    await userEvent.type(screen.getByLabelText('Stream key'), 'FB-123-0-Ab');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Add destination' }));
  };

  it('tests the key as soon as it is saved', async () => {
    await addFacebook({ ...stream([1]), live: { state: 'DRAFT', uptimeSeconds: 0 } } as StreamView);
    expect(await screen.findByText(/Checking the connection to Facebook/)).toBeInTheDocument();
    expect(posts).toEqual(['/api/destinations', '/api/destinations/2/test']);
  });

  it("doesn't test a key the live stream starts sending to (the test would fight the stream)", async () => {
    await addFacebook(stream([1]));
    expect(posts).toEqual(['/api/destinations']);
  });
});

describe('DestinationsTab: the first destination ever (found by the e2e journey)', () => {
  let puts: unknown[] = [];
  beforeEach(() => {
    puts = [];
    let saved: typeof dests = [];
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (init?.method === 'PUT') puts.push(JSON.parse(String(init.body)));
      if (path === '/api/destinations' && init?.method === 'POST') saved = [dests[1]];
      const body = path === '/api/me' ? me
        : path === '/api/destinations' ? (init?.method === 'POST' ? dests[1] : saved)
        : path.endsWith('/test') ? { testId: 3, status: 'PENDING' } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('turns the new destination on for this stream', async () => {
    const s = { ...stream([]), live: { state: 'DRAFT', uptimeSeconds: 0 } } as StreamView;
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><DestinationsTab stream={s} /></MemoryRouter></QueryClientProvider>);
    await userEvent.click(await screen.findByRole('button', { name: 'Add destination' }));
    await userEvent.click(screen.getByRole('radio', { name: /Facebook/ }));
    await userEvent.type(screen.getByLabelText('Stream key'), 'FB-123-0-Ab');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Add destination' }));
    await vi.waitFor(() => expect(puts).toEqual([[{ destinationId: 2, enabled: true }]]));
  });
});

describe('DestinationsTab in plain words (UX audit #13, #22)', () => {
  const free = { ...me, plan: { ...me.plan, name: 'Free', destinations: 1 } };
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).split('?')[0];
      const body = path === '/api/me' ? free : path === '/api/destinations' ? dests : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('says why a switch is off-limits at the plan limit, with the way to more', async () => {
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><DestinationsTab stream={{ ...stream([1]), live: { state: 'DRAFT', uptimeSeconds: 0 } } as StreamView} /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole('switch', { name: 'Send to Facebook page' })).toBeDisabled();
    const why = screen.getByText(/Your plan sends to 1 destination at once\. Turn one off or/);
    expect(within(why).getByRole('link', { name: 'upgrade' })).toHaveAttribute('href', '/studio/settings/billing');
  });

  it('names the platform and the end of the key instead of the server address', async () => {
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><DestinationsTab stream={stream([1])} /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('YouTube, key ending 8f2k')).toBeInTheDocument();
    expect(screen.getByText('Facebook, key ending a91c')).toBeInTheDocument();
    expect(screen.queryByText(/rtmp:\/\//)).not.toBeInTheDocument();
  });
});

describe('destinationSummary', () => {
  it('keeps the address only for a server we have no name for', () => {
    expect(destinationSummary({ platform: 'YOUTUBE', server: 'rtmp://a.rtmp.youtube.com/live2', keyHint: '••••1234' })).toBe('YouTube, key ending 1234');
    expect(destinationSummary({ platform: 'CUSTOM', server: 'rtmp://my.server/live', keyHint: '••••' })).toBe('rtmp://my.server/live, key saved');
    expect(destinationSummary({ platform: 'KICK', server: 'rtmps://x', keyHint: '' })).toBe('Kick, no key yet');
  });
});
