import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { CapacityEstimate, Preflight } from '@/lib/types';
import { ToastProvider } from './ui';
import { GoLiveButton, SlowNotice } from './live-actions';

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
