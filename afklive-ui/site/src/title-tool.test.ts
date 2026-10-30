/// <reference types="vite/client" />
import { DRAFT_KEY, messageFor, mountTitleTool, stashDraft, type ToolResult } from './title-tool';
import { alternates, jsonLd, text, withLangSwitch, withStructuredData } from './seo';
import { STATIC_PLANS } from './static-plans';
import { FIRST_STREAM_DRAFT_KEY, readFirstStreamDraft } from '../../src/lib/first-stream-draft';

// MK-7: the free title writer's page script, against the real page markup (English and Hindi).

const pages = import.meta.glob('../*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

function load(file: string) {
  const html = pages[`../${file}`];
  const lang = html.match(/<html lang="([a-z]+)"/)![1];
  document.documentElement.lang = lang;
  document.body.innerHTML = html.match(/<main[\s\S]*<\/main>/)![0];
  return document.querySelector<HTMLElement>('[data-title-tool]')!;
}

const OK: ToolResult = {
  status: 'OK',
  titles: ['Hanuman Chalisa · 24/7 replay', '<img src=x onerror="window.hacked=1">Hanuman Chalisa morning path', 'Hanuman Chalisa for peace'],
  description: 'Hanuman Chalisa every morning.\n\nTracklist',
  tags: ['hanuman chalisa', 'bhajan', 'aarti', 'devotional', 'morning prayer'],
  language: 'en',
};

type Call = { url: string; init?: RequestInit };

function fakeFetch(status: unknown, write: { code: number; body: unknown } = { code: 200, body: OK }) {
  const calls: Call[] = [];
  const fn = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(url), init });
    if (String(url).endsWith('/status')) return new Response(JSON.stringify(status), { status: 200 });
    return new Response(JSON.stringify(write.body), { status: write.code });
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

const UP = { available: true, turnstileSiteKey: null, descriptionMax: 200 };
const flush = () => new Promise((r) => setTimeout(r, 0));

async function submit(root: HTMLElement, description = 'Hanuman Chalisa every morning') {
  root.querySelector<HTMLSelectElement>('[name="niche"]')!.value = 'devotional';
  root.querySelector<HTMLTextAreaElement>('[name="description"]')!.value = description;
  root.querySelector<HTMLFormElement>('[data-tool-form]')!.dispatchEvent(new Event('submit', { cancelable: true }));
  await flush();
  await flush();
}

