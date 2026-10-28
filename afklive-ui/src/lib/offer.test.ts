import { offerDay, offerFor, offerLine, offerWho, quoteOfferNote } from './offer';
import type { PublicOffer, Quote } from './types';

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

const quote = (over: Partial<Quote> = {}): Quote => ({
  kind: 'NEW',
  plan: 'ESSENTIALS',
  cycle: 'MONTHLY',
  currency: 'INR',
  price: 44900,
  credit: 0,
  amount: 44900,
  periodStart: '2026-11-02T00:00:00Z',
  periodEnd: '2026-12-02T00:00:00Z',
  listPrice: 49900,
  discount: 5000,
  offer: { id: 1, name: 'Launch offer', percentOff: 10, payments: 3, paymentsLeft: 2 },
  renewalPrice: 49900,
  gst: 6849,
  ...over,
});

describe('launch offer copy (BI-9)', () => {
  it('names the real prices, how long and for whom', () => {
    const deal = offerFor(offer(), 'ESSENTIALS', 'MONTHLY', 'INR')!;
    expect(deal).toEqual({ list: '₹499', price: '₹449' });
    expect(offerLine(offer(), 'MONTHLY', deal)).toBe('Launch offer: 10% off your first 3 months — ₹449/month, then ₹499.');
    expect(offerLine(offer(), 'YEARLY', offerFor(offer(), 'ESSENTIALS', 'YEARLY', 'INR')!)).toBe('Launch offer: 10% off your first year — ₹4,491, then ₹4,990/year.');
    expect(offerWho(offer())).toBe('For new subscribers, until 30 Nov 2026.');
    expect(offerDay('2026-11-30T18:29:59Z')).toBe('30 Nov 2026');
  });

  it('applies only where it is real', () => {
    expect(offerFor(null, 'ESSENTIALS', 'MONTHLY', 'INR')).toBeNull();
    expect(offerFor(offer({ eligible: false }), 'ESSENTIALS', 'MONTHLY', 'INR')).toBeNull();
    expect(offerFor(offer(), 'ESSENTIALS', 'MONTHLY', 'USD')).toBeNull();
    expect(offerFor(offer(), 'PRO', 'MONTHLY', 'INR')).toBeNull();
    expect(offerFor(offer({ includeYearly: false }), 'ESSENTIALS', 'YEARLY', 'INR')).toBeNull();
  });

  it('explains the quote: now, the discounted renewals, then the list price incl. GST', () => {
    expect(quoteOfferNote(quote())).toBe('10% off your first 3 months: ₹449 now and at each of the next 2 renewals, then ₹499 incl. GST a month.');
    expect(quoteOfferNote(quote({ cycle: 'YEARLY', price: 449100, amount: 449100, renewalPrice: 499000, offer: { id: 1, name: 'Launch offer', percentOff: 10, payments: 1, paymentsLeft: 0 } }))).toBe(
      '10% off your first year: ₹4,491 now, then ₹4,990 incl. GST a year.',
    );
    expect(quoteOfferNote(quote({ kind: 'RENEW', offer: { id: 1, name: 'Launch offer', percentOff: 10, payments: 3, paymentsLeft: 0 } }))).toBe(
      'This is the last discounted month of your launch offer. After it, the plan renews at ₹499 incl. GST a month.',
    );
    expect(quoteOfferNote(quote({ offer: null, discount: 0 }))).toBeNull();
  });
});
