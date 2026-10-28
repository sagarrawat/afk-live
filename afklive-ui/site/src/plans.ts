/** Plan cards for the site, from the same catalogue the app bills with (MK-2: cards match real limits). */
import { pageLang, t, type Lang } from './i18n';

export interface PlanView {
  id: string;
  name: string;
  concurrentStreams: number;
  maxHeight: number;
  maxFps: number;
  storageBytes: number;
  monthlyHours: number;
  watermark: boolean;
  destinationsPerStream: number;
  currency: 'INR' | 'USD';
  monthly: number;
  yearly: number;
  monthlyLabel: string;
  yearlyLabel: string;
  /** Monthly streaming data allowance in GB (fair use); null or missing = none. */
  monthlyTrafficGb?: number | null;
}

/**
 * BI-9: the launch offer open now, from /api/billing/offer (null when there is none; the cards then show only the
 * list prices, which is also what the page shows without JavaScript). Prices are the real list price and the
 * real discounted price, GST included.
 */
export interface OfferPlanPrice {
  id: string;
  name: string;
  listMonthly: number;
  offerMonthly: number;
  listMonthlyLabel: string;
  offerMonthlyLabel: string;
  listYearly: number;
  offerYearly: number | null;
  listYearlyLabel: string;
  offerYearlyLabel: string | null;
}

export interface PublicOffer {
  name: string;
  percentOff: number;
  monthlyPeriods: number;
  includeYearly: boolean;
  startsAt: string;
  endsAt: string;
  currency: 'INR' | 'USD';
  plans: OfferPlanPrice[];
  eligible?: boolean | null;
}

/** "30 Nov 2026" in India time: the offer's real last day. */
export function offerDay(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Asia/Kolkata' }).format(new Date(iso));
  } catch {
    return iso.slice(0, 10);
  }
}

