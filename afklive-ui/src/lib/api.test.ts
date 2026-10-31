import { ApiError, errorMessage, get, isExpiredToken, isUpdating, post, UPDATING_MESSAGE, updatingNow } from './api';

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('api', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('gets a fresh security token and retries once when the page expired', async () => {
    document.cookie = 'XSRF-TOKEN=old; path=/';
    const sent: { url: string; token: string | null }[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      sent.push({ url, token: new Headers(init?.headers).get('X-XSRF-TOKEN') });
      if (url === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=fresh; path=/'; // what the server's Set-Cookie does
        return new Response(null, { status: 204 });
      }
      return sent.filter((s) => s.url === '/api/streams/1/go-live').length === 1
        ? json({ success: false, code: 'csrf', message: 'This page expired. Please try again.' }, 403)
        : json({ ok: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(post('/api/streams/1/go-live', {})).resolves.toEqual({ ok: true });
    expect(sent.map((s) => s.url)).toEqual(['/api/streams/1/go-live', '/api/auth/csrf', '/api/streams/1/go-live']);
    expect(sent[0].token).toBe('old');
    expect(sent[2].token).toBe('fresh');
  });

  it('retries only once, then shows the message', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) =>
      String(input) === '/api/auth/csrf' ? new Response(null, { status: 204 }) : json({ code: 'csrf', message: 'This page expired. Please try again.' }, 403),
    );
    vi.stubGlobal('fetch', fetchMock);

    const err = await post('/api/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).message).toBe('This page expired. Please try again.');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not retry a real refusal', async () => {
    const fetchMock = vi.fn(async () => json({ success: false, message: 'Not yours.' }, 403));
    vi.stubGlobal('fetch', fetchMock);

    await expect(post('/api/x')).rejects.toThrow('Not yours.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('recognises the expired-page answer', () => {
    expect(isExpiredToken({ code: 'csrf' })).toBe(true);
    expect(isExpiredToken({ message: 'csrf' })).toBe(false);
    expect(isExpiredToken('csrf')).toBe(false);
    expect(isExpiredToken(undefined)).toBe(false);
  });

  describe('while the web app restarts (Caddy answers "updating")', () => {
    const updatingAnswer = () => json({ error: 'updating', retryAfter: 30 }, 503);
    afterEach(() => {
      vi.useRealTimers();
    });

    it('a read waits, says "updating", and gets its answer once the app is back', async () => {
      vi.useFakeTimers();
      let calls = 0;
      const fetchMock = vi.fn(async () => (++calls <= 2 ? updatingAnswer() : json({ id: 1 })));
      vi.stubGlobal('fetch', fetchMock);

      const result = get('/api/me');
      await vi.advanceTimersByTimeAsync(0);
      expect(updatingNow()).toBe(true);
      await vi.advanceTimersByTimeAsync(3_000); // second try: still updating
      expect(fetchMock).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(4_500); // third try: back
      await expect(result).resolves.toEqual({ id: 1 });
      expect(fetchMock).toHaveBeenCalledTimes(3);
      expect(updatingNow()).toBe(false);
    });

    it('a change is not repeated on its own: it says why, in plain words', async () => {
      const fetchMock = vi.fn(async () => updatingAnswer());
      vi.stubGlobal('fetch', fetchMock);

      const err = await post('/api/streams/1/go-live').catch((e: unknown) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(503);
      expect((err as ApiError).message).toBe(UPDATING_MESSAGE);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(updatingNow()).toBe(true);

      vi.stubGlobal('fetch', vi.fn(async () => json({ ok: true })));
      await post('/api/x');
      expect(updatingNow()).toBe(false);
    });

    it('a read gives up after a few minutes and shows the message', async () => {
      vi.useFakeTimers();
      const fetchMock = vi.fn(async () => updatingAnswer());
      vi.stubGlobal('fetch', fetchMock);

      const result = get('/api/me').catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(4 * 60_000);
      const err = await result;
      expect((err as ApiError).message).toBe(UPDATING_MESSAGE);
      expect(fetchMock.mock.calls.length).toBeGreaterThan(5);
      expect(fetchMock.mock.calls.length).toBeLessThan(25);
      vi.stubGlobal('fetch', vi.fn(async () => json({})));
      await get('/api/me');
    });

    it("the app's own 503 is an ordinary error, not an update", async () => {
      const fetchMock = vi.fn(async () => json({ message: 'Storage is unavailable.' }, 503));
      vi.stubGlobal('fetch', fetchMock);

      await expect(get('/api/media')).rejects.toThrow('Storage is unavailable.');
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(updatingNow()).toBe(false);
    });

    it('recognises the answer and never shows the raw word', () => {
      expect(isUpdating({ error: 'updating', retryAfter: 30 })).toBe(true);
      expect(isUpdating({ error: 'other' })).toBe(false);
      expect(isUpdating('updating')).toBe(false);
      expect(errorMessage({ error: 'updating' }, 503)).toBe(UPDATING_MESSAGE);
    });
  });
});
