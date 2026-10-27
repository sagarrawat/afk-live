/// <reference types="vite/client" />
import { alternates, jsonLd, text, withLangSwitch, withStructuredData } from './seo';
import { STATIC_PLANS } from './static-plans';
import { DEFAULTS, formula, monthlyCost } from './calc';
import publicPages from '../../../afklive-web/src/main/java/com/afklive/streamer/endpoint/PublicPages.java?raw';
import homeController from '../../../afklive-web/src/main/java/com/afklive/streamer/endpoint/HomeController.java?raw';
import securityConfig from '../../../afklive-web/src/main/java/com/afklive/streamer/config/SecurityConfig.java?raw';
import siteConfig from '../../vite.site.config.ts?raw';

// The help articles in Hindi (/hi/help, site/help.py HI_ARTICLES), written for Hindi and Hinglish
// searches: served, in the sitemap, paired with their English twins by hreflang both ways, marked up
// to match what they show, linked into the rest of the site, and honest about costs.

const pages = import.meta.glob('../*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const partials = import.meta.glob('../partials/*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const page = (name: string) => pages[`../${name}`];
const built = (name: string) =>
  withLangSwitch(withStructuredData(page(name).replace(/<!-- include:([a-z-]+) -->/g, (_, p: string) => partials[`../partials/${p}.html`]), STATIC_PLANS));
const files = Object.keys(pages).map((p) => p.replace('../', ''));
const DEVANAGARI = /[ऀ-ॿ]/;

const slugs = (publicPages.match(/HINDI_HELP_ARTICLES = List\.of\(([^;]+)\);/)?.[1] ?? '').match(/"[^"]+"/g)!.map((s) => s.slice(1, -1));
const articleFiles = slugs.map((s) => `hi-help-${s}.html`);
const hindiFiles = ['hi-help.html', ...articleFiles];

/** The site file a public path is served from (the conventions in PublicPages). */
function fileFor(path: string): string | undefined {
  const known: Record<string, string> = { '/': 'index.html', '/hi': 'hi.html', '/pricing': 'pricing.html', '/help': 'help.html', '/hi/help': 'hi-help.html' };
  if (known[path]) return known[path];
  const m = path.match(/^\/(hi\/help|help|for)\/([a-z0-9-]+)$/);
  if (!m) return undefined;
  return `${m[1].replace('/', '-')}-${m[2]}.html`;
}

type Json = Record<string, any>;
const ofType = (html: string, type: string): Json[] =>
  jsonLd(html).flatMap((d) => (d['@graph'] as Json[] | undefined) ?? [d]).filter((d) => d['@type'] === type);
const canonical = (html: string) => html.match(/<link rel="canonical" href="([^"]+)"/)?.[1];
const h1 = (html: string) => text(html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)?.[1] ?? '');
const hrefs = (html: string) => Array.from(html.matchAll(/href="([^"]+)"/g), (m) => m[1]);

