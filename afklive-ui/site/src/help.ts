/**
 * MK-4: search on the help index (site/help.html). Every article is in the page already, so search is
 * a filter: without JavaScript the full list still shows. `?q=` fills the box, so the app and emails
 * can link to a search.
 */

/** Words of a query, lower-case, without punctuation that people type around them. */
export function terms(query: string): string[] {
  return query
    .toLowerCase()
    .split(/[\s,.;:!?"“”'‘’()]+/)
    .filter(Boolean);
}

/** True when every word of the query appears in the article's text (title, summary and keywords). */
export function matches(text: string, query: string): boolean {
  const t = text.toLowerCase();
  return terms(query).every((w) => t.includes(w));
}

export function countText(shown: number, total: number, query: string): string {
  if (!terms(query).length) return '';
  if (shown === 0) return 'No articles match.';
  return shown === total ? `All ${total} articles match.` : `${shown} of ${total} articles match.`;
}

export function mountHelpSearch(root: HTMLElement, search = window.location.search) {
  const input = root.querySelector<HTMLInputElement>('[data-help-search]');
  const items = Array.from(root.querySelectorAll<HTMLElement>('[data-help-item]'));
  const empty = root.querySelector<HTMLElement>('[data-help-empty]');
  const count = root.querySelector<HTMLElement>('[data-help-count]');
  if (!input) return;
  const apply = () => {
    let shown = 0;
    for (const item of items) {
      const hit = matches(item.dataset.search ?? item.textContent ?? '', input.value);
      item.hidden = !hit;
      if (hit) shown++;
    }
    empty?.classList.toggle('hidden', shown > 0);
    if (count) count.textContent = countText(shown, items.length, input.value);
  };
  root.querySelector('[data-help-form]')?.addEventListener('submit', (e) => {
    e.preventDefault();
    apply();
  });
  input.addEventListener('input', apply);
  const q = new URLSearchParams(search).get('q');
  if (q) input.value = q;
  apply();
}
