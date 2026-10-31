import { render, screen } from '@testing-library/react';
import { MemoryRouter, NavLink } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Panel, ScrollTabs, Segmented } from './ui';

// Phone layout of the shared kit (360 to 412 px wide screens).

describe('ScrollTabs', () => {
  const sections = ['Overview', 'Playlist', 'Audio', 'Overlays', 'Destinations', 'Schedule', 'Settings'];

  function tabs(current: string) {
    return render(
      <MemoryRouter initialEntries={[`/s/${current}`]}>
        <ScrollTabs label="Stream sections">
          <ul className="flex min-w-max">
            {sections.map((s) => (
              <li key={s}>
                <NavLink to={`/s/${s}`}>{s}</NavLink>
              </li>
            ))}
          </ul>
        </ScrollTabs>
      </MemoryRouter>,
    );
  }

  it('scrolls the current section into view, so a phone never shows it cut off at the edge', () => {
    // jsdom has no layout: a 360 px row of 90 px links, and a scroll position that sticks.
    const scroll = new WeakMap<Element, number>();
    const scrollLeft = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollLeft');
    const rect = Element.prototype.getBoundingClientRect;
    Object.defineProperty(Element.prototype, 'scrollLeft', { configurable: true, get() { return scroll.get(this) ?? 0; }, set(v: number) { scroll.set(this, v); } });
    Element.prototype.getBoundingClientRect = function (this: Element) {
      const at = (left: number, width: number) => ({ left, right: left + width, top: 0, bottom: 40, width, height: 40, x: left, y: 0, toJSON: () => ({}) }) as DOMRect;
      if (this.tagName === 'NAV') return at(0, 360);
      if (this.tagName === 'A') return at(sections.indexOf(this.textContent ?? '') * 90 - (scroll.get(this.closest('nav')!) ?? 0), 90);
      return rect.call(this);
    };
    try {
      tabs('Destinations');
      expect(screen.getByRole('link', { name: 'Destinations' })).toHaveAttribute('aria-current', 'page');
      // Destinations spans 360-450 px: scrolled until it ends 32 px (the fade) inside the right edge.
      expect(screen.getByRole('navigation', { name: 'Stream sections' }).scrollLeft).toBe(450 - (360 - 32));
    } finally {
      Element.prototype.getBoundingClientRect = rect;
      if (scrollLeft) Object.defineProperty(Element.prototype, 'scrollLeft', scrollLeft);
    }
  });

  it('leaves the row alone when the current section is already in view', () => {
    tabs('Overview');
    expect(screen.getByRole('navigation', { name: 'Stream sections' }).scrollLeft).toBe(0);
  });

  it('scrolls sideways without a scrollbar and has a fade on each side for "more this way"', () => {
    const { container } = tabs('Overview');
    const nav = screen.getByRole('navigation', { name: 'Stream sections' });
    expect(nav.className).toContain('overflow-x-auto');
    expect(container.querySelectorAll('[data-fade]')).toHaveLength(2);
    container.querySelectorAll('[data-fade]').forEach((f) => expect(f).toHaveAttribute('aria-hidden'));
  });
});

describe('Panel', () => {
  it('lets a wide header action wrap under the title instead of squeezing it (Plans toggles at 360 px)', () => {
    render(
      <Panel title="Plans" action={<button type="button">Monthly</button>}>
        body
      </Panel>,
    );
    const header = screen.getByRole('heading', { name: 'Plans' }).parentElement!;
    expect(header.className).toContain('flex-wrap');
  });
});

describe('Segmented', () => {
  it('options grow, so a row that wraps on a phone fills the width instead of leaving one option alone', () => {
    render(
      <Segmented
        label="Scene"
        value="all"
        onChange={() => {}}
        options={[
          { value: 'all', label: 'All' },
          { value: 'soon', label: 'Starting soon' },
          { value: 'ending', label: 'Ending' },
        ]}
      />,
    );
    for (const r of screen.getAllByRole('radio')) expect(r.className).toMatch(/\bgrow\b/);
    expect(screen.getByRole('radiogroup', { name: 'Scene' }).className).toContain('flex-wrap');
  });
});