describe('Hindi help pages', () => {
  it('has the four articles the research asked for, and no strays', () => {
    expect(slugs).toEqual(['24-ghante-live', 'mobile-se-live', 'recorded-video-live', 'youtube-stream-key']);
    expect(files.filter((f) => /^hi-help/.test(f)).sort()).toEqual([...hindiFiles].sort());
  });

  it('is built, routed, open to everyone signed out, and in the sitemap', () => {
    expect(siteConfig).toContain('hi-help');
    for (const f of hindiFiles) expect(f, f).toMatch(/^(hi-help-[a-z0-9-]+|hi-help)\.html$/);
    expect(homeController).toContain('@GetMapping("/hi/help")');
    expect(homeController).toContain('@GetMapping("/hi/help/{slug}")');
    expect(homeController).toContain('forward:/site/hi-help-');
    expect(securityConfig).toContain('"/hi/help", "/hi/help/**"');
    expect(publicPages).toContain('pages.put("/hi/help", "hi-help.html")');
    expect(publicPages).toContain('pages.put("/hi/help/" + slug, "hi-help-" + slug + ".html")');
  });

  it('is in Hindi: language, Devanagari title, description and heading, Hindi header and footer', () => {
    for (const f of hindiFiles) {
      const html = page(f);
      expect(html, f).toContain('<html lang="hi">');
      expect(html, f).toContain('<meta property="og:locale" content="hi_IN" />');
      expect(html.match(/<title>([^<]+)<\/title>/)![1], f).toMatch(DEVANAGARI);
      expect(html.match(/name="description" content="([^"]+)"/)![1], f).toMatch(DEVANAGARI);
      expect(h1(html), f).toMatch(DEVANAGARI);
      expect(html, f).toContain('<!-- include:header-hi -->');
      expect(html, f).toContain('<!-- include:footer-hi -->');
      expect(canonical(html), f).toBe(`https://afklive.in${f === 'hi-help.html' ? '/hi/help' : `/hi/help/${f.slice(8, -5)}`}`);
    }
  });

  it('puts the Hinglish search words people type in the description', () => {
    const desc = (f: string) => page(f).match(/name="description" content="([^"]+)"/)![1];
    expect(desc('hi-help-24-ghante-live.html')).toContain('24 ghante live kaise kare');
    expect(desc('hi-help-recorded-video-live.html')).toContain('youtube par recorded video live kaise kare');
    expect(desc('hi-help-youtube-stream-key.html')).toContain('youtube stream key kahan milega');
    expect(desc('hi-help-mobile-se-live.html')).toContain('mobile se youtube live kaise kare');
    expect(page('hi-help-24-ghante-live.html').match(/<title>([^<]+)<\/title>/)![1]).toContain('24 घंटे');
  });
});

describe('hreflang', () => {
  const all = files.filter((f) => f !== 'status.html');

  it('pairs pages both ways: each names itself and its twin, and the twin names it back', () => {
    let pairs = 0;
    for (const f of all) {
      const alt = alternates(page(f));
      if (!Object.keys(alt).length) continue;
      expect(Object.keys(alt).sort(), f).toEqual(['en', 'hi', 'x-default']);
      expect(alt['x-default'], f).toBe(alt.en);
      const self = canonical(page(f))!.replace('https://afklive.in', '');
      expect([alt.en, alt.hi], f).toContain(self);
      const twin = self === alt.en ? alt.hi : alt.en;
      const twinFile = fileFor(twin);
      expect(twinFile && page(twinFile), `${f} → ${twin}`).toBeTruthy();
      expect(alternates(page(twinFile!)), `${twinFile} names ${f} back`).toEqual(alt);
      expect(page(twinFile!).includes('<html lang="hi">'), twinFile).toBe(twin === alt.hi);
      pairs++;
    }
    // The homepages, the help centres and the stream-key article, each counted from both sides.
    expect(pairs).toBe(6);
    expect(alternates(page('hi-help-youtube-stream-key.html'))).toEqual({ en: '/help/youtube-stream-key', hi: '/hi/help/youtube-stream-key', 'x-default': '/help/youtube-stream-key' });
    expect(alternates(page('hi-help.html')).en).toBe('/help');
  });

  it('only pairs real translations: the other Hindi articles have no English twin', () => {
    for (const s of ['24-ghante-live', 'mobile-se-live', 'recorded-video-live']) expect(alternates(page(`hi-help-${s}.html`)), s).toEqual({});
  });

  it("points the header's language switch at the twin, or at the homepages when there is none", () => {
    const switchOf = (html: string) => html.match(/<nav [^>]*class="lang-switch"[\s\S]*?<\/nav>/)![0];
    expect(switchOf(built('hi-help-youtube-stream-key.html'))).toContain('<a href="/help/youtube-stream-key" hreflang="en"');
    expect(switchOf(built('hi-help-youtube-stream-key.html'))).toContain('<a href="/hi/help/youtube-stream-key" hreflang="hi"');
    expect(switchOf(built('help-youtube-stream-key.html'))).toContain('<a href="/hi/help/youtube-stream-key" hreflang="hi"');
    expect(switchOf(built('help.html'))).toContain('<a href="/hi/help" hreflang="hi"');
    expect(switchOf(built('index.html'))).toContain('<a href="/hi" hreflang="hi"');
    expect(switchOf(built('hi-help-mobile-se-live.html'))).toContain('<a href="/" hreflang="en"');
    expect(switchOf(built('help-music-on-your-live.html'))).toContain('<a href="/hi" hreflang="hi"');
  });
});

