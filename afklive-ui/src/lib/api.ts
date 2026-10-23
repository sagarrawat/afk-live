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
  const headers = new Headers(rest.headers);
  headers.set('Accept', 'application/json');
  const method = (rest.method ?? 'GET').toUpperCase();
  if (json !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken();
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  const res = await fetch(path, {
    ...rest,
    method,
    headers,
    credentials: 'same-origin',
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  });
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

export function errorMessage(body: unknown, status: number): string {
  if (body && typeof body === 'object') {
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
export const del = <T>(path: string) => api<T>(path, { method: 'DELETE' });
