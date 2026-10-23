import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { App, makeQueryClient } from '@/App';
import { MediaPicker } from '@/components/MediaPicker';
import { ToastProvider } from '@/components/ui';
import type { MediaItem, MediaTag, Me } from '@/lib/types';

const me: Me = {
  id: 1,
  email: 'aarti@example.com',
  name: 'Aarti',
  admin: false,
  plan: { id: 'ESSENTIALS', name: 'Creator', streams: 1, maxHeight: 1080, maxFps: 30, storageBytes: 1, monthlyHours: 0, destinations: 2, watermark: false },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};

function file(id: number, title: string, tags: { id: number; name: string }[] = []): MediaItem {
  return { id, title, type: 'VIDEO', status: 'READY', sizeBytes: 1000, durationSeconds: 60, createdAt: '2026-10-01T10:00:00', previewUrl: `/api/media/${id}/file`, usedBy: [], inLiveUse: false, tags };
}

const files = [file(1, 'rain.mp4', [{ id: 3, name: 'Chill' }]), file(2, 'storm.mp4'), file(4, 'lofi.mp4', [{ id: 3, name: 'Chill' }, { id: 5, name: 'Night' }])];
let tags: MediaTag[] = [];
let calls: { method: string; url: string; body?: unknown }[] = [];

function mockApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = (typeof input === 'string' ? input : input.toString()).replace(/^https?:\/\/[^/]+/, '');
      const method = init?.method ?? 'GET';
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      const [path, query] = url.split('?');
      const params = new URLSearchParams(query);
      let body: unknown = {};
      if (path === '/api/me') body = me;
      else if (path === '/api/notifications') body = { unread: 0, emailAlerts: true, items: [] };
      else if (path === '/api/media/storage') body = { usedBytes: 0, limitBytes: 10_000_000, percent: 0, warning: false, full: false, plan: 'ESSENTIALS' };
      else if (path === '/api/media/uploads') body = [];
      else if (path === '/api/media/tags' && method === 'GET') body = tags;
      else if (path === '/api/media/tags' && method === 'POST') body = { id: 9, name: (JSON.parse(String(init!.body)) as { name: string }).name, fileCount: 0 };
      else if (/^\/api\/media\/tags\/\d+\/files/.test(path)) body = { tagId: 3, name: 'Chill', changed: 2 };
      else if (path === '/api/media' && method === 'GET') {
        const tag = params.get('tag');
        const content = files.filter((f) => !tag || (tag === 'none' ? !f.tags?.length : f.tags?.some((t) => String(t.id) === tag)));
        body = { content, totalElements: content.length, totalPages: 1, number: 0, size: 24 };
      }
      if (method === 'DELETE') return new Response(null, { status: 204 });
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const mediaRequests = () => calls.filter((c) => c.method === 'GET' && c.url.startsWith('/api/media?'));

describe('Library tags (ML-8)', () => {
  beforeEach(() => {
    calls = [];
    tags = [
      { id: 3, name: 'Chill', fileCount: 2 },
      { id: 5, name: 'Night', fileCount: 1 },
    ];
    mockApi();
    window.history.pushState({}, '', '/studio/library');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('shows tags on files and filters the library by a tag', async () => {
    render(<App client={makeQueryClient()} />);
    const card = (await screen.findByText('lofi.mp4')).closest('li')!;
    expect(within(card).getByText('Chill')).toBeInTheDocument();
    expect(within(card).getByText('Night')).toBeInTheDocument();

    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Tag' }), 'Night (1)');
    await waitFor(() => expect(screen.queryByText('rain.mp4')).not.toBeInTheDocument());
    expect(screen.getByText('lofi.mp4')).toBeInTheDocument();
    expect(mediaRequests().at(-1)!.url).toContain('tag=5');

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Tag' }), 'No tags');
    expect(await screen.findByText('storm.mp4')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('lofi.mp4')).not.toBeInTheDocument());
  });

  it('hides the tag filter until the creator has tags', async () => {
    tags = [];
    render(<App client={makeQueryClient()} />);
    await screen.findByText('rain.mp4');
    expect(screen.queryByRole('combobox', { name: 'Tag' })).not.toBeInTheDocument();
  });

  it('adds a tag to every selected file at once', async () => {
    render(<App client={makeQueryClient()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Select rain.mp4' }));
    await userEvent.click(screen.getByRole('button', { name: 'Select storm.mp4' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add tag' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a tag to 2 files' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Night' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST' && c.url === '/api/media/tags/5/files')?.body).toEqual({ ids: [1, 2] }));
    expect(calls.some((c) => c.method === 'POST' && c.url === '/api/media/tags')).toBe(false); // existing tag: not created again
  });

  it('creates a new tag from the bulk dialog and puts it on the files', async () => {
    render(<App client={makeQueryClient()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Select storm.mp4' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add tag' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a tag to 1 file' });
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'New tag' }), 'Monsoon show');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(calls.find((c) => c.url === '/api/media/tags/9/files')?.body).toEqual({ ids: [2] }));
    expect(calls.find((c) => c.method === 'POST' && c.url === '/api/media/tags')?.body).toEqual({ name: 'Monsoon show' });
  });

  it('renames and deletes tags', async () => {
    render(<App client={makeQueryClient()} />);
    await screen.findByText('rain.mp4');
    await userEvent.click(screen.getByRole('button', { name: 'Tags' }));
    const dialog = await screen.findByRole('dialog', { name: 'Tags' });
    expect(within(dialog).getByText('2 files')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename Chill' }));
    const input = within(dialog).getByLabelText('New name for Chill');
    await userEvent.clear(input);
    await userEvent.type(input, 'Calm');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save name' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PATCH')).toEqual({ method: 'PATCH', url: '/api/media/tags/3', body: { name: 'Calm' } }));

    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete Night' }));
    expect(within(dialog).getByText(/Its file stays in your library/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete tag' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url === '/api/media/tags/5')).toBe(true));
  });

  it('removes a tag from one file in its preview', async () => {
    render(<App client={makeQueryClient()} />);
    await userEvent.click(await screen.findByText('rain.mp4'));
    const dialog = await screen.findByRole('dialog', { name: 'rain.mp4' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove tag Chill' }));
    await waitFor(() => expect(calls.find((c) => c.url === '/api/media/tags/3/files/remove')?.body).toEqual({ ids: [1] }));
  });
});

describe('MediaPicker tag filter (ML-8)', () => {
  beforeEach(() => {
    calls = [];
    tags = [{ id: 3, name: 'Chill', fileCount: 2 }];
    mockApi();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('narrows the files to pick by tag', async () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>
          <MemoryRouter>
            <MediaPicker open onClose={() => {}} onPick={() => {}} type="VIDEO" title="Add videos" />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('storm.mp4')).toBeInTheDocument();
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Tag' }), 'Chill (2)');
    await waitFor(() => expect(screen.queryByText('storm.mp4')).not.toBeInTheDocument());
    expect(screen.getByText('rain.mp4')).toBeInTheDocument();
    expect(mediaRequests().at(-1)!.url).toMatch(/tag=3/);
  });
});