describe('structured data on Hindi help', () => {
  it('gives each article a Hindi breadcrumb and a TechArticle matching the page', () => {
    for (const f of articleFiles) {
      const html = built(f);
      const url = canonical(html)!;
      const [crumbs] = ofType(html, 'BreadcrumbList');
      expect(crumbs.itemListElement.map((i: Json) => [i.position, i.name, i.item]), f).toEqual([
        [1, 'होम', 'https://afklive.in/hi'],
        [2, 'मदद', 'https://afklive.in/hi/help'],
        [3, h1(html), url],
      ]);
      // The same breadcrumb is on the page.
      expect(html, f).toMatch(/<nav aria-label="Breadcrumb"[^>]*><a href="\/hi"[^>]*>होम<\/a>.*<a href="\/hi\/help"[^>]*>मदद<\/a>/);
      const [article] = ofType(html, 'TechArticle');
      expect(article, f).toMatchObject({ headline: h1(html), url, mainEntityOfPage: url, inLanguage: 'hi', author: { '@type': 'Organization', name: 'AFK Live' } });
      expect(article.description, f).toBe(page(f).match(/name="description" content="([^"]+)"/)![1]);
    }
    expect(ofType(built('hi-help.html'), 'BreadcrumbList')[0].itemListElement).toHaveLength(2);
  });

  it('marks up a FAQ only where the questions are shown, word for word', () => {
    for (const f of hindiFiles) {
      const html = built(f);
      const shown = page(f).includes('data-faq');
      const faqs = ofType(html, 'FAQPage');
      expect(faqs.length, f).toBe(shown ? 1 : 0);
      for (const q of faqs[0]?.mainEntity ?? []) {
        expect(text(html), f).toContain(q.name);
        expect(text(html), f).toContain(q.acceptedAnswer.text);
      }
    }
    for (const f of articleFiles) expect(page(f), f).toContain('data-faq');
  });
});