/** The plan's offer price for this cycle, or null when the offer doesn't cover it. */
export function offerFor(offer: PublicOffer | null | undefined, id: string, cycle: 'MONTHLY' | 'YEARLY'): { list: string; price: string } | null {
  const p = offer?.plans.find((x) => x.id === id);
  if (!offer || !p) return null;
  if (cycle === 'YEARLY') return offer.includeYearly && p.offerYearlyLabel ? { list: p.listYearlyLabel, price: p.offerYearlyLabel } : null;
  return { list: p.listMonthlyLabel, price: p.offerMonthlyLabel };
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

export function gb(bytes: number): string {
  const v = bytes / 1024 ** 3;
  return `${Number.isInteger(v) ? v : v.toFixed(1)} GB`;
}

export function features(p: PlanView, lang: Lang = 'en'): string[] {
  const d = t(lang).plans;
  return [
    d.streamsAtOnce(p.concurrentStreams),
    d.upTo(p.maxHeight === 2160 ? '4K' : `${p.maxHeight}p`, p.maxFps),
    p.monthlyHours > 0 ? d.hoursAMonth(p.monthlyHours) : d.unlimitedHours,
    d.destinations(p.destinationsPerStream),
    d.storage(gb(p.storageBytes)),
    ...(p.monthlyTrafficGb ? [d.streamingData(p.monthlyTrafficGb.toLocaleString(t(lang).locale))] : []),
    p.watermark ? d.watermark : d.noWatermark,
  ];
}

export function planCards(plans: PlanView[], cycle: 'MONTHLY' | 'YEARLY', lang: Lang = 'en', offer: PublicOffer | null = null): string {
  const d = t(lang).plans;
  const pitch: Record<string, string> = d.pitch;
  const usable = offer && offer.eligible !== false && offer.currency === plans[0]?.currency ? offer : null;
  return plans
    .map((p) => {
      const deal = p.monthly > 0 ? offerFor(usable, p.id, cycle) : null;
      const price = p.monthly === 0 && d.freePrice ? d.freePrice : deal ? deal.price : cycle === 'YEARLY' ? p.yearlyLabel : p.monthlyLabel;
      const gst = p.currency === 'INR' ? ` ${d.inclGst}` : '';
      const offerLine =
        deal && usable
          ? `\n  <p class="mt-2 text-sm"><span class="font-semibold text-accent">${esc(
              cycle === 'YEARLY'
                ? d.offerYearly(usable.name, usable.percentOff, deal.price, deal.list)
                : d.offerMonthly(usable.name, usable.percentOff, usable.monthlyPeriods, deal.price, deal.list),
            )}</span> <span class="text-muted">${esc(d.offerWho(offerDay(usable.endsAt)))}</span></p>`
          : '';
      const featured = p.id === 'ESSENTIALS';
      return `<li class="flex flex-col rounded-2xl border ${featured ? 'night border-transparent shadow-[0_30px_60px_-30px_rgba(16,18,42,0.7)]' : 'border-line bg-panel'} p-6">
  <h3 class="text-xl font-bold tracking-tight">${esc(p.name)}${featured ? ` <span class="ml-1 align-middle rounded-full bg-sun/15 px-2 py-0.5 font-sans text-xs font-semibold text-sun">${esc(d.mostPicked)}</span>` : ''}</h3>
  <p class="mt-1 text-sm text-muted">${esc(pitch[p.id] ?? '')}</p>
  <p class="mt-5">${deal ? `<s class="mr-2 text-xl text-muted"><span class="sr-only">${esc(d.listPrice)} </span>${esc(deal.list)}</s>` : ''}<span class="display text-[40px] font-extrabold tracking-tight">${esc(price)}</span>${p.monthly > 0 ? `<span class="text-muted"> / ${cycle === 'YEARLY' ? esc(d.perYear) : esc(d.perMonth)}${esc(gst)}</span>` : ''}</p>${offerLine}
  <ul class="mt-6 flex-1 space-y-2 text-[15px]">${features(p, lang)
    .map((f) => `<li class="flex gap-2.5"><svg aria-hidden="true" class="mt-1 h-4 w-4 shrink-0 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M20 6 9 17l-5-5"/></svg>${esc(f)}</li>`)
    .join('')}</ul>
  <a href="/register?plan=${encodeURIComponent(p.id)}" class="${featured ? 'btn-primary' : 'btn-secondary'} mt-6">${p.monthly > 0 ? esc(d.startWith(p.name)) : esc(d.startFree)}</a>
</li>`;
    })
    .join('');
}

/** The launch offer open now, or null (none, or the request failed: the list prices stand). */
export async function loadOffer(currency: 'INR' | 'USD'): Promise<PublicOffer | null> {
  try {
    const r = await fetch(`/api/billing/offer?currency=${currency}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
    if (r.status !== 200) return null;
    const o = (await r.json()) as PublicOffer;
    return o && Array.isArray(o.plans) && typeof o.percentOff === 'number' ? o : null;
  } catch {
    return null;
  }
}

const inFlight: Partial<Record<'INR' | 'USD', Promise<PlanView[]>>> = {};

/** One request per currency per page: the pricing cards and the homepage calculator share it. */
export function loadPlans(currency: 'INR' | 'USD'): Promise<PlanView[]> {
  const p = (inFlight[currency] ??= fetch(`/api/billing/plans?currency=${currency}`, { headers: { Accept: 'application/json' } }).then((r) => {
    if (!r.ok) throw new Error(String(r.status));
    return r.json() as Promise<PlanView[]>;
  }));
  p.catch(() => delete inFlight[currency]);
  return p;
}

export const CURRENCY_KEY = 'afk.site.currency';

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/**
 * Rupees, unless this visitor chose dollars with the toggle before. Everyone, search engines included,
 * first sees the ₹ prices that are in the page's HTML. There is no guess from the time zone: Google
 * renders pages from the US, so a guess showed it dollars where the page says ₹.
 */
export function savedCurrency(storage: Pick<Storage, 'getItem'> | null = browserStorage()): 'INR' | 'USD' {
  try {
    return storage?.getItem(CURRENCY_KEY) === 'USD' ? 'USD' : 'INR';
  } catch {
    return 'INR';
  }
}

/** Remembers the toggle; without storage (a private window, storage blocked) it lasts for this page only. */
export function saveCurrency(currency: 'INR' | 'USD', storage: Pick<Storage, 'setItem'> | null = browserStorage()) {
  try {
    storage?.setItem(CURRENCY_KEY, currency);
  } catch {
    /* nothing to do: the page still shows the chosen currency */
  }
}

/** Wires a pricing block: [data-plans] list, [data-currency] and [data-cycle] buttons. */
export function mountPricing(root: HTMLElement, lang: Lang = pageLang()) {
  const list = root.querySelector<HTMLElement>('[data-plans]');
  if (!list) return;
  let currency = savedCurrency();
  let cycle: 'MONTHLY' | 'YEARLY' = 'MONTHLY';
  let cache: Partial<Record<'INR' | 'USD', PlanView[]>> = {};
  const offers: Partial<Record<'INR' | 'USD', Promise<PublicOffer | null>>> = {};
  const press = () => {
    root.querySelectorAll<HTMLButtonElement>('[data-currency]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.currency === currency)));
    root.querySelectorAll<HTMLButtonElement>('[data-cycle]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.cycle === cycle)));
  };
  const render = async () => {
    press();
    try {
      const offer = (offers[currency] ??= loadOffer(currency));
      const plans = cache[currency] ?? (cache[currency] = await loadPlans(currency));
      list.innerHTML = planCards(plans, cycle, lang, await offer);

      list.removeAttribute('aria-busy');
    } catch {
      /* the static cards in the page stay */
    }
  };
  root.querySelectorAll<HTMLButtonElement>('[data-currency]').forEach((b) =>
    b.addEventListener('click', () => {
      currency = b.dataset.currency as 'INR' | 'USD';
      saveCurrency(currency);
      render();
    }),
  );
  root.querySelectorAll<HTMLButtonElement>('[data-cycle]').forEach((b) =>
    b.addEventListener('click', () => {
      cycle = b.dataset.cycle as 'MONTHLY' | 'YEARLY';
      render();
    }),
  );
  render();
}
