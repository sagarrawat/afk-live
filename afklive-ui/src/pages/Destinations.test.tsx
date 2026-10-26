import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { testPolling } from '@/components/DestinationTest';
import { Destinations } from './Destinations';

HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };

const saved = { id: 4, name: 'YouTube', type: 'RTMP', platform: 'YOUTUBE', server: 'rtmp://a.rtmp.youtube.com/live2', keyHint: '••••ijkl', selected: true };

/** A server where the destination list starts empty and the test reports `result` once polled. */
function server(result: { ok: boolean; message: string }) {
  const calls: string[] = [];
  let list: unknown[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? 'GET';
    calls.push(`${method} ${path}`);
    let body: unknown = {};
    if (path === '/api/destinations' && method === 'GET') body = list;
    if (path === '/api/destinations' && method === 'POST') {
      list = [saved];
      body = saved;
    }
    if (path === '/api/destinations/4/test') body = { testId: 9, status: 'PENDING' };
    if (path === '/api/destinations/tests/9') body = { testId: 9, status: result.ok ? 'OK' : 'FAILED', ...result };
    return new Response(JSON.stringify(body), { status: path.endsWith('/test') ? 202 : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return calls;
}

async function addYouTubeKey() {
  render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><Destinations /></MemoryRouter></QueryClientProvider>);
  await userEvent.click(await screen.findByRole('button', { name: 'Add YouTube' }));
  await userEvent.type(screen.getByLabelText('Stream key'), 'abcd-efgh-ijkl');
  const dialog = screen.getByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Add destination' }));
}

describe('a new destination is tested straight away (MS-6)', () => {
  const every = testPolling.everyMs;
  beforeEach(() => {
    testPolling.everyMs = 1;
  });
  afterEach(() => {
    testPolling.everyMs = every;
    vi.unstubAllGlobals();
  });

  it('says when the platform accepted the key', async () => {
    const calls = server({ ok: true, message: 'Connected and sent a 10-second test.' });
    await addYouTubeKey();
    expect(await screen.findByText('Connected to YouTube. The stream key works.')).toBeInTheDocument();
    expect(calls).toContain('POST /api/destinations');
    expect(calls).toContain('POST /api/destinations/4/test');
  });

  it("says in plain words when it didn't", async () => {
    server({ ok: false, message: 'The platform refused the stream key. Copy the key again from the platform and save it.' });
    await addYouTubeKey();
    expect(await screen.findByText(/The platform refused the stream key/)).toBeInTheDocument();
    expect(screen.queryByText(/Connected to YouTube/)).not.toBeInTheDocument();
  });
});

describe('editing a destination (DS-8)', () => {
  const kick = { id: 6, name: 'Kick', type: 'RTMP', platform: 'KICK', server: 'rtmps://fa723fc1b171.global-contribute.live-video.net/app', keyHint: '••••wxyz', selected: true };
  let calls: { method: string; path: string; body?: Record<string, unknown> }[] = [];
  const every = testPolling.everyMs;
  function api(list: unknown[]) {
    calls = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      let body: unknown = {};
      if (path === '/api/destinations' && method === 'GET') body = list;
      if (path.startsWith('/api/destinations/') && method === 'PUT') body = { ...(list[0] as object), ...(calls.at(-1)!.body ?? {}), keyHint: '••••9876' };
      if (path.endsWith('/test')) body = { testId: 9, status: 'PENDING' };
      if (path === '/api/destinations/tests/9') body = { testId: 9, status: 'OK', ok: true, message: 'ok' };
      return new Response(JSON.stringify(body), { status: path.endsWith('/test') ? 202 : 200, headers: { 'Content-Type': 'application/json' } });
    }));
  }
  const show = (at = '/studio/destinations') =>
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter initialEntries={[at]}><Destinations /></MemoryRouter></QueryClientProvider>);
  beforeEach(() => {
    testPolling.everyMs = 1;
  });
  afterEach(() => {
    testPolling.everyMs = every;
    vi.unstubAllGlobals();
  });

  it('opens straight to the destination from a "Fix key" link, and an empty key keeps the saved one', async () => {
    api([saved]);
    show('/studio/destinations?edit=4');
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Edit YouTube')).toBeInTheDocument();
    // The saved key never comes to the browser: the box starts empty and says what empty means.
    expect(within(dialog).getByLabelText('New stream key')).toHaveValue('');
    expect(within(dialog).getByText(/Leave empty to keep the saved one \(ending ijkl\)/)).toBeInTheDocument();
    await userEvent.clear(within(dialog).getByLabelText('Name'));
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Bhajans channel');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')).toMatchObject({ path: '/api/destinations/4', body: { name: 'Bhajans channel' } });
    expect(calls.find((c) => c.method === 'PUT')!.body).not.toHaveProperty('key');
    // Only the name changed: nothing to test.
    expect(calls.some((c) => c.path.endsWith('/test'))).toBe(false);
  });

  it('saves a new key as the full address and tests it straight away', async () => {
    api([saved]);
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit YouTube' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('New stream key'), 'new-key-9876');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(calls.some((c) => c.path === '/api/destinations/4/test')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ name: 'YouTube', key: 'rtmp://a.rtmp.youtube.com/live2/new-key-9876' });
    expect(await screen.findByText('Connected to YouTube. The stream key works.')).toBeInTheDocument();
  });

  it('asks for the key again when the server address changes', async () => {
    api([kick]);
    show('/studio/destinations?edit=6');
    const dialog = await screen.findByRole('dialog');
    const server = within(dialog).getByLabelText('Stream URL');
    expect(server).toHaveValue(kick.server);
    await userEvent.clear(server);
    await userEvent.type(server, 'rtmps://other.live-video.net/app');
    expect(within(dialog).getByText('Paste the stream key again when you change the stream URL.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Save changes' })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('New stream key'), 'sk_new');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ name: 'Kick', key: 'rtmps://other.live-video.net/app/sk_new' });
  });

  it("says a live stream keeps its key until it next goes live, and won't remove a destination in live use", async () => {
    api([{ ...saved, liveIn: ['Lofi 24/7'] }]);
    show('/studio/destinations?edit=4');
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Lofi 24\/7 is live with this destination now\. A new stream key applies the next time the stream goes live/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Remove YouTube' })).toBeDisabled();
    // The reason is on the page, not only in a tooltip.
    expect(screen.getByText('Sending to Lofi 24/7 now. To remove it, stop the stream or turn this destination off for it first.')).toBeInTheDocument();
  });
});
