import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { CapacityEstimate, DestinationChecks, Preflight } from '@/lib/types';
import { ToastProvider } from './ui';
import { checksTitle, GoLiveButton, SlowNotice } from './live-actions';

const stream = { id: 7, name: 'Night drive', source: 'PLAYLIST' as const, live: { state: 'DRAFT' as const, uptimeSeconds: 0 } };

// The 24 Oct 2026 case: 1080p 60 fps on a busy server.
const busy: CapacityEstimate = {
  needCpus: 2.1,
  freeCpus: 0.6,
  fits: false,
  impossible: false,
  suggestion: { height: 720, fps: 30 },
  suggestionFits: true,
  message: 'Our servers are busy right now. By our estimate 1080p 60 fps would run slower than real time, so viewers would see it play slowly. 720p 30 fps fits.',
};

type Call = { url: string; method: string; body: unknown };

function respond(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
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

describe('going live (GL-7 capacity warning)', () => {
  let calls: Call[] = [];
  let liveResponses: (() => Response)[] = [];
  beforeEach(() => {
    calls = [];
    liveResponses = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : null });
        if (url.endsWith('/live')) return (liveResponses.shift() ?? (() => respond(202, { jobId: 20, state: 'STARTING' })))();
        return respond(200, {});
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const lives = () => calls.filter((c) => c.url.endsWith('/live'));
  const warned = () => respond(409, { success: false, message: busy.message, preflight: { ok: true, checks: [], capacity: busy } satisfies Preflight });

  it('warns in plain words, labelled as an estimate, and takes the suggested quality in one tap', async () => {
    liveResponses.push(warned);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('This quality may not keep up right now')).toBeInTheDocument();
    expect(screen.getByText(/would run slower than real time, so viewers would see it play slowly/)).toBeInTheDocument();
    expect(screen.getByText(/This is an estimate: about 2\.1 processor cores needed, 0\.6 free/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Use 720p 30 fps' }));
    await waitFor(() => expect(lives()).toHaveLength(2));
    expect(calls.find((c) => c.method === 'PATCH')).toEqual({ url: '/api/streams/7', method: 'PATCH', body: { qualityHeight: 720, fps: 30 } });
    expect(lives()[1].body).toEqual({}); // the lighter quality goes live without "anyway"
    await waitFor(() => expect(screen.queryByText('This quality may not keep up right now')).not.toBeInTheDocument());
  });

  it('can go live anyway', async () => {
    liveResponses.push(warned);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Go live anyway' }));
    await waitFor(() => expect(lives()).toHaveLength(2));
    expect(lives()[1].body).toEqual({ anyway: true });
    expect(calls.some((c) => c.method === 'PATCH')).toBe(false);
  });

  it('a failed check still shows the pre-flight list, not the capacity warning', async () => {
    liveResponses.push(() =>
      respond(409, { preflight: { ok: false, checks: [{ id: 'playlist', ok: false, message: 'Add at least one video to the playlist.', fix: 'playlist' }] } }),
    );
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Add at least one video to the playlist.')).toBeInTheDocument();
    expect(screen.queryByText('This quality may not keep up right now')).not.toBeInTheDocument();
  });

  it('sends one request for a double tap', async () => {
    let release: (r: Response) => void = () => {};
    liveResponses.push(() => respond(202, {}));
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        calls.push({ url: String(input), method: init?.method ?? 'GET', body: null });
        if (String(input).endsWith('/live')) return new Promise<Response>((r) => (release = r));
        return Promise.resolve(respond(200, {}));
      }),
    );
    show();
    const button = screen.getByRole('button', { name: 'Go live' });
    act(() => {
      button.click();
      button.click(); // before React re-renders it as busy
    });
    await waitFor(() => expect(button).toBeDisabled());
    await userEvent.click(button);
    expect(lives()).toHaveLength(1);
    await act(async () => release(respond(202, { jobId: 20, state: 'STARTING' })));
    await waitFor(() => expect(button).toBeEnabled());
  });
});

