import { CURRENCY_KEY, features, gb, mountPricing, planCards, saveCurrency, savedCurrency, type PlanView } from './plans';

const plan = (over: Partial<PlanView>): PlanView => ({
  id: 'ESSENTIALS',
  name: 'Creator',
  concurrentStreams: 1,
  maxHeight: 1080,
  maxFps: 30,
  storageBytes: 25 * 1024 ** 3,
  monthlyHours: 0,
  watermark: false,
  destinationsPerStream: 2,
  currency: 'INR',
  monthly: 49900,
  yearly: 499000,
  monthlyLabel: '₹499.00',
  yearlyLabel: '₹4,990.00',
  ...over,
});

describe('site plan cards', () => {
  it('list the real limits', () => {
    expect(features(plan({}))).toEqual(['1 stream at once', 'Up to 1080p at 30 fps', 'Unlimited hours, 24/7', '2 destinations per stream', '25 GB of storage', 'No watermark']);
    expect(features(plan({ id: 'FREE', monthlyHours: 10, watermark: true, maxHeight: 720, destinationsPerStream: 1 }))).toContain('10 hours a month');
    expect(features(plan({ maxHeight: 2160 }))[1]).toBe('Up to 4K at 30 fps');
    expect(gb(1.5 * 1024 ** 3)).toBe('1.5 GB');
  });

  it('show the yearly price when asked and escape names', () => {
    const html = planCards([plan({ name: '<b>Creator</b>' })], 'YEARLY');
    expect(html).toContain('₹4,990.00');
    expect(html).toContain('/ year');
    expect(html).toContain('&lt;b&gt;Creator&lt;/b&gt;');
    expect(html).toContain('/register?plan=ESSENTIALS');
  });
});

describe('site currency (SEO: ₹ for everyone, search engines included)', () => {
  const memory = () => {
    const data: Record<string, string> = {};
    return { getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => void (data[k] = v), data };
  };
  const blocked = {
    getItem: (): string | null => {
      throw new Error('blocked');
    },
    setItem: () => {
      throw new Error('blocked');
    },
  };

  it('defaults to rupees whatever the time zone, and remembers a choice of dollars', () => {
    const tz = vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockReturnValue({ timeZone: 'America/Los_Angeles' } as Intl.ResolvedDateTimeFormatOptions);
    expect(savedCurrency(memory())).toBe('INR');
    expect(savedCurrency(null)).toBe('INR');
    const store = memory();
    saveCurrency('USD', store);
    expect(store.data[CURRENCY_KEY]).toBe('USD');
    expect(savedCurrency(store)).toBe('USD');
    saveCurrency('INR', store);
    expect(savedCurrency(store)).toBe('INR');
    tz.mockRestore();
  });

  it('works when storage is blocked (private window)', () => {
    expect(savedCurrency(blocked)).toBe('INR');
    expect(() => saveCurrency('USD', blocked)).not.toThrow();
  });

  it('shows ₹ first and switches to $ only from the toggle, which it remembers', async () => {
    window.localStorage.clear();
    const fetchMock = vi.fn((url: string) => {
      const usd = url.includes('USD');
      return Promise.resolve(new Response(JSON.stringify([plan({ currency: usd ? 'USD' : 'INR', monthlyLabel: usd ? '$6' : '₹499' })])));
    });
    vi.stubGlobal('fetch', fetchMock);
    document.body.innerHTML = `<div data-pricing>
      <button data-currency="INR" aria-pressed="true"></button><button data-currency="USD" aria-pressed="false"></button>
      <button data-cycle="MONTHLY" aria-pressed="true"></button><ul data-plans>₹499</ul></div>`;
    const root = document.querySelector<HTMLElement>('[data-pricing]')!;
    mountPricing(root, 'en');
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/billing/plans?currency=INR']);
    await vi.waitFor(() => expect(root.querySelector('[data-plans]')!.innerHTML).toContain('₹499'));
    expect(root.querySelector('[data-currency="INR"]')!.getAttribute('aria-pressed')).toBe('true');

    root.querySelector<HTMLButtonElement>('[data-currency="USD"]')!.click();
    await vi.waitFor(() => expect(root.querySelector('[data-plans]')!.innerHTML).toContain('$6'));
    expect(window.localStorage.getItem(CURRENCY_KEY)).toBe('USD');
    expect(savedCurrency()).toBe('USD');
    window.localStorage.clear();
    vi.unstubAllGlobals();
  });
});
