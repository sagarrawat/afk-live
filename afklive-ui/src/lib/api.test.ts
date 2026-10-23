import { ApiError, isExpiredToken, post } from './api';

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
});
