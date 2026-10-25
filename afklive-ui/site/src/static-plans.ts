import type { PlanView } from './plans';

/**
 * The plans as the static cards in site/partials/pricing.html and pricing-hi.html show them before
 * /api/billing/plans answers (or if it fails). Monthly INR, matching plan_configs. Tests check that
 * both partials are exactly planCards(STATIC_PLANS) in their language, so the prices can't drift
 * apart between English and Hindi.
 */
const base = { currency: 'INR' as const, monthlyHours: 0, watermark: false, maxFps: 30, maxHeight: 1080, yearly: 0, yearlyLabel: '' };

export const STATIC_PLANS: PlanView[] = [
  { ...base, id: 'FREE', name: 'Free', concurrentStreams: 1, maxHeight: 720, storageBytes: 2 * 1024 ** 3, monthlyHours: 10, watermark: true, destinationsPerStream: 1, monthly: 0, monthlyLabel: 'Free' },
  { ...base, id: 'ESSENTIALS', name: 'Creator', concurrentStreams: 1, storageBytes: 25 * 1024 ** 3, destinationsPerStream: 2, monthly: 49900, monthlyLabel: '₹499' },
  { ...base, id: 'PRO', name: 'Pro', concurrentStreams: 3, maxFps: 60, storageBytes: 100 * 1024 ** 3, destinationsPerStream: 5, monthly: 149900, monthlyLabel: '₹1,499' },
  { ...base, id: 'AGENCY', name: 'Agency', concurrentStreams: 10, maxHeight: 2160, maxFps: 60, storageBytes: 500 * 1024 ** 3, destinationsPerStream: 5, monthly: 399900, monthlyLabel: '₹3,999' },
];
