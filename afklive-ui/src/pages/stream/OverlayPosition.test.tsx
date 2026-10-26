import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import type { Overlay, StreamView } from '@/lib/types';
import { OverlaysTab, spotPosition } from './OverlaysTab';

const text: Overlay = { id: 'text-1', type: 'TEXT', visible: true, x: 0.05, y: 0.05, width: 0, size: 0.05, opacity: 1, speed: 0, color: '#ffffff', background: null, text: 'Hello' } as Overlay;

const stream = {
  id: 7, name: 'Phone', source: 'PHONE', orientation: 'LANDSCAPE', qualityHeight: 720, fps: 30, timeZone: 'Asia/Kolkata',
  overlays: [text], scenes: [], playlist: [], music: [], destinations: [], schedules: [], live: { state: 'DRAFT', uptimeSeconds: 0 },
} as unknown as StreamView;

let puts: unknown[] = [];
beforeEach(() => {
  puts = [];
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') puts.push(JSON.parse(String(init.body)));
    const path = String(input).split('?')[0];
    const body = path === '/api/media' ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 24 } : path.startsWith('/api/media/') ? [] : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

const show = () =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <OverlaysTab stream={stream} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('placing overlays on a phone (MS-3)', () => {
  it("lets touch drag the layer instead of scrolling the page", () => {
    show();
    const layer = screen.getByRole('button', { name: /drag to move/ });
    expect(layer).toHaveClass('touch-none');
    // A drag that leaves the layer still moves it: the layer itself holds the pointer.
    const capture = vi.fn();
    layer.setPointerCapture = capture;
    fireEvent.pointerDown(layer, { clientX: 10, clientY: 10, pointerId: 3 });
    expect(capture).toHaveBeenCalledWith(3);
  });

  it('places a layer with the nine spots and nudges it, without dragging', async () => {
    show();
    const position = screen.getByRole('group', { name: 'Place it' });
    await userEvent.click(within(position).getByRole('button', { name: 'Bottom right' }));
    await userEvent.click(within(screen.getByRole('group', { name: 'Nudge it' })).getByRole('button', { name: 'Move left' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save overlays' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    const [saved] = puts[0] as Overlay[];
    expect(saved.x).toBeCloseTo(0.94, 3);
    expect(saved.y).toBeCloseTo(0.88, 3);
  });

  it('keeps every spot inside the safe area', () => {
    expect(spotPosition('top-left', 0.2, 0.1)).toEqual({ x: 0.05, y: 0.05 });
    expect(spotPosition('center', 0.2, 0.1)).toEqual({ x: 0.4, y: 0.45 });
    expect(spotPosition('bottom-right', 0.2, 0.1)).toEqual({ x: 0.75, y: 0.85 });
    expect(spotPosition('top', 1, 0.1)).toEqual({ x: 0, y: 0.05 }); // a full-width ticker can't go further left than the edge
  });
});