describe('links', () => {
  const existing = new Set(slugs);

  it('only links Hindi articles that exist, from any page', () => {
    for (const f of files) {
      for (const [, slug] of page(f).matchAll(/href="\/hi\/help\/([^"#?]+)/g)) expect(existing, `${f} links /hi/help/${slug}`).toContain(slug);
    }
  });

  it('links each article to /hi, the Hindi help, another article, a use-case page, pricing and sign-up', () => {
    for (const f of articleFiles) {
      const links = hrefs(page(f));
      expect(links, f).toContain('/hi');
      expect(links, f).toContain('/hi/help');
      expect(links.filter((h) => /^\/hi\/help\/[a-z0-9-]+/.test(h) && !canonical(page(f))!.endsWith(h)).length, f).toBeGreaterThanOrEqual(1);
      expect(links.some((h) => /^\/for\/[a-z-]+$/.test(h)), f).toBe(true);
      expect(links, f).toContain('/hi#pricing');
      expect(page(f), f).toContain('<a href="/register" hreflang="en" data-funnel="start" class="btn-primary');
    }
  });

  it('marks links to English-only pages, and keeps sign-up in the English app', () => {
    for (const f of hindiFiles) {
      for (const m of page(f).matchAll(/<a href="(\/(?:for|help|pricing)[^"]*)"([^>]*)>([\s\S]*?)<\/a>(.{0,60})/g)) {
        const [, href, attrs, label, after] = m;
        if (href === '/help/youtube-stream-key' && label === 'Read in English') continue;
        expect(attrs, `${f} ${href}`).toContain('hreflang="en"');
        expect(`${label}${after}`, `${f} ${href}`).toContain('(अंग्रेज़ी में)');
      }
    }
  });

  it('is linked from /hi, its header and footer, and the matching English articles', () => {
    for (const s of slugs) expect(page('hi.html'), s).toContain(`href="/hi/help/${s}"`);
    expect(page('hi.html')).toContain('href="/hi/help"');
    expect(partials['../partials/header-hi.html']).toContain('href="/hi/help"');
    expect(partials['../partials/footer-hi.html']).toContain('href="/hi/help"');
    expect(page('help-youtube-stream-key.html')).toContain('<a href="/hi/help/youtube-stream-key" hreflang="hi" class="link">हिन्दी में पढ़ें</a>');
    expect(page('help-loops-and-youtube-rules.html')).toContain('href="/hi/help/24-ghante-live"');
    expect(page('help-phone-streaming.html')).toContain('href="/hi/help/mobile-se-live"');
    expect(page('help.html')).toContain('href="/hi/help"');
  });
});

describe('honest numbers', () => {
  const pillar = text(page('hi-help-24-ghante-live.html'));

  it("uses the homepage calculator's electricity assumptions and the plans as they are", () => {
    expect(pillar).toContain(formula(DEFAULTS, 'hi'));
    expect(pillar).toContain(`₹${monthlyCost(DEFAULTS)}`);
    const creator = STATIC_PLANS.find((p) => p.id === 'ESSENTIALS')!;
    const free = STATIC_PLANS.find((p) => p.id === 'FREE')!;
    expect(pillar).toContain(`${creator.monthlyLabel} Creator plan`);
    expect(pillar).toContain(`Free plan में हर महीने ${free.monthlyHours} घंटे (${free.maxHeight}p`);
    for (const f of hindiFiles) {
      const t = text(page(f));
      if (t.includes('Creator plan ₹')) expect(t, f).toContain(`Creator plan ${creator.monthlyLabel} महीना`);
      if (/\d+ घंटे फ़्री/.test(t)) expect(t, f).toContain(`${free.monthlyHours} घंटे फ़्री`);
    }
  });

  it('never promises monetisation, views or subscribers', () => {
    for (const f of hindiFiles) {
      const t = text(page(f));
      expect(t, f).not.toMatch(/(गारंटी|पक्का) (से )?(monetis|कमाई|views|subscribers)/);
    }
    expect(pillar).toContain('Monetisation का वादा कोई नहीं कर सकता');
    expect(pillar).toContain('इसकी कोई गारंटी नहीं');
  });

  it("cites YouTube's own help pages for the mobile rules", () => {
    const mobile = page('hi-help-mobile-se-live.html');
    for (const id of ['9228390', '2474026', '2907883']) expect(mobile, id).toContain(`https://support.google.com/youtube/answer/${id}`);
    expect(text(mobile)).toContain('कम से कम 50 subscribers');
    expect(text(mobile)).toContain('ये रास्ता AFK Live के बिना भी काम करता है');
  });
});

describe('phones', () => {
  it('fits 375 px: viewport, 16 px gutters, nothing fixed wider than the screen', () => {
    for (const f of hindiFiles) {
      const html = page(f);
      expect(html, f).toContain('<meta name="viewport" content="width=device-width, initial-scale=1" />');
      expect(html, f).toMatch(/<main class="[^"]*\bpx-4\b/);
      for (const [, w] of html.matchAll(/\b(?:min-)?w-\[(\d+)px\]/g)) expect(Number(w), f).toBeLessThanOrEqual(343);
    }
  });
});
