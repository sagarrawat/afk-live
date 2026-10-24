import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
import { slotsText, type Template, TemplatePicker, templatesFor } from './Templates';

const templates: Template[] = [
  { id: 'lofi', name: 'Lofi and chill', description: 'Now playing in the corner.', source: 'PLAYLIST', languages: ['en', 'hi'], schedules: [] },
  {
    id: 'devotional',
    name: 'Bhajans and aarti',
    description: 'Now playing and what is next.',
    source: 'PLAYLIST',
    languages: ['en', 'hi'],
    schedules: [{ id: 'aarti', name: 'Morning and evening aarti', description: '', slots: [{ start: '05:30', end: '07:00' }, { start: '18:30', end: '20:00' }] }],
  },
  { id: 'phone-gaming', name: 'Phone gaming', description: 'Screens for breaks.', source: 'PHONE', languages: ['en', 'hi'], schedules: [] },
];

describe('Niche templates (GR-8)', () => {
  let posts: [string, unknown][] = [];
  beforeEach(() => {
    posts = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === 'POST') posts.push([url, JSON.parse(String(init.body))]);
        const body = url === '/api/templates' ? templates : { template: 'Bhajans and aarti', layers: 6, schedulesAdded: 2 };
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const stream = { id: 9, name: 'Mandir', source: 'PLAYLIST', timeZone: 'Asia/Kolkata', live: { state: 'DRAFT' } } as unknown as StreamView;

  it('offers only the templates that fit the stream', () => {
    expect(templatesFor(templates, 'PLAYLIST').map((t) => t.id)).toEqual(['lofi', 'devotional']);
    expect(templatesFor(templates, 'PHONE').map((t) => t.id)).toEqual(['phone-gaming']);
  });

  it('says schedule times in plain words', () => {
    expect(slotsText([{ start: '05:30', end: '07:00' }, { start: '18:30', end: '20:00' }])).toBe('5:30 am to 7 am and 6:30 pm to 8 pm');
    expect(slotsText([{ start: '21:00', end: '07:00' }])).toBe('9 pm to 7 am');
    expect(slotsText([{ start: '00:00', end: '12:00' }])).toBe('12 am to 12 pm');
  });

  it('applies a template with Hindi words and the aarti schedule', async () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter>
          <TemplatePicker stream={stream} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Start from a template' }));
    await userEvent.click(await screen.findByRole('radio', { name: /Bhajans and aarti/ }));
    expect(screen.queryByRole('radio', { name: /Phone gaming/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'हिन्दी' }));
    await userEvent.click(screen.getByRole('radio', { name: /Morning and evening aarti/ }));
    expect(screen.getByText(/Every day, 5:30 am to 7 am and 6:30 pm to 8 pm \(Asia\/Kolkata\)/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Use this template' }));
    await waitFor(() => expect(posts).toEqual([['/api/streams/9/template', { template: 'devotional', language: 'hi', schedulePreset: 'aarti' }]]));
  });

  it("can't be opened over unsaved overlay changes", () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <TemplatePicker stream={stream} disabled />
      </QueryClientProvider>,
    );
    expect(screen.getByRole('button', { name: 'Start from a template' })).toBeDisabled();
  });
});
