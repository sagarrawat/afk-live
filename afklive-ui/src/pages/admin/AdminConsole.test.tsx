import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { hits } from './AdminSearch';
import { summarize } from './HistoryChart';
import { reachReason, reachWord, worstFirst } from './live-logic';
import { sinceText } from './OverviewPage';
import type { ConsoleOverview, LiveRow } from './types';
import { paidText } from './UsersPage';

const admin: Me = {
  id: 1,
  email: 'ops@example.com',
  name: 'Ops',
  admin: true,
  plan: { id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours: 10, destinations: 1, watermark: true },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};
const base = {
  '/api/me': admin,
  '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
  '/api/admin/overview': { users: 12, liveStreams: 7, storageBytes: 0, openTickets: 0, freeStreamLimit: 100, revenue: [] },
};

function mockApi(routes: Record<string, unknown>) {
  const fn = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const path = String(input).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    const body = routes[path];
    return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const row = (over: Partial<LiveRow>): LiveRow => ({
  jobId: 1,
  streamName: 'Stream',
  userEmail: 'a@example.com',
  userId: 10,
  plan: 'PRO',
  worker: 'worker-1',
  status: 'LIVE',
  health: 'green',
  uptimeSeconds: 100,
  restarts: 0,
  fps: 30,
  speed: 1,
  quality: '1080p 30 fps',
  destinationHealth: [{ name: 'Main', platform: 'YouTube', reach: 'ON', restarts: 0, message: 'On YouTube' }],
  ...over,
});

const healthy = row({ jobId: 1, streamName: 'Lofi beats', uptimeSeconds: 9000 });
const slow = row({ jobId: 2, streamName: 'Rain sounds', health: 'amber', speed: 0.82, fps: 24, uptimeSeconds: 600 });
const unreached = row({
  jobId: 3,
  streamName: 'Bhajan 24/7',
  userEmail: 'meera@example.com',
  health: 'red',
  uptimeSeconds: 300,
  destinationHealth: [
    { name: 'Main', platform: 'YouTube', reach: 'NOT_REACHING', restarts: 4, message: 'Not reaching YouTube. YouTube refused the stream key. Copy it again from YouTube Studio.' },
    { name: 'Backup', platform: 'Facebook', reach: 'ON', restarts: 0, message: 'On Facebook' },
  ],
});

const overview: ConsoleOverview = {
  attention: [
    { kind: 'NOT_REACHING', level: 'red', title: "Bhajan 24/7 isn't reaching anyone", detail: 'Not reaching YouTube. YouTube refused the stream key.', since: new Date(Date.now() - 12 * 60_000).toISOString(), jobId: 3, userId: 10, link: '/studio/admin/runs/3' },
    { kind: 'EMAIL_OFF', level: 'amber', title: 'Email is off', detail: 'Nothing is sent.', link: '/studio/admin/servers#email' },
  ],
  health: { green: 5, amber: 1, red: 1 },
  liveStreams: 7,
  signUpsToday: 4,
  firstGoLivesToday: 2,
  workers: 1,
};

describe('Admin console', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('has its own layout: admin nav, Admin marker, a way back, and none of the creator app', async () => {
    window.history.pushState({}, '', '/studio/admin');
    mockApi({ ...base, '/api/admin/attention': overview, '/api/admin/live': [unreached], '/api/admin/servers/history': [] });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Needs attention now')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/studio/admin/overview');
    const navs = screen.getAllByRole('navigation', { name: 'Admin' });
    for (const label of ['Overview', 'Live', 'Users', 'Support', 'Billing', 'Servers', 'Growth', 'Audit']) {
      expect(within(navs[0]).getByRole('link', { name: new RegExp(`^${label}`) })).toBeInTheDocument();
    }
    expect(screen.getAllByText('Admin').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: /Creator app/ })).toHaveAttribute('href', '/studio');
    // Creator Shell items and the plan card don't appear here.
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
    expect(screen.queryByText('Free plan')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Schedule' })).toBeNull();
  });

  it('lists what needs attention, worst first, with email off as one compact item and Stop / Move on streams', async () => {
    window.history.pushState({}, '', '/studio/admin/overview');
    mockApi({ ...base, '/api/admin/attention': overview, '/api/admin/live': [unreached], '/api/admin/servers/history': [] });
    render(<App client={makeQueryClient()} />);
    const list = (await screen.findByText("Bhajan 24/7 isn't reaching anyone")).closest('ul')!;
    const items = within(list).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Problem');
    expect(items[0]).toHaveTextContent(/for 12 m/);
    expect(within(items[0]).getByRole('button', { name: 'Stop Bhajan 24/7' })).toBeInTheDocument();
    expect(within(items[0]).getByRole('button', { name: 'Move Bhajan 24/7' })).toBeEnabled();
    expect(items[1]).toHaveTextContent('Needs a look');
    expect(items[1]).toHaveTextContent('Email is off');
    expect(screen.queryByRole('alert')).toBeNull(); // no banner
    expect(screen.getByText('Sign-ups').nextSibling).toHaveTextContent('4');
    expect(screen.getByText('First Go live').nextSibling).toHaveTextContent('2');
    // The Overview nav item counts the problems.
    expect(screen.getAllByLabelText('2 need attention').length).toBeGreaterThan(0);
  });

  it('says plainly when nothing needs attention', async () => {
    window.history.pushState({}, '', '/studio/admin/overview');
    mockApi({ ...base, '/api/admin/attention': { ...overview, attention: [] }, '/api/admin/live': [], '/api/admin/servers/history': [] });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Nothing needs you right now.')).toBeInTheDocument();
    expect(screen.getByText('All clear')).toBeInTheDocument();
  });

  it('sorts live streams worst first and shows speed, fps, quality, plan and each platform’s reach in words', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    mockApi({ ...base, '/api/admin/live': [healthy, slow, unreached], '/api/admin/attention': overview });
    render(<App client={makeQueryClient()} />);
    await screen.findAllByText('Bhajan 24/7');
    const table = screen.getAllByRole('table')[0];
    const bodyRows = within(table).getAllByRole('row').slice(1);
    expect(bodyRows.map((r) => within(r).getAllByRole('link')[0].textContent)).toEqual(['Bhajan 24/7', 'Rain sounds', 'Lofi beats']);
    const [bad, slowRow] = bodyRows;
    expect(within(bad).getByText('Problem')).toBeInTheDocument();
    expect(within(bad).getByText('Not reaching')).toHaveClass('text-bad');
    expect(within(bad).getByText('YouTube refused the stream key. Copy it again from YouTube Studio.')).toBeInTheDocument();
    expect(within(bad).getByText('On')).toBeInTheDocument();
    expect(within(slowRow).getByText('0.82×')).toBeInTheDocument();
    expect(within(slowRow).getByText('slow')).toBeInTheDocument();
    expect(within(slowRow).getByText('low')).toBeInTheDocument(); // 24 of 30 fps
    expect(within(slowRow).getByText('1080p 30 fps')).toBeInTheDocument();
    expect(within(slowRow).getByText('PRO')).toBeInTheDocument();
    // A row opens the run's support view.
    await userEvent.click(within(slowRow).getByText('1080p 30 fps'));
    expect(window.location.pathname).toBe('/studio/admin/runs/2');
  });

  it('keeps Stop apart from Move and both still ask for a reason', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    const fetch = mockApi({ ...base, '/api/admin/live': [unreached], '/api/admin/attention': overview, '/api/admin/jobs/3/stop': { ok: true } });
    render(<App client={makeQueryClient()} />);
    await screen.findAllByText('Bhajan 24/7');
    const table = screen.getAllByRole('table')[0];
    const stop = within(table).getByRole('button', { name: 'Stop Bhajan 24/7' });
    const move = within(table).getByRole('button', { name: 'Move Bhajan 24/7' });
    expect(stop.previousElementSibling).not.toBe(move); // a divider sits between them
    await userEvent.click(stop);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Stop stream' })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Key leaked in chat');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Stop stream' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/jobs/3/stop')!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ reason: 'Key leaked in chat' });
    expect(window.location.pathname).toBe('/studio/admin/live'); // the row didn't open
  });

  it('has compact phone rows with Move and Stop for each live stream', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    mockApi({ ...base, '/api/admin/live': [healthy, unreached], '/api/admin/attention': overview });
    render(<App client={makeQueryClient()} />);
    await screen.findAllByText('Bhajan 24/7');
    const phone = screen.getByRole('list', { name: 'Live streams' });
    expect(phone).toHaveClass('md:hidden');
    const items = within(phone).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Bhajan 24/7');
    expect(within(items[0]).getByText('Not reaching')).toBeInTheDocument();
    expect(within(items[0]).getByRole('button', { name: 'Stop Bhajan 24/7' })).toBeInTheDocument();
    expect(within(items[0]).getByRole('button', { name: 'Move Bhajan 24/7' })).toBeInTheDocument();
    // The phone nav scrolls sideways under the header.
    expect(screen.getAllByRole('navigation', { name: 'Admin' }).some((n) => n.className.includes('overflow-x-auto'))).toBe(true);
  });

  it('searches users, streams and runs from the header with Ctrl+K and the keyboard', async () => {
    window.history.pushState({}, '', '/studio/admin/overview');
    const fetch = mockApi({
      ...base,
      '/api/admin/attention': overview,
      '/api/admin/live': [],
      '/api/admin/servers/history': [],
      '/api/admin/search': {
        users: [{ id: 10, email: 'meera@example.com', name: 'Meera', plan: 'PRO' }],
        streams: [{ id: 5, name: 'Bhajan 24/7', userId: 10, userEmail: 'meera@example.com', liveJobId: 3, lastJobId: 3 }],
        runs: [],
      },
      '/api/admin/users/10': null,
      '/api/admin/jobs/3': null,
    });
    render(<App client={makeQueryClient()} />);
    await screen.findByText('Needs attention now');
    await userEvent.keyboard('{Control>}k{/Control}');
    const box = screen.getAllByRole('combobox', { name: 'Search users, streams and runs' })[0];
    expect(box).toHaveFocus();
    await userEvent.keyboard('meera');
    const listbox = await screen.findByRole('listbox', { name: 'Search results' });
    expect(await within(listbox).findByText('meera@example.com', { selector: 'span.font-medium' })).toBeInTheDocument();
    expect(String(fetch.mock.calls.find(([u]) => String(u).startsWith('/api/admin/search'))![0])).toContain('q=meera');
    const options = within(listbox).getAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    await userEvent.keyboard('{ArrowDown}');
    expect(options[1]).toHaveAttribute('aria-selected', 'true');
    expect(box).toHaveAttribute('aria-activedescendant', options[1].id);
    await userEvent.keyboard('{Enter}');
    expect(window.location.pathname).toBe('/studio/admin/runs/3'); // a live stream opens its run
  });

  it('shows the users table with plan, sign-up (or a dash), last live, hours and paid, and filters', async () => {
    window.history.pushState({}, '', '/studio/admin/users');
    const fetch = mockApi({
      ...base,
      '/api/admin/attention': overview,
      '/api/admin/users': {
        content: [
          { id: 10, email: 'meera@example.com', name: 'Meera', plan: 'PRO', enabled: true, liveStreams: 1, storageBytes: 0, createdAt: '2026-10-01T10:00:00Z', lastLiveAt: new Date().toISOString(), hoursThisMonth: 210.5, paid: [{ currency: 'INR', amount: 149900 }] },
          { id: 9, email: 'old@example.com', plan: 'FREE', enabled: true, liveStreams: 0, storageBytes: 0, createdAt: null, lastLiveAt: null, hoursThisMonth: 0, paid: [] },
        ],
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 25,
      },
    });
    render(<App client={makeQueryClient()} />);
    const meera = (await screen.findByText('meera@example.com')).closest('tr')!;
    expect(meera).toHaveTextContent('PRO');
    expect(meera).toHaveTextContent('Live now');
    expect(meera).toHaveTextContent('210.5 h');
    expect(meera).toHaveTextContent('₹1,499');
    const old = screen.getByText('old@example.com').closest('tr')!;
    expect(within(old).getAllByText('–').length).toBeGreaterThanOrEqual(3); // no sign-up date, hours, paid
    expect(old).toHaveTextContent('Never');
    await userEvent.click(screen.getByRole('radio', { name: 'Never went live' }));
    expect(fetch.mock.calls.some(([u]) => String(u).includes('filter=never'))).toBe(true);
  });

  it('works out sorting, reach words, summaries and search hits', () => {
    expect(worstFirst([healthy, slow, unreached]).map((r) => r.jobId)).toEqual([3, 2, 1]);
    // Same health: not reaching beats slow beats fine; then longest-running first.
    const a = row({ jobId: 11, health: 'amber', speed: 0.5 });
    const b = row({ jobId: 12, health: 'amber', destinationHealth: [{ name: 'x', reach: 'NOT_REACHING', restarts: 0 }] });
    const c = row({ jobId: 13, health: 'amber', uptimeSeconds: 5 });
    const d = row({ jobId: 14, health: 'amber', uptimeSeconds: 50 });
    expect(worstFirst([c, d, a, b]).map((r) => r.jobId)).toEqual([12, 11, 14, 13]);
    expect(reachWord({ name: 'x', reach: 'ON', restarts: 0 })).toBe('On');
    expect(reachWord({ name: 'x', reach: null, status: 'OFF', restarts: 0 })).toBe('Off');
    expect(reachReason({ name: 'x', reach: 'NOT_REACHING', restarts: 0, message: 'Not reaching Twitch. Wrong key.' })).toBe('Wrong key.');
    expect(reachReason({ name: 'x', reach: 'ON', restarts: 0, message: 'On Twitch' })).toBeNull();
    expect(summarize([], 'cpu').now).toBeNull();
    expect(summarize([{ at: 'a', cpu: 10, cpuPeak: 90, streams: 0 }, { at: 'b', cpu: 30, cpuPeak: 35, streams: 0 }], 'cpu')).toEqual({ now: 30, average: 20, peak: 90, peakAt: 'a' });
    expect(sinceText(null)).toBe('');
    expect(sinceText(new Date(Date.now() - 10_000).toISOString())).toBe('just now');
    expect(paidText([])).toBe('–');
    expect(paidText([{ currency: 'USD', amount: 2400 }])).toBe('$24.00');
    expect(hits({ users: [], streams: [{ id: 1, name: 'S', userId: 2, userEmail: 'e', liveJobId: null, lastJobId: null }], runs: [] })[0].to).toBe('/studio/admin/users/2');
  });
});
