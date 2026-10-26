import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import type { Overlay, StreamView } from '@/lib/types';
import { OverlaysTab, spotPosition, withVisibility } from './OverlaysTab';

const text: Overlay = { id: 'text-1', type: 'TEXT', visible: true, x: 0.05, y: 0.05, width: 0, size: 0.05, opacity: 1, speed: 0, color: '#ffffff', background: null, text: 'Hello' } as Overlay;

const stream = {
  id: 7, name: 'Phone', source: 'PHONE', orientation: 'LANDSCAPE', qualityHeight: 720, fps: 30, timeZone: 'Asia/Kolkata',
  overlays: [text], scenes: [], playlist: [], music: [], destinations: [], schedules: [], live: { state: 'DRAFT', uptimeSeconds: 0 },
} as unknown as StreamView;

let puts: unknown[] = [];
let posts: { path: string; body: unknown }[] = [];
beforeEach(() => {
  puts = [];
  posts = [];
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') puts.push(JSON.parse(String(init.body)));
    if (init?.method === 'POST') posts.push({ path: String(input), body: JSON.parse(String(init.body)) });
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
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    const [saved] = puts[0] as Overlay[];
    expect(saved.x).toBeCloseTo(0.94, 3);
    expect(saved.y).toBeCloseTo(0.88, 3);
  });

  it('shows or hides a saved layer at once from the layer list, as on Overview (UX audit #9)', async () => {
    show();
    await userEvent.click(screen.getByRole('switch', { name: 'Show Text: Hello' }));
    await waitFor(() => expect(posts).toEqual([{ path: '/api/streams/7/overlays/text-1/visible', body: { visible: false } }]));
    expect(puts).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
  });

  it('keeps other edits until Save changes, and says on the page why templates wait (UX audit #9, #13)', async () => {
    show();
    expect(screen.getByRole('button', { name: 'Start from a template' })).toBeEnabled();
    await userEvent.click(within(screen.getByRole('group', { name: 'Place it' })).getByRole('button', { name: 'Centre' }));
    expect(screen.getByRole('region', { name: 'Unsaved changes' })).toHaveTextContent('Unsaved changes to overlays');
    expect(screen.getByRole('button', { name: 'Start from a template' })).toBeDisabled();
    expect(screen.getByText('Save or undo your changes to start from a template.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('takes show/hide from a newer server copy without losing unsaved edits', () => {
    const local = [{ ...text, x: 0.5 }];
    expect(withVisibility(local, [{ ...text, visible: false }])).toEqual([{ ...text, x: 0.5, visible: false }]);
    expect(withVisibility(local, [text])[0]).toBe(local[0]); // unchanged layers stay as they are
  });

  it('keeps every spot inside the safe area', () => {
    expect(spotPosition('top-left', 0.2, 0.1)).toEqual({ x: 0.05, y: 0.05 });
    expect(spotPosition('center', 0.2, 0.1)).toEqual({ x: 0.4, y: 0.45 });
    expect(spotPosition('bottom-right', 0.2, 0.1)).toEqual({ x: 0.75, y: 0.85 });
    expect(spotPosition('top', 1, 0.1)).toEqual({ x: 0, y: 0.05 }); // a full-width ticker can't go further left than the edge
  });
});
