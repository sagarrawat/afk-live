import { waitFor } from '@testing-library/react';
// static/remote.html, the phone remote: plain HTML and a script, run here as a browser would.
import html from '../../../afklive-web/src/main/resources/static/remote.html?raw';

function load(live: Record<string, unknown>) {
  const calls: { method: string; path: string }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ method, path: String(input) });
      if (method === 'DELETE') return new Response(null, { status: 204 });
      const stream = { id: 7, name: 'Bhajans 24/7', source: 'PLAYLIST', timeZone: 'Asia/Kolkata', overlays: [], live: { state: 'LIVE', uptimeSeconds: 90, reach: 'ALL', ...live } };
      return new Response(JSON.stringify(stream), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  window.history.pushState({}, '', '/remote.html?stream=7');
  const doc = new DOMParser().parseFromString(html, 'text/html');
  document.body.innerHTML = doc.body.innerHTML;
  const script = doc.querySelector('script')!.textContent!;
  new Function(script)();
  return calls;
}

describe('remote control: a planned stop (GL-3, UX audit)', () => {
  // The page polls every 3 s; stop its timers after each test.
  const timers: ReturnType<typeof setInterval>[] = [];
  const realSetInterval = window.setInterval.bind(window);
  beforeEach(() => {
    vi.spyOn(window, 'setInterval').mockImplementation(((fn: () => void, ms?: number) => {
      const t = realSetInterval(fn, ms);
      timers.push(t);
      return t;
    }) as typeof window.setInterval);
  });
  afterEach(() => {
    timers.splice(0).forEach((t) => clearInterval(t));
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
  });

  it('shows "Ends after this playlist pass" with Cancel, which takes it back', async () => {
    const calls = load({ finishAfterPass: true });
    await waitFor(() => expect(document.querySelector('#planned-box')!.hasAttribute('hidden')).toBe(false));
    expect(document.querySelector('#planned')!.textContent).toContain('Ends after this playlist pass');
    const cancel = document.querySelector<HTMLButtonElement>('#planned button[aria-label="Cancel ending after this playlist pass"]')!;
    cancel.click();
    await waitFor(() => expect(calls).toContainEqual({ method: 'DELETE', path: '/api/streams/7/finish-after-pass' }));
  });

  it('shows a scheduled stop time with its own Cancel', async () => {
    const calls = load({ stopAt: '2026-11-04T17:30:00Z' });
    await waitFor(() => expect(document.querySelector('#planned')!.textContent).toMatch(/^Stops /));
    document.querySelector<HTMLButtonElement>('#planned button[aria-label="Cancel the scheduled stop"]')!.click();
    await waitFor(() => expect(calls).toContainEqual({ method: 'DELETE', path: '/api/streams/7/stop-at' }));
  });

  it('shows nothing when no stop is planned', async () => {
    const calls = load({});
    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    await waitFor(() => expect(document.querySelector('#name')!.textContent).toBe('Bhajans 24/7'));
    expect(document.querySelector('#planned-box')!.hasAttribute('hidden')).toBe(true);
  });
});
