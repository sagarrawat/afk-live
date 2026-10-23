import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { CalendarOccurrence, Me, ScheduleCalendar } from '@/lib/types';

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

const daily = (day: string): CalendarOccurrence => ({
  streamId: 1,
  streamName: 'Bhajans',
  scheduleId: 10,
  kind: 'DAILY',
  start: `${day}T04:30:00Z`,
  end: `${day}T06:30:00Z`,
  startLocal: `${day}T10:00`,
  endLocal: `${day}T12:00`,
  scheduleTimeZone: 'Asia/Kolkata',
  movable: false,
});

const once: CalendarOccurrence = {
  streamId: 2,
  streamName: 'Kirtan',
  scheduleId: 20,
  kind: 'ONCE',
  start: '2026-11-05T05:30:00Z',
  end: '2026-11-05T07:30:00Z',
  startLocal: '2026-11-05T11:00',
  endLocal: '2026-11-05T13:00',
  scheduleTimeZone: 'Asia/Kolkata',
  movable: true,
};

const week: ScheduleCalendar = {
  from: '2026-11-02',
  days: 7,
  timeZone: 'Asia/Kolkata',
  limit: 1,
  planName: 'Creator',
  streams: [
    { id: 1, name: 'Bhajans' },
    { id: 2, name: 'Kirtan' },
  ],
  occurrences: [daily('2026-11-04'), daily('2026-11-05'), once],
  clashes: [
    {
      start: '2026-11-05T05:30:00Z',
      end: '2026-11-05T06:30:00Z',
      startLocal: '2026-11-05T11:00',
      endLocal: '2026-11-05T12:00',
      streams: 2,
      streamIds: [1, 2],
      streamNames: ['Bhajans', 'Kirtan'],
      message: '2 streams would be on air at once, but the Creator plan runs 1 stream at a time. Move a run or upgrade your plan.',
    },
  ],
};

interface Call {
  method: string;
  url: string;
  body?: unknown;
}

function mockApi(calendar: ScheduleCalendar) {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = (typeof input === 'string' ? input : input.toString()).replace(/^https?:\/\/[^/]+/, '');
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      const path = url.split('?')[0];
      const routes: Record<string, unknown> = {
        '/api/me': me,
        '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
        '/api/schedules/calendar': calendar,
      };
      const body = method === 'PUT' ? {} : routes[path];
      return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return calls;
}

function wideScreen(wide: boolean) {
  vi.stubGlobal('matchMedia', (q: string) => ({ matches: wide, media: q, addEventListener: () => {}, removeEventListener: () => {} }));
}

describe('Schedule calendar', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-04T06:00:00Z')); // Wednesday, 11:30 in India
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('on a phone, lists runs day by day with the clash banner first', async () => {
    wideScreen(false);
    const calls = mockApi(week);
    window.history.pushState({}, '', '/studio/schedule');
    render(<App client={makeQueryClient()} />);

    const banner = await screen.findByRole('region', { name: 'Clashes' });
    expect(within(banner).getByText(/1 clash: more streams than your Creator plan runs at once/)).toBeInTheDocument();
    expect(within(banner).getByText(/the Creator plan runs 1 stream at a time/)).toBeInTheDocument();
    expect(within(banner).getByText(/Bhajans, Kirtan/)).toBeInTheDocument();

    expect(calls.some((c) => c.url === '/api/schedules/calendar?from=2026-11-02&days=7')).toBe(true);
    const thursday = screen.getByRole('region', { name: /5 November|November 5/ });
    expect(within(thursday).getByText('Clash')).toBeInTheDocument();
    expect(within(thursday).getByText('Kirtan')).toBeInTheDocument();
    expect(within(thursday).getByText(/11:00 to 13:00 · Once/)).toBeInTheDocument();
    expect(within(thursday).getByText('Kirtan').closest('a')).toHaveAttribute('href', '/studio/streams/2/schedule');
    expect(screen.getAllByRole('link', { name: 'Schedule' }).length).toBeGreaterThan(0);
  });

  it('asks for a six-week grid for a month that needs one', async () => {
    wideScreen(true);
    const calls = mockApi(week);
    window.history.pushState({}, '', '/studio/schedule');
    render(<App client={makeQueryClient()} />);
    await screen.findByRole('region', { name: 'Clashes' });
    fireEvent.click(screen.getByRole('radio', { name: 'Month' }));
    await waitFor(() => expect(calls.some((c) => c.url === '/api/schedules/calendar?from=2026-10-26&days=42')).toBe(true));
    expect(screen.getByText(/November 2026/)).toBeInTheDocument();
  });

  it('on a wide screen, drags a one-time run to a new time and keeps its length', async () => {
    wideScreen(true);
    const calls = mockApi(week);
    window.history.pushState({}, '', '/studio/schedule');
    render(<App client={makeQueryClient()} />);

    const block = await screen.findByRole('button', { name: /^Kirtan, .*Drag to move$/ });
    expect(screen.getAllByRole('button', { name: /^Bhajans, / })).toHaveLength(2);
    expect(screen.getAllByText('Clash').length).toBeGreaterThan(0);

    fireEvent.pointerDown(block, { clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerMove(window, { clientX: 100, clientY: 160 }); // 1.5 hours down
    fireEvent.pointerUp(window, { clientX: 100, clientY: 160 });

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeDefined());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toBe('/api/streams/2/schedules/20');
    expect(put.body).toMatchObject({ kind: 'ONCE', startAt: '2026-11-05T12:30', endAt: '2026-11-05T14:30', timeZone: 'Asia/Kolkata', enabled: true });
  });

  it('opens the stream schedule for a repeating run instead of dragging it', async () => {
    wideScreen(true);
    const calls = mockApi(week);
    window.history.pushState({}, '', '/studio/schedule');
    render(<App client={makeQueryClient()} />);
    const [bhajans] = await screen.findAllByRole('button', { name: /^Bhajans, / });
    fireEvent.pointerDown(bhajans, { clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerMove(window, { clientX: 100, clientY: 200 });
    fireEvent.pointerUp(window, { clientX: 100, clientY: 200 });
    fireEvent.click(bhajans);
    await waitFor(() => expect(window.location.pathname).toBe('/studio/streams/1/schedule'));
    expect(calls.some((c) => c.method === 'PUT')).toBe(false);
  });
});
