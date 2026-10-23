import { fileKey, partLength, Upload, type Store, type Transport } from './upload';
import type { MediaItem, PartTarget, UploadView } from './types';

const PART = 4;

function memoryStore(): Store & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return { data, get: (k) => data.get(k) ?? null, set: (k, v) => void data.set(k, v), remove: (k) => void data.delete(k) };
}

function fakeServer(opts: { failFirst?: number[] } = {}) {
  const stored = new Map<number, string>();
  const sent: number[] = [];
  const failures = new Set(opts.failFirst ?? []);
  let view: UploadView | undefined;
  const completed: Record<number, string>[] = [];
  const transport: Transport = {
    async start(filename, size) {
      view = { id: 'up-1', filename, type: 'VIDEO', sizeBytes: size, partSize: PART, partCount: Math.ceil(size / PART), uploadedParts: [], uploadedBytes: 0, status: 'UPLOADING', expiresAt: '' };
      return view;
    },
    async status(id) {
      if (!view || view.id !== id) throw new Error('not found');
      return { ...view, uploadedParts: [...stored.keys()] };
    },
    async target(id, part): Promise<PartTarget> {
      return { partNumber: part, url: `/parts/${id}/${part}`, method: 'PUT', size: PART, direct: false };
    },
    async send(target, body, onProgress) {
      if (failures.has(target.partNumber)) {
        failures.delete(target.partNumber);
        throw new Error('network');
      }
      sent.push(target.partNumber);
      onProgress(body.size);
      const etag = `"e${target.partNumber}"`;
      stored.set(target.partNumber, etag);
      return etag;
    },
    async complete(_id, etags) {
      completed.push(etags);
      return { id: 9, title: 'clip.mp4', type: 'VIDEO', status: 'PROCESSING' } as MediaItem;
    },
    async cancel() {},
  };
  return { transport, stored, sent, completed };
}

const file = () => new File(['0123456789'], 'clip.mp4', { lastModified: 1 }); // 10 bytes: parts of 4, 4, 2

describe('resumable upload', () => {
  it('sends every part and completes with their ETags', async () => {
    const srv = fakeServer();
    const store = memoryStore();
    const u = new Upload(file(), srv.transport, store, 1);
    const media = await u.run();
    expect(media?.id).toBe(9);
    expect(srv.sent.sort()).toEqual([1, 2, 3]);
    expect(srv.completed[0]).toEqual({ 1: '"e1"', 2: '"e2"', 3: '"e3"' });
    expect(u.progress.state).toBe('done');
    expect(u.progress.sent).toBe(10);
    expect(store.data.size).toBe(0); // nothing left to resume
  });

  it('resumes after a refresh, skipping parts already stored', async () => {
    const srv = fakeServer();
    const store = memoryStore();
    const f = file();
    await srv.transport.start(f.name, f.size);
    srv.stored.set(1, '"e1"');
    srv.stored.set(2, '"e2"');
    store.set(fileKey(f), 'up-1');

    const u = new Upload(f, srv.transport, store, 1);
    await u.run();
    expect(srv.sent).toEqual([3]);
    expect(u.progress.state).toBe('done');
  });

  it('retries a failed part', async () => {
    const srv = fakeServer({ failFirst: [2] });
    const u = new Upload(file(), srv.transport, memoryStore(), 1);
    await u.run();
    expect(u.progress.state).toBe('done');
    expect(srv.sent).toContain(2);
  });

  it('reports a failure with the reason after the retries', async () => {
    const srv = fakeServer();
    srv.transport.send = async () => {
      throw new Error('Network error while uploading.');
    };
    const u = new Upload(file(), srv.transport, memoryStore(), 0);
    await u.run();
    expect(u.progress.state).toBe('failed');
    expect(u.progress.error).toContain('Network error');
  });

  it('cancel stops the upload and forgets it', async () => {
    const srv = fakeServer();
    const store = memoryStore();
    srv.transport.send = (_t, _b, _p, signal) =>
      new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))));
    const u = new Upload(file(), srv.transport, store, 1);
    const running = u.run();
    await new Promise((r) => setTimeout(r, 10));
    await u.cancel();
    await running;
    expect(u.progress.state).toBe('cancelled');
    expect(store.data.size).toBe(0);
  });

  it('part lengths cover the file exactly', () => {
    const v = { partSize: 4, sizeBytes: 10 };
    expect([1, 2, 3].map((n) => partLength(v, n))).toEqual([4, 4, 2]);
  });
});
