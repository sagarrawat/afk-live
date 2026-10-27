/// <reference types="vite/client" />
import { HELP } from '@/lib/help';
import publicPages from '../../../afklive-web/src/main/java/com/afklive/streamer/endpoint/PublicPages.java?raw';
import siteConfig from '../../vite.site.config.ts?raw';

// MK-4: the help centre is plain HTML written by site/help.py. These checks stand in for a link
// checker: every article the app links to exists, is built, routed, listed in the sitemap and on the
// index, and every help link inside the site points at a real article.

const pages = import.meta.glob('../*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const appSources = import.meta.glob(['../../src/**/*.{ts,tsx}', '!../../src/**/*.test.{ts,tsx}', '!../../src/lib/help.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const page = (name: string) => pages[`../${name}`];
const slugs = Object.values(HELP).map((p) => p.replace('/help/', ''));
const articleFiles = Object.keys(pages)
  .map((p) => p.replace('../', ''))
  .filter((f) => /^help-[a-z0-9-]+\.html$/.test(f));

describe('help centre pages', () => {
  it('has a page for every article the app links to, and no strays', () => {
    expect(articleFiles.sort()).toEqual(slugs.map((s) => `help-${s}.html`).sort());
    expect(page('help.html')).toBeDefined();
  });

  it('builds them, and serves and lists them in the sitemap (PublicPages)', () => {
    expect(siteConfig).toMatch(/\(for\|help\|hi-help\)-/);
    expect(siteConfig).toContain("f === 'help.html'");
    expect(publicPages).toContain('pages.put("/help", "help.html")');
    const listed = publicPages.match(/HELP_ARTICLES = List\.of\(([^;]+)\);/)?.[1] ?? '';
    for (const slug of slugs) expect(listed, slug).toContain(`"${slug}"`);
  });

  it('links every article from the searchable index', () => {
    const index = page('help.html');
    expect(index).toContain('data-help-search');
    expect(index).toContain('type="search"');
    for (const slug of slugs) expect(index, slug).toContain(`href="/help/${slug}"`);
  });

  it('only links to articles that exist, from any site page', () => {
    for (const [file, html] of Object.entries(pages)) {
      for (const [, slug] of html.matchAll(/href="\/help\/([^"#?]+)/g)) {
        expect(slugs, `${file} links /help/${slug}`).toContain(slug);
      }
    }
  });

  it('uses the shared head, header and footer, and fits a phone', () => {
    for (const f of ['help.html', ...articleFiles]) {
      const html = page(f);
      expect(html, f).toContain('<meta name="viewport" content="width=device-width, initial-scale=1" />');
      expect(html, f).toContain('<!-- include:header -->');
      expect(html, f).toContain('<!-- include:footer -->');
      expect(html, f).toMatch(/<link rel="canonical" href="https:\/\/afklive\.in\/help/);
      expect(html, f).toContain('px-4'); // a 16 px gutter at 375 px
    }
    expect(page('index.html')).toBeDefined();
  });

  it('links each article from somewhere in the app', () => {
    const app = Object.values(appSources).join('\n');
    for (const key of Object.keys(HELP)) {
      expect(app, `HELP.${key}`).toMatch(new RegExp(`HELP\\.${key}\\b`));
    }
  });

  it('is linked from the site header and footer', () => {
    const partials = import.meta.glob('../partials/{header,footer}.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
    for (const [f, html] of Object.entries(partials)) expect(html, f).toContain('href="/help"');
  });
});
