import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { HELP } from '@/lib/help';
import type { StreamView } from '@/lib/types';
import { type CheckFlag, type CheckView, checkHelp, GoLiveCheckCard } from './GoLiveCheck';

const still: CheckFlag = {
  id: 'still-picture',
  kind: 'POLICY',
  title: 'Viewers see one still picture',
  detail: "Your video is one picture that doesn't change, and nothing on screen changes.",
  fix: 'ADD_OVERLAYS',
  fixLabel: 'Add a clock and now playing',
  help: '/help/loops-and-youtube-rules',
  dismissed: false,
};
const loop: CheckFlag = {
  ...still,
  id: 'one-file-loop',
  title: 'One video plays over and over',
  fix: 'OPEN_PLAYLIST',
  fixLabel: 'Add videos',
};
const live: CheckFlag = {
  id: 'title-live',
  kind: 'FOUND',
  title: 'Your title says “LIVE” over a replay',
  detail: 'This stream plays videos you uploaded.',
  fix: 'USE_TITLE',
  fixLabel: 'Use “Lofi beats to study and relax all night long with rain sounds · 24/7 replay”',
  suggestion: 'Lofi beats · 24/7 replay',
  help: '/help/titles-and-thumbnails',
  dismissed: false,
};

const base: CheckView = {
  flags: [],
  open: 0,
  checking: false,
  musicNote: "We can't tell whether YouTube will claim a song.",
  musicHelp: HELP.music,
  youtubeTitle: null,
  thumbnail: null,
};

describe('Stream check card (GR-9)', () => {
  let calls: [string, string, unknown][] = [];
  let view: CheckView;

  beforeEach(() => {
    calls = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? 'GET';
        if (url.includes('/check')) {
          calls.push([method, url, init?.body ? JSON.parse(String(init.body)) : undefined]);
          if (method === 'POST' && url.endsWith('/dismiss')) {
            const id = url.split('/').at(-2);
            view = {
              ...view,
              flags: view.flags.map((f) => (f.id === id ? { ...f, dismissed: true } : f)),
            };
          } else if (method === 'POST' && url.endsWith('/fix')) {
            const id = url.split('/').at(-2);
            view = { ...view, flags: view.flags.filter((f) => f.id !== id) };
          } else if (method === 'PUT') {
            view = {
              ...view,
              youtubeTitle: JSON.parse(String(init!.body)).youtubeTitle,
            };
          }
          return new Response(JSON.stringify(view), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response('{}', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = (entry = '/studio/streams/4') =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={[entry]}>
          <GoLiveCheckCard
            stream={
              {
                id: 4,
                name: 'Lofi',
                source: 'PLAYLIST',
                live: { state: 'DRAFT' },
              } as unknown as StreamView
            }
          />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('lists each flag with its fix, a way to keep it, and why it matters', async () => {
    view = { ...base, flags: [loop, still], open: 2 };
    show();
    expect(await screen.findByText('2 to look at')).toBeInTheDocument();
    const row = screen.getByText('Viewers see one still picture').closest('li')!;
    expect(within(row).getByRole('button', { name: 'Add a clock and now playing' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Keep as it is' })).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Why this matters' })).toHaveAttribute('href', '/help/loops-and-youtube-rules');
    // No one-tap way to add videos for someone: it opens the playlist.
    expect(screen.getByRole('link', { name: 'Add videos' })).toHaveAttribute('href', '/studio/streams/4/playlist');
    // A general music note, never a verdict.
    expect(screen.getByText(/can't tell whether YouTube will claim/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Music on your live' })).toHaveAttribute('href', '/help/music-on-your-live');
  });

  it('fixes in one tap, and keeping a flag moves it out of the way but not out of reach', async () => {
    view = { ...base, flags: [loop, still], open: 2 };
    show();
    await userEvent.click(
      await screen.findByRole('button', {
        name: 'Add a clock and now playing',
      }),
    );
    await waitFor(() => expect(calls).toContainEqual(['POST', '/api/streams/4/check/still-picture/fix', {}]));
    expect(await screen.findByText('1 to look at')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Keep as it is' }));
    expect(await screen.findByText('Nothing to fix')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /1 kept as it is/ }));
    expect(screen.getByRole('button', { name: 'Check it again' })).toBeInTheDocument();
  });

  it('replies posted before automatic replies were removed only explain, with nothing to switch off (AI-3)', async () => {
    const repeated: CheckFlag = {
      ...still,
      id: 'repeated-message',
      title: 'The same reply was posted 3 times',
      detail: 'Automatic replies are gone for good, so this won’t happen again.',
      fix: 'NONE',
      fixLabel: 'Got it',
    };
    view = { ...base, flags: [repeated], open: 1 };
    show();
    const row = (await screen.findByText('The same reply was posted 3 times')).closest('li')!;
    expect(within(row).queryByRole('button', { name: /automatic replies/i })).toBeNull();
    expect(within(row).queryByRole('button', { name: 'Keep as it is' })).toBeNull();
    await userEvent.click(within(row).getByRole('button', { name: 'Got it' }));
    await waitFor(() => expect(calls).toContainEqual(['POST', '/api/streams/4/check/repeated-message/dismiss', {}]));
    expect(calls.some(([, url]) => url.endsWith('/fix'))).toBe(false);
    expect(await screen.findByText('Nothing to fix')).toBeInTheDocument();
  });

  it('a long suggested title wraps instead of running off a phone screen', async () => {
    view = {
      ...base,
      flags: [live],
      open: 1,
      youtubeTitle: '🔴 LIVE Lofi beats',
    };
    show();
    const fix = await screen.findByRole('button', { name: /^Use / });
    expect(fix.className).toContain('whitespace-normal');
  });

  it('takes the YouTube title typed in and says nothing changes on YouTube', async () => {
    view = { ...base };
    show();
    expect(await screen.findByText(/Nothing we can see would hurt this stream/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add title and thumbnail' }));
    expect(screen.getByText(/we don't change anything on YouTube/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Your YouTube title'), 'Lofi beats to study');
    await userEvent.click(screen.getByRole('button', { name: 'Save and check' }));
    await waitFor(() => expect(calls).toContainEqual(['PUT', '/api/streams/4/check/title', { youtubeTitle: 'Lofi beats to study' }]));
    expect(await screen.findByText('Lofi beats to study')).toBeInTheDocument();
  });

  it("opens the title editor when Home's checklist links here (ON-9)", async () => {
    view = { ...base };
    show('/studio/streams/4?title=write');
    const input = await screen.findByLabelText('Your YouTube title');
    await waitFor(() => expect(input).toHaveFocus());
  });

  it('only links help articles that exist', () => {
    expect(checkHelp('/help/titles-and-thumbnails')).toBe(HELP.titles);
    expect(checkHelp('/help/nope')).toBeNull();
  });
});
