import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '@/components/ui';
import type { StreamView } from '@/lib/types';
import { OverviewTab } from './OverviewTab';
import { SetupUploadRow } from '../Setup';

const me = {
  id: 1, email: 'a@example.com', name: 'A', admin: false, onboarded: true, emailAlerts: true, timeZone: 'Asia/Kolkata', currency: 'INR', dismissedHints: ['youtube-side'], goLives: 3,
  plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 5, watermark: false },
};

const stream = {
  id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', qualityHeight: 1080, fps: 30, timeZone: 'Asia/Kolkata', playbackOrder: 'IN_ORDER', loopCount: 0,
  overlays: [], scenes: [], playlist: [{ id: 1, title: 'Rain', durationSeconds: 60, width: 1920, height: 1080, contentType: 'VIDEO', status: 'READY' }], music: [],
  destinations: [{ destinationId: 1, name: 'YouTube main', type: 'RTMP', enabled: true }], schedules: [], totalRuntimeSeconds: 60,
  live: {
    state: 'LIVE', uptimeSeconds: 60, reach: 'NONE',
    nowPlaying: { mediaId: 1, title: 'Rain', elapsed: 5, duration: 60, next: [] },
    health: { overall: 'red', metrics: [], destinations: [{ name: 'YouTube main', status: 'LIVE', level: 'red', restarts: 0, platform: 'YouTube', reach: 'NOT_REACHING', message: 'Not reaching YouTube.' }] },
  },
} as unknown as StreamView;

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).split('?')[0];
      const body = path === '/api/me' ? me : path === '/api/destinations' ? [] : undefined;
      return new Response(JSON.stringify(body ?? {}), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const show = (s: StreamView) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ToastProvider>
        <MemoryRouter>
          <OverviewTab stream={s} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('stream Overview (UX audit #10, #20)', () => {
  it('puts health and "is it reaching YouTube" first, before tips and settings (one column on a phone)', () => {
    show(stream);
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings.slice(0, 2)).toEqual(['Health', 'Destinations']);
    expect(headings.indexOf('Now playing')).toBeGreaterThan(1);
    expect(within(screen.getByTestId('overview-status')).getByText('Not reaching YouTube.')).toBeInTheDocument();
  });

  it('offers the remote control for a playlist stream, on air or not', () => {
    const { unmount } = show(stream);
    expect(screen.getByRole('link', { name: 'remote control' })).toHaveAttribute('href', '/remote.html?stream=7');
    unmount();
    show({ ...stream, live: { state: 'DRAFT', uptimeSeconds: 0 } } as StreamView);
    expect(screen.getByRole('link', { name: 'remote control' })).toHaveAttribute('href', '/remote.html?stream=7');
  });
});

describe('a failed upload in setup (UX audit #17)', () => {
  it('says why and offers Try again, with Free up space when storage is full', () => {
    const retry = vi.fn();
    render(
      <MemoryRouter>
        <ul>
          <SetupUploadRow name="big.mp4" progress={{ state: 'failed', sent: 0, total: 10, rate: 0, error: 'Not enough storage space left on your plan.' }} onRetry={retry} />
        </ul>
      </MemoryRouter>,
    );
    expect(screen.getByText(/Didn't upload\. Not enough storage space left on your plan\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Free up space' })).toHaveAttribute('href', '/studio/library');
    screen.getByRole('button', { name: 'Try again' }).click();
    expect(retry).toHaveBeenCalled();
  });
});

describe('Recent runs (RE-9, found by the e2e journey)', () => {
  it('shows the run as ended once the stream stops, without a reload', async () => {
    let ended = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input).split('?')[0];
        const run = { jobId: 3, startedAt: '2026-10-27T07:00:00Z', endedAt: ended ? '2026-10-27T07:00:30Z' : null, endReason: ended ? 'Stopped by you' : null };
        const body = path === '/api/me' ? me : path === '/api/destinations' ? [] : path === '/api/streams/7/runs' ? [run] : undefined;
        return new Response(JSON.stringify(body ?? {}), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } });
    const view = (s: StreamView) => (
      <QueryClientProvider client={client}>
        <ToastProvider>
          <MemoryRouter>
            <OverviewTab stream={s} />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    );
    const { rerender } = render(view(stream));
    expect(await screen.findByText('On air')).toBeInTheDocument();
    ended = true;
    rerender(view({ ...stream, live: { state: 'ENDED', uptimeSeconds: 0 } } as StreamView));
    expect(await screen.findByText('Stopped by you')).toBeInTheDocument();
    expect(screen.queryByText('On air')).not.toBeInTheDocument();
  });
});
