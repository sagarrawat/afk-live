import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { attachmentKind, loadLevel, planForm, planProblem, rate, type PlanSettings, type Ticket } from './Admin';

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
  const fn = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    const body = routes[path];
    return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const pro: PlanSettings = {
  plan: 'PRO',
  displayName: 'Pro',
  maxStorageBytes: 100 * 1024 ** 3,
  maxActiveStreams: 3,
  maxDestinations: 5,
  maxChannels: 10,
  maxResolution: 1080,
  maxFps: 60,
  monthlyHours: 0,
  watermark: false,
  maxScheduledPosts: 2147483647,
  priceInrMonth: 149900,
  priceInrYear: 1499000,
  priceUsdMonth: 2400,
  priceUsdYear: 24000,
};
const free: PlanSettings = { ...pro, plan: 'FREE', displayName: 'Free', maxActiveStreams: 1, maxResolution: 720, maxFps: 30, monthlyHours: 10, watermark: true, priceInrMonth: 0, priceInrYear: 0, priceUsdMonth: 0, priceUsdYear: 0 };

const ticket: Ticket = {
  id: 5,
  userEmail: 'aarti@example.com',
  userId: 42,
  category: 'Billing',
  message: 'My payment went through but the plan did not change.',
  status: 'OPEN',
  createdAt: new Date().toISOString(),
  attachmentName: 'receipt.png',
  hasAttachment: true,
};

const base = { '/api/me': admin, '/api/notifications': { unread: 0, emailAlerts: true, items: [] } };

