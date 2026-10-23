import { countText, matches, mountHelpSearch, terms } from './help';

describe('help search', () => {
  it('matches every word, in any order and case', () => {
    const text = 'Where to find your YouTube stream key copy paste';
    expect(matches(text, 'stream key')).toBe(true);
    expect(matches(text, 'KEY youtube')).toBe(true);
    expect(matches(text, '"stream key"?')).toBe(true);
    expect(matches(text, 'stream vertical')).toBe(false);
    expect(matches(text, '   ')).toBe(true);
    expect(terms('Auto-start, Preparing!')).toEqual(['auto-start', 'preparing']);
  });

  it('says how many match only while searching', () => {
    expect(countText(5, 5, '')).toBe('');
    expect(countText(0, 5, 'zzz')).toBe('No articles match.');
    expect(countText(2, 5, 'youtube')).toBe('2 of 5 articles match.');
  });

  it('filters the list as you type and starts from ?q=', () => {
    document.body.innerHTML = `
      <main data-help>
        <form data-help-form><input data-help-search /></form>
        <p data-help-count></p>
        <ul>
          <li data-help-item data-search="where to find your youtube stream key">Key</li>
          <li data-help-item data-search="youtube says preparing auto-start auto-stop">Preparing</li>
        </ul>
        <p class="hidden" data-help-empty>Nothing matches</p>
      </main>`;
    const root = document.querySelector<HTMLElement>('[data-help]')!;
    mountHelpSearch(root, '?q=preparing');
    const [key, prep] = Array.from(root.querySelectorAll<HTMLElement>('[data-help-item]'));
    const input = root.querySelector<HTMLInputElement>('[data-help-search]')!;
    expect(input.value).toBe('preparing');
    expect(key.hidden).toBe(true);
    expect(prep.hidden).toBe(false);

    input.value = 'nothing like this';
    input.dispatchEvent(new Event('input'));
    expect(prep.hidden).toBe(true);
    expect(root.querySelector('[data-help-empty]')!.classList.contains('hidden')).toBe(false);
    expect(root.querySelector('[data-help-count]')!.textContent).toBe('No articles match.');

    input.value = '';
    input.dispatchEvent(new Event('input'));
    expect(key.hidden || prep.hidden).toBe(false);
  });
});
