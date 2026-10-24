import { bars, clean, creatorPrice, DEFAULTS, formula, monthlyCost, monthlyUnits, mountCalc, verdict } from './calc';
import type { PlanView } from './plans';

describe('PC electricity calculator', () => {
  it('works out units and rupees from watts, hours and the tariff', () => {
    // 150 W × 24 h × 30 days = 108 kWh; × ₹8 = ₹864.
    expect(monthlyUnits(DEFAULTS)).toBe(108);
    expect(monthlyCost(DEFAULTS)).toBe(864);
    expect(monthlyCost({ watts: 200, hoursPerDay: 12, rupeesPerUnit: 6.5 })).toBe(468);
    expect(monthlyCost({ watts: 65, hoursPerDay: 8, rupeesPerUnit: 5 })).toBe(78);
  });

  it('shows the formula with the same numbers', () => {
    expect(formula(DEFAULTS)).toBe('150 W × 24 h × 30 days ÷ 1000 = 108 units × ₹8 = ₹864');
    expect(formula({ watts: 250, hoursPerDay: 10, rupeesPerUnit: 7.5 })).toBe('250 W × 10 h × 30 days ÷ 1000 = 75 units × ₹7.5 = ₹563');
  });

  it('keeps typed values inside sensible limits and falls back on blanks', () => {
    expect(clean('hoursPerDay', '30')).toBe(24);
    expect(clean('watts', '-5')).toBe(10);
    expect(clean('watts', '')).toBe(150);
    expect(clean('rupeesPerUnit', 'abc')).toBe(8);
    expect(clean('watts', '1,200')).toBe(1200);
  });

  it('says plainly when electricity is cheaper than the plan', () => {
    expect(verdict(864, 499)).toContain('₹365 a month more');
    expect(verdict(300, 499)).toMatch(/^Electricity alone costs less than Creator/);
    expect(verdict(499, 499)).toMatch(/^About the same/);
  });

  it('draws the bigger number full width', () => {
    expect(bars(864, 499)).toEqual({ pc: 100, plan: 58 });
    expect(bars(250, 500)).toEqual({ pc: 50, plan: 100 });
    expect(bars(0, 0)).toEqual({ pc: 0, plan: 0 });
  });

  it('reads the Creator price from the plan catalogue, and keeps the page price if that fails', async () => {
    const plan = { id: 'ESSENTIALS', monthly: 59900 } as PlanView;
    expect(await creatorPrice(async () => [plan])).toBe(599);
    expect(await creatorPrice(async () => { throw new Error('offline'); })).toBeNull();

    document.body.innerHTML = `<div data-calc data-price="499">
      <input data-calc-input="watts" value="150"><input data-calc-input="hoursPerDay" value="24"><input data-calc-input="rupeesPerUnit" value="8">
      <span data-calc-cost></span><span data-calc-price></span><span data-calc-formula></span><span data-calc-verdict></span>
      <div data-calc-bar-pc></div><div data-calc-bar-plan></div></div>`;
    const root = document.querySelector<HTMLElement>('[data-calc]')!;
    mountCalc(root, async () => [plan]);
    expect(root.querySelector('[data-calc-cost]')!.textContent).toBe('₹864');
    await vi.waitFor(() => expect(root.querySelector('[data-calc-price]')!.textContent).toBe('₹599'));

    const hours = root.querySelector<HTMLInputElement>('[data-calc-input="hoursPerDay"]')!;
    hours.value = '12';
    hours.dispatchEvent(new Event('input'));
    expect(root.querySelector('[data-calc-cost]')!.textContent).toBe('₹432');
    expect(root.querySelector('[data-calc-verdict]')!.textContent).toMatch(/costs less than Creator/);
  });
});
