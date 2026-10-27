import { render, screen, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { cashMajor, weekLabel, type BusinessReport } from './BusinessPage';

const admin: Me = {
  id: 1,
  email: 'ops@example.com',
  name: 'Ops',
  admin: true,
  plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 3, watermark: false },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};
const base = {
  '/api/me': admin,
  '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
};

function mockApi(routes: Record<string, unknown>) {
  const fn = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const path = String(input).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    const body = routes[path];
    return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const weeks = (n: number, last: Partial<BusinessReport['weeks'][number]> = {}) =>
  Array.from({ length: n }, (_, i) => {
    const d = new Date(Date.UTC(2031, 2, 17 - 7 * (n - 1 - i)));
    const w = { start: d.toISOString().slice(0, 10), partial: i === n - 1, signUps: 0, signUpsWentLive: 0, activeCreators: 0, streamHours: 0 };
    return i === n - 1 ? { ...w, ...last } : w;
  });

const report: BusinessReport = {
  generatedAt: '2031-03-19T12:00:00Z',
  weekStarts: 'Monday 00:00 UTC',
  accounts: { total: 120, undated: 4, wentLive: 48, wentLivePercent: 40 },
  weeks: weeks(12, { signUps: 9, signUpsWentLive: 3, activeCreators: 31, streamHours: 812.5 }),
  conversion: { everPaid: 15, everPaidPercent: 12.5, payingNow: 11, payingNowPercent: 9.2, inGrace: 1, endingAtPeriodEnd: 2, givenByAdmin: 1 },
  plans: [
    { plan: 'ESSENTIALS', name: 'Creator', cycle: 'MONTHLY', currency: 'INR', accounts: 8, inGrace: 1, monthlyEach: 422.88, mrr: 2960.17 },
    { plan: 'PRO', name: 'Pro', cycle: 'YEARLY', currency: 'INR', accounts: 2, inGrace: 0, monthlyEach: 1058.62, mrr: 2117.23 },
    { plan: 'ESSENTIALS', name: 'Creator', cycle: 'MONTHLY', currency: 'USD', accounts: 1, inGrace: 0, monthlyEach: 9, mrr: 9 },
  ],
  mrr: { inr: 5077.4, usd: 9, formula: 'Sum over paid plans of: list price for the cycle (yearly ÷ 12), ÷ 1.18 to take out GST for INR.' },
  churn: { month: '2031-03', thisMonth: 1, previousMonth: '2031-02', lastMonth: 2, ratePercent: 8.3, formula: 'Moved to Free this month ÷ (paying now + moved to Free this month).' },
  cohorts: [
    { start: '2031-03-17', accounts: 9, activePercent: [33.3, null, null, null, null, null, null, null] },
    { start: '2031-03-10', accounts: 0, activePercent: [null, null, null, null, null, null, null, null] },
    { start: '2031-03-03', accounts: 10, activePercent: [60, 40, 30, null, null, null, null, null] },
  ],
};

describe('Admin > Business', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('is in the admin nav after Billing and shows the headline numbers, marking money as estimates', async () => {
    window.history.pushState({}, '', '/studio/admin/business');
    mockApi({ ...base, '/api/admin/business': report });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByRole('heading', { name: 'Business' })).toBeInTheDocument();
    const nav = screen.getAllByRole('navigation', { name: 'Admin' })[0];
    const labels = within(nav).getAllByRole('link').map((a) => a.textContent?.trim());
    expect(labels.indexOf('Business')).toBe(labels.indexOf('Billing') + 1);
    expect(within(nav).getByRole('link', { name: 'Business' })).toHaveAttribute('aria-current', 'page');

    expect(screen.getByTestId('biz-accounts')).toHaveTextContent('120');
    expect(screen.getByText('9 new this week')).toBeInTheDocument();
    expect(screen.getByTestId('biz-active')).toHaveTextContent('31');
    expect(screen.getByTestId('biz-mrr')).toHaveTextContent(cashMajor(5077.4, 'INR'));
    expect(screen.getByText(`+ ${cashMajor(9, 'USD')} from USD plans`)).toBeInTheDocument();
    expect(screen.getByTestId('biz-went-live')).toHaveTextContent('40%');
    expect(screen.getByTestId('biz-ever-paid')).toHaveTextContent('12.5%');
    expect(screen.getAllByText('estimate').length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText(/÷ 1.18 to take out GST/)).toBeInTheDocument();
    expect(screen.getByText(/4 older accounts have no sign-up date/)).toBeInTheDocument();
    // No jargon in the primary labels.
    expect(screen.queryByText(/RTMP|bitrate|stream key/i)).toBeNull();
  });

  it('lists paying accounts by plan, churn, weeks newest first and cohort retention', async () => {
    window.history.pushState({}, '', '/studio/admin/business');
    mockApi({ ...base, '/api/admin/business': report });
    render(<App client={makeQueryClient()} />);
    const plans = await screen.findByRole('table', { name: 'Paying accounts by plan' });
    const planRows = within(plans).getAllByRole('row').slice(1);
    expect(planRows).toHaveLength(3);
    expect(planRows[0]).toHaveTextContent('Creator');
    expect(planRows[0]).toHaveTextContent('(1 in grace)');
    expect(planRows[1]).toHaveTextContent('yearly');
    expect(planRows[2]).toHaveTextContent('USD');

    expect(screen.getByTestId('biz-churn')).toHaveTextContent('1');
    expect(screen.getByText('Left in March so far')).toBeInTheDocument();
    expect(screen.getByText('Left in February')).toBeInTheDocument();

    const weekly = screen.getByRole('table', { name: /per week/ });
    const weekRows = within(weekly).getAllByRole('row').slice(1);
    expect(weekRows).toHaveLength(12);
    expect(weekRows[0]).toHaveTextContent(`${weekLabel('2031-03-17')} (so far)`);
    expect(weekRows[0]).toHaveTextContent('813 h');

    const cohorts = screen.getByRole('table', { name: /sign-up week/ });
    const header = within(cohorts).getAllByRole('columnheader').map((h) => h.textContent);
    expect(header).toEqual(['Signed up', 'Accounts', 'Week 0', 'Wk 1', 'Wk 2', 'Wk 3', 'Wk 4', 'Wk 5', 'Wk 6', 'Wk 7']);
    const cohortRows = within(cohorts).getAllByRole('row').slice(1);
    expect(cohortRows[2]).toHaveTextContent('60%40%30%–');
    expect(within(cohortRows[1]).getAllByText('–')).toHaveLength(8);
  });

  it('says so when there are no accounts yet', async () => {
    window.history.pushState({}, '', '/studio/admin/business');
    const empty: BusinessReport = {
      ...report,
      accounts: { total: 0, undated: 0, wentLive: 0, wentLivePercent: null },
      weeks: weeks(12),
      conversion: { everPaid: 0, everPaidPercent: null, payingNow: 0, payingNowPercent: null, inGrace: 0, endingAtPeriodEnd: 0, givenByAdmin: 0 },
      plans: [],
      mrr: { ...report.mrr, inr: 0, usd: 0 },
      churn: { ...report.churn, thisMonth: 0, lastMonth: 0, ratePercent: null },
      cohorts: [],
    };
    mockApi({ ...base, '/api/admin/business': empty });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText(/No creator accounts yet/)).toBeInTheDocument();
    expect(screen.getByText('No paying accounts yet.')).toBeInTheDocument();
    expect(screen.getByTestId('biz-went-live')).toHaveTextContent('–');
    expect(screen.getByTestId('biz-mrr')).toHaveTextContent(cashMajor(0, 'INR'));
  });

  it('formats week labels and money', () => {
    expect(weekLabel('2031-03-17')).toBe('17 Mar');
    expect(cashMajor(5077.4, 'INR')).toContain('5,077');
    expect(cashMajor(9, 'USD')).toBe('$9.00');
  });
});
