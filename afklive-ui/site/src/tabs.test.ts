import { mountTabs } from './tabs';
import { CYCLE_MS, mountHero } from './hero';
import { mountNichePicker } from './niche-picker';

const key = (el: Element, k: string) => el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));

function picker() {
  document.body.innerHTML = `<section data-niche-picker>
    <div role="tablist" aria-label="Kinds of channel">
      <button role="tab" id="t1" data-niche="lofi" aria-controls="p1" aria-selected="true">Lofi</button>
      <button role="tab" id="t2" data-niche="devotional" aria-controls="p2" aria-selected="false">Devotional</button>
      <button role="tab" id="t3" data-niche="gaming" aria-controls="p3" aria-selected="false">Phone gaming</button>
    </div>
    <img data-niche-img src="/site/img/frame-lofi.webp" alt="">
    <div role="tabpanel" id="p1">one</div><div role="tabpanel" id="p2" hidden>two</div><div role="tabpanel" id="p3" hidden>three</div>
  </section>`;
  return document.querySelector<HTMLElement>('[data-niche-picker]')!;
}

describe('tabs', () => {
  it('keeps only the selected tab in the tab order and shows its panel', () => {
    const root = picker();
    const tabs = mountTabs(root.querySelector('[role="tablist"]')!);
    const [a, b, c] = Array.from(root.querySelectorAll<HTMLElement>('[role="tab"]'));
    expect([a.tabIndex, b.tabIndex, c.tabIndex]).toEqual([0, -1, -1]);
    b.click();
    expect(tabs.index).toBe(1);
    expect(b.getAttribute('aria-selected')).toBe('true');
    expect(a.getAttribute('aria-selected')).toBe('false');
    expect(document.getElementById('p1')!.hidden).toBe(true);
    expect(document.getElementById('p2')!.hidden).toBe(false);
  });

  it('moves with the arrow keys, wraps, and jumps with Home and End', () => {
    const root = picker();
    const tabs = mountTabs(root.querySelector('[role="tablist"]')!);
    const all = Array.from(root.querySelectorAll<HTMLElement>('[role="tab"]'));
    key(all[0], 'ArrowLeft');
    expect(tabs.index).toBe(2);
    expect(document.activeElement).toBe(all[2]);
    key(all[2], 'ArrowRight');
    expect(tabs.index).toBe(0);
    key(all[0], 'End');
    expect(tabs.index).toBe(2);
    key(all[2], 'Home');
    expect(tabs.index).toBe(0);
    key(all[0], 'a');
    expect(tabs.index).toBe(0);
  });

  it('swaps the example frame in the niche picker', () => {
    const root = picker();
    mountNichePicker(root);
    root.querySelectorAll<HTMLElement>('[role="tab"]')[1].click();
    const img = root.querySelector<HTMLImageElement>('[data-niche-img]')!;
    expect(img.getAttribute('src')).toBe('/site/img/frame-devotional.webp');
    expect(img.srcset).toContain('/site/img/frame-devotional-640.webp 640w');
    expect(img.alt).toMatch(/temple/i);
  });
});

function hero() {
  document.body.innerHTML = `<div data-hero>
    <img data-hero-img src="/site/img/frame-lofi.webp" alt=""><p data-hero-title></p><span data-hero-template></span>
    <div role="tablist"><button role="tab" data-niche="lofi" aria-selected="true">Lofi</button><button role="tab" data-niche="devotional" aria-selected="false">Aarti</button><button role="tab" data-niche="gaming" aria-selected="false">Game</button></div>
  </div>`;
  return document.querySelector<HTMLElement>('[data-hero]')!;
}

describe('hero player', () => {
  afterEach(() => vi.useRealTimers());

  it('cycles through the niches on its own', () => {
    vi.useFakeTimers();
    const h = mountHero(hero(), false)!;
    expect(h.cycling).toBe(true);
    vi.advanceTimersByTime(CYCLE_MS + 200);
    expect(h.tabs.index).toBe(1);
    expect(document.querySelector('[data-hero-title]')!.textContent).toMatch(/आरती/);
    vi.advanceTimersByTime((CYCLE_MS + 200) * 2);
    expect(h.tabs.index).toBe(0);
  });

  it('never moves on its own with reduced motion: the tabs are the only control', () => {
    vi.useFakeTimers();
    const root = hero();
    const h = mountHero(root, true)!;
    expect(h.cycling).toBe(false);
    vi.advanceTimersByTime(CYCLE_MS * 5);
    expect(h.tabs.index).toBe(0);
    expect(root.querySelector('[role="tablist"]')!.classList.contains('cycling')).toBe(false);
    root.querySelectorAll<HTMLElement>('[role="tab"]')[2].click();
    expect(root.querySelector<HTMLImageElement>('[data-hero-img]')!.getAttribute('src')).toBe('/site/img/frame-gaming.webp');
  });

  it('stops cycling once someone picks a niche, and waits while the pointer is on it', () => {
    vi.useFakeTimers();
    const root = hero();
    const h = mountHero(root, false)!;
    root.dispatchEvent(new Event('pointerenter'));
    vi.advanceTimersByTime(CYCLE_MS * 3);
    expect(h.tabs.index).toBe(0);
    root.dispatchEvent(new Event('pointerleave'));
    root.querySelectorAll<HTMLElement>('[role="tab"]')[1].click();
    vi.advanceTimersByTime(CYCLE_MS * 3);
    expect(h.tabs.index).toBe(1);
    expect(h.cycling).toBe(false);
  });
});
