/**
 * MK-7: the free title and description writer (/tools/youtube-live-title-generator and the Hindi page). Asks
 * /api/public/title-writer/status whether the tool is up (that call also sets the CSRF cookie), posts the form,
 * and shows three titles, a description and tags, each with Copy. Everything from the server is shown with
 * textContent, never as HTML. "Stream this 24/7" keeps the chosen title and description in this browser
 * (localStorage, one key, for a week) so the first stream after sign-up starts with them (app: lib/first-stream-draft).
 */

export type ToolLang = 'en' | 'hi';

export interface ToolStatus {
  available: boolean;
  turnstileSiteKey?: string | null;
  descriptionMax: number;
}

export interface ToolResult {
  status: 'OK' | 'INVALID' | 'CHECK' | 'LIMIT' | 'BUSY' | 'UNAVAILABLE' | 'FAILED' | string;
  message?: string | null;
  titles?: string[] | null;
  description?: string | null;
  tags?: string[] | null;
  language?: string | null;
}

/** What the app's first-stream setup reads (afklive-ui/src/lib/first-stream-draft.ts). Keep the two in step. */
export const DRAFT_KEY = 'afk.firstStreamDraft';

export interface StashedDraft {
  title: string;
  description: string;
  language: string;
  mode: 'replay' | 'phone';
  savedAt: number;
}

const WORDS = {
  en: {
    resting: 'This tool is resting right now. You can still sign up and write titles for your own streams.',
    writing: 'Writing…',
    write: 'Write titles',
    copy: 'Copy',
    copied: 'Copied',
    use: 'Use this title',
    error: 'Something went wrong. Try again in a minute.',
    statuses: {} as Record<string, string>,
  },
  hi: {
    resting: 'यह टूल अभी आराम कर रहा है। फिर भी आप sign up करके अपनी streams के लिए titles लिख सकते हैं।',
    writing: 'लिख रहे हैं…',
    write: 'Titles लिखें',
    copy: 'Copy करें',
    copied: 'Copy हो गया',
    use: 'यह title चुनें',
    error: 'कुछ गड़बड़ हुई। एक मिनट बाद फिर कोशिश करें।',
    statuses: {
      INVALID: 'आप क्या stream करते हैं, भाषा और तरीका चुनें, और कुछ शब्दों में बताएँ कि stream पर क्या है।',
      CHECK: 'हम जाँच नहीं पाए कि आप इंसान हैं। पेज reload करके फिर कोशिश करें।',
      LIMIT: 'आपने अभी के लिए काफ़ी titles लिख लिए। थोड़ी देर बाद आएँ, या फ़्री sign up करके अपनी streams के लिए और लिखें।',
      BUSY: 'आज बहुत लोगों ने इसे इस्तेमाल किया, इसलिए यह कल तक आराम कर रहा है। फ़्री sign up करके अपनी streams के लिए titles लिखें।',
      UNAVAILABLE: 'यह टूल अभी आराम कर रहा है। बाद में फिर कोशिश करें।',
      FAILED: 'यह वाला नहीं लिख पाए। एक मिनट बाद फिर कोशिश करें।',
    } as Record<string, string>,
  },
};

export function words(lang: ToolLang) {
  return WORDS[lang];
}

/** The message to show for a result that isn't OK: the Hindi page says it in Hindi, the English page uses the server's words. */
export function messageFor(r: ToolResult, lang: ToolLang): string {
  const w = WORDS[lang];
  return w.statuses[r.status] ?? r.message ?? w.error;
}

