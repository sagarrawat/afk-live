import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { DestinationChecks, StreamSummary } from '@/lib/types';
import { checksTitle, GoLiveButton } from './live-actions';
import { ToastProvider } from './ui';

const stream = { id: 7, name: 'Lofi 24/7', source: 'PLAYLIST', live: { state: 'DRAFT', uptimeSeconds: 0 } } as unknown as StreamSummary;

const passed: DestinationChecks = { ready: true, checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'PASSED', message: 'Worked in a test in the last 24 hours.' }] };
const testing: DestinationChecks = { ready: false, checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'TESTING', message: 'Sending a 10-second test to YouTube…' }] };
const failed: DestinationChecks = {
  ready: false,
  checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'FAILED', message: 'YouTube refused the stream key. Copy the key again from YouTube and save it.' }],
};

/** Answers the checks with each of {@code answers} in turn (the last one repeats); records every call. */
function mockApi(answers: (DestinationChecks | 'error')[]) {
  const calls: string[] = [];
  let i = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push(`${method} ${url}`);
      if (url.endsWith('/destination-checks')) {
        const a = answers[Math.min(i++, answers.length - 1)];
        if (a === 'error') return new Response('{"message":"boom"}', { status: 500, headers: { 'Content-Type': 'application/json' } });
        return new Response(JSON.stringify(a), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response(url.endsWith('/live') ? '{"success":true}' : '[]', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return calls;
}

const show = () =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <GoLiveButton stream={stream} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

const wentLive = (calls: string[]) => calls.filter((c) => c === 'POST /api/streams/7/live').length;

describe('Go live checks the destinations first (GL-1)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('goes straight live when every destination passed a test recently', async () => {
    const calls = mockApi([passed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    await waitFor(() => expect(wentLive(calls)).toBe(1));
    expect(calls[0]).toBe('POST /api/streams/7/destination-checks');
    expect(screen.queryByText('Checking your destinations')).not.toBeInTheDocument();
  });

  it('shows the test while it runs and goes live by itself when it passes', async () => {
    const calls = mockApi([testing, passed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Checking your destinations')).toBeInTheDocument();
    expect(screen.getByText('Sending a 10-second test to YouTube…')).toBeInTheDocument();
    expect(wentLive(calls)).toBe(0);
    await waitFor(() => expect(wentLive(calls)).toBe(1), { timeout: 4000 });
    expect(calls).toContain('GET /api/streams/7/destination-checks');
    await waitFor(() => expect(screen.queryByText('Checking your destinations')).not.toBeInTheDocument());
  });

  it('blocks on a failure with the reason, Fix key and Go live anyway', async () => {
    const calls = mockApi([failed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Not reaching YouTube')).toBeInTheDocument();
    expect(screen.getByText('YouTube refused the stream key. Copy the key again from YouTube and save it.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Fix key' })).toHaveAttribute('href', '/studio/streams/7/destinations');
    expect(wentLive(calls)).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Go live anyway' }));
    await waitFor(() => expect(wentLive(calls)).toBe(1));
  });

  it('never stands in the way when the check itself fails', async () => {
    const calls = mockApi(['error']);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    await waitFor(() => expect(wentLive(calls)).toBe(1));
  });

  it('titles the dialog by what is happening', () => {
    expect(checksTitle(testing)).toBe('Checking your destinations');
    expect(checksTitle(failed)).toBe('Not reaching YouTube');
    expect(checksTitle({ ready: false, checks: [failed.checks[0], { ...failed.checks[0], destinationId: 2, name: 'My server', platform: null }] })).toBe(
      "Some destinations can't be reached",
    );
    expect(checksTitle({ ready: false, checks: [{ ...failed.checks[0], platform: null, name: 'My server' }] })).toBe('Not reaching My server');
  });
});
