import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { MediaRef, StreamView } from '@/lib/types';
import { ShapeNotes, VerticalHint, verticalVideos } from './VerticalHint';

const phoneClip: MediaRef = { id: 1, title: 'Phone clip', width: 1080, height: 1920, contentType: 'VIDEO', status: 'READY' };
const wideClip: MediaRef = { id: 2, title: 'Wide clip', width: 1920, height: 1080, contentType: 'VIDEO', status: 'READY' };
const unprobed: MediaRef = { id: 3, title: 'Still processing', contentType: 'VIDEO', status: 'PROCESSING' };

function stream(over: Partial<StreamView>): StreamView {
  return { id: 7, name: 'Lofi', source: 'PLAYLIST', orientation: 'LANDSCAPE', playlist: [phoneClip], live: { state: 'DRAFT', uptimeSeconds: 0 }, ...over } as unknown as StreamView;
}

const show = (s: StreamView) => render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><VerticalHint stream={s} /></MemoryRouter></QueryClientProvider>);
const notes = (s: StreamView) => render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><ShapeNotes stream={s} /></MemoryRouter></QueryClientProvider>);

describe('vertical videos in a landscape stream', () => {
  let patches: [string, unknown][] = [];
  beforeEach(() => {
    patches = [];
    window.localStorage.clear();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH') patches.push([String(input), JSON.parse(String(init.body))]);
      const body = String(input).startsWith('/api/me') ? { plan: { maxFps: 30 } } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('finds videos taller than wide, ignoring ones not measured yet', () => {
    expect(verticalVideos([phoneClip, wideClip, unprobed])).toEqual([phoneClip]);
  });

  it('suggests the vertical shape and switches to it', async () => {
    show(stream({}));
    expect(screen.getByText(/This video is vertical\. Switch the stream to Vertical 9:16 so it fills the screen on phones\./)).toBeInTheDocument();
    // What YouTube does with a vertical live, and the article.
    expect(screen.getByText(/full screen to people watching on phones/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Vertical or landscape?' })).toHaveAttribute('href', '/help/vertical-or-landscape');
    await userEvent.click(screen.getByRole('button', { name: 'Switch to Vertical 9:16' }));
    expect(patches).toEqual([['/api/streams/7', { orientation: 'VERTICAL' }]]);
  });

  it('counts the vertical ones when most of a mixed playlist is vertical', () => {
    show(stream({ playlist: [phoneClip, { ...phoneClip, id: 4 }, wideClip, unprobed] }));
    expect(screen.getByText(/2 of your 3 videos are vertical/)).toBeInTheDocument();
  });

  it('does not suggest Vertical when only some videos are vertical', () => {
    show(stream({ playlist: [phoneClip, wideClip, { ...wideClip, id: 5 }] }));
    expect(screen.queryByRole('complementary', { name: 'Vertical videos' })).not.toBeInTheDocument();
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

describe('how the playlist plays (MS-10, ST-1)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const body = String(input).startsWith('/api/me') ? { plan: { maxFps: 30 } } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('says in plain words which fit a mixed playlist uses, with a way to change it', () => {
    notes(stream({ playlist: [wideClip, phoneClip], fit: 'BLUR' } as Partial<StreamView>));
    expect(screen.getByText('1 vertical video plays over a blurred copy of the picture that fills the sides.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Change how they fit' })).toHaveAttribute('href', '/studio/streams/7/settings');
  });

  it('explains 60 frames per second videos on a 30 fps plan without jargon', async () => {
    notes(stream({ playlist: [{ ...wideClip, fps: 60 }], fps: 30 } as Partial<StreamView>));
    expect(await screen.findByText('One of your videos was filmed at 60 frames per second. It plays smoothly at 30 frames per second on your plan.')).toBeInTheDocument();
  });

  it('stays quiet when everything matches the stream', () => {
    notes(stream({ playlist: [wideClip], fps: 30, fit: 'BLUR' } as Partial<StreamView>));
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });
});
