import { pageLang, t, type Lang } from './i18n';

/**
 * Channels shown on the homepage ("Channels that stay live with AFK Live").
 *
 * Only real channels whose owners agreed to be shown. Use their public YouTube @handle, never an email
 * or a made-up name (a wrong handle can point at someone else's channel). Quotes only in the creator's
 * own words. While the list is empty the section stays hidden.
 */
export type Creator = {
  /** Public YouTube handle, with or without the leading @. */
  handle: string;
  /** One or two words, e.g. "Gaming", "Devotional". */
  niche?: string;
  /** The creator's own words, only if they wrote or approved them. */
  quote?: string;
};

export const CREATORS: Creator[] = [];

const HANDLE = /^@?[A-Za-z0-9._-]{3,30}$/;

/** Creators with a valid handle, normalised to start with @. */
export function shownCreators(list: Creator[]): Creator[] {
  return list
    .map((c) => ({ ...c, handle: c.handle.trim() }))
    .filter((c) => HANDLE.test(c.handle) && !c.handle.includes('@', 1))
    .map((c) => ({ ...c, handle: c.handle.startsWith('@') ? c.handle : '@' + c.handle }));
}

/** Fills the creators section and shows it; leaves it hidden when there is nobody to show. */
export function mountCreators(section: HTMLElement, list: Creator[] = CREATORS, lang: Lang = pageLang()): void {
  const shown = shownCreators(list);
  const ul = section.querySelector('ul');
  if (!ul || shown.length === 0) {
    section.hidden = true;
    return;
  }
  ul.replaceChildren(
    ...shown.map((c) => {
      const li = document.createElement('li');
      li.className = 'rounded-2xl border border-line bg-white p-6';
      const a = document.createElement('a');
      a.href = 'https://www.youtube.com/' + encodeURIComponent(c.handle).replace('%40', '@');
      a.rel = 'noopener';
      a.target = '_blank';
      a.className = 'text-lg font-bold tracking-tight hover:underline';
      a.textContent = c.handle;
      li.append(a);
      if (c.niche) {
        const p = document.createElement('p');
        p.className = 'mt-1 text-sm text-muted';
        p.textContent = t(lang).creators.liveWith(c.niche);
        li.append(p);
      }
      if (c.quote) {
        const q = document.createElement('blockquote');
        q.className = 'mt-4 text-[15px]';
        q.textContent = '“' + c.quote + '”';
        li.append(q);
      }
      return li;
    }),
  );
  section.hidden = false;
}