describe('running slower than real time (RE-10)', () => {
  it('says what viewers see and how to fix it', () => {
    render(
      <MemoryRouter>
        <SlowNotice live={{ slowSince: '2026-10-24T09:12:00Z' }} streamId={7} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Running slower than real time, so viewers see it play slowly.');
    expect(screen.getByRole('link', { name: 'Lower it to 720p 30 fps' })).toHaveAttribute('href', '/studio/streams/7/settings');
  });

  it('shows nothing while the stream keeps up', () => {
    const { container } = render(
      <MemoryRouter>
        <SlowNotice live={{ slowSince: null }} streamId={7} />
      </MemoryRouter>,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

// ------------------------------------------------------------------ GL-1: destination tests before going live

const passed: DestinationChecks = { ready: true, checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'PASSED', message: 'Worked in a test in the last 24 hours.' }] };
const testing: DestinationChecks = { ready: false, checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'TESTING', message: 'Sending a 10-second test to YouTube…' }] };
const failed: DestinationChecks = {
  ready: false,
  checks: [{ destinationId: 1, name: 'Main channel', platform: 'YouTube', state: 'FAILED', message: 'YouTube refused the stream key. Copy the key again from YouTube and save it.' }],
};

/**
 * Answers the destination checks with each of {@code answers} in turn (the last one repeats), the pre-flight
 * with {@code preflight}; records every call as "METHOD url" with its body.
 */
function mockChecks(answers: (DestinationChecks | 'error')[], preflight: unknown = { ok: true, checks: [] }) {
  const calls: Call[] = [];
  let i = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : null });
      if (url.endsWith('/destination-checks')) {
        const a = answers[Math.min(i++, answers.length - 1)];
        return a === 'error' ? respond(500, { message: 'boom' }) : respond(200, a);
      }
      if (url.endsWith('/preflight')) return respond(200, preflight);
      return respond(200, url.endsWith('/live') ? { success: true } : {});
    }),
  );
  return calls;
}

const wentLive = (calls: Call[]) => calls.filter((c) => c.method === 'POST' && c.url === '/api/streams/7/live');

describe('Go live checks the destinations first (GL-1)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('goes straight live when every destination passed a test recently', async () => {
    const calls = mockChecks([passed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1));
    expect(calls[0].url).toBe('/api/streams/7/destination-checks');
    expect(screen.queryByText('Checking your destinations')).not.toBeInTheDocument();
  });

  it('shows the test while it runs and goes live by itself when it passes', async () => {
    const calls = mockChecks([testing, passed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Checking your destinations')).toBeInTheDocument();
    expect(screen.getByText('Sending a 10-second test to YouTube…')).toBeInTheDocument();
    expect(wentLive(calls)).toHaveLength(0);
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1), { timeout: 4000 });
    expect(calls.some((c) => c.method === 'GET' && c.url === '/api/streams/7/destination-checks')).toBe(true);
    await waitFor(() => expect(screen.queryByText('Checking your destinations')).not.toBeInTheDocument());
  });

  it('blocks on a failure with the reason, Fix key and Go live anyway', async () => {
    const calls = mockChecks([failed]);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Not reaching YouTube')).toBeInTheDocument();
    expect(screen.getByText('YouTube refused the stream key. Copy the key again from YouTube and save it.')).toBeInTheDocument();
    // DS-8: straight to editing the destination that failed, not the stream's list of switches.
    expect(screen.getByRole('link', { name: 'Fix key' })).toHaveAttribute('href', '/studio/destinations?edit=1');
    expect(wentLive(calls)).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Go live anyway' }));
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1));
    expect(wentLive(calls)[0].body).toEqual({}); // no capacity warning was shown: nothing to override
  });

  it('never stands in the way when the check itself fails', async () => {
    const calls = mockChecks(['error']);
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1));
  });

  it('shows a failed test and the capacity warning in one dialog; one answer covers both (GL-1 + GL-7)', async () => {
    const calls = mockChecks([failed], { ok: true, checks: [], capacity: busy });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Before you go live')).toBeInTheDocument();
    expect(screen.getByText('YouTube refused the stream key. Copy the key again from YouTube and save it.')).toBeInTheDocument();
    expect(screen.getByText(/would run slower than real time/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Fix key' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use 720p 30 fps' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Go live anyway' }));
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1));
    expect(wentLive(calls)[0].body).toEqual({ anyway: true });
  });

  it('passing tests never skip the capacity warning', async () => {
    const calls = mockChecks([testing, passed], { ok: true, checks: [], capacity: busy });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Go live' }));
    expect(await screen.findByText('Checking your destinations')).toBeInTheDocument();
    expect(screen.getByText(/would run slower than real time/)).toBeInTheDocument(); // both, in one dialog
    await waitFor(() => expect(screen.queryByText('Sending a 10-second test to YouTube…')).not.toBeInTheDocument(), { timeout: 4000 });
    expect(screen.getByText('This quality may not keep up right now')).toBeInTheDocument();
    expect(wentLive(calls)).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Use 720p 30 fps' }));
    await waitFor(() => expect(wentLive(calls)).toHaveLength(1));
    expect(wentLive(calls)[0].body).toEqual({});
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
