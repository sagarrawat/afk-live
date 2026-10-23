import { render, screen, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { loadLevel } from './Admin';

const admin: Me = {
  id: 1,
  email: 'ops@example.com',
  name: 'Ops',
  admin: true,
  plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 3, watermark: false },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};

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

const base = { '/api/me': admin, '/api/notifications': { unread: 0, emailAlerts: true, items: [] } };

describe('Admin', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows each worker’s CPU and memory and the cost per stream hour', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    mockApi({
      ...base,
      '/api/admin/live': [],
      '/api/admin/workers': [
        { worker: 'worker-mumbai-1', streams: 3, lastHeartbeatAgeSeconds: 2, healthy: true, capacity: 20, cpuPercent: 92.4, memoryUsedBytes: 4 * 1024 ** 3, memoryTotalBytes: 8 * 1024 ** 3 },
        { worker: 'worker-idle', streams: 0, lastHeartbeatAgeSeconds: 5, healthy: true, capacity: 20 },
      ],
      '/api/admin/cost': { monthlyServerCost: 12000, currency: 'INR', streamHours30d: 2400, costPerStreamHour: 5 },
    });
    render(<App client={makeQueryClient()} />);
    const card = (await screen.findByText('worker-mumbai-1')).closest('li')!;
    expect(within(card).getByText('92%')).toHaveClass('text-bad');
    expect(within(card).getByText('4 GB of 8 GB')).toBeInTheDocument();
    expect(within(card).getByText(/3 of 20 streams/)).toBeInTheDocument();
    expect(within((await screen.findByText('worker-idle')).closest('li')!).getByText(/No CPU or memory report yet/)).toBeInTheDocument();
    expect(await screen.findByText(/per stream hour/)).toHaveTextContent('2,400 stream hours in the last 30 days');
  });

  it('asks for the server cost when it isn’t set', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    mockApi({ ...base, '/api/admin/live': [], '/api/admin/workers': [], '/api/admin/cost': { currency: 'INR', streamHours30d: 10 } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('APP_MONTHLY_SERVER_COST')).toBeInTheDocument();
  });

  it('shows YouTube quota and the status page incidents on Platform', async () => {
    window.history.pushState({}, '', '/studio/admin/platform');
    mockApi({
      ...base,
      '/api/admin/quota': { usedToday: 8200, dailyLimit: 10000, percent: 82, resetsAt: new Date(Date.now() + 3 * 3600_000).toISOString(), breakdown: [{ api: 'videos.insert', units: 8000 }, { api: 'liveBroadcasts.list', units: 200 }] },
      '/api/admin/status/incidents': [
        { incident: { id: 7, title: 'Streams reconnecting', status: 'identified', startedAt: new Date().toISOString(), updates: [{ status: 'identified', message: 'A network provider is down.', at: new Date().toISOString() }] }, createdBy: 'ops@example.com' },
      ],
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('8,200')).toBeInTheDocument();
    expect(screen.getByText('82% used')).toHaveClass('text-warn');
    expect(screen.getByText('videos.insert')).toBeInTheDocument();
    expect(await screen.findByText('Streams reconnecting')).toBeInTheDocument();
    expect(screen.getByText('A network provider is down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Post update' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Post an incident' })).toBeInTheDocument();
  });

  it('colours load by threshold', () => {
    expect(loadLevel(10)).toBe('green');
    expect(loadLevel(80)).toBe('amber');
    expect(loadLevel(95)).toBe('red');
    expect(loadLevel(null)).toBe('green');
  });
});
