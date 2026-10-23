import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
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
