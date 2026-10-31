/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';
// The stylesheet is read from disk: a `?raw` CSS import is empty under the test runner (it skips CSS).
// The project has no Node types, hence the cast; tests run from afklive-ui (npm test, and Maven's ui-test).
const fs = (await import(/* @vite-ignore */ `node:${'fs'}`)) as { readFileSync(path: string, encoding: 'utf8'): string };
const siteCss = fs.readFileSync('site/src/site.css', 'utf8');

// How the public site reads on a phone (360 to 412 px), checked against the real pages and stylesheet.

const pages = import.meta.glob('../*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const partials = import.meta.glob('../partials/*.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

/** The declarations of the first rule whose selector list is exactly `selector`. */
function rule(selector: string) {
  const m = siteCss.match(new RegExp(`(^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`));
  if (!m) throw new Error(`no rule for ${selector}`);
  return m[2];
}

describe('phone layout of the public site', () => {
  it('every question list is a styled .faq (a +/− per question, never the browser’s triangles)', () => {
    let lists = 0;
    for (const [file, html] of Object.entries(pages)) {
      for (const m of html.matchAll(/<div class="([^"]*)" data-faq>/g)) {
        lists++;
        expect(m[1].split(/\s+/), file).toContain('faq');
      }
      // A <summary> with its own classes is a sign of a hand-styled list that kept the default marker.
      expect(html, file).not.toMatch(/<summary class=/);
    }
    expect(lists).toBeGreaterThan(5);
    expect(pages['../tools-youtube-live-title-generator.html']).toContain('class="faq mt-4" data-faq');
    expect(pages['../hi-tools-youtube-live-title-generator.html']).toContain('class="faq mt-4" data-faq');
  });

  it('the channel tabs keep their gap under the heading (the scroller’s focus-ring margin is sides only)', () => {
    const tabs = rule('.hero-tabs,\n.niche-tabs');
    expect(tabs).not.toMatch(/(^|[\s;])margin\s*:/); // a shorthand would beat mt-8 and glue the tabs to the heading
    expect(tabs).toMatch(/margin-left:\s*-3px/);
    expect(pages['../index.html']).toMatch(/class="niche-tabs mt-8"/);
    expect(pages['../hi.html']).toMatch(/class="niche-tabs mt-8"/);
  });

  it('footer links are full-height rows on a phone, so each is easy to tap', () => {
    for (const f of ['../partials/footer.html', '../partials/footer-hi.html']) expect(partials[f], f).toMatch(/<nav aria-label="[^"]+" class="footer-links /);
    const phone = siteCss.match(/@media \(max-width: 767px\) \{\s*\.footer-links \{([^}]*)\}\s*\.footer-links a \{([^}]*)\}/);
    expect(phone).not.toBeNull();
    expect(phone![1]).toMatch(/row-gap:\s*0/);
    const pad = Number(phone![2].match(/padding-block:\s*(\d+)px/)![1]);
    expect(20 + 2 * pad).toBeGreaterThanOrEqual(40); // 13 px text on a 20 px line, plus the padding
  });
});
