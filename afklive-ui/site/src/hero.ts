import { mountTabs } from './tabs';
import { niches, frameSrc, showFrame } from './niches';
import { pageLang, type Lang } from './i18n';

export const CYCLE_MS = 6000;

export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

/**
 * The hero player: an example stream that changes niche every few seconds. Tabs under it pick one;
 * picking stops the cycling for good. With reduced motion it never cycles on its own. It also waits
 * while the pointer or focus is on the player, and while the page is hidden.
 */
export function mountHero(root: HTMLElement, reduced = prefersReducedMotion(), lang: Lang = pageLang()) {
  const NICHES = niches(lang);
  const list = root.querySelector<HTMLElement>('[role="tablist"]');
  const img = root.querySelector<HTMLImageElement>('[data-hero-img]');
  const title = root.querySelector<HTMLElement>('[data-hero-title]');
  const template = root.querySelector<HTMLElement>('[data-hero-template]');
  if (!list || !img) return null;
  const ids = Array.from(list.querySelectorAll<HTMLElement>('[role="tab"]')).map((t) => t.dataset.niche ?? '');

  let auto = !reduced;
  let held = false;
  let timer: number | undefined;

  const show = (i: number) => {
    const n = NICHES[ids[i]];
    if (!n) return;
    const swap = () => {
      showFrame(img, n);
      if (title) title.textContent = n.title;
      if (template) template.textContent = n.template;
      img.style.opacity = '1';
    };
    if (reduced) swap();
    else {
      img.style.opacity = '0';
      window.setTimeout(swap, 160);
    }
  };

  const schedule = () => {
    window.clearTimeout(timer);
    list.classList.toggle('cycling', auto && !held);
    if (!auto || held) return;
    timer = window.setTimeout(() => tabs.select(tabs.index + 1), CYCLE_MS);
  };

  const tabs = mountTabs(list, (i, user) => {
    if (user) auto = false;
    show(i);
    schedule();
  });

  const hold = (on: boolean) => {
    held = on;
    schedule();
  };
  root.addEventListener('pointerenter', () => hold(true));
  root.addEventListener('pointerleave', () => hold(false));
  root.addEventListener('focusin', () => hold(true));
  root.addEventListener('focusout', () => hold(false));
  document.addEventListener('visibilitychange', () => hold(document.hidden));

  if (auto) {
    // Fetch the other frames once the page has loaded, so the first swap doesn't flash.
    window.addEventListener('load', () => ids.forEach((id) => NICHES[id] && (new Image().src = frameSrc(NICHES[id], img.currentSrc.includes('-640') ? 640 : 1280))), { once: true });
  }
  schedule();
  return { tabs, get cycling() { return auto && !held; } };
}
