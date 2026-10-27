/**
 * Structured data (JSON-LD) for the public site, written at build time by vite.site.config.ts from what
 * the page already shows, so the markup can't say something the page doesn't (Google's rule):
 *
 *   <!-- jsonld:org -->       Organization + WebSite (/ and /hi)
 *   <!-- jsonld:software -->  SoftwareApplication with the plans in ₹, from static-plans.ts (/ and /pricing)
 *   <!-- jsonld:faq -->       FAQPage from the page's own visible questions (the element marked data-faq)
 *
 * Help articles and use-case pages carry their BreadcrumbList / TechArticle from site/help.py and
 * site/use-cases.py. Imported by the build (Node) and the tests, so nothing here touches the DOM.
 */
import type { PlanView } from './plans';

export const ORIGIN = 'https://afklive.in';
const ORG_ID = `${ORIGIN}/#organization`;

type Json = Record<string, unknown>;

/** A <script type="application/ld+json"> with "<" escaped, so text can never close the script early. */
export function ldScript(data: Json): string {
  return `<script type="application/ld+json">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>`;
}

export function organization(lang: 'en' | 'hi'): Json {
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'Organization',
        '@id': ORG_ID,
        name: 'AFK Live',
        url: `${ORIGIN}/`,
        logo: `${ORIGIN}/site/brand/apple-touch-icon.png`,
        email: 'support@afklive.in',
        contactPoint: { '@type': 'ContactPoint', contactType: 'customer support', email: 'support@afklive.in', availableLanguage: ['en', 'hi'] },
      },
      {
        '@type': 'WebSite',
        '@id': `${ORIGIN}/#website`,
        name: 'AFK Live',
        url: `${ORIGIN}/`,
        inLanguage: lang === 'hi' ? 'hi-IN' : 'en-IN',
        publisher: { '@id': ORG_ID },
      },
    ],
  };
}

/** Rupees from the catalogue's paise, as schema.org wants a price: "499", not "₹499". */
export function rupees(paise: number): string {
  return String(Math.round(paise / 100));
}

export function softwareApplication(plans: PlanView[]): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'AFK Live',
    url: `${ORIGIN}/`,
    applicationCategory: 'MultimediaApplication',
    operatingSystem: 'Web',
    description: 'Keeps a YouTube channel live 24/7 from the cloud, from uploaded videos or a phone stream, with no PC left on.',
    publisher: { '@id': ORG_ID },
    offers: plans.map((p) => ({
      '@type': 'Offer',
      name: p.name,
      price: rupees(p.monthly),
      priceCurrency: 'INR',
      url: `${ORIGIN}/pricing`,
      ...(p.monthly > 0
        ? { priceSpecification: { '@type': 'UnitPriceSpecification', price: rupees(p.monthly), priceCurrency: 'INR', billingDuration: 'P1M', unitText: 'month' } }
        : {}),
    })),
  };
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ldquo: '“', rdquo: '”', lsquo: '‘', rsquo: '’', mdash: '—', ndash: '–' };

/** Visible text of an HTML fragment: tags dropped, entities decoded, whitespace collapsed. */
export function text(html: string): string {
  return html
    .replace(/<[^>]+>/g, '')
    .replace(/&#x([0-9a-f]+);/gi, (_, h: string) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d: string) => String.fromCodePoint(Number(d)))
    .replace(/&(\w+);/g, (m, e: string) => ENTITIES[e] ?? m)
    .replace(/\s+/g, ' ')
    .trim();
}

/** The questions and answers shown in the element marked data-faq (<details>/<summary> or <dt>/<dd>). */
export function faqEntries(html: string): Array<[string, string]> {
  const start = html.indexOf('data-faq');
  if (start < 0) return [];
  const end = html.indexOf('</section>', start);
  const block = html.slice(start, end < 0 ? undefined : end);
  const out: Array<[string, string]> = [];
  for (const m of block.matchAll(/<summary[^>]*>([\s\S]*?)<\/summary>\s*<p[^>]*>([\s\S]*?)<\/p>/g)) out.push([text(m[1]), text(m[2])]);
  for (const m of block.matchAll(/<dt[^>]*>([\s\S]*?)<\/dt>\s*<dd[^>]*>([\s\S]*?)<\/dd>/g)) out.push([text(m[1]), text(m[2])]);
  return out;
}

export function faqPage(entries: Array<[string, string]>): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: entries.map(([q, a]) => ({ '@type': 'Question', name: q, acceptedAnswer: { '@type': 'Answer', text: a } })),
  };
}

/** Replaces the jsonld placeholders in a page (after partials are included, so the FAQ is in the page). */
export function withStructuredData(html: string, plans: PlanView[]): string {
  const lang = /<html lang="hi"/.test(html) ? 'hi' : 'en';
  return html
    .replace('<!-- jsonld:org -->', () => ldScript(organization(lang)))
    .replace('<!-- jsonld:software -->', () => ldScript(softwareApplication(plans)))
    .replace('<!-- jsonld:faq -->', () => {
      const entries = faqEntries(html);
      if (!entries.length) throw new Error('jsonld:faq on a page without a data-faq block of questions');
      return ldScript(faqPage(entries));
    });
}

/** Every JSON-LD block in a page, parsed. */
export function jsonLd(html: string): Json[] {
  return Array.from(html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g), (m) => JSON.parse(m[1]) as Json);
}
