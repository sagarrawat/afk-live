/** Plan cards for the site, from the same catalogue the app bills with (MK-2: cards match real limits). */

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

export function features(p: PlanView): string[] {
  return [
    `${p.concurrentStreams} stream${p.concurrentStreams === 1 ? '' : 's'} at once`,
    `Up to ${p.maxHeight === 2160 ? '4K' : `${p.maxHeight}p`} at ${p.maxFps} fps`,
    p.monthlyHours > 0 ? `${p.monthlyHours} hours a month` : 'Unlimited hours, 24/7',
    `${p.destinationsPerStream} destination${p.destinationsPerStream === 1 ? '' : 's'} per stream`,
    `${gb(p.storageBytes)} of storage`,
    p.watermark ? 'Small AFK Live mark on stream' : 'No watermark',
  ];
}

const pitch: Record<string, string> = {
  FREE: 'Try it with a real stream.',
  ESSENTIALS: 'One channel, live around the clock.',
  PRO: 'Several channels, or 60 fps gaming.',
  AGENCY: 'Run streams for clients.',
};

export function planCards(plans: PlanView[], cycle: 'MONTHLY' | 'YEARLY'): string {
  return plans
    .map((p) => {
      const price = cycle === 'YEARLY' ? p.yearlyLabel : p.monthlyLabel;
      const featured = p.id === 'ESSENTIALS';
      return `<li class="flex flex-col rounded-2xl border ${featured ? 'night border-transparent shadow-[0_30px_60px_-30px_rgba(16,18,42,0.7)]' : 'border-line bg-panel'} p-6">
  <h3 class="text-xl font-bold tracking-tight">${esc(p.name)}${featured ? ' <span class="ml-1 align-middle rounded-full bg-sun/15 px-2 py-0.5 font-sans text-xs font-semibold text-sun">Most picked</span>' : ''}</h3>
  <p class="mt-1 text-sm text-muted">${esc(pitch[p.id] ?? '')}</p>
  <p class="mt-5"><span class="display text-[40px] font-extrabold tracking-tight">${esc(price)}</span>${p.monthly > 0 ? `<span class="text-muted"> / ${cycle === 'YEARLY' ? 'year' : 'month'}</span>` : ''}</p>
  <ul class="mt-6 flex-1 space-y-2 text-[15px]">${features(p)
    .map((f) => `<li class="flex gap-2.5"><svg aria-hidden="true" class="mt-1 h-4 w-4 shrink-0 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M20 6 9 17l-5-5"/></svg>${esc(f)}</li>`)
    .join('')}</ul>
  <a href="/register?plan=${encodeURIComponent(p.id)}" class="${featured ? 'btn-primary' : 'btn-secondary'} mt-6">${p.monthly > 0 ? `Start with ${esc(p.name)}` : 'Start free'}</a>
</li>`;
    })
    .join('');
}

export async function loadPlans(currency: 'INR' | 'USD'): Promise<PlanView[]> {
  const r = await fetch(`/api/billing/plans?currency=${currency}`, { headers: { Accept: 'application/json' } });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}

/** INR for visitors in India, USD elsewhere (a guess from the time zone; the toggle switches). */
export function guessCurrency(): 'INR' | 'USD' {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return tz === 'Asia/Kolkata' || tz === 'Asia/Calcutta' ? 'INR' : 'USD';
  } catch {
    return 'INR';
  }
}

/** Wires a pricing block: [data-plans] list, [data-currency] and [data-cycle] buttons. */
export function mountPricing(root: HTMLElement) {
  const list = root.querySelector<HTMLElement>('[data-plans]');
  if (!list) return;
  let currency = guessCurrency();
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
      list.innerHTML = planCards(plans, cycle);
      list.removeAttribute('aria-busy');
    } catch {
      /* the static cards in the page stay */
    }
  };
  root.querySelectorAll<HTMLButtonElement>('[data-currency]').forEach((b) =>
    b.addEventListener('click', () => {
      currency = b.dataset.currency as 'INR' | 'USD';
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
