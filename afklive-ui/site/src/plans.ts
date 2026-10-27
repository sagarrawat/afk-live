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
    p.watermark ? d.watermark : d.noWatermark,
  ];
}

export function planCards(plans: PlanView[], cycle: 'MONTHLY' | 'YEARLY', lang: Lang = 'en'): string {
  const d = t(lang).plans;
  const pitch: Record<string, string> = d.pitch;
  return plans
    .map((p) => {
      const price = p.monthly === 0 && d.freePrice ? d.freePrice : cycle === 'YEARLY' ? p.yearlyLabel : p.monthlyLabel;
      const featured = p.id === 'ESSENTIALS';
      return `<li class="flex flex-col rounded-2xl border ${featured ? 'night border-transparent shadow-[0_30px_60px_-30px_rgba(16,18,42,0.7)]' : 'border-line bg-panel'} p-6">
  <h3 class="text-xl font-bold tracking-tight">${esc(p.name)}${featured ? ` <span class="ml-1 align-middle rounded-full bg-sun/15 px-2 py-0.5 font-sans text-xs font-semibold text-sun">${esc(d.mostPicked)}</span>` : ''}</h3>
  <p class="mt-1 text-sm text-muted">${esc(pitch[p.id] ?? '')}</p>
  <p class="mt-5"><span class="display text-[40px] font-extrabold tracking-tight">${esc(price)}</span>${p.monthly > 0 ? `<span class="text-muted"> / ${cycle === 'YEARLY' ? esc(d.perYear) : esc(d.perMonth)}</span>` : ''}</p>
  <ul class="mt-6 flex-1 space-y-2 text-[15px]">${features(p, lang)
    .map((f) => `<li class="flex gap-2.5"><svg aria-hidden="true" class="mt-1 h-4 w-4 shrink-0 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M20 6 9 17l-5-5"/></svg>${esc(f)}</li>`)
    .join('')}</ul>
  <a href="/register?plan=${encodeURIComponent(p.id)}" class="${featured ? 'btn-primary' : 'btn-secondary'} mt-6">${p.monthly > 0 ? esc(d.startWith(p.name)) : esc(d.startFree)}</a>
</li>`;
    })
    .join('');
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
  const press = () => {
    root.querySelectorAll<HTMLButtonElement>('[data-currency]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.currency === currency)));
    root.querySelectorAll<HTMLButtonElement>('[data-cycle]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.cycle === cycle)));
  };
  const render = async () => {
    press();
    try {
      const plans = cache[currency] ?? (cache[currency] = await loadPlans(currency));
      list.innerHTML = planCards(plans, cycle, lang);
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
