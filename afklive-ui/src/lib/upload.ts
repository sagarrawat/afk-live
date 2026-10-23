import { api, csrfToken } from './api';
import type { MediaItem, PartTarget, UploadView } from './types';

/**
 * ML-1: resumable uploads. A file goes up in the parts the server chose (16 MB), three at a time,
 * each retried with backoff. Parts go straight to object storage when the server hands out a
 * presigned URL. The upload id is remembered per file (name, size, modified time), so picking the
 * same file again after a refresh or a dropped connection skips the parts already stored.
 */

export interface Transport {
  start(filename: string, size: number): Promise<UploadView>;
  status(id: string): Promise<UploadView>;
  target(id: string, part: number): Promise<PartTarget>;
  /** Sends the bytes; resolves with the part's ETag. */
  send(target: PartTarget, body: Blob, onProgress: (sent: number) => void, signal: AbortSignal): Promise<string>;
  complete(id: string, etags: Record<number, string>): Promise<MediaItem>;
  cancel(id: string): Promise<void>;
}

export interface Store {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

export type UploadState = 'queued' | 'uploading' | 'finishing' | 'done' | 'failed' | 'cancelled';

export interface Progress {
  state: UploadState;
  sent: number;
  total: number;
  /** Bytes per second over the last few seconds. */
  rate: number;
  error?: string;
  media?: MediaItem;
}

export const PARALLEL = 3;
export const RETRIES = 5;

export function fileKey(f: { name: string; size: number; lastModified: number }): string {
  return `afk-upload:${f.name}:${f.size}:${f.lastModified}`;
}

const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(t);
      reject(new DOMException('Aborted', 'AbortError'));
    });
  });

export class Upload {
  readonly controller = new AbortController();
  private listeners = new Set<(p: Progress) => void>();
  private partSent = new Map<number, number>();
  private samples: { t: number; sent: number }[] = [];
  progress: Progress;
  id?: string;

  constructor(
    readonly file: File,
    private transport: Transport,
    private store: Store,
    private backoffMs = 1000,
  ) {
    this.progress = { state: 'queued', sent: 0, total: file.size, rate: 0 };
  }

  onChange(fn: (p: Progress) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(patch: Partial<Progress>) {
    this.progress = { ...this.progress, ...patch };
    this.listeners.forEach((l) => l(this.progress));
  }

  private updateSent() {
    let sent = 0;
    this.partSent.forEach((v) => (sent += v));
    const now = Date.now();
    this.samples.push({ t: now, sent });
    while (this.samples.length > 2 && now - this.samples[0].t > 5000) this.samples.shift();
    const first = this.samples[0];
    const rate = now > first.t ? ((sent - first.sent) * 1000) / (now - first.t) : 0;
    this.emit({ sent, rate });
  }

  async run(): Promise<MediaItem | undefined> {
    const signal = this.controller.signal;
    const key = fileKey(this.file);
    try {
      this.emit({ state: 'uploading' });
      let view: UploadView | undefined;
      const remembered = this.store.get(key);
      if (remembered) {
        try {
          view = await this.transport.status(remembered);
          if (view.status !== 'UPLOADING') view = undefined;
        } catch {
          view = undefined;
        }
      }
      if (!view) {
        view = await this.transport.start(this.file.name, this.file.size);
        this.store.set(key, view.id);
      }
      this.id = view.id;
      const done = new Set(view.uploadedParts);
      const etags: Record<number, string> = {};
      for (let n = 1; n <= view.partCount; n++) {
        if (done.has(n)) this.partSent.set(n, partLength(view, n));
      }
      this.updateSent();

      const todo: number[] = [];
      for (let n = 1; n <= view.partCount; n++) if (!done.has(n)) todo.push(n);
      const v = view;
      const worker = async () => {
        while (todo.length) {
          const n = todo.shift()!;
          etags[n] = await this.sendPart(v, n, signal);
        }
      };
      await Promise.all(Array.from({ length: Math.min(PARALLEL, todo.length) }, worker));

      this.emit({ state: 'finishing' });
      const media = await this.transport.complete(view.id, etags);
      this.store.remove(key);
      this.emit({ state: 'done', media, sent: this.file.size });
      return media;
    } catch (e) {
      if (signal.aborted) {
        this.emit({ state: 'cancelled' });
        return undefined;
      }
      this.emit({ state: 'failed', error: e instanceof Error ? e.message : String(e) });
      return undefined;
    }
  }

  private async sendPart(view: UploadView, n: number, signal: AbortSignal): Promise<string> {
    const start = (n - 1) * view.partSize;
    const blob = this.file.slice(start, start + partLength(view, n));
    let lastError: unknown;
    for (let attempt = 0; attempt <= RETRIES; attempt++) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
      try {
        // A fresh target each try: presigned URLs expire.
        const target = await this.transport.target(view.id, n);
        const etag = await this.transport.send(
          target,
          blob,
          (sent) => {
            this.partSent.set(n, sent);
            this.updateSent();
          },
          signal,
        );
        this.partSent.set(n, blob.size);
        this.updateSent();
        return etag;
      } catch (e) {
        if (signal.aborted) throw e;
        lastError = e;
        this.partSent.set(n, 0);
        await sleep(this.backoffMs * 2 ** attempt, signal);
      }
    }
    throw lastError instanceof Error ? lastError : new Error('Upload failed. Check your connection and try again.');
  }

  async cancel() {
    this.controller.abort();
    this.store.remove(fileKey(this.file));
    if (this.id) {
      try {
        await this.transport.cancel(this.id);
      } catch {
        /* the server expires it anyway */
      }
    }
  }
}

export function partLength(view: Pick<UploadView, 'partSize' | 'sizeBytes'>, n: number): number {
  return Math.min(view.partSize, view.sizeBytes - (n - 1) * view.partSize);
}

/** The real transport: the app API plus XHR PUTs (for progress events). */
export const httpTransport: Transport = {
  start: (filename, size) => api<UploadView>('/api/media/uploads', { method: 'POST', json: { filename, size } }),
  status: (id) => api<UploadView>(`/api/media/uploads/${id}`),
  target: (id, part) => api<PartTarget>(`/api/media/uploads/${id}/parts/${part}`, { method: 'POST', json: {} }),
  send: (target, body, onProgress, signal) =>
    new Promise<string>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(target.method || 'PUT', target.url);
      if (!target.direct) {
        const token = csrfToken();
        if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.withCredentials = true;
      }
      xhr.upload.onprogress = (e) => onProgress(e.loaded);
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(xhr.getResponseHeader('ETag') ?? '');
        } else {
          reject(new Error(`Part ${target.partNumber} failed (${xhr.status}).`));
        }
      };
      xhr.onerror = () => reject(new Error('Network error while uploading.'));
      signal.addEventListener('abort', () => xhr.abort());
      xhr.send(body);
    }),
  complete: (id, etags) => api<MediaItem>(`/api/media/uploads/${id}/complete`, { method: 'POST', json: { etags } }),
  cancel: async (id) => {
    await api<void>(`/api/media/uploads/${id}`, { method: 'DELETE' });
  },
};

export const localStore: Store = {
  get: (k) => {
    try {
      return window.localStorage.getItem(k);
    } catch {
      return null;
    }
  },
  set: (k, v) => {
    try {
      window.localStorage.setItem(k, v);
    } catch {
      /* private mode: no resume across refresh */
    }
  },
  remove: (k) => {
    try {
      window.localStorage.removeItem(k);
    } catch {
      /* ignore */
    }
  },
};
