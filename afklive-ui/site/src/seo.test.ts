/// <reference types="vite/client" />
import { faqEntries, jsonLd, rupees, softwareApplication, text, withStructuredData } from './seo';
import { STATIC_PLANS } from './static-plans';
import siteCss from './site.css?raw';

// SEO checks on the site's source pages: structured data matches what the page shows (Google's rule),
// titles and descriptions fit search results, and pages link to each other.

const pages = import.meta.glob('../*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const partials = import.meta.glob('../partials/*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const page = (name: string) => pages[`../${name}`];
/** What the build makes of a page: partials included, then the jsonld placeholders filled. */
const built = (name: string) =>
  withStructuredData(page(name).replace(/<!-- include:([a-z-]+) -->/g, (_, p: string) => partials[`../partials/${p}.html`]), STATIC_PLANS);
const files = Object.keys(pages).map((p) => p.replace('../', ''));
const helpArticles = files.filter((f) => /^help-.+\.html$/.test(f));
const useCases = files.filter((f) => /^for-.+\.html$/.test(f));
const DEVANAGARI = /[ऀ-ॿ]/;

type Json = Record<string, any>;
const ofType = (html: string, type: string): Json[] =>
  jsonLd(html).flatMap((d) => (d['@graph'] as Json[] | undefined) ?? [d]).filter((d) => d['@type'] === type);
const canonical = (html: string) => html.match(/<link rel="canonical" href="([^"]+)"/)?.[1];
const h1 = (html: string) => text(html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)?.[1] ?? '');

describe('structured data', () => {
  it('names the organisation and the site on both homepages', () => {
    for (const [name, lang] of [['index.html', 'en-IN'], ['hi.html', 'hi-IN']]) {
      const html = built(name);
      const [org] = ofType(html, 'Organization');
      const [site] = ofType(html, 'WebSite');
      expect(org, name).toMatchObject({ '@id': 'https://afklive.in/#organization', name: 'AFK Live', url: 'https://afklive.in/', email: 'support@afklive.in' });
      expect(site, name).toMatchObject({ name: 'AFK Live', url: 'https://afklive.in/', inLanguage: lang, publisher: { '@id': org['@id'] } });
      expect(jsonLd(html)[0]['@context']).toBe('https://schema.org');
    }
  });

  it('prices the app in rupees from the same catalogue as the visible plan cards', () => {
    const app = softwareApplication(STATIC_PLANS);
    expect(app.offers).toHaveLength(STATIC_PLANS.length);
    expect((app.offers as Json[]).map((o) => [o.name, o.price, o.priceCurrency])).toEqual([
      ['Free', '0', 'INR'],
      ['Creator', '499', 'INR'],
      ['Pro', '1499', 'INR'],
      ['Agency', '3999', 'INR'],
    ]);
    for (const name of ['index.html', 'pricing.html']) {
      const html = built(name);
      const [found] = ofType(html, 'SoftwareApplication');
      expect(found, name).toMatchObject({ name: 'AFK Live', applicationCategory: 'MultimediaApplication', operatingSystem: 'Web' });
      // Every price in the markup is on the page, as the card shows it (₹1,499).
      for (const p of STATIC_PLANS.filter((x) => x.monthly > 0)) {
        expect(html, `${name} ${p.name}`).toContain(`>₹${Number(rupees(p.monthly)).toLocaleString('en-IN')}<`);
      }
    }
    expect(ofType(built('hi.html'), 'SoftwareApplication')).toHaveLength(0);
  });

  it('marks up each visible FAQ, once per site', () => {
    const asked: string[] = [];
    for (const [name, count] of [['index.html', 6], ['hi.html', 6], ['pricing.html', 7]] as const) {
      const html = built(name);
      const [faq] = ofType(html, 'FAQPage');
      expect(faq.mainEntity, name).toHaveLength(count);
      for (const q of faq.mainEntity as Json[]) {
        expect(q['@type']).toBe('Question');
        expect(q.acceptedAnswer['@type']).toBe('Answer');
        expect(q.name.length).toBeGreaterThan(5);
        expect(q.acceptedAnswer.text.length).toBeGreaterThan(20);
        // The question is shown on the page, word for word.
        expect(text(html), `${name}: ${q.name}`).toContain(q.name);
        if (name !== 'hi.html') asked.push(q.name);
      }
      if (name === 'hi.html') for (const q of faq.mainEntity as Json[]) expect(q.name).toMatch(DEVANAGARI);
    }
    // Google: never mark up the same question on two pages.
    expect(new Set(asked).size).toBe(asked.length);
    expect(faqEntries(page('pricing.html')).find(([q]) => q.includes('US dollars'))?.[1]).toContain('$ USD');
  });

  it('gives help articles a breadcrumb and an article matching the page', () => {
    for (const f of helpArticles) {
      const html = page(f);
      const url = canonical(html)!;
      const [crumbs] = ofType(html, 'BreadcrumbList');
      expect(crumbs.itemListElement.map((i: Json) => [i.position, i.name, i.item]), f).toEqual([
        [1, 'Home', 'https://afklive.in/'],
        [2, 'Help', 'https://afklive.in/help'],
        [3, h1(html), url],
      ]);
      const [article] = ofType(html, 'TechArticle');
      expect(article, f).toMatchObject({ headline: h1(html), url, mainEntityOfPage: url, author: { '@type': 'Organization', name: 'AFK Live' } });
      expect(article.dateModified).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(html, f).toMatch(/<nav aria-label="Breadcrumb"[^>]*><a href="\/"[^>]*>Home<\/a>.*<a href="\/help"/);
    }
    expect(ofType(page('help.html'), 'BreadcrumbList')[0].itemListElement).toHaveLength(2);
  });

  it('gives use-case pages a breadcrumb that is also on the page', () => {
    for (const f of useCases) {
      const html = page(f);
      const [crumbs] = ofType(html, 'BreadcrumbList');
      const [home, self] = crumbs.itemListElement as Json[];
      expect(home).toMatchObject({ position: 1, name: 'Home', item: 'https://afklive.in/' });
      expect(self).toMatchObject({ position: 2, item: canonical(html) });
      expect(html, f).toContain(`aria-current="page">${self.name}</span></nav>`);
    }
  });

  it('leaves no placeholder unfilled and only parseable JSON-LD', () => {
    for (const f of files) {
      const html = f.match(/^(index|hi|pricing)\.html$/) ? built(f) : page(f);
      expect(html, f).not.toContain('<!-- jsonld:');
      expect(() => jsonLd(html), f).not.toThrow();
    }
  });
});

