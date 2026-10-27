/// <reference types="vite/client" />
import { DICT, LANGS, pageLang, type Dict } from './i18n';
import { planCards } from './plans';
import { STATIC_PLANS } from './static-plans';
import { formula, verdict, DEFAULTS } from './calc';
import { proofLine } from './proof';
import { niches } from './niches';
import { mountHero } from './hero';
import publicPages from '../../../afklive-web/src/main/java/com/afklive/streamer/endpoint/PublicPages.java?raw';
import homeController from '../../../afklive-web/src/main/java/com/afklive/streamer/endpoint/HomeController.java?raw';
import securityConfig from '../../../afklive-web/src/main/java/com/afklive/streamer/config/SecurityConfig.java?raw';
import siteConfig from '../../vite.site.config.ts?raw';

// MK-1: the homepage in Hindi at /hi. Same sections and scripts as the English homepage, its own copy.

const pages = import.meta.glob('../{index,hi}.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const partials = import.meta.glob('../partials/*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const en = pages['../index.html'];
const hi = pages['../hi.html'];
const partial = (name: string) => partials[`../partials/${name}.html`];
const DEVANAGARI = /[ऀ-ॿ]/;

/** Every leaf of a dictionary as path → value (functions called with sample arguments). */
function leaves(d: object, prefix = ''): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(d)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'function') out[path] = (v as (...a: unknown[]) => unknown)(2, 30);
    else if (v && typeof v === 'object') Object.assign(out, leaves(v, path));
    else out[path] = v;
  }
  return out;
}

describe('per-language dictionary', () => {
  it('has every key in both languages, none empty', () => {
    const keys = (d: Dict) => Object.keys(leaves(d)).sort();
    expect(keys(DICT.hi)).toEqual(keys(DICT.en));
    for (const lang of LANGS) {
      for (const [k, v] of Object.entries(leaves(DICT[lang]))) {
        if (v === null) continue; // freePrice: null means "use the catalogue's label"
        expect(typeof v, `${lang}.${k}`).toBe('string');
        expect((v as string).trim(), `${lang}.${k}`).not.toBe('');
      }
    }
  });

  it('is actually translated: every Hindi sentence has Devanagari in it', () => {
    for (const [k, v] of Object.entries(leaves(DICT.hi))) {
      if (k === 'locale') continue;
      expect(String(v), `hi.${k}`).toMatch(DEVANAGARI);
    }
  });

  it('reads the page language from <html lang>', () => {
    const doc = document.implementation.createHTMLDocument('');
    doc.documentElement.lang = 'hi';
    expect(pageLang(doc)).toBe('hi');
    doc.documentElement.lang = 'hi-IN';
    expect(pageLang(doc)).toBe('hi');
    doc.documentElement.lang = 'en';
    expect(pageLang(doc)).toBe('en');
    doc.documentElement.lang = '';
    expect(pageLang(doc)).toBe('en');
  });

  it('writes Hindi from the shared components', () => {
    expect(formula(DEFAULTS, 'hi')).toBe('150 W × 24 घंटे × 30 दिन ÷ 1000 = 108 यूनिट × ₹8 = ₹864');
    expect(verdict(864, 499, 'hi')).toContain('₹365');
    expect(proofLine({ liveNow: 12, hoursStreamed: 1200 }, 'hi')).toContain('1,200+');
    expect(niches('hi').devotional.title).toBe('संध्या आरती, हर शाम live');
    // Template names stay as the (English) app names them.
    expect(niches('hi').devotional.template).toBe(niches('en').devotional.template);
    const cards = planCards(STATIC_PLANS, 'YEARLY', 'hi');
    expect(cards).toContain('/ साल');
    expect(cards).toContain('/register?plan=ESSENTIALS'); // into the English app
  });
});