describe('Admin', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows each worker’s CPU and memory history and the cost per stream hour on Servers', async () => {
    window.history.pushState({}, '', '/studio/admin/servers');
    const at = (minAgo: number) => new Date(Date.now() - minAgo * 60_000).toISOString();
    mockApi({
      ...base,
      '/api/admin/workers': [
        { worker: 'worker-mumbai-1', streams: 3, lastHeartbeatAgeSeconds: 2, healthy: true, capacity: 20, cpuPercent: 92.4, memoryUsedBytes: 4 * 1024 ** 3, memoryTotalBytes: 8 * 1024 ** 3 },
        { worker: 'worker-idle', streams: 0, lastHeartbeatAgeSeconds: 5, healthy: true, capacity: 20, draining: true },
      ],
      '/api/admin/servers/history': [
        {
          worker: 'worker-mumbai-1',
          memoryTotalBytes: 8 * 1024 ** 3,
          bucketSeconds: 300,
          points: [
            { at: at(30), cpu: 40, cpuPeak: 45, memory: 50, streams: 3 },
            { at: at(25), cpu: 42, cpuPeak: 96, memory: 50, streams: 3 },
            { at: at(20), cpu: 41, cpuPeak: 44, memory: 50, streams: 3 },
          ],
        },
        { worker: 'worker-idle', bucketSeconds: 300, points: [] },
      ],
      '/api/admin/cost': { monthlyServerCost: 12000, currency: 'INR', streamHours30d: 2400, costPerStreamHour: 5 },
      '/api/admin/email': { configured: true, sent24h: 0, failed24h: 0 },
    });
    render(<App client={makeQueryClient()} />);
    const card = (await screen.findByText('worker-mumbai-1')).closest('li')!;
    expect(within(card).queryByText('Being replaced')).not.toBeInTheDocument();
    // OPS-3: a worker handing its streams to a new version says so.
    expect(within((await screen.findByText('worker-idle')).closest('li')!).getByText('Being replaced')).toBeInTheDocument();
    // A single reading is read against its window: now, average and peak, in words.
    expect(within(card).getByRole('img', { name: /CPU over the last 24 hours: now 41%, average 41%, peak 96%/ })).toBeInTheDocument();
    expect(within(card).getByText('Memory now 4 GB of 8 GB')).toBeInTheDocument();
    expect(within(card).getByText(/3 of 20 streams/)).toBeInTheDocument();
    expect(within(card).getByText('Healthy')).toBeInTheDocument();
    expect(within((await screen.findByText('worker-idle')).closest('li')!).getByText(/No history yet/)).toBeInTheDocument();
    expect(await screen.findByText(/per stream hour/)).toHaveTextContent('2,400 stream hours in the last 30 days');
  });

  it('asks for the server cost when it isn’t set', async () => {
    window.history.pushState({}, '', '/studio/admin/servers');
    mockApi({ ...base, '/api/admin/workers': [], '/api/admin/servers/history': [], '/api/admin/cost': { currency: 'INR', streamHours30d: 10 } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('APP_MONTHLY_SERVER_COST')).toBeInTheDocument();
  });

  it('shows YouTube quota and the status page incidents on Servers', async () => {
    window.history.pushState({}, '', '/studio/admin/servers');
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

  it('shows the platform at a glance and the free plan capacity on Overview (old Platform links redirect)', async () => {
    window.history.pushState({}, '', '/studio/admin/platform');
    mockApi({
      ...base,
      '/api/admin/overview': { users: 1234, liveStreams: 87, storageBytes: 2 * 1024 ** 4, openTickets: 3, freeStreamLimit: 100, revenue: [{ currency: 'INR', allTime: 50000000, last30Days: 1499000 }] },
      '/api/admin/quota': { usedToday: 0, dailyLimit: 10000, percent: 0, resetsAt: new Date().toISOString(), breakdown: [] },
      '/api/admin/status/incidents': [],
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('1,234')).toBeInTheDocument();
    expect(screen.getByText('87')).toBeInTheDocument();
    expect(screen.getByText('2 TB')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '3' })).toHaveAttribute('href', '/studio/admin/support');
    expect(screen.getByText(/free accounts can't go live/)).toHaveTextContent('100 or more streams');
    expect(window.location.pathname).toBe('/studio/admin/overview');
  });

  it('shows payments received on Billing', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({
      ...base,
      '/api/admin/overview': { users: 1234, liveStreams: 87, storageBytes: 0, openTickets: 3, freeStreamLimit: 100, revenue: [{ currency: 'INR', allTime: 50000000, last30Days: 1499000 }] },
      '/api/admin/plans': [],
    });
    render(<App client={makeQueryClient()} />);
    expect((await screen.findByText(/in the last 30 days/)).closest('li')).toHaveTextContent('₹14,990 in the last 30 days, ₹5,00,000 in all');
  });

  it('shows the homepage funnel with each step as a share of the one before on Growth', async () => {
    window.history.pushState({}, '', '/studio/admin/growth');
    mockApi({
      ...base,
      '/api/admin/funnel': [
        { day: '2026-10-24', views: 1200, startClicks: 96, signUps: 40, firstGoLives: 12, firstReached: 10 },
        { day: '2026-10-23', views: 800, startClicks: 4, signUps: 0, firstGoLives: 0, firstReached: 0 },
        { day: '2026-10-22', views: 0, startClicks: 0, signUps: 0, firstGoLives: 0, firstReached: 0 },
      ],
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Homepage to first live, last 30 days')).toBeInTheDocument();
    expect(screen.getByText('2,000')).toBeInTheDocument();
    expect(screen.getByText('5%')).toBeInTheDocument(); // 100 clicks of 2,000 views
    expect(screen.getByText('2026-10-24')).toBeInTheDocument();
    expect(screen.queryByText('2026-10-22')).not.toBeInTheDocument(); // empty days are left out
    expect(rate(1, 0)).toBe('–');
    expect(rate(10, 12)).toBe('83%');
  });

  it('changes the free plan capacity with a reason', async () => {
    window.history.pushState({}, '', '/studio/admin/overview');
    const overview = { users: 1, liveStreams: 0, storageBytes: 0, openTickets: 0, freeStreamLimit: 100, revenue: [] };
    const fetch = mockApi({ ...base, '/api/admin/overview': overview, '/api/admin/settings/free-stream-limit': { ...overview, freeStreamLimit: 40 } });
    render(<App client={makeQueryClient()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Change' }));
    const limit = screen.getByLabelText(/Live streams before free accounts wait/);
    await userEvent.clear(limit);
    await userEvent.type(limit, '-3');
    await userEvent.type(screen.getByLabelText('Reason'), 'Worker pool shrank');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.clear(limit);
    await userEvent.type(limit, '40');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    const call = fetch.mock.calls.find(([u]) => String(u).includes('/free-stream-limit'))!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ limit: 40, reason: 'Worker pool shrank' });
    expect(await screen.findByText(/free accounts can't go live/)).toHaveTextContent('40 or more streams');
  });

  it('lists support tickets and opens one with its attachment', async () => {
    window.history.pushState({}, '', '/studio/admin/support');
    const fetch = mockApi({
      ...base,
      '/api/admin/tickets': { content: [ticket], totalElements: 1, totalPages: 1, number: 0, size: 25 },
      '/api/admin/tickets/5': ticket,
      '/api/admin/tickets/5/status': { ...ticket, status: 'RESOLVED' },
    });
    render(<App client={makeQueryClient()} />);
    const row = (await screen.findByText('aarti@example.com')).closest('a')!;
    expect(row).toHaveAttribute('href', '/studio/admin/support/5');
    expect(within(row).getByText('Open')).toBeInTheDocument();
    expect(within(row).getByLabelText('Has an attachment')).toBeInTheDocument();
    expect(String(fetch.mock.calls.find(([u]) => String(u).startsWith('/api/admin/tickets?'))![0])).toContain('status=OPEN');

    await userEvent.click(row);
    expect(await screen.findByText('My payment went through but the plan did not change.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'aarti@example.com' })).toHaveAttribute('href', '/studio/admin/users/42');
    expect(screen.getByRole('img', { name: 'Attachment: receipt.png' })).toHaveAttribute('src', '/api/admin/tickets/5/attachment');
    expect(screen.getByRole('link', { name: /Download/ })).toHaveAttribute('href', '/api/admin/tickets/5/attachment');

    await userEvent.click(screen.getByRole('button', { name: 'Mark resolved' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'Plan applied by hand');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Mark resolved' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/tickets/5/status')!;
    expect(call[1]!.method).toBe('POST');
    expect(JSON.parse(String(call[1]!.body))).toEqual({ status: 'RESOLVED', reason: 'Plan applied by hand' });
    expect(await screen.findByRole('button', { name: 'Reopen' })).toBeInTheDocument();
  });

  it('shows each plan and checks edits before saving (old Plans links go to Billing)', async () => {
    window.history.pushState({}, '', '/studio/admin/plans');
    const fetch = mockApi({ ...base, '/api/admin/plans': [free, pro], '/api/admin/plans/PRO': { ...pro, maxActiveStreams: 4 } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('₹1,499 a month, ₹14,990 a year')).toBeInTheDocument();
    expect(screen.getByText('$24.00 a month, $240.00 a year')).toBeInTheDocument();
    expect(screen.getByText(/Changes apply right away/)).toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]); // Free, then Pro
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Price review');
    const inrMonth = within(dialog).getByLabelText('Price a month (₹)');
    await userEvent.clear(inrMonth);
    await userEvent.type(inrMonth, '-5');
    expect(within(dialog).getByRole('alert')).toHaveTextContent("Prices can't be empty or negative.");
    expect(within(dialog).getByRole('button', { name: 'Save plan' })).toBeDisabled();

    await userEvent.clear(inrMonth);
    await userEvent.type(inrMonth, '1599');
    const streams = within(dialog).getByLabelText('Streams at a time');
    await userEvent.clear(streams);
    await userEvent.type(streams, '4');
    expect(within(dialog).queryByRole('alert')).toBeNull();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save plan' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/plans/PRO')!;
    const sent = JSON.parse(String(call[1]!.body));
    expect(sent.reason).toBe('Price review');
    expect(sent.plan).toMatchObject({ maxActiveStreams: 4, priceInrMonth: 159900, priceInrYear: 1499000, maxStorageBytes: 100 * 1024 ** 3, maxFps: 60 });
  });

  it('validates plan edits like the server does', () => {
    expect(planProblem('PRO', planForm(pro))).toBeNull();
    expect(planProblem('FREE', planForm(free))).toBeNull();
    expect(planProblem('FREE', { ...planForm(free), inrMonth: '10' })).toMatch(/stay 0/);
    expect(planProblem('PRO', { ...planForm(pro), usdYear: '999' })).toMatch(/12 monthly/);
    expect(planProblem('PRO', { ...planForm(pro), usdMonth: '0' })).toMatch(/above 0/);
    expect(planProblem('PRO', { ...planForm(pro), inrMonth: '1499.999' })).toMatch(/two decimals/);
    expect(planProblem('PRO', { ...planForm(pro), streams: '0' })).toMatch(/Streams at a time/);
    expect(planProblem('PRO', { ...planForm(pro), streams: '2.5' })).toMatch(/Streams at a time/);
    expect(planProblem('PRO', { ...planForm(pro), storageGb: '' })).toMatch(/Storage/);
    expect(planProblem('PRO', { ...planForm(pro), displayName: '  ' })).toMatch(/name/);
    expect(planForm(pro)).toMatchObject({ storageGb: '100', inrMonth: '1499', usdYear: '240' });
  });

  it('previews only images and videos', () => {
    expect(attachmentKind('Screen.PNG')).toBe('image');
    expect(attachmentKind('a.jpeg')).toBe('image');
    expect(attachmentKind('clip.mp4')).toBe('video');
    expect(attachmentKind('page.html')).toBe('file');
    expect(attachmentKind(null)).toBe('file');
  });

  it('colours load by threshold', () => {
    expect(loadLevel(10)).toBe('green');
    expect(loadLevel(80)).toBe('amber');
    expect(loadLevel(95)).toBe('red');
    expect(loadLevel(null)).toBe('green');
  });
});
