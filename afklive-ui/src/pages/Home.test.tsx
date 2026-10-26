import { fireEvent, render, screen, within } from '@testing-library/react';
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

describe('Home: why streams stopped, and hours left (UX audit 6, 7, 11)', () => {
  afterEach(() => vi.unstubAllGlobals());
  const free: Me = { ...me, plan: { ...me.plan, id: 'FREE', name: 'Free', maxHeight: 720, monthlyHours: 10 }, hoursUsed: 8.5 };

  it('says why each off-air stream stopped, and how many free hours are left', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({
      '/api/me': free,
      '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
      '/api/streams': [
        stream({ id: 1, name: 'Bhajans morning', live: { state: 'ENDED', uptimeSeconds: 0, endReason: "This month's free streaming hours are used up" } }),
        stream({ id: 2, name: 'Lofi 24/7', live: { state: 'ENDED', uptimeSeconds: 0, endReason: 'Scheduled stop' } }),
        stream({ id: 3, name: 'Rain sounds', live: { state: 'ERROR', uptimeSeconds: 0, endReason: 'Stream kept failing: connection refused' } }),
        stream({ id: 4, name: 'Never run' }),
      ],
    });
    render(<App client={makeQueryClient()} />);
    const row = async (name: string) => (await screen.findByText(name)).closest('li')!;
    expect(within(await row('Bhajans morning')).getByText("Stopped because this month's free streaming hours are used up.")).toHaveClass('text-muted');
    expect(within(await row('Lofi 24/7')).getByText('Stopped at its scheduled time.')).toBeInTheDocument();
    expect(within(await row('Rain sounds')).getByText('Stream kept failing: connection refused')).toHaveClass('text-bad');
    expect(within(await row('Never run')).getByText('4 videos, 1080p')).toBeInTheDocument();
    // Hours left sit in the page description, so they show on phones too (the sidebar is hidden there).
    expect(screen.getAllByText(/1\.5 of 10 h left this month/).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'Upgrade' })[0]).toHaveAttribute('href', '/studio/settings/billing');
  });

  it('has no retired "YouTube upload tools" link in the account menu', async () => {
    window.history.pushState({}, '', '/studio');
    mockApi({ '/api/me': me, '/api/notifications': { unread: 0, emailAlerts: true, items: [] }, '/api/streams': [stream({})] });
    render(<App client={makeQueryClient()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    expect(await screen.findByText('Help')).toBeInTheDocument();
    expect(screen.queryByText('YouTube upload tools')).not.toBeInTheDocument();
    expect(document.querySelector('a[href="/classic"]')).toBeNull();
  });
});
