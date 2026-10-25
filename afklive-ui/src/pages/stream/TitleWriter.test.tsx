import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
import { type CheckView, GoLiveCheckCard } from './GoLiveCheck';
import { defaultLanguage, TitleWriterPanel, type WriterResult, type WriterStatus } from './TitleWriter';

const languages = [
  { code: 'en', name: 'English', nativeName: 'English' },
  { code: 'hi', name: 'Hindi', nativeName: 'हिन्दी' },
  { code: 'ta', name: 'Tamil', nativeName: 'தமிழ்' },
  { code: 'te', name: 'Telugu', nativeName: 'తెలుగు' },
  { code: 'bn', name: 'Bengali', nativeName: 'বাংলা' },
  { code: 'mr', name: 'Marathi', nativeName: 'मराठी' },
];

const ok = (language: string, used: number): WriterResult => ({
  status: 'OK',
  language,
  cached: false,
  usage: { used, limit: 20 },
  draft: {
    titles: ['Lofi beats to study · 24/7 replay', 'Rainy lofi for focus', 'Chill lofi mix for work'],
    description: 'Soft lofi beats with rain.\n\nTracklist\n00:00 Midnight rain\n03:05 Sunrise',
    tags: ['lofi', 'study music', 'rain sounds', 'chill beats', 'focus music'],
  },
});

