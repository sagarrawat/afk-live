/**
 * Accessible tabs for the site (WAI-ARIA tabs pattern): a [role=tablist] of [role=tab] buttons.
 * Arrow keys move between tabs (wrapping), Home and End jump, and focus follows selection. Only the
 * selected tab is in the tab order. Panels, if any, are linked by aria-controls and hidden otherwise.
 */
export interface Tabs {
  select(index: number, opts?: { focus?: boolean; user?: boolean }): void;
  readonly index: number;
  readonly count: number;
}

export function mountTabs(list: HTMLElement, onSelect?: (index: number, user: boolean) => void): Tabs {
  const tabs = Array.from(list.querySelectorAll<HTMLElement>('[role="tab"]'));
  let current = Math.max(
    0,
    tabs.findIndex((t) => t.getAttribute('aria-selected') === 'true'),
  );

  const apply = () => {
    tabs.forEach((t, i) => {
      const on = i === current;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      const panelId = t.getAttribute('aria-controls');
      const panel = panelId ? document.getElementById(panelId) : null;
      // Several tabs may share one panel whose content changes; only hide panels that are tab-specific.
      if (panel && tabs.filter((o) => o.getAttribute('aria-controls') === panelId).length === 1) panel.hidden = !on;
    });
  };

  const api: Tabs = {
    select(index, opts = {}) {
      const next = ((index % tabs.length) + tabs.length) % tabs.length;
      const changed = next !== current;
      current = next;
      apply();
      if (opts.focus) tabs[current].focus();
      if (changed || opts.user) onSelect?.(current, !!opts.user);
    },
    get index() {
      return current;
    },
    get count() {
      return tabs.length;
    },
  };

  tabs.forEach((t, i) => {
    t.addEventListener('click', () => api.select(i, { user: true }));
    t.addEventListener('keydown', (e) => {
      const key = e.key;
      let to: number | null = null;
      if (key === 'ArrowRight' || key === 'ArrowDown') to = current + 1;
      else if (key === 'ArrowLeft' || key === 'ArrowUp') to = current - 1;
      else if (key === 'Home') to = 0;
      else if (key === 'End') to = tabs.length - 1;
      if (to === null) return;
      e.preventDefault();
      api.select(to, { focus: true, user: true });
    });
  });
  apply();
  return api;
}
