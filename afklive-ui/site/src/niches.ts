/**
 * The example streams on the homepage. Each frame is drawn by the real studio with that niche's
 * template (site/frames.sh, SiteFrames.java), so what's on screen is what a creator's viewers see.
 * Words here are page copy, kept out of the images so they can be translated.
 */
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

export const NICHES: Record<string, Niche> = {
  lofi: {
    id: 'lofi',
    frame: 'frame-lofi',
    title: 'Rainy night lofi, live 24/7',
    alt: 'A lofi stream: a desk lamp and a rainy window at night, with "Now playing: Rain on the window, lofi mix" at the bottom and the time, 02:14, in the corner.',
    template: 'Lofi and chill',
  },
  devotional: {
    id: 'devotional',
    frame: 'frame-devotional',
    title: 'संध्या आरती, live every evening',
    alt: 'A temple stream at dusk: a lit aarti thali and marigold garlands, with the now-playing and up-next lines in Hindi and the time, 18:42.',
    template: 'Bhajans and aarti',
  },
  gaming: {
    id: 'gaming',
    frame: 'frame-gaming',
    title: 'Night drive, streamed from my phone',
    alt: 'A racing game streamed from a phone, with the time, 21:37, added in the corner by AFK Live.',
    template: 'Phone gaming',
  },
  podcasts: {
    id: 'podcasts',
    frame: 'frame-podcast',
    title: 'Past episodes, live between new ones',
    alt: 'A podcast stream: a microphone at a desk, with "Now playing: Episode 41" and "Up next: Episode 42" at the bottom and the time, 23:05.',
    template: 'Bhajans and aarti, in English',
  },
  study: {
    id: 'study',
    frame: 'frame-study',
    title: 'Study with me, 6 am to 11 pm',
    alt: 'A study stream before dawn: a lamp over an open notebook, a big clock at the top showing 05:48 and the music playing at the bottom.',
    template: 'Study with me',
  },
};

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