describe('Hindi homepage', () => {
  it('is built, served at /hi, permitted signed out and in the sitemap', () => {
    expect(siteConfig).toContain("hi: resolve(__dirname, 'site/hi.html')");
    expect(homeController).toContain('@GetMapping("/hi")');
    expect(homeController).toContain('forward:/site/hi.html');
    expect(securityConfig).toContain('"/hi"');
    expect(publicPages).toContain('pages.put("/hi", "hi.html")'); // in /sitemap.xml
  });

  it('declares its language, canonical and Hindi meta and Open Graph tags', () => {
    expect(hi).toContain('<html lang="hi">');
    expect(hi).toContain('<link rel="canonical" href="https://afklive.in/hi" />');
    expect(hi).toContain('<meta property="og:url" content="https://afklive.in/hi" />');
    expect(hi).toContain('<meta property="og:locale" content="hi_IN" />');
    for (const tag of [/<title>([^<]+)<\/title>/, /name="description" content="([^"]+)"/, /property="og:title" content="([^"]+)"/, /property="og:description" content="([^"]+)"/]) {
      expect(hi.match(tag)?.[1], String(tag)).toMatch(DEVANAGARI);
    }
    expect(en).toContain('<meta property="og:locale" content="en_IN" />');
  });

  it('links both versions to each other with hreflang, on both pages', () => {
    for (const [name, html] of [['index.html', en], ['hi.html', hi]]) {
      expect(html, name).toContain('<link rel="alternate" hreflang="en" href="https://afklive.in/" />');
      expect(html, name).toContain('<link rel="alternate" hreflang="hi" href="https://afklive.in/hi" />');
      expect(html, name).toContain('<link rel="alternate" hreflang="x-default" href="https://afklive.in/" />');
    }
  });

  it('has a visible language switch on both pages, marking the current language', () => {
    expect(en).toContain('<!-- include:header -->');
    expect(hi).toContain('<!-- include:header-hi -->');
    expect(hi).toContain('<!-- include:footer-hi -->');
    const switchOf = (html: string) => html.match(/<nav aria-label="[^"]+" class="lang-switch"[\s\S]*?<\/nav>/)?.[0] ?? '';
    const enSwitch = switchOf(partial('header'));
    const hiSwitch = switchOf(partial('header-hi'));
    for (const s of [enSwitch, hiSwitch]) {
      expect(s).toContain('<a href="/" hreflang="en" lang="en"');
      expect(s).toContain('<a href="/hi" hreflang="hi" lang="hi"');
      expect(s).not.toMatch(/class="[^"]*hidden/); // visible at every width, not only in the menu
    }
    expect(enSwitch).toMatch(/hreflang="en" lang="en" aria-current="true">English</);
    expect(hiSwitch).toMatch(/hreflang="hi" lang="hi" aria-current="true">हिन्दी</);
  });

  it('has the same sections and script hooks as the English homepage', () => {
    const ids = (html: string) => [...html.matchAll(/<section id="([a-z-]+)"/g)].map((m) => m[1]);
    expect(ids(hi)).toEqual(ids(en));
    expect(ids(hi)).toEqual(['channels', 'pc', 'how', 'reliability', 'pricing', 'creators', 'faq']);
    const hooks = (html: string) => [...html.matchAll(/\s(data-[a-z-]+)(?=[\s=>])/g)].map((m) => m[1]).filter((h) => h !== 'data-niche').sort();
    expect(hooks(hi)).toEqual(hooks(en));
    const count = (html: string, re: RegExp) => html.match(re)?.length ?? 0;
    for (const re of [/role="tabpanel"/g, /<details>/g, /data-calc-input=/g, /<time class="tnum">/g, /class="step-title"/g]) {
      expect(count(hi, re), String(re)).toBe(count(en, re));
    }
  });

  it('shows the Hindi aarti example first in the hero', () => {
    document.body.innerHTML = hi.slice(hi.indexOf('<div class="hero-player"'), hi.indexOf('<section id="channels"'));
    const tabs = Array.from(document.querySelectorAll<HTMLElement>('[data-hero] [role="tab"]'));
    expect(tabs[0].dataset.niche).toBe('devotional');
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(document.querySelector<HTMLImageElement>('[data-hero-img]')!.getAttribute('src')).toBe('/site/img/frame-devotional.webp');
    // Switching tabs writes the Hindi title.
    const hero = mountHero(document.querySelector<HTMLElement>('[data-hero]')!, true, 'hi')!;
    hero.tabs.select(1, { user: true });
    expect(document.querySelector('[data-hero-title]')!.textContent).toBe(niches('hi').lofi.title);
  });

  it('keeps sign-up and sign-in in the English app', () => {
    for (const html of [hi, partial('header-hi'), partial('footer-hi')]) {
      for (const [, href] of html.matchAll(/href="([^"]+)"/g)) {
        if (/register|login|studio/.test(href)) expect(href).toMatch(/^\/(register|login)(\?|$)/);
      }
    }
    expect(hi).toContain('href="/register"');
    expect(partial('header-hi')).toContain('href="/login"');
  });

  it('shows the same plans and prices in both static pricing blocks', () => {
    const cards = (html: string) => html.slice(html.indexOf('<li class="flex flex-col'), html.lastIndexOf('</li>') + 5).replaceAll('</li>\n<li', '</li><li');
    expect(cards(partial('pricing'))).toBe(planCards(STATIC_PLANS, 'MONTHLY', 'en'));
    expect(cards(partial('pricing-hi'))).toBe(planCards(STATIC_PLANS, 'MONTHLY', 'hi'));
    const prices = (html: string) => [...html.matchAll(/₹[\d,]+/g)].map((m) => m[0]);
    expect(prices(partial('pricing-hi'))).toEqual(prices(partial('pricing')));
    expect(hi).toContain('<!-- include:pricing-hi -->');
  });

  it('fits a 375 px phone: 16 px gutters and nothing fixed wider than the screen', () => {
    expect(hi).toContain('<meta name="viewport" content="width=device-width, initial-scale=1" />');
    expect(hi.match(/class="[^"]*\bpx-4\b/g)?.length).toBe(en.match(/class="[^"]*\bpx-4\b/g)?.length);
    for (const html of [hi, partial('header-hi'), partial('footer-hi'), partial('pricing-hi')]) {
      for (const [, w] of html.matchAll(/\b(?:min-)?w-\[(\d+)px\]/g)) expect(Number(w)).toBeLessThanOrEqual(343);
    }
  });
});
