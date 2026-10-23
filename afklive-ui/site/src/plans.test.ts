import { features, gb, planCards, type PlanView } from './plans';

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
