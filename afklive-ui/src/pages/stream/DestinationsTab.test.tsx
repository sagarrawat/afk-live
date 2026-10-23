import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
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

  it("won't turn off the only destination of a live stream", async () => {
    show(stream([1]));
    expect(await screen.findByRole('switch', { name: 'Send to YouTube main' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Send to Facebook page' })).toBeEnabled();
    expect(screen.getByText(/other destinations stay connected/)).toBeInTheDocument();
  });

  it('adds a destination to the live stream straight away', async () => {
    show(stream([1]));
    await userEvent.click(await screen.findByRole('switch', { name: 'Send to Facebook page' }));
    expect(puts).toEqual([[{ destinationId: 1, enabled: true }, { destinationId: 2, enabled: true }]]);
  });
});
