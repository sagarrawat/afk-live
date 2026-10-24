import { mountTabs } from './tabs';
import { NICHES, showFrame } from './niches';

/**
 * "What's your channel?": tabs that swap the example frame and show that niche's panel. Panels are in
 * the HTML (one per tab, linked by aria-controls), so the copy is translatable and readable without JS.
 */
export function mountNichePicker(root: HTMLElement) {
  const list = root.querySelector<HTMLElement>('[role="tablist"]');
  const img = root.querySelector<HTMLImageElement>('[data-niche-img]');
  if (!list) return null;
  const ids = Array.from(list.querySelectorAll<HTMLElement>('[role="tab"]')).map((t) => t.dataset.niche ?? '');
  return mountTabs(list, (i) => {
    const n = NICHES[ids[i]];
    if (img && n) showFrame(img, n);
  });
}
