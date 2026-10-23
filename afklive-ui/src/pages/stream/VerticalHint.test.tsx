import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { MediaRef, StreamView } from '@/lib/types';
import { VerticalHint, verticalVideos } from './VerticalHint';

const phoneClip: MediaRef = { id: 1, title: 'Phone clip', width: 1080, height: 1920, contentType: 'VIDEO', status: 'READY' };
const wideClip: MediaRef = { id: 2, title: 'Wide clip', width: 1920, height: 1080, contentType: 'VIDEO', status: 'READY' };
const unprobed: MediaRef = { id: 3, title: 'Still processing', contentType: 'VIDEO', status: 'PROCESSING' };

function stream(over: Partial<StreamView>): StreamView {
  return { id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', playlist: [phoneClip], live: { state: 'DRAFT', uptimeSeconds: 0 }, ...over } as unknown as StreamView;
}

const show = (s: StreamView) => render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><VerticalHint stream={s} /></MemoryRouter></QueryClientProvider>);

describe('vertical videos in a landscape stream', () => {
  let patches: [string, unknown][] = [];
  beforeEach(() => {
    patches = [];
    window.localStorage.clear();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH') patches.push([String(input), JSON.parse(String(init.body))]);
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('finds videos taller than wide, ignoring ones not measured yet', () => {
    expect(verticalVideos([phoneClip, wideClip, unprobed])).toEqual([phoneClip]);
  });

  it('suggests the vertical shape and switches to it', async () => {
    show(stream({}));
    expect(screen.getByText(/This video is vertical\. Switch the stream to Vertical 9:16 so it fills the screen on phones\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Switch to Vertical 9:16' }));
    expect(patches).toEqual([['/api/streams/7', { orientation: 'VERTICAL' }]]);
  });

  it('counts the vertical ones in a mixed playlist', () => {
    show(stream({ playlist: [phoneClip, wideClip, unprobed] }));
    expect(screen.getByText(/1 of your videos is vertical/)).toBeInTheDocument();
  });

  it('only explains while on air: the shape applies the next time you go live', () => {
    show(stream({ live: { state: 'LIVE', uptimeSeconds: 60 } as StreamView['live'] }));
    expect(screen.getByText(/applies the next time you go live/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Switch to Vertical 9:16' })).not.toBeInTheDocument();
  });

  it('stays quiet for vertical streams, landscape videos, or once dismissed', async () => {
    const { unmount } = show(stream({ orientation: 'VERTICAL' }));
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
    unmount();
    show(stream({ playlist: [wideClip, unprobed] })).unmount();
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
    const again = show(stream({}));
    await userEvent.click(screen.getByRole('button', { name: 'Keep landscape' }));
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
    again.unmount();
    show(stream({}));
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });
});
