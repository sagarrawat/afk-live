import { render, screen, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me, StreamSummary } from '@/lib/types';

const me: Me = {
  id: 1,
  email: 'aarti@example.com',
  name: 'Aarti',
  admin: false,
  plan: { id: 'ESSENTIALS', name: 'Creator', streams: 1, maxHeight: 1080, maxFps: 30, storageBytes: 1, monthlyHours: 0, destinations: 2, watermark: false },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};

function stream(over: Partial<StreamSummary>): StreamSummary {
  return { id: 1, name: 'Lofi 24/7', source: 'PLAYLIST', orientation: 'LANDSCAPE', qualityHeight: 1080, playlistCount: 4, live: { state: 'DRAFT', uptimeSeconds: 0 }, ...over };
}

function mockApi(routes: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
      const body = routes[path];
      return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('Home', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lists streams with live ones first, showing state, now playing and uptime', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      '/api/me': me,
      '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
      '/api/streams': [
        stream({ id: 1, name: 'Bhajans morning' }),
        stream({
          id: 2,
          name: 'Lofi 24/7',
          live: { state: 'LIVE', uptimeSeconds: 3725, startedAt: new Date(Date.now() - 3725_000).toISOString(), nowPlaying: { title: 'rain.mp4' }, health: { overall: 'green', metrics: [], destinations: [] } },
        }),
      ],
    });
    render(<App client={makeQueryClient()} />);
    const rows = await screen.findAllByRole('listitem');
    const first = rows.find((r) => within(r).queryByText('Lofi 24/7'))!;
    expect(within(first).getByText('Live')).toBeInTheDocument();
    expect(within(first).getByText('Now playing rain.mp4')).toBeInTheDocument();
    expect(within(first).getByText(/^01:02:0\d$/)).toBeInTheDocument();
    expect(within(first).getByRole('button', { name: 'Stop' })).toBeInTheDocument();
    expect(await screen.findByText('1 of 2 on air right now.')).toBeInTheDocument();
    // live first
    const names = rows.map((r) => within(r).queryByText(/Lofi 24\/7|Bhajans morning/)?.textContent).filter(Boolean);
    expect(names[0]).toBe('Lofi 24/7');
  });

  it('sends a new creator to the setup guide', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({ '/api/me': { ...me, onboarded: false }, '/api/streams': [], '/api/notifications': { unread: 0, emailAlerts: true, items: [] } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Set up your first stream')).toBeInTheDocument();
  });
});