describe('Title writer (AI-1 / GR-10)', () => {
  let status: WriterStatus;
  let answer: (lang: string) => WriterResult;
  let posts: { language: string; brief: string | null }[];
  let check: CheckView;

  beforeEach(() => {
    posts = [];
    status = { available: true, language: null, languages, usage: { used: 0, limit: 20 } };
    answer = (lang) => ok(lang, posts.length);
    check = { flags: [], open: 0, checking: false, musicNote: null, musicHelp: '/help/music-on-your-live', youtubeTitle: null, thumbnail: null };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
        if (url.endsWith('/ai/metadata')) {
          if (init?.method === 'POST') {
            const body = JSON.parse(String(init.body));
            posts.push(body);
            return json(answer(body.language));
          }
          return json(status);
        }
        if (url.includes('/check')) return json(check);
        return json({});
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const wrap = (ui: React.ReactNode) =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter>{ui}</MemoryRouter>
      </QueryClientProvider>,
    );

  it('is hidden when AI is not set up on the server', async () => {
    status = { ...status, available: false };
    const { container } = wrap(<TitleWriterPanel streamId={4} />);
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole('button', { name: /Write title/ })).toBeNull();
  });

  it('writes three titles, a description and tags, each with its own Copy', async () => {
    const user = userEvent.setup();
    wrap(<TitleWriterPanel streamId={4} />);
    await user.click(await screen.findByRole('button', { name: 'Write title and description' }));
    expect(await screen.findByDisplayValue('Lofi beats to study · 24/7 replay')).toBeInTheDocument();
    expect(screen.getByLabelText('Title option 2')).toHaveValue('Rainy lofi for focus');
    expect(screen.getByLabelText('Description')).toHaveValue('Soft lofi beats with rain.\n\nTracklist\n00:00 Midnight rain\n03:05 Sunrise');
    expect(screen.getByLabelText('Tags')).toHaveValue('lofi, study music, rain sounds, chill beats, focus music');
    expect(screen.getByText('19 of 20 left today')).toBeInTheDocument();
    expect(screen.getByText(/We never post anything/)).toBeInTheDocument();

    // Edits are what gets copied.
    await user.clear(screen.getByLabelText('Title option 2'));
    await user.type(screen.getByLabelText('Title option 2'), 'Rain lofi to study');
    await user.click(screen.getByRole('button', { name: 'Copy title option 2' }));
    expect(await navigator.clipboard.readText()).toBe('Rain lofi to study');
    expect(within(screen.getByRole('button', { name: 'Copy title option 2' })).getByText('Copied')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Copy description' }));
    expect(await navigator.clipboard.readText()).toContain('03:05 Sunrise');
    await user.click(screen.getByRole('button', { name: 'Copy tags' }));
    expect(await navigator.clipboard.readText()).toBe('lofi, study music, rain sounds, chill beats, focus music');
  });

  it('starts in the language chosen last time and switches when asked', async () => {
    const user = userEvent.setup();
    status = { ...status, language: 'hi' };
    wrap(<TitleWriterPanel streamId={4} />);
    const select = await screen.findByLabelText('Language');
    expect(select).toHaveValue('hi');
    expect(within(select).getByRole('option', { name: 'Tamil · தமிழ்' })).toBeInTheDocument();
    await user.selectOptions(select, 'ta');
    await user.type(screen.getByLabelText(/Anything to add/), 'Evening aarti');
    await user.click(screen.getByRole('button', { name: 'Write title and description' }));
    await waitFor(() => expect(posts).toEqual([{ language: 'ta', brief: 'Evening aarti' }]));
    expect(await screen.findByRole('button', { name: 'Write again' })).toBeInTheDocument();
  });

  it('says plainly when today’s limit is reached', async () => {
    const user = userEvent.setup();
    answer = (lang) => ({
      status: 'LIMIT',
      language: lang,
      cached: false,
      usage: { used: 20, limit: 20 },
      message: "You've written 20 drafts today, your plan's daily limit. You can write more tomorrow.",
    });
    wrap(<TitleWriterPanel streamId={4} />);
    await user.click(await screen.findByRole('button', { name: 'Write title and description' }));
    expect(await screen.findByText(/your plan's daily limit\. You can write more tomorrow/)).toBeInTheDocument();
    expect(screen.getByText('0 of 20 left today')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Write title and description' })).toBeDisabled();
  });

  it('a limit already used up shows before anyone taps', async () => {
    status = { ...status, usage: { used: 20, limit: 20 } };
    wrap(<TitleWriterPanel streamId={4} />);
    expect(await screen.findByText(/You can write more tomorrow/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Write title and description' })).toBeDisabled();
  });

  it('shows a fallback start with its explanation when the answer could not be used', async () => {
    const user = userEvent.setup();
    answer = (lang) => ({
      ...ok(lang, 0),
      status: 'FALLBACK',
      draft: { titles: ['Lofi radio'], description: 'Tracklist\n00:00 Midnight rain', tags: ['lofi radio', 'lofi', 'radio'] },
      message: "We couldn't write this one, so here's a start from your stream's name and playlist. Try again in a minute; it wasn't counted.",
    });
    wrap(<TitleWriterPanel streamId={4} />);
    await user.click(await screen.findByRole('button', { name: 'Write title and description' }));
    expect(await screen.findByText(/it wasn't counted/)).toBeInTheDocument();
    expect(screen.getByLabelText('Title option 1')).toHaveValue('Lofi radio');
  });

  it('the stream check offers "Suggest a better title" on a title flag, and a title can be used in one tap', async () => {
    const user = userEvent.setup();
    check = {
      ...check,
      youtubeTitle: 'LIVE stream',
      open: 1,
      flags: [
        {
          id: 'title-no-phrase',
          kind: 'FOUND',
          title: 'Your title has nothing people search for',
          detail: '“LIVE stream” has no words that say what’s on the stream.',
          fix: 'EDIT_TITLE',
          fixLabel: 'Edit the title',
          help: '/help/titles-and-thumbnails',
          dismissed: false,
        },
      ],
    };
    wrap(<GoLiveCheckCard stream={{ id: 4, name: 'Lofi', source: 'PLAYLIST', loopCount: 0, live: { state: 'DRAFT' } } as unknown as StreamView} />);
    await user.click(await screen.findByRole('button', { name: 'Suggest a better title' }));
    // It starts writing at once.
    expect(await screen.findByDisplayValue('Rainy lofi for focus')).toBeInTheDocument();
    expect(posts).toHaveLength(1);
    await user.click(screen.getAllByRole('button', { name: 'Use this title' })[1]);
    expect(screen.getByLabelText('Your YouTube title')).toHaveValue('Rainy lofi for focus');
  });

  it('the stream check hides the writer when AI is not set up', async () => {
    status = { ...status, available: false };
    check = { ...check, youtubeTitle: 'Lofi beats' };
    const user = userEvent.setup();
    wrap(<GoLiveCheckCard stream={{ id: 4, name: 'Lofi', source: 'PLAYLIST', live: { state: 'DRAFT' } } as unknown as StreamView} />);
    await user.click(await screen.findByRole('button', { name: 'Change' }));
    expect(screen.getByLabelText('Your YouTube title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Write title and description' })).toBeNull();
  });

  it('picks the default language: last choice, then the browser, then English', () => {
    expect(defaultLanguage('mr', ['hi-IN'], languages)).toBe('mr');
    expect(defaultLanguage(null, ['ta-IN', 'en-US'], languages)).toBe('ta');
    expect(defaultLanguage(null, ['fr-FR', 'bn'], languages)).toBe('bn');
    expect(defaultLanguage('xx', ['fr-FR'], languages)).toBe('en');
  });
});
