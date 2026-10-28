import { CURRENCY_KEY, features, gb, loadOffer, mountPricing, planCards, saveCurrency, savedCurrency, type PlanView, type PublicOffer } from './plans';

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

  it('state the monthly streaming data allowance when the plan has one (FO-5), and nothing when it has none', () => {
    expect(features(plan({ monthlyTrafficGb: 2000 }))).toContain('2,000 GB of streaming data a month');
    expect(features(plan({ monthlyTrafficGb: 15000 }), 'hi')).toContain('हर महीने 15,000 GB streaming data');
    expect(features(plan({ monthlyTrafficGb: null })).join(' ')).not.toContain('streaming data');
    expect(features(plan({})).join(' ')).not.toContain('streaming data');
  });

  it('show the yearly price when asked and escape names', () => {
    const html = planCards([plan({ name: '<b>Creator</b>' })], 'YEARLY');
    expect(html).toContain('₹4,990.00');
    expect(html).toContain('/ year');
    expect(html).toContain('&lt;b&gt;Creator&lt;/b&gt;');
    expect(html).toContain('/register?plan=ESSENTIALS');
  });
});

describe('launch offer on the cards (BI-9)', () => {
  const offer = (over: Partial<PublicOffer> = {}): PublicOffer => ({
    name: 'Launch offer',
    percentOff: 10,
    monthlyPeriods: 3,
    includeYearly: true,
    startsAt: '2026-11-01T00:00:00Z',
    endsAt: '2026-11-30T18:29:59Z',
    currency: 'INR',
    plans: [
      { id: 'ESSENTIALS', name: 'Creator', listMonthly: 49900, offerMonthly: 44900, listMonthlyLabel: '₹499', offerMonthlyLabel: '₹449', listYearly: 499000, offerYearly: 449100, listYearlyLabel: '₹4,990', offerYearlyLabel: '₹4,491' },
    ],
    ...over,
  });
  const free = plan({ id: 'FREE', name: 'Free', monthly: 0, yearly: 0, monthlyLabel: 'Free', yearlyLabel: 'Free' });
  const creator = plan({ monthlyLabel: '₹499', yearlyLabel: '₹4,990' });

  it('shows only list prices, GST included, when there is no offer', () => {
    const html = planCards([free, creator], 'MONTHLY', 'en', null);
    expect(html).toContain('₹499</span><span class="text-muted"> / month incl. GST</span>');
    expect(html).not.toContain('Launch offer');
    expect(html).not.toContain('<s ');
    expect(planCards([plan({ currency: 'USD', monthlyLabel: '$9' })], 'MONTHLY')).not.toContain('GST');
  });

  it('strikes the real list price next to the real offer price and says how long and for whom', () => {
    const html = planCards([free, creator], 'MONTHLY', 'en', offer());
    expect(html).toContain('<s class="mr-2 text-xl text-muted"><span class="sr-only">List price </span>₹499</s>');
    expect(html).toContain('>₹449</span><span class="text-muted"> / month incl. GST</span>');
    expect(html).toContain('Launch offer: 10% off your first 3 months — ₹449/month, then ₹499.');
    expect(html).toContain('For new subscribers, until 30 Nov 2026.');
    expect(html.match(/Launch offer:/g)).toHaveLength(1); // not on the Free card
    const yearly = planCards([creator], 'YEARLY', 'en', offer());
    expect(yearly).toContain('Launch offer: 10% off your first year — ₹4,491, then ₹4,990/year.');
    expect(planCards([creator], 'YEARLY', 'en', offer({ includeYearly: false }))).not.toContain('Launch offer');
    expect(planCards([creator], 'MONTHLY', 'hi', offer())).toContain('Launch offer: पहले 3 महीने 10% छूट — ₹449/महीना, फिर ₹499।');
  });

  it('hides the offer from an account that can no longer take it, and from the other currency', () => {
    expect(planCards([creator], 'MONTHLY', 'en', offer({ eligible: false }))).not.toContain('Launch offer');
    expect(planCards([plan({ currency: 'USD' })], 'MONTHLY', 'en', offer())).not.toContain('Launch offer');
  });

  it('treats no offer, an error or an odd answer as no offer', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    expect(await loadOffer('INR')).toBeNull();
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('offline'))));
    expect(await loadOffer('INR')).toBeNull();
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify([1, 2])))));
    expect(await loadOffer('INR')).toBeNull();
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(offer())))));
    expect((await loadOffer('INR'))?.percentOff).toBe(10);
    vi.unstubAllGlobals();
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
    expect(fetchMock.mock.calls.map((c) => c[0]).filter((u) => u.startsWith('/api/billing/plans'))).toEqual(['/api/billing/plans?currency=INR']);
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
