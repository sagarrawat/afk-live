/**
 * BI-9 launch offer copy for the app. Same rules as the public pricing page: the struck price is the real list
 * price, the offer price is real, the copy says how long it lasts, what it renews at and who it's for, and every
 * rupee price says "incl. GST". No countdowns, no made-up "was" prices.
 */
import { money } from './format';
import type { PublicOffer, Quote } from './types';

/** "30 Nov 2026" in India time. */
export function offerDay(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Asia/Kolkata' }).format(new Date(iso));
  } catch {
    return iso.slice(0, 10);
  }
}

/** The offer's list and offer price for a plan and cycle, or null when it doesn't apply (or this account can't take it). */
export function offerFor(offer: PublicOffer | null | undefined, id: string, cycle: 'MONTHLY' | 'YEARLY', currency: string): { list: string; price: string } | null {
  if (!offer || offer.eligible === false || offer.currency !== currency) return null;
  const p = offer.plans.find((x) => x.id === id);
  if (!p) return null;
  if (cycle === 'YEARLY') return offer.includeYearly && p.offerYearlyLabel ? { list: p.listYearlyLabel, price: p.offerYearlyLabel } : null;
  return { list: p.listMonthlyLabel, price: p.offerMonthlyLabel };
}

/** "Launch offer: 10% off your first 3 months — ₹449/month, then ₹499." */
export function offerLine(offer: PublicOffer, cycle: 'MONTHLY' | 'YEARLY', deal: { list: string; price: string }): string {
  if (cycle === 'YEARLY') return `${offer.name}: ${offer.percentOff}% off your first year — ${deal.price}, then ${deal.list}/year.`;
  const months = offer.monthlyPeriods === 1 ? 'month' : `${offer.monthlyPeriods} months`;
  return `${offer.name}: ${offer.percentOff}% off your first ${months} — ${deal.price}/month, then ${deal.list}.`;
}

export function offerWho(offer: PublicOffer): string {
  return `For new subscribers, until ${offerDay(offer.endsAt)}.`;
}

/** What the quote's discount covers and what comes after, in one sentence (null without an offer). */
export function quoteOfferNote(q: Quote): string | null {
  if (!q.offer) return null;
  const per = q.cycle === 'YEARLY' ? 'year' : 'month';
  const gst = q.currency === 'INR' ? ' incl. GST' : '';
  const price = money(q.price, q.currency);
  const renews = `${money(q.renewalPrice, q.currency)}${gst} a ${per}`;
  if (q.kind === 'RENEW') {
    return q.offer.paymentsLeft === 0
      ? `This is the last discounted ${per} of your ${q.offer.name.toLowerCase()}. After it, the plan renews at ${renews}.`
      : `${q.offer.name}: ${q.offer.percentOff}% off this ${per} and ${q.offer.paymentsLeft} more, then ${renews}.`;
  }
  if (q.cycle === 'YEARLY' || q.offer.payments <= 1) return `${q.offer.percentOff}% off your first ${per}: ${price} now, then ${renews}.`;
  const next = q.offer.paymentsLeft === 1 ? 'the next renewal' : `each of the next ${q.offer.paymentsLeft} renewals`;
  return `${q.offer.percentOff}% off your first ${q.offer.payments} months: ${price} now and at ${next}, then ${renews}.`;
}
