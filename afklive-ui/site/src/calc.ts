import { loadPlans } from './plans';
import { pageLang, t, type Lang } from './i18n';

/**
 * "Leaving your PC on costs more than you think": the electricity a PC uses streaming all month,
 * next to the Creator plan. Everything is an assumption the visitor can change, and the formula is
 * shown. Price comes from the same plan catalogue the pricing cards use.
 */
export interface Inputs {
  watts: number;
  hoursPerDay: number;
  rupeesPerUnit: number;
}

/** A desktop running OBS and a browser; a common Indian home tariff at the higher slabs. */
export const DEFAULTS: Inputs = { watts: 150, hoursPerDay: 24, rupeesPerUnit: 8 };
export const DAYS = 30;
export const LIMITS = { watts: [10, 2000], hoursPerDay: [1, 24], rupeesPerUnit: [1, 50] } as const;

/** Units (kWh) a month: watts × hours a day × 30 days ÷ 1000. */
export function monthlyUnits(i: Inputs): number {
  return (i.watts * i.hoursPerDay * DAYS) / 1000;
}

/** Rupees a month, rounded to the rupee. */
export function monthlyCost(i: Inputs): number {
  return Math.round(monthlyUnits(i) * i.rupeesPerUnit);
}

/** A typed value inside its limits; blanks and nonsense fall back to the default. */
export function clean(key: keyof Inputs, raw: string): number {
  const v = Number(raw.replace(/[,₹\s]/g, ''));
  const [lo, hi] = LIMITS[key];
  if (!Number.isFinite(v) || raw.trim() === '') return DEFAULTS[key];
  return Math.min(hi, Math.max(lo, v));
}

const inr = (n: number) => `₹${Math.round(n).toLocaleString('en-IN')}`;
const num = (n: number) => (Number.isInteger(n) ? n.toLocaleString('en-IN') : n.toLocaleString('en-IN', { maximumFractionDigits: 1 }));

export function formula(i: Inputs, lang: Lang = 'en'): string {
  const d = t(lang).calc;
  return `${num(i.watts)} W × ${num(i.hoursPerDay)} ${d.hours} × ${DAYS} ${d.days} ÷ 1000 = ${num(monthlyUnits(i))} ${d.units} × ₹${num(i.rupeesPerUnit)} = ${inr(monthlyCost(i))}`;
}

/** The honest comparison: cheaper or not, the plan isn't only about the bill. */
export function verdict(cost: number, price: number, lang: Lang = 'en'): string {
  const d = t(lang).calc;
  if (cost > price) return d.more(inr(cost - price));
  if (cost === price) return d.same;
  return d.less;
}

/** Bar widths in percent, the larger value full width. */
export function bars(cost: number, price: number): { pc: number; plan: number } {
  const max = Math.max(cost, price, 1);
  return { pc: Math.round((cost / max) * 100), plan: Math.round((price / max) * 100) };
}

export async function creatorPrice(load = loadPlans): Promise<number | null> {
  try {
    const plans = await load('INR');
    const p = plans.find((x) => x.id === 'ESSENTIALS');
    return p && p.monthly > 0 ? Math.round(p.monthly / 100) : null;
  } catch {
    return null;
  }
}

/** Wires [data-calc]: inputs [data-calc-input=watts|hoursPerDay|rupeesPerUnit] and outputs [data-calc-*]. */
export function mountCalc(root: HTMLElement, load = loadPlans, lang: Lang = pageLang()) {
  let price = Number(root.dataset.price) || 499;
  const out = (name: string) => root.querySelector<HTMLElement>(`[data-calc-${name}]`);
  const read = (): Inputs => {
    const get = (k: keyof Inputs) => clean(k, root.querySelector<HTMLInputElement>(`[data-calc-input="${k}"]`)?.value ?? '');
    return { watts: get('watts'), hoursPerDay: get('hoursPerDay'), rupeesPerUnit: get('rupeesPerUnit') };
  };
  const render = () => {
    const i = read();
    const cost = monthlyCost(i);
    const b = bars(cost, price);
    out('cost')!.textContent = inr(cost);
    out('price')!.textContent = inr(price);
    out('formula')!.textContent = formula(i, lang);
    out('verdict')!.textContent = verdict(cost, price, lang);
    out('bar-pc')!.style.width = `${Math.max(2, b.pc)}%`;
    out('bar-plan')!.style.width = `${Math.max(2, b.plan)}%`;
  };
  root.querySelectorAll<HTMLInputElement>('[data-calc-input]').forEach((el) => el.addEventListener('input', render));
  render();
  creatorPrice(load).then((p) => {
    if (p) {
      price = p;
      render();
    }
  });
  return { render };
}
