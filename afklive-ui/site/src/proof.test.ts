/// <reference types="vite/client" />
import { incidentNote, MIN_HOURS, MIN_LIVE, mountProof, proofLine } from './proof';
import { send, mountFunnel } from './funnel';
import { NICHES } from './niches';
import type { PublicStatus } from './status';

const status = (over: Partial<PublicStatus> = {}): PublicStatus => ({
  overall: 'operational',
  checkedAt: '2026-10-24T08:00:00Z',
  components: [],
  incidents: [],
  proof: { liveNow: null, hoursStreamed: null },
  ...over,
});

describe('proof line', () => {
  it('shows nothing without real numbers', () => {
    expect(proofLine(undefined)).toBeNull();
    expect(proofLine({ liveNow: null, hoursStreamed: null })).toBeNull();
  });

  it('hides numbers that are too small to mean anything, even if the server sends them', () => {
    expect(proofLine({ liveNow: 1, hoursStreamed: 12 })).toBeNull();
    expect(proofLine({ liveNow: MIN_LIVE - 1, hoursStreamed: MIN_HOURS - 1 })).toBeNull();
  });

  it('says each number it has, in Indian grouping', () => {
    expect(proofLine({ liveNow: 37, hoursStreamed: null })).toBe('37 streams live on AFK Live right now.');
    expect(proofLine({ liveNow: 2, hoursStreamed: 123400 })).toBe('1,23,400+ hours streamed on AFK Live so far.');
    expect(proofLine({ liveNow: 12, hoursStreamed: 1200 })).toBe('12 streams live on AFK Live right now. 1,200+ hours streamed so far.');
  });

  it('counts platform incidents for the reliability section', () => {
    expect(incidentNote(status())).toBe('No platform incidents in the last 90 days.');
    const one = { id: 1, title: 't', status: 'resolved', startedAt: '', updates: [] };
    expect(incidentNote(status({ incidents: [one] }))).toMatch(/^1 platform incident in/);
  });

  it('fills the page from the status call and stays hidden when it fails', async () => {
    const html = `<p data-proof hidden><span data-proof-text></span></p><p data-incidents hidden><span data-incidents-text></span></p>`;
    document.body.innerHTML = html;
    await mountProof(async () => status({ proof: { liveNow: 40, hoursStreamed: 5000 } }));
    expect(document.querySelector<HTMLElement>('[data-proof]')!.hidden).toBe(false);
    expect(document.querySelector('[data-proof-text]')!.textContent).toContain('40 streams');
    expect(document.querySelector<HTMLElement>('[data-incidents]')!.hidden).toBe(false);

    document.body.innerHTML = html;
    await mountProof(async () => status({ proof: { liveNow: 1, hoursStreamed: 3 } }));
    expect(document.querySelector<HTMLElement>('[data-proof]')!.hidden).toBe(true);

    document.body.innerHTML = html;
    await mountProof(async () => {
      throw new Error('offline');
    });
    expect(document.querySelector<HTMLElement>('[data-proof]')!.hidden).toBe(true);
    expect(document.querySelector<HTMLElement>('[data-incidents]')!.hidden).toBe(true);
  });
});

describe('homepage funnel beacon', () => {
  it('sends only the step name, and nothing from automated browsers', () => {
    const sendBeacon = vi.fn(() => true);
    expect(send('view', { webdriver: false, sendBeacon } as unknown as Navigator)).toBe(true);
    const [url, body] = sendBeacon.mock.calls[0] as unknown as [string, Blob];
    expect(url).toBe('/api/public/funnel');
    expect(body.size).toBe(4);
    expect(send('view', { webdriver: true, sendBeacon } as unknown as Navigator)).toBe(false);
    expect(sendBeacon).toHaveBeenCalledTimes(1);
  });

  it('counts the homepage view and Start free clicks, nowhere else', () => {
    const sendBeacon = vi.fn(() => true);
    const nav = { webdriver: false, sendBeacon } as unknown as Navigator;
    document.body.innerHTML = '<main><a href="/register" data-funnel="start">Start free</a></main>';
    mountFunnel(document, nav);
    expect(sendBeacon).not.toHaveBeenCalled();
    document.body.innerHTML = '<main data-funnel-view><a href="#" data-funnel="start"><span>Start free</span></a><a href="#x">Other</a></main>';
    mountFunnel(document, nav);
    document.querySelector('span')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    document.querySelector<HTMLElement>('a[href="#x"]')!.click();
    expect(sendBeacon).toHaveBeenCalledTimes(2);
  });
});

const page = import.meta.glob('../index.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const images = import.meta.glob('../public/img/*', { query: '?url', import: 'default', eager: true }) as Record<string, string>;
const home = page['../index.html'];

describe('homepage', () => {
  it('says nothing technical in its copy', () => {
    const text = home.replace(/<!--[\s\S]*?-->/g, '').replace(/<[^>]+>/g, ' ');
    expect(text).not.toMatch(/\bRTMP\b|\bSRT\b|bitrate|stream key/i);
  });

  it('has a committed frame, at both sizes, for every example stream', () => {
    for (const n of Object.values(NICHES)) {
      expect(Object.keys(images), n.id).toContain(`../public/img/${n.frame}.webp`);
      expect(Object.keys(images), n.id).toContain(`../public/img/${n.frame}-640.webp`);
    }
  });

  it('keeps the creators section hidden and empty until real creators say yes', () => {
    const creators = home.match(/<section id="creators"[^>]*>[\s\S]*?<\/section>/)![0];
    expect(creators).toMatch(/\shidden[\s>]/);
    expect(creators).toMatch(/<ul[^>]*><\/ul>/);
  });

  it('keeps one primary action, its no-card promise, and the SEO tags', () => {
    expect(home).toContain('10 free hours · No card · Pay by UPI');
    expect(home).toContain('<link rel="canonical" href="https://afklive.in/" />');
    expect(home).toMatch(/<meta property="og:title"/);
    expect(home).toContain('Go to sleep. Stay live.');
  });

  it('lazy-loads every image below the hero', () => {
    const imgs = home.match(/<img [^>]*>/g)!;
    const eager = imgs.filter((i) => !i.includes('loading="lazy"'));
    expect(eager).toHaveLength(1);
    expect(eager[0]).toContain('data-hero-img');
    for (const i of imgs) expect(i, i).toMatch(/width="\d+" height="\d+"/);
  });
});
