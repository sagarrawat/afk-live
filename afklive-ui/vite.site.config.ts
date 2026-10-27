import { defineConfig, type Plugin } from 'vite';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import Beasties from 'beasties';
import { STATIC_PLANS } from './site/src/static-plans';
import { withLangSwitch, withStructuredData } from './site/src/seo';

const outDir = resolve(__dirname, '../afklive-web/target/classes/static/site');

/** `<!-- include:header -->` pulls in site/partials/header.html, so pages share one header and footer. */
function partials(): Plugin {
  return {
    name: 'site-partials',
    transformIndexHtml: (html) =>
      html.replace(/<!-- include:([a-z-]+) -->/g, (_, name: string) => readFileSync(resolve(__dirname, 'site/partials', `${name}.html`), 'utf8')),
  };
}

/**
 * `<!-- jsonld:... -->` placeholders become JSON-LD from the page's own content, and the language switch
 * points at the page's twin in the other language when it has one (site/src/seo.ts).
 */
function structuredData(): Plugin {
  return { name: 'site-structured-data', transformIndexHtml: (html) => withLangSwitch(withStructuredData(html, STATIC_PLANS)) };
}

/**
 * Speed (mobile LCP): preload the two Latin fonts every page uses (Inter and Schibsted Grotesk), so
 * they arrive with the HTML rather than after the stylesheet. Devanagari is only on /hi (devanagari.css),
 * where it's the main text font, so it's preloaded there too (otherwise the swap moves the text: CLS).
 */
function preloadFonts(): Plugin {
  const latin = [/^assets\/schibsted-grotesk-latin-wght-normal-[\w-]+\.woff2$/, /^assets\/inter-latin-wght-normal-[\w-]+\.woff2$/];
  const devanagari = /^assets\/noto-sans-devanagari-devanagari-wght-normal-[\w-]+\.woff2$/;
  return {
    name: 'site-preload-fonts',
    transformIndexHtml: {
      order: 'post',
      handler: (html, ctx) => {
        const files = Object.keys(ctx.bundle ?? {});
        const wanted = /<html lang="hi"/.test(html) ? [devanagari, ...latin] : latin;
        const links = wanted
          .map((re) => files.find((f) => re.test(f)))
          .filter((f): f is string => Boolean(f))
          .map((f) => `<link rel="preload" as="font" type="font/woff2" href="/site/${f}" crossorigin>`);
        return html.replace('</title>', `</title>\n  ${links.join('\n  ')}`);
      },
    },
  };
}

/**
 * When each page's file last changed in git, for <lastmod> in /sitemap.xml (SitemapController). Only
 * from full history: in a shallow clone every file would get the newest commit's date, and a checkout's
 * file times are just as wrong, so then pages get no date (Google ignores lastmod it can't trust).
 * The production build (web-build.yml) checks out full history.
 */
function lastModified(pages: string[]): Plugin {
  const git = (...args: string[]) => execFileSync('git', args, { cwd: resolve(__dirname, 'site'), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  const when = (file: string): string | null => {
    try {
      if (git('rev-parse', '--is-shallow-repository') !== 'false') return null;
      if (git('status', '--porcelain', '--', file)) return null; // edited and not committed yet: no honest date
      return git('log', '-1', '--format=%cI', '--', file) || null;
    } catch {
      return null; // no git here
    }
  };
  return {
    name: 'site-lastmod',
    apply: 'build',
    generateBundle() {
      const dates = Object.fromEntries(pages.map((f) => [f, when(f)]).filter(([, d]) => d));
      this.emitFile({ type: 'asset', fileName: 'lastmod.json', source: JSON.stringify(dates, null, 1) });
    },
  };
}

/**
 * The pages link a hashed stylesheet (cached for a year). The Thymeleaf sign-in and error pages link a
 * stable copy, /site/site.css, which browsers recheck. assets/site.css is the old stable address, kept
 * for one release so pages served by the previous version during a deploy keep their styles.
 */
function stableStylesheet(): Plugin {
  return {
    name: 'site-stable-css',
    apply: 'build',
    generateBundle(_, bundle) {
      // The main stylesheet: the largest one (the other is /hi's few lines of Devanagari @font-face).
      const css = Object.values(bundle)
        .filter((a) => a.type === 'asset' && a.fileName.endsWith('.css'))
        .sort((x, y) => String((y as { source: unknown }).source).length - String((x as { source: unknown }).source).length)[0];
      if (!css || css.type !== 'asset') throw new Error('site stylesheet not found in the bundle');
      for (const fileName of ['site.css', 'assets/site.css']) this.emitFile({ type: 'asset', fileName, source: css.source });
    },
  };
}

/**
 * Speed: each page gets the CSS its own markup uses inlined in <head>, and loads the full stylesheet
 * without blocking the first paint (Beasties). Runs on the written files, after Vite.
 */
function criticalCss(): Plugin {
  return {
    name: 'site-critical-css',
    apply: 'build',
    async writeBundle() {
      const beasties = new Beasties({ path: outDir, publicPath: '/site/', preload: 'media', inlineThreshold: 2048, inlineFonts: true, preloadFonts: false, pruneSource: false, logLevel: 'warn' });
      for (const f of readdirSync(outDir).filter((n) => n.endsWith('.html'))) {
        const file = join(outDir, f);
        writeFileSync(file, await beasties.process(readFileSync(file, 'utf8')));
      }
    },
  };
}

const pages = readdirSync(resolve(__dirname, 'site')).filter((f) => f.endsWith('.html'));

// The public site (MK-1, MK-2) and the sign-in pages' stylesheet: plain HTML pages, built for speed
// and search. Served by Spring at /, /pricing and /login (templates link /site/site.css).
export default defineConfig({
  plugins: [partials(), structuredData(), preloadFonts(), lastModified(pages), stableStylesheet(), criticalCss()],
  root: resolve(__dirname, 'site'),
  base: '/site/',
  publicDir: resolve(__dirname, 'site/public'),
  build: {
    outDir,
    emptyOutDir: true,
    rollupOptions: {
      input: {
        index: resolve(__dirname, 'site/index.html'),
        // MK-1: the homepage in Hindi (served at /hi).
        hi: resolve(__dirname, 'site/hi.html'),
        pricing: resolve(__dirname, 'site/pricing.html'),
        contact: resolve(__dirname, 'site/contact.html'),
        privacy: resolve(__dirname, 'site/privacy.html'),
        'refund-policy': resolve(__dirname, 'site/refund-policy.html'),
        terms: resolve(__dirname, 'site/terms.html'),
        // RE-7: the public status page (served at /status).
        status: resolve(__dirname, 'site/status.html'),
        // MK-3: the use-case pages (site/use-cases.py); MK-4: the help centre, in English and Hindi (site/help.py).
        ...Object.fromEntries(
          readdirSync(resolve(__dirname, 'site'))
            .filter((f) => /^(for|help|hi-help)-[a-z0-9-]+\.html$/.test(f) || f === 'help.html' || f === 'hi-help.html')
            .map((f) => [f.replace('.html', ''), resolve(__dirname, 'site', f)]),
        ),
      },
      output: {
        // Content-hashed names, so Spring can let browsers keep them for a year (MvcConfig).
        assetFileNames: 'assets/[name]-[hash][extname]',
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
      },
    },
  },
});