describe('titles, descriptions and headings', () => {
  it('fit in search results', () => {
    for (const f of files) {
      const html = page(f);
      const title = text(html.match(/<title>([^<]+)<\/title>/)![1]);
      const desc = text(html.match(/<meta name="description" content="([^"]+)"/)![1]);
      expect(title.length, `${f}: ${title}`).toBeLessThanOrEqual(60);
      expect(desc.length, `${f}: ${desc}`).toBeLessThanOrEqual(160);
      expect(desc.length, `${f}: ${desc}`).toBeGreaterThanOrEqual(DEVANAGARI.test(desc) ? 60 : 90);
      expect(html.match(/<h1[\s>]/g), f).toHaveLength(1);
    }
  });

  it('say what people search for on the main pages', () => {
    const title = (f: string) => page(f).match(/<title>([^<]+)<\/title>/)![1];
    expect(title('index.html')).toMatch(/24\/7 YouTube live.*without a PC/);
    expect(title('pricing.html')).toMatch(/24\/7 YouTube live.*₹/);
    expect(title('for-devotional.html')).toMatch(/aarti.*bhajan/i);
    expect(title('for-mobile-gaming.html')).toMatch(/BGMI.*Free Fire.*phone.*overlays/);
    expect(title('hi.html')).toMatch(/24 घंटे/);
    expect(h1(page('index.html'))).toContain('YouTube 24/7');
  });

  it('never skip from the page heading to a card heading (pricing)', () => {
    const html = built('pricing.html');
    expect(html.indexOf('<h2')).toBeLessThan(html.indexOf('<h3'));
    expect(page('help.html').match(/<h2[\s>]/g)!.length).toBeGreaterThanOrEqual(3);
  });

  it('keeps /status out of search and gives legal pages link previews', () => {
    expect(page('status.html')).toContain('<meta name="robots" content="noindex" />');
    for (const f of ['privacy.html', 'terms.html']) {
      for (const p of ['og:title', 'og:description', 'og:url']) expect(page(f), `${f} ${p}`).toContain(`property="${p}"`);
    }
    expect(partials['../partials/head.html']).toContain('<meta property="og:site_name" content="AFK Live" />');
  });
});

describe('internal links', () => {
  it('lists every use case in the footer', () => {
    for (const f of useCases) {
      const slug = f.replace(/^for-|\.html$/g, '');
      expect(partials['../partials/footer.html'], slug).toContain(`href="/for/${slug}"`);
      expect(partials['../partials/footer-hi.html'], slug).toContain(`href="/for/${slug}"`);
    }
  });

  it('links each use case to its help articles, and each article back', () => {
    for (const f of useCases) {
      const links = new Set(Array.from(page(f).matchAll(/href="\/help\/([a-z0-9-]+)"/g), (m) => m[1]));
      expect(links.size, f).toBeGreaterThanOrEqual(2);
      expect(links.size, f).toBeLessThanOrEqual(3);
    }
    for (const f of helpArticles) expect(page(f), f).toMatch(/href="\/for\/[a-z-]+"/);
  });
});

describe('fonts', () => {
  it('loads Devanagari only on the Hindi page', () => {
    expect(siteCss).not.toContain("@import '@fontsource-variable/noto-sans-devanagari'");
    expect(page('hi.html')).toContain('href="./src/devanagari.css"');
    for (const f of files.filter((x) => x !== 'hi.html')) expect(page(f), f).not.toContain('devanagari.css');
  });
});
