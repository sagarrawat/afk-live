import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, makeQueryClient } from '@/App';
import { FIRST_STREAM_DRAFT_KEY } from '@/lib/first-stream-draft';
import type { Me } from '@/lib/types';

const me: Me = {
  id: 1,
  email: 'aarti@example.com',
  name: 'Aarti',
  admin: false,
  plan: { id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours: 10, destinations: 1, watermark: true },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: false,
  emailAlerts: true,
};

type Call = { method: string; path: string; body: unknown };

function mockApi() {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      const routes: Record<string, unknown> = {
        'GET /api/me': me,
        'GET /api/streams': [],
        'GET /api/notifications': { unread: 0, emailAlerts: true, items: [] },
        'POST /api/streams': { id: 7, name: 'x', source: 'PLAYLIST' },
      };
      const body = routes[`${method} ${path}`];
      return new Response(JSON.stringify(body ?? {}), { status: body === undefined && method === 'GET' ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return calls;
}

describe('setup starts from the title chosen on the free title writer (MK-7)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('names the first stream with it, sets it as the YouTube title, offers the description, and uses it once', async () => {
    localStorage.setItem(
      FIRST_STREAM_DRAFT_KEY,
      JSON.stringify({ title: 'Hanuman Chalisa · 24/7 replay', description: '<b>Morning</b> path', language: 'en', mode: 'replay', savedAt: Date.now() }),
    );
    window.history.pushState({}, '', '/studio/setup');
    const calls = mockApi();
    render(<App client={makeQueryClient()} />);
    const note = await screen.findByTestId('title-draft');
    expect(note).toHaveTextContent('Hanuman Chalisa · 24/7 replay');
    expect(note.querySelector('b')).toBeNull();
    expect(screen.getByRole('button', { name: 'Copy description' })).toBeInTheDocument();

    await userEvent.click(screen.getByText('My videos, 24/7'));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.path === '/api/streams/7/check/title')).toBe(true));
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/streams')!.body).toMatchObject({ name: 'Hanuman Chalisa · 24/7 replay', source: 'PLAYLIST' });
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ youtubeTitle: 'Hanuman Chalisa · 24/7 replay' });
    expect(localStorage.getItem(FIRST_STREAM_DRAFT_KEY)).toBeNull();
  });

  it('without a draft, setup is as before', async () => {
    window.history.pushState({}, '', '/studio/setup');
    const calls = mockApi();
    render(<App client={makeQueryClient()} />);
    await userEvent.click(await screen.findByText('Live from my phone'));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/api/streams')).toBe(true));
    expect(screen.queryByTestId('title-draft')).not.toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/streams')!.body).toMatchObject({ name: 'Live from my phone' });
    expect(calls.some((c) => c.path.endsWith('/check/title'))).toBe(false);
  });
});
