import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { Destination, LiveStateName, Me, StreamView } from '@/lib/types';
import { FRESH_SECONDS, liveSeconds, showYouTubeSide, YouTubeSideCard, YOUTUBE_SIDE_HINT } from './YouTubeSideCard';

const NOW = Date.parse('2026-10-23T10:00:00Z');
const ago = (s: number) => new Date(NOW - s * 1000).toISOString();

const youtube = { id: 11, platform: 'YOUTUBE' } as Destination;
const twitch = { id: 12, platform: 'TWITCH' } as Destination;

function stream(state: LiveStateName = 'LIVE', startedSecondsAgo = 20, targets = [{ destinationId: 11, name: 'YouTube', type: 'RTMP', enabled: true }]) {
  return { id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', playlist: [], overlays: [], destinations: targets, live: { state, startedAt: ago(startedSecondsAgo), uptimeSeconds: startedSecondsAgo } } as unknown as StreamView;
}

const veteran: Pick<Me, 'dismissedHints' | 'goLives'> = { dismissedHints: [], goLives: 12 };
const firstTimer: Pick<Me, 'dismissedHints' | 'goLives'> = { dismissedHints: [], goLives: 1 };

describe('"What you\'ll see on YouTube" (GL-6): when it shows', () => {
  it('shows in the first two minutes of any run', () => {
    expect(showYouTubeSide({ me: veteran, stream: stream('LIVE', 20), destinations: [youtube], now: NOW })).toBe(true);
    expect(showYouTubeSide({ me: veteran, stream: stream('STARTING', 0), destinations: [youtube], now: NOW })).toBe(true);
    expect(showYouTubeSide({ me: veteran, stream: stream('LIVE', FRESH_SECONDS + 1), destinations: [youtube], now: NOW })).toBe(false);
  });

  it('stays for the whole of an account\'s first go-live', () => {
    expect(showYouTubeSide({ me: firstTimer, stream: stream('LIVE', 3600), destinations: [youtube], now: NOW })).toBe(true);
    expect(showYouTubeSide({ me: { dismissedHints: [], goLives: 0 }, stream: stream('LIVE', 3600), destinations: [youtube], now: NOW })).toBe(true);
  });

  it('only while on air, and only when the stream goes to YouTube', () => {
    for (const state of ['DRAFT', 'ENDED', 'ERROR', 'STOPPING'] as LiveStateName[]) {
      expect(showYouTubeSide({ me: firstTimer, stream: stream(state), destinations: [youtube], now: NOW }), state).toBe(false);
    }
    const toTwitch = stream('LIVE', 10, [{ destinationId: 12, name: 'Twitch', type: 'RTMP', enabled: true }]);
    expect(showYouTubeSide({ me: firstTimer, stream: toTwitch, destinations: [youtube, twitch], now: NOW })).toBe(false);
    const youtubeOff = stream('LIVE', 10, [{ destinationId: 11, name: 'YouTube', type: 'RTMP', enabled: false }]);
    expect(showYouTubeSide({ me: firstTimer, stream: youtubeOff, destinations: [youtube], now: NOW })).toBe(false);
    expect(showYouTubeSide({ me: firstTimer, stream: stream(), destinations: undefined, now: NOW })).toBe(false);
  });

  it('never again once dismissed for the account', () => {
    expect(showYouTubeSide({ me: { dismissedHints: [YOUTUBE_SIDE_HINT], goLives: 1 }, stream: stream(), destinations: [youtube], now: NOW })).toBe(false);
    expect(showYouTubeSide({ me: undefined, stream: stream(), destinations: [youtube], now: NOW })).toBe(false);
  });

  it('times the run by its start, falling back to the last reported uptime', () => {
    expect(liveSeconds({ startedAt: ago(90), uptimeSeconds: 10 }, NOW)).toBe(90);
    expect(liveSeconds({ startedAt: null, uptimeSeconds: 42 }, NOW)).toBe(42);
  });
});

describe('"What you\'ll see on YouTube" (GL-6): the card', () => {
  let patches: [string, unknown][] = [];
  let me: Partial<Me>;
  beforeEach(() => {
    patches = [];
    me = { id: 1, email: 'a@example.com', name: 'A', admin: false, onboarded: true, emailAlerts: true, dismissedHints: [], goLives: 1, plan: { maxFps: 30 } as Me['plan'] };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'PATCH') {
        const body = JSON.parse(String(init.body));
        patches.push([url, body]);
        me = { ...me, dismissedHints: [body.dismissHint] };
      }
      const body = url.startsWith('/api/me') ? me : url.startsWith('/api/destinations') ? [youtube] : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = () =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter>
          <YouTubeSideCard stream={stream('LIVE', 5)} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('explains Preparing, Auto-start and Auto-stop, and a stuck live, with links to YouTube Studio and the article', async () => {
    show();
    expect(await screen.findByRole('heading', { name: "What you'll see on YouTube" })).toBeInTheDocument();
    expect(screen.getByText(/"Preparing" is normal/)).toBeInTheDocument();
    expect(screen.getByText(/up to about 30 seconds/)).toBeInTheDocument();
    expect(screen.getByText(/turn Auto-start on and Auto-stop off/)).toBeInTheDocument();
    expect(screen.getByText(/start a fresh one with the same stream key/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open YouTube Studio/ })).toHaveAttribute('href', 'https://studio.youtube.com/channel/UC/livestreaming');
    expect(screen.getByRole('link', { name: /Auto-start and Auto-stop/ })).toHaveAttribute('href', '/help/youtube-preparing');
  });

  it('is dismissed for the account through /api/me, not the browser', async () => {
    window.localStorage.clear();
    show();
    await userEvent.click(await screen.findByRole('button', { name: "Don't show again" }));
    expect(screen.queryByRole('heading', { name: "What you'll see on YouTube" })).not.toBeInTheDocument();
    await waitFor(() => expect(patches).toEqual([['/api/me', { dismissHint: YOUTUBE_SIDE_HINT }]]));
    expect(window.localStorage.length).toBe(0);
  });
});