describe('free title writer page (MK-7)', () => {
  afterEach(() => {
    localStorage.clear();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('posts the form with the CSRF token and shows titles, description and tags as text with Copy', async () => {
    const root = load('tools-youtube-live-title-generator.html');
    document.cookie = 'XSRF-TOKEN=abc%3D123';
    const { fn, calls } = fakeFetch(UP);
    const copied: string[] = [];
    await mountTitleTool(root, { fetch: fn, storage: localStorage, clipboard: { writeText: async (t: string) => void copied.push(t) } });
    expect(root.querySelector<HTMLTextAreaElement>('[name="description"]')!.maxLength).toBe(200);
    expect(root.querySelector('[data-tool-counter]')!.textContent).toBe('0/200');

    await submit(root);
    const post = calls.find((c) => c.init?.method === 'POST')!;
    expect(post.url).toBe('/api/public/title-writer');
    expect((post.init!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc=123');
    expect(JSON.parse(String(post.init!.body))).toEqual({
      niche: 'devotional', description: 'Hanuman Chalisa every morning', language: 'en', mode: 'replay', turnstileToken: null,
    });

    const result = root.querySelector<HTMLElement>('[data-tool-result]')!;
    expect(result.hidden).toBe(false);
    const items = Array.from(root.querySelectorAll('[data-tool-titles] li'));
    expect(items).toHaveLength(3);
    // Text from the server is never HTML.
    expect(root.querySelector('[data-tool-titles] img')).toBeNull();
    expect(items[1].textContent).toContain('<img src=x');
    expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
    expect(root.querySelector('[data-tool-description]')!.textContent).toBe(OK.description);
    expect(root.querySelector('[data-tool-tags]')!.textContent).toBe('hanuman chalisa, bhajan, aarti, devotional, morning prayer');

    (items[2].querySelector('button') as HTMLButtonElement).click();
    root.querySelector<HTMLElement>('[data-tool-tags-copy] button')!.click();
    await flush();
    expect(copied).toEqual(['Hanuman Chalisa for peace', 'hanuman chalisa, bhajan, aarti, devotional, morning prayer']);
    expect(items[2].querySelector('button')!.textContent).toBe('Copied');
  });

  it('carries the chosen title and description to sign-up, for the first stream in the app', async () => {
    const root = load('tools-youtube-live-title-generator.html');
    const { fn } = fakeFetch(UP);
    await mountTitleTool(root, { fetch: fn, storage: localStorage });
    root.querySelector<HTMLInputElement>('input[name="mode"][value="phone"]')!.checked = true;
    await submit(root);
    const radios = root.querySelectorAll<HTMLInputElement>('input[name="chosen-title"]');
    expect(radios[0].checked).toBe(true);
    radios[2].checked = true;
    const cta = root.querySelector<HTMLAnchorElement>('[data-tool-cta]')!;
    expect(cta.getAttribute('href')).toBe('/register');
    expect(cta.textContent).toBe('Stream this 24/7 with AFK Live');
    cta.addEventListener('click', (e) => e.preventDefault());
    cta.click();
    expect(DRAFT_KEY).toBe(FIRST_STREAM_DRAFT_KEY);
    expect(readFirstStreamDraft(localStorage)).toEqual({ title: 'Hanuman Chalisa for peace', description: OK.description, language: 'en', mode: 'phone' });
  });

  it('says plainly when the tool is resting, and still offers sign-up', async () => {
    const root = load('tools-youtube-live-title-generator.html');
    const { fn, calls } = fakeFetch({ ...UP, available: false });
    await mountTitleTool(root, { fetch: fn, storage: localStorage });
    expect(root.querySelector<HTMLFormElement>('[data-tool-form]')!.hidden).toBe(true);
    const note = root.querySelector<HTMLElement>('[data-tool-resting]')!;
    expect(note.hidden).toBe(false);
    expect(note.textContent).toMatch(/resting right now.*sign up/);
    expect(calls).toHaveLength(1);
    expect(pages['../tools-youtube-live-title-generator.html']).toContain('href="/register"');

    // The status call failing is the same as resting.
    const broken = load('tools-youtube-live-title-generator.html');
    await mountTitleTool(broken, { fetch: (async () => { throw new Error('offline'); }) as unknown as typeof fetch });
    expect(broken.querySelector<HTMLElement>('[data-tool-resting]')!.hidden).toBe(false);
  });

  it('shows limits and errors without losing the form; Hindi in Hindi', async () => {
    const root = load('tools-youtube-live-title-generator.html');
    const limit = { status: 'LIMIT', message: "You've written 5 in the last hour. Try again in a little while, or sign up free to write more for your own streams." };
    const { fn } = fakeFetch(UP, { code: 429, body: limit });
    await mountTitleTool(root, { fetch: fn, storage: localStorage });
    await submit(root);
    const err = root.querySelector<HTMLElement>('[data-tool-error]')!;
    expect(err.hidden).toBe(false);
    expect(err.textContent).toBe(limit.message);
    expect(root.querySelector<HTMLFormElement>('[data-tool-form]')!.hidden).toBe(false);
    expect(root.querySelector<HTMLButtonElement>('[data-tool-submit]')!.disabled).toBe(false);

    const busy = load('tools-youtube-live-title-generator.html');
    await mountTitleTool(busy, { fetch: fakeFetch(UP, { code: 429, body: { status: 'BUSY', message: 'Lots of people used this today, so it is resting until tomorrow.' } }).fn });
    await submit(busy);
    expect(busy.querySelector<HTMLElement>('[data-tool-resting]')!.textContent).toContain('resting until tomorrow');

    const hi = load('hi-tools-youtube-live-title-generator.html');
    await mountTitleTool(hi, { fetch: fakeFetch(UP, { code: 429, body: limit }).fn });
    expect(hi.querySelector<HTMLSelectElement>('[name="language"]')!.value).toBe('hi');
    await submit(hi, 'हर सुबह हनुमान चालीसा');
    expect(hi.querySelector<HTMLElement>('[data-tool-error]')!.textContent).toMatch(/[ऀ-ॿ]/);
    expect(messageFor({ status: 'WHATEVER' }, 'hi')).toMatch(/गड़बड़/);
    expect(messageFor({ status: 'FAILED', message: 'x' }, 'en')).toBe('x');
  });

  it('uses Turnstile only when the server has a site key, and sends its token', async () => {
    const root = load('tools-youtube-live-title-generator.html');
    const { fn, calls } = fakeFetch({ ...UP, turnstileSiteKey: 'site-key' });
    let reset = 0;
    await mountTitleTool(root, {
      fetch: fn,
      storage: localStorage,
      loadTurnstile: (key, _el, onToken) => {
        expect(key).toBe('site-key');
        onToken('tok-1');
        return { reset: () => reset++ };
      },
    });
    await submit(root);
    expect(JSON.parse(String(calls.find((c) => c.init?.method === 'POST')!.init!.body)).turnstileToken).toBe('tok-1');
    expect(reset).toBe(1);
  });

  it('never throws when storage is blocked', () => {
    const blocked = { setItem: () => { throw new Error('blocked'); } } as unknown as Storage;
    expect(stashDraft(blocked, { title: 't', description: '', language: 'en', mode: 'replay' })).toBe(false);
    expect(stashDraft(undefined, { title: 't', description: '', language: 'en', mode: 'replay' })).toBe(false);
  });
});

describe('free title writer pages', () => {
  const en = pages['../tools-youtube-live-title-generator.html'];
  const hi = pages['../hi-tools-youtube-live-title-generator.html'];

  it('offer the site niches, six languages and the two ways of streaming, the same values in both', () => {
    for (const html of [en, hi]) {
      const values = (name: string) => Array.from(html.matchAll(new RegExp(`name="${name}"[\\s\\S]*?</(select|fieldset)>`, 'g')))[0][0];
      for (const v of ['lofi', 'devotional', 'gaming', 'podcasts', 'study', 'kids', 'other']) expect(values('niche')).toContain(`value="${v}"`);
      for (const v of ['en', 'hi', 'ta', 'te', 'bn', 'mr']) expect(values('language')).toContain(`value="${v}"`);
      expect(html).toContain('name="mode" value="replay" checked');
      expect(html).toContain('name="mode" value="phone"');
      expect(html).toContain('maxlength="200"');
      expect(html).toContain('data-tool-cta');
    }
  });

  it('carry structured data that matches what they show, and name each other as translations', () => {
    const partials = import.meta.glob('../partials/*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
    const built = (html: string) =>
      withLangSwitch(withStructuredData(html.replace(/<!-- include:([a-z-]+) -->/g, (_, p: string) => partials[`../partials/${p}.html`]), STATIC_PLANS));
    type Json = Record<string, any>;
    const ofType = (html: string, type: string): Json[] => jsonLd(html).filter((d) => d['@type'] === type);
    for (const [html, path, home, lang] of [
      [en, '/tools/youtube-live-title-generator', 'https://afklive.in/', 'en'],
      [hi, '/hi/tools/youtube-live-title-generator', 'https://afklive.in/hi', 'hi'],
    ] as const) {
      const page = built(html);
      const url = `https://afklive.in${path}`;
      const h1 = text(page.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)![1]);
      expect(page).toContain(`<link rel="canonical" href="${url}" />`);
      const [crumbs] = ofType(page, 'BreadcrumbList');
      expect(crumbs.itemListElement.map((i: Json) => [i.position, i.item])).toEqual([[1, home], [2, url]]);
      expect(crumbs.itemListElement[1].name).toBe(h1);
      expect(page).toContain(`aria-current="page">${h1}</span></nav>`);
      const [app] = ofType(page, 'WebApplication');
      expect(app).toMatchObject({ name: h1, url, inLanguage: lang, isAccessibleForFree: true, offers: { price: '0' } });
      const [faq] = ofType(page, 'FAQPage');
      expect(faq.mainEntity).toHaveLength(3);
      for (const q of faq.mainEntity as Json[]) expect(text(page)).toContain(q.name);
      expect(alternates(page)).toEqual({ en: '/tools/youtube-live-title-generator', hi: '/hi/tools/youtube-live-title-generator', 'x-default': '/tools/youtube-live-title-generator' });
      // The header's language switch leads to the twin.
      expect(page).toContain('<a href="/hi/tools/youtube-live-title-generator" hreflang="hi"');
      expect(page).toContain('<a href="/tools/youtube-live-title-generator" hreflang="en"');
    }
    expect(hi).toContain('href="./src/devanagari.css"');
    expect(en).not.toContain('devanagari.css');
    // Sign-up from the Hindi page is the English app, and says so.
    expect(hi).toMatch(/<a href="\/register" hreflang="en"[^>]*data-tool-cta/);
  });

  it('are linked from both footers and the titles help article', async () => {
    const partials = import.meta.glob('../partials/footer*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
    expect(partials['../partials/footer.html']).toContain('href="/tools/youtube-live-title-generator"');
    expect(partials['../partials/footer-hi.html']).toContain('href="/hi/tools/youtube-live-title-generator"');
    expect(pages['../help-titles-and-thumbnails.html']).toContain('href="/tools/youtube-live-title-generator"');
  });
});
