import { defineConfig, type Plugin } from 'vite';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** `<!-- include:header -->` pulls in site/partials/header.html, so pages share one header and footer. */
function partials(): Plugin {
  return {
    name: 'site-partials',
    transformIndexHtml: (html) =>
      html.replace(/<!-- include:([a-z-]+) -->/g, (_, name: string) => readFileSync(resolve(__dirname, 'site/partials', `${name}.html`), 'utf8')),
  };
}

// The public site (MK-1, MK-2) and the sign-in pages' stylesheet: plain HTML pages, built for speed
// and search. Served by Spring at /, /pricing and /login (templates link /site/assets/site.css).
export default defineConfig({
  plugins: [partials()],
  root: resolve(__dirname, 'site'),
  base: '/site/',
  publicDir: resolve(__dirname, 'site/public'),
  build: {
    outDir: resolve(__dirname, '../afklive-web/target/classes/static/site'),
    emptyOutDir: true,
    rollupOptions: {
      input: {
        index: resolve(__dirname, 'site/index.html'),
        pricing: resolve(__dirname, 'site/pricing.html'),
        privacy: resolve(__dirname, 'site/privacy.html'),
        terms: resolve(__dirname, 'site/terms.html'),
        // RE-7: the public status page (served at /status).
        status: resolve(__dirname, 'site/status.html'),
        // MK-3: the use-case pages (site/use-cases.py).
        ...Object.fromEntries(
          readdirSync(resolve(__dirname, 'site'))
            .filter((f) => /^for-[a-z-]+\.html$/.test(f))
            .map((f) => [f.replace('.html', ''), resolve(__dirname, 'site', f)]),
        ),
      },
      output: {
        // Stable names: the Thymeleaf sign-in pages link the stylesheet directly.
        assetFileNames: (a) => (a.names?.some((n) => n.endsWith('.css')) ? 'assets/site.css' : 'assets/[name]-[hash][extname]'),
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
      },
    },
  },
});