export function csrfToken(doc: Document = document): string | undefined {
  const m = doc.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

/** Keeps the chosen draft for the first stream after sign-up. Never throws (private windows, blocked storage). */
export function stashDraft(storage: Storage | undefined, d: Omit<StashedDraft, 'savedAt'>, now = Date.now()): boolean {
  try {
    storage?.setItem(DRAFT_KEY, JSON.stringify({ ...d, savedAt: now }));
    return Boolean(storage);
  } catch {
    return false;
  }
}

type Fetch = typeof fetch;

interface Deps {
  fetch?: Fetch;
  storage?: Storage;
  clipboard?: Pick<Clipboard, 'writeText'>;
  loadTurnstile?: (siteKey: string, el: HTMLElement, onToken: (t: string) => void) => { reset(): void } | null;
}

/** Cloudflare's widget, loaded only when the server has a site key (APP_TURNSTILE_SITE_KEY). */
function cloudflareTurnstile(siteKey: string, el: HTMLElement, onToken: (t: string) => void) {
  type T = { render(el: HTMLElement, o: Record<string, unknown>): string; reset(id?: string): void };
  const w = window as unknown as { turnstile?: T; onAfkTurnstile?: () => void };
  let id: string | undefined;
  const render = () => {
    id = w.turnstile?.render(el, { sitekey: siteKey, callback: onToken, 'expired-callback': () => onToken('') });
  };
  if (w.turnstile) render();
  else {
    w.onAfkTurnstile = render;
    const s = document.createElement('script');
    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onAfkTurnstile';
    s.async = true;
    s.defer = true;
    document.head.appendChild(s);
  }
  return { reset: () => w.turnstile?.reset(id) };
}

export async function mountTitleTool(root: HTMLElement, deps: Deps = {}) {
  const doFetch = deps.fetch ?? fetch.bind(window);
  const storage = deps.storage ?? safeLocalStorage();
  const clipboard = deps.clipboard ?? navigator.clipboard;
  const lang: ToolLang = (root.ownerDocument.documentElement.lang === 'hi' ? 'hi' : 'en');
  const w = WORDS[lang];
  const q = <T extends Element>(sel: string) => root.querySelector<T>(sel);
  const form = q<HTMLFormElement>('[data-tool-form]');
  const resting = q<HTMLElement>('[data-tool-resting]');
  const error = q<HTMLElement>('[data-tool-error]');
  const result = q<HTMLElement>('[data-tool-result]');
  const submit = q<HTMLButtonElement>('[data-tool-submit]');
  const desc = q<HTMLTextAreaElement>('[name="description"]');
  const counter = q<HTMLElement>('[data-tool-counter]');
  if (!form || !submit || !result) return;
  const box: HTMLElement = result;

  const rest = () => {
    form.hidden = true;
    if (resting) {
      resting.textContent = w.resting;
      resting.hidden = false;
    }
  };

  let status: ToolStatus | null = null;
  try {
    const r = await doFetch('/api/public/title-writer/status', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    status = r.ok ? ((await r.json()) as ToolStatus) : null;
  } catch {
    status = null;
  }
  if (!status?.available) {
    rest();
    return;
  }
  if (desc) {
    desc.maxLength = status.descriptionMax;
    const count = () => counter && (counter.textContent = `${[...desc.value].length}/${status!.descriptionMax}`);
    desc.addEventListener('input', count);
    count();
  }

  let token = '';
  let widget: { reset(): void } | null = null;
  const slot = q<HTMLElement>('[data-turnstile]');
  if (status.turnstileSiteKey && slot) {
    widget = (deps.loadTurnstile ?? cloudflareTurnstile)(status.turnstileSiteKey, slot, (t) => (token = t));
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = new FormData(form);
    const mode = String(data.get('mode') ?? 'replay') as 'replay' | 'phone';
    const body = {
      niche: String(data.get('niche') ?? ''),
      description: String(data.get('description') ?? ''),
      language: String(data.get('language') ?? lang),
      mode,
      turnstileToken: token || null,
    };
    if (error) error.hidden = true;
    submit.disabled = true;
    submit.textContent = w.writing;
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'application/json' };
      const csrf = csrfToken(root.ownerDocument);
      if (csrf) headers['X-XSRF-TOKEN'] = csrf;
      const r = await doFetch('/api/public/title-writer', { method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify(body) });
      const res = (await r.json().catch(() => ({ status: 'FAILED' }))) as ToolResult;
      if (res.status === 'OK' && res.titles?.length) {
        showResult(res, mode);
      } else if (res.status === 'UNAVAILABLE' || res.status === 'BUSY') {
        result.hidden = true;
        rest();
        if (resting) resting.textContent = messageFor(res, lang);
      } else {
        showError(messageFor(res, lang));
      }
    } catch {
      showError(w.error);
    } finally {
      submit.disabled = false;
      submit.textContent = w.write;
      token = '';
      widget?.reset();
    }
  });

  function showError(msg: string) {
    if (!error) return;
    error.textContent = msg;
    error.hidden = false;
  }

  function copyButton(text: () => string) {
    const b = root.ownerDocument.createElement('button');
    b.type = 'button';
    b.className = 'btn-secondary h-9 shrink-0 px-4 text-sm';
    b.textContent = w.copy;
    b.addEventListener('click', async () => {
      try {
        await clipboard?.writeText(text());
        b.textContent = w.copied;
        setTimeout(() => (b.textContent = w.copy), 1500);
      } catch {
        /* the text is on the page to select by hand */
      }
    });
    return b;
  }

  function showResult(res: ToolResult, mode: 'replay' | 'phone') {
    const doc = root.ownerDocument;
    const titles = q<HTMLElement>('[data-tool-titles]')!;
    const description = q<HTMLElement>('[data-tool-description]')!;
    const tags = q<HTMLElement>('[data-tool-tags]')!;
    titles.replaceChildren();
    (res.titles ?? []).forEach((t, i) => {
      const li = doc.createElement('li');
      li.className = 'flex items-start justify-between gap-3 border-b border-line py-3';
      const label = doc.createElement('label');
      label.className = 'flex min-w-0 items-start gap-3';
      const radio = doc.createElement('input');
      radio.type = 'radio';
      radio.name = 'chosen-title';
      radio.value = String(i);
      radio.checked = i === 0;
      radio.className = 'mt-1.5';
      radio.setAttribute('aria-label', `${w.use}: ${t}`);
      const span = doc.createElement('span');
      span.className = 'min-w-0 break-words font-medium';
      span.textContent = t;
      label.append(radio, span);
      li.append(label, copyButton(() => t));
      titles.append(li);
    });
    description.textContent = res.description ?? '';
    q<HTMLElement>('[data-tool-description-copy]')?.replaceChildren(copyButton(() => res.description ?? ''));
    const tagText = (res.tags ?? []).join(', ');
    tags.textContent = tagText;
    q<HTMLElement>('[data-tool-tags-copy]')?.replaceChildren(copyButton(() => tagText));
    box.hidden = false;
    const cta = q<HTMLAnchorElement>('[data-tool-cta]');
    if (cta) {
      cta.onclick = () => {
        const chosen = root.querySelector<HTMLInputElement>('input[name="chosen-title"]:checked');
        const title = (res.titles ?? [])[chosen ? Number(chosen.value) : 0] ?? '';
        stashDraft(storage, { title, description: res.description ?? '', language: res.language ?? lang, mode });
      };
    }
    box.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }
}

function safeLocalStorage(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}
