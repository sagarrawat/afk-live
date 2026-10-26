import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import type { MediaRef, StreamView } from '@/lib/types';
import { PlaylistTab } from './PlaylistTab';

const video = (id: number, title: string): MediaRef => ({ id, title, durationSeconds: 60, width: 1920, height: 1080, contentType: 'VIDEO', status: 'READY' });

const stream = {
  id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', qualityHeight: 1080, fps: 30, timeZone: 'Asia/Kolkata', playbackOrder: 'IN_ORDER', loopCount: 0,
  overlays: [], scenes: [], playlist: [video(1, 'Rain'), video(2, 'Forest'), video(3, 'Sea')], music: [], destinations: [], schedules: [],
  live: { state: 'DRAFT', uptimeSeconds: 0 },
} as unknown as StreamView;

const me = { id: 1, email: 'a@example.com', name: 'A', admin: false, plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 5, watermark: false }, timeZone: 'Asia/Kolkata', currency: 'INR', onboarded: true, emailAlerts: true };

let puts: { path: string; body: unknown }[] = [];
beforeEach(() => {
  puts = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') puts.push({ path: String(input), body: JSON.parse(String(init.body)) });
    const path = String(input).split('?')[0];
    const body = path === '/api/me' ? me : path === '/api/media' ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 24 } : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

const show = () =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <PlaylistTab stream={stream} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('playlist edits (UX audit #9, #16)', () => {
  it('saves a removal at once, like an addition, so "Remove" is never only pretend', async () => {
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Remove Forest' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({ path: '/api/streams/7/playlist', body: { mediaIds: [1, 3] } });
    expect(await screen.findByText('Removed Forest.')).toBeInTheDocument();
  });

  it('lets one video fill the frame its own way on a phone too', () => {
    show();
    const fit = screen.getByRole('combobox', { name: 'How Rain fills the frame' });
    expect(fit.className).not.toMatch(/\bhidden\b/);
    expect(fit).toBeEnabled();
  });

  it('says a removal on air applies after the current video', async () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>
          <MemoryRouter>
            <PlaylistTab stream={{ ...stream, live: { state: 'LIVE', uptimeSeconds: 5 } } as StreamView} />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Remove Sea' }));
    expect(await screen.findByText('Removed Sea. The change applies after the current video.')).toBeInTheDocument();
  });
});
