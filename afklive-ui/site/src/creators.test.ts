// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { CREATORS, mountCreators, shownCreators } from './creators';

function section(): HTMLElement {
  document.body.innerHTML = '<section id="creators" hidden><ul></ul></section>';
  return document.getElementById('creators')!;
}

describe('creators section', () => {
  it('ships empty, so the section stays hidden until real channels are added', () => {
    expect(CREATORS).toEqual([]);
    const s = section();
    mountCreators(s);
    expect(s.hidden).toBe(true);
  });

  it('never shows an email or an invalid handle', () => {
    expect(shownCreators([{ handle: 'someone@gmail.com' }, { handle: 'a b' }, { handle: '@x' }])).toEqual([]);
  });

  it('shows real handles with a link to the channel and the creator’s own words', () => {
    const s = section();
    mountCreators(s, [{ handle: 'DailyAarti', niche: 'Devotional', quote: 'On air every morning.' }]);
    expect(s.hidden).toBe(false);
    const a = s.querySelector('a')!;
    expect(a.textContent).toBe('@DailyAarti');
    expect(a.getAttribute('href')).toBe('https://www.youtube.com/@DailyAarti');
    expect(s.textContent).toContain('Devotional · Live with AFK Live');
    expect(s.querySelector('blockquote')!.textContent).toBe('“On air every morning.”');
  });
});
