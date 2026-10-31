import { clearFirstStreamDraft, FIRST_STREAM_DRAFT_KEY, readFirstStreamDraft } from './first-stream-draft';

const NOW = 1_800_000_000_000;
const put = (v: unknown) => localStorage.setItem(FIRST_STREAM_DRAFT_KEY, typeof v === 'string' ? v : JSON.stringify(v));

describe('the first stream draft from the site title writer (MK-7)', () => {
  afterEach(() => localStorage.clear());

  it('reads a fresh draft as plain text', () => {
    put({ title: 'Hanuman Chalisa · 24/7 replay', description: 'Line one.\nTracklist', language: 'hi', mode: 'replay', savedAt: NOW - 1000 });
    expect(readFirstStreamDraft(localStorage, NOW)).toEqual({ title: 'Hanuman Chalisa · 24/7 replay', description: 'Line one.\nTracklist', language: 'hi', mode: 'replay' });
  });

  it('never trusts it: no markup characters, limits, known languages and modes only', () => {
    put({ title: '<img src=x onerror=alert(1)>Lofi\u0000 beats', description: 'a'.repeat(6000), language: 'xx', mode: 'admin', savedAt: NOW });
    const d = readFirstStreamDraft(localStorage, NOW)!;
    expect(d.title).toBe('img src=x onerror=alert(1) Lofi beats');
    expect(d.description).toHaveLength(5000);
    expect(d.language).toBe('en');
    expect(d.mode).toBe('replay');
    put({ title: 'x'.repeat(300), savedAt: NOW });
    expect(readFirstStreamDraft(localStorage, NOW)!.title).toHaveLength(100);
  });

  it('ignores old, empty, future-dated or broken drafts, and clears', () => {
    put({ title: 'Old', savedAt: NOW - 8 * 24 * 3600 * 1000 });
    expect(readFirstStreamDraft(localStorage, NOW)).toBeNull();
    put({ title: '   ', savedAt: NOW });
    expect(readFirstStreamDraft(localStorage, NOW)).toBeNull();
    put({ title: 'Future', savedAt: NOW + 3600 * 1000 });
    expect(readFirstStreamDraft(localStorage, NOW)).toBeNull();
    put('{not json');
    expect(readFirstStreamDraft(localStorage, NOW)).toBeNull();
    put({ title: 'Lofi', savedAt: NOW });
    clearFirstStreamDraft(localStorage);
    expect(readFirstStreamDraft(localStorage, NOW)).toBeNull();
    expect(readFirstStreamDraft(undefined, NOW)).toBeNull();
  });
});
