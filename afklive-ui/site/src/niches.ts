/**
 * The example streams on the homepage. Each frame is drawn by the real studio with that niche's
 * template (site/frames.sh, SiteFrames.java), so what's on screen is what a creator's viewers see.
 * Words here are page copy, kept out of the images so they can be translated.
 */
import { t, type Lang, type NicheId } from './i18n';

export interface Niche {
  id: string;
  frame: string;
  /** The stream's title under the player, as a creator might name it. */
  title: string;
  /** What the frame shows, for screen readers. */
  alt: string;
  /** Which template drew the overlays. */
  template: string;
}

/** Which frame and template each example uses; the words come from the dictionary (i18n.ts). */
const FRAMES: Record<NicheId, { frame: string; template: string }> = {
  lofi: { frame: 'frame-lofi', template: 'Lofi and chill' },
  devotional: { frame: 'frame-devotional', template: 'Bhajans and aarti' },
  gaming: { frame: 'frame-gaming', template: 'Phone gaming' },
  podcasts: { frame: 'frame-podcast', template: 'Bhajans and aarti, in English' },
  study: { frame: 'frame-study', template: 'Study with me' },
};

/**
 * The example streams in one language. Template names stay in English in every language: they're
 * what the creator will find in the (English) app.
 */
export function niches(lang: Lang = 'en'): Record<string, Niche> {
  const words = t(lang).niches;
  return Object.fromEntries((Object.keys(FRAMES) as NicheId[]).map((id) => [id, { id, ...FRAMES[id], ...words[id] }]));
}

export const NICHES: Record<string, Niche> = niches('en');

export function frameSrc(n: Niche, width: 640 | 1280 = 1280): string {
  return `/site/img/${n.frame}${width === 640 ? '-640' : ''}.webp`;
}

export function frameSrcset(n: Niche): string {
  return `${frameSrc(n, 640)} 640w, ${frameSrc(n)} 1280w`;
}

/** Points an <img> at a niche's frame. */
export function showFrame(img: HTMLImageElement, n: Niche) {
  img.srcset = frameSrcset(n);
  img.src = frameSrc(n);
  img.alt = n.alt;
}
