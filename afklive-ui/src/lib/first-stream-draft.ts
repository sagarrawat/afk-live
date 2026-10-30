/**
 * MK-7: a title and description chosen on the public site's free title writer (site/src/title-tool.ts), kept in
 * this browser's localStorage until the first stream is set up. It's text someone could have edited by hand, so
 * it's checked here and only ever shown as text (React escapes it) and sent through the normal stream API, which
 * validates it again. Anything odd, or older than a week, is ignored.
 */

/** Same key as the site writes (site/src/title-tool.ts DRAFT_KEY). */
export const FIRST_STREAM_DRAFT_KEY = 'afk.firstStreamDraft';
const MAX_AGE_MS = 7 * 24 * 3600 * 1000;
const LANGUAGES = ['en', 'hi', 'ta', 'te', 'bn', 'mr'];

export interface FirstStreamDraft {
  title: string;
  description: string;
  language: string;
  mode: 'replay' | 'phone';
}

/** Text only: control characters out, angle brackets out, trimmed and cut to {@code max} characters. */
function plain(v: unknown, max: number, oneLine: boolean): string {
  if (typeof v !== 'string') return '';
  // eslint-disable-next-line no-control-regex
  let s = v.replace(oneLine ? /[\u0000-\u001f\u007f]/g : /[\u0000-\u0009\u000b-\u001f\u007f]/g, ' ').replace(/[<>]/g, ' ');
  s = oneLine ? s.replace(/\s+/g, ' ').trim() : s.replace(/[ \t]+/g, ' ').trim();
  return [...s].slice(0, max).join('');
}

function store(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

export function readFirstStreamDraft(storage: Storage | undefined = store(), now = Date.now()): FirstStreamDraft | null {
  try {
    const raw = storage?.getItem(FIRST_STREAM_DRAFT_KEY);
    if (!raw || raw.length > 20000) return null;
    const d = JSON.parse(raw) as Record<string, unknown>;
    if (typeof d.savedAt !== 'number' || now - d.savedAt > MAX_AGE_MS || d.savedAt > now + 60000) return null;
    const title = plain(d.title, 100, true);
    if (!title) return null;
    return {
      title,
      description: plain(d.description, 5000, false),
      language: typeof d.language === 'string' && LANGUAGES.includes(d.language) ? d.language : 'en',
      mode: d.mode === 'phone' ? 'phone' : 'replay',
    };
  } catch {
    return null;
  }
}

export function clearFirstStreamDraft(storage: Storage | undefined = store()) {
  try {
    storage?.removeItem(FIRST_STREAM_DRAFT_KEY);
  } catch {
    /* nothing to clear */
  }
}
