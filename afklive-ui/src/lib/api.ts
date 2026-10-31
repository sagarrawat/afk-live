/** Thin fetch wrapper: same-origin cookies, Spring's CSRF cookie echoed as a header, readable errors. */

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(message);
  }
}

export function csrfToken(): string | undefined {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

type Json = Record<string, unknown> | unknown[] | string | number | boolean | null;

export async function api<T>(path: string, init: RequestInit & { json?: Json } = {}): Promise<T> {
  const { json, ...rest } = init;
  const method = (rest.method ?? 'GET').toUpperCase();
  const send = () => {
    const headers = new Headers(rest.headers);
    headers.set('Accept', 'application/json');
    if (json !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (method !== 'GET' && method !== 'HEAD') {
      const token = csrfToken();
      if (token) headers.set('X-XSRF-TOKEN', token);
    }
    return fetch(path, {
      ...rest,
      method,
      headers,
      credentials: 'same-origin',
      body: json !== undefined ? JSON.stringify(json) : rest.body,
    });
  };
  const attempt = async () => {
    let res = await send();
    if (res.status === 403 && isExpiredToken(await peek(res))) {
      // OPS-2: the page's security token expired (a deploy, or a sign-in in another tab). Fetch a fresh
      // one and try once more, so nobody sees an error for it.
      await fetch('/api/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      res = await send();
    }
    return res;
  };
  let res = await attempt();
  // While the web app restarts (a deploy), Caddy answers every API call with 503 {"error":"updating"}
  // (afklive-scripts/caddy/Caddyfile). Say so instead of failing; reads wait and try again until it's back.
  // Changes (POST, PUT, ...) are not repeated on their own: the person sees why and tries again.
  const started = Date.now();
  let wait = UPDATE_FIRST_WAIT_MS;
  while (res.status === 503 && isUpdating(await peek(res))) {
    setUpdating(true);
    if ((method !== 'GET' && method !== 'HEAD') || Date.now() - started + wait > UPDATE_MAX_WAIT_MS) break;
    await new Promise((r) => setTimeout(r, wait));
    wait = Math.min(UPDATE_MAX_STEP_MS, Math.round(wait * 1.5));
    res = await attempt();
  }
  if (!(res.status === 503 && isUpdating(await peek(res)))) setUpdating(false);
  if (res.status === 401 || (res.redirected && new URL(res.url).pathname === '/login')) {
    window.location.href = '/login';
    throw new ApiError('Signed out', 401);
  }
  const text = await res.text();
  let body: unknown = undefined;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!res.ok) {
    throw new ApiError(errorMessage(body, res.status), res.status, body);
  }
  return body as T;
}

const UPDATE_FIRST_WAIT_MS = 3_000;
const UPDATE_MAX_STEP_MS = 15_000;
/** A restart takes about a minute; after this long a read gives up and shows the message. */
const UPDATE_MAX_WAIT_MS = 3 * 60_000;

export const UPDATING_MESSAGE = 'AFK Live is updating. Try again in a minute. Your live streams keep running.';

/** Caddy's answer while the web app restarts (a deploy), not an error in what was asked. */
export function isUpdating(body: unknown): boolean {
  return !!body && typeof body === 'object' && (body as { error?: unknown }).error === 'updating';
}

// Whether the last API answer said "updating": the app shows "Updating, reconnecting…" while it does.
let updating = false;
const updatingListeners = new Set<() => void>();

function setUpdating(value: boolean) {
  if (updating === value) return;
  updating = value;
  updatingListeners.forEach((l) => l());
}

export const updatingNow = () => updating;

export function onUpdatingChange(listener: () => void): () => void {
  updatingListeners.add(listener);
  return () => updatingListeners.delete(listener);
}

/** A response's JSON, read without using the response up. */
async function peek(res: Response): Promise<unknown> {
  try {
    return await res.clone().json();
  } catch {
    return undefined;
  }
}

/** The server's "this page expired" answer (an expired security token), not a real refusal. */
export function isExpiredToken(body: unknown): boolean {
  return !!body && typeof body === 'object' && (body as { code?: unknown }).code === 'csrf';
}

export function errorMessage(body: unknown, status: number): string {
  if (body && typeof body === 'object') {
    if (isUpdating(body)) return UPDATING_MESSAGE;
    const b = body as { message?: unknown; error?: unknown };
    if (typeof b.message === 'string' && b.message) return b.message;
    if (typeof b.error === 'string' && b.error) return b.error;
  }
  if (status === 403) return "You don't have access to that.";
  if (status === 404) return 'Not found.';
  if (status >= 500) return 'Something went wrong on our side. Try again in a moment.';
  return 'That didn’t work. Check the details and try again.';
}

export const get = <T>(path: string) => api<T>(path);
export const post = <T>(path: string, json?: Json) => api<T>(path, { method: 'POST', json: json ?? {} });
export const put = <T>(path: string, json?: Json) => api<T>(path, { method: 'PUT', json: json ?? {} });
export const patch = <T>(path: string, json?: Json) => api<T>(path, { method: 'PATCH', json: json ?? {} });
export const del = <T>(path: string, json?: Json) => api<T>(path, json === undefined ? { method: 'DELETE' } : { method: 'DELETE', json });
