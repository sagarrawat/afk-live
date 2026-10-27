import { render, screen, within } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { cash, type CopyStats, type CostReport, type WorstCase } from './CostPanel';

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
const worst: WorstCase = {
  month: '2026-10',
  inputs: { currency: 'INR', pricePerGb: 0.85, serverPerStreamHour: null, serverSource: 'unset', gatewayFeePercent: 2 },
  maxCostShare: 0.6,
  plans: [
    { plan: 'FREE', name: 'Free', streams: 1, destinations: 1, quality: '720p 30 fps', videoKbps: 4000, streamHours: 10, trafficGb: null, fullUseGb: 18, worstGb: 18, netPrice: 0, trafficCost: 15.3, worstCost: 15.3 },
    {
      plan: 'ESSENTIALS', name: 'Creator', streams: 1, destinations: 2, quality: '1080p 30 fps', videoKbps: 6000, streamHours: 720, trafficGb: 2000, fullUseGb: 3846,
      worstGb: 2923, netPrice: 414.42, trafficCost: 2484.6, worstCost: 2484.6, worstMargin: -2070.2, costShare: 5.995, meetsRule: false,
      typicalAccounts: 3, typicalGb: 120, typicalHours: 90, typicalCost: 102, typicalMargin: 312.42, typicalShare: 0.246,
    },
  ],
  suggestedAllowanceGb: {},
};

const base = {
  '/api/me': admin,
  '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
  '/api/admin/overview': { revenue: [] },
  '/api/admin/cost': { currency: 'INR', streamHours30d: 0 },
  '/api/admin/plans': [],
  '/api/admin/costs/worst-case': worst,
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

const report: CostReport = {
  cost: {
    inputs: { monthlyServerCost: 4000, currency: 'INR', includedGb: 5000, includedDefault: true, pricePerGb: 0.85, priceDefault: true },
    month: '2026-10',
    monthElapsed: 0.5,
    totals: {
      hours: 300,
      gb: 3800,
      projectedHours: 600,
      projectedGb: 7600,
      usedPercent: 76,
      projectedPercent: 152,
      serverPerHour: 6.6667,
      trafficCostMonth: 2210,
      trafficPerHour: 3.6833,
      marginalTrafficPerHour: 10.77,
      totalPerHour: 10.35,
      projectedCostMonth: 6210,
    },
    byPlan: [
      { key: 'FREE', hours: 200, gb: 1800, gbPerHour: 9, serverPerHour: 6.6667, trafficPerHour: 2.62, marginalTrafficPerHour: 7.65, totalPerHour: 9.29 },
      { key: 'PRO', hours: 100, gb: 2000, gbPerHour: 20, serverPerHour: 6.6667, trafficPerHour: 5.82, marginalTrafficPerHour: 17, totalPerHour: 12.49 },
    ],
    byQuality: [
      { key: '720p 30 fps', hours: 200, gb: 1800, gbPerHour: 9, serverPerHour: 6.6667, trafficPerHour: 2.62, marginalTrafficPerHour: 7.65, totalPerHour: 9.29 },
      { key: '1080p 60 fps', hours: 100, gb: 2000, gbPerHour: 20, serverPerHour: 6.6667, trafficPerHour: 5.82, marginalTrafficPerHour: 17, totalPerHour: 12.49 },
    ],
    margins: [
      { plan: 'FREE', name: 'Free', accounts: 100, netPrice: 0, hoursPerAccount: 4, costPerAccount: 37.16, marginPerAccount: -37.16, marginPercent: null, fullUseHours: 10, fullUseCost: 92.9, fullUseMargin: -92.9 },
      { plan: 'PRO', name: 'Pro', accounts: 4, netPrice: 1270.25, hoursPerAccount: 50, costPerAccount: 624.5, marginPerAccount: 645.75, marginPercent: 51, fullUseHours: 720, fullUseCost: 8992.8, fullUseMargin: -7722.55 },
    ],
  },
  cpu: { percentPerStream: 14.2, averageStreams: 3.1, samples: 9000 },
  topAccounts: [{ key: 'aarti@example.com', userId: 42, plan: 'PRO', hours: 100, gb: 2000 }],
  byServer: [{ key: 'worker-mumbai-1', hours: 300, gb: 3800 }],
};

describe('Admin billing: cost per stream-hour', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the cost per stream-hour, traffic against the included transfer, plans, qualities and margins, labelled as an estimate', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({ ...base, '/api/admin/costs': report });
    render(<App client={makeQueryClient()} />);

    expect(await screen.findByText('Cost per stream-hour (estimate)')).toBeInTheDocument();
    expect(screen.getByTestId('cost-per-hour')).toHaveTextContent('₹10.35');
    expect(screen.getAllByText('3,800 GB').length).toBeGreaterThan(0);
    // 76% of the included transfer, on an amber meter, with the projection.
    expect(screen.getByRole('meter', { name: 'Included transfer, 2026-10' })).toHaveAttribute('aria-valuenow', '76');
    expect(screen.getByText(/152% by month end/)).toBeInTheDocument();
    expect(screen.getByText(/alerted at 70% and 90%/)).toBeInTheDocument();
    // By plan uses plan names; by quality lists both.
    expect(screen.getByRole('heading', { name: 'By plan' })).toBeInTheDocument();
    expect(screen.getAllByText('1080p 60 fps').length).toBeGreaterThan(0);
    // Margins: Pro is positive on average and negative at full use.
    const proRow = screen.getAllByRole('row').find((r) => within(r).queryByText('Pro') && within(r).queryByText(/51%/));
    expect(proRow).toBeTruthy();
    expect(within(proRow!).getByText('-₹7,722.55')).toHaveClass('text-bad');
    // The inputs, with defaults called out.
    const inputs = screen.getByRole('list', { name: 'Inputs' });
    expect(within(inputs).getByText(/DigitalOcean 4 vCPU/)).toBeInTheDocument();
    expect(within(inputs).getByText(/14.2% of a server per stream/)).toBeInTheDocument();
    expect(screen.getByText('aarti@example.com')).toBeInTheDocument();
    expect(screen.getByText('worker-mumbai-1')).toBeInTheDocument();
  });

  it('says what is missing when there is no data and no server bill', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    const empty: CostReport = {
      ...report,
      cost: {
        ...report.cost,
        inputs: { ...report.cost.inputs, monthlyServerCost: null, currency: 'EUR', pricePerGb: null },
        totals: { hours: 0, gb: 0, projectedHours: 0, projectedGb: 0, usedPercent: 0, projectedPercent: 0 },
        byPlan: [],
        byQuality: [],
        margins: [],
      },
      cpu: { samples: 0 },
      topAccounts: [],
      byServer: [],
    };
    mockApi({ ...base, '/api/admin/costs': empty });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Cost per stream-hour (estimate)')).toBeInTheDocument();
    expect(screen.getByTestId('cost-per-hour')).toHaveTextContent('–');
    expect(screen.getAllByText('No streams this month yet.')).toHaveLength(2);
    const inputs = screen.getByRole('list', { name: 'Inputs' });
    expect(within(inputs).getByText('APP_MONTHLY_SERVER_COST')).toBeInTheDocument();
    expect(within(inputs).getByText('APP_TRANSFER_PRICE_PER_GB')).toBeInTheDocument();
    expect(within(inputs).getByText(/no server samples/)).toBeInTheDocument();
  });

  it('warns when streams ran but no traffic was reported yet', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({ ...base, '/api/admin/costs': { ...report, cost: { ...report.cost, totals: { ...report.cost.totals, gb: 0, usedPercent: 0 } } } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText(/No traffic recorded yet/)).toBeInTheDocument();
  });

  it('shows prepared copies and their storage on Servers', async () => {
    window.history.pushState({}, '', '/studio/admin/servers');
    const copies: CopyStats = {
      kinds: [
        { kind: 'PLAYOUT', ready: 12, bytes: 3 * 1024 ** 3, unknownSize: 0, waiting: 1, failed: 0, dueForDeletion: 2 },
        { kind: 'PROFILE', ready: 3, bytes: 1024 ** 3, unknownSize: 1, waiting: 0, failed: 1, dueForDeletion: 0 },
        { kind: 'PROFILE_OVERLAY', ready: 2, bytes: 1024 ** 3, unknownSize: 0, waiting: 0, failed: 0, dueForDeletion: 0 },
      ],
      ready: 17,
      bytes: 5 * 1024 ** 3,
      keepDays: 30,
    };
    mockApi({ ...base, '/api/admin/workers': [], '/api/admin/servers/history': [], '/api/admin/copies': copies, '/api/admin/quota': { usedToday: 0, dailyLimit: 10000, percent: 0, resetsAt: new Date().toISOString(), breakdown: [] }, '/api/admin/incidents': [] });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Prepared copies')).toBeInTheDocument();
    expect(screen.getByText(/nobody plays for 30 days are deleted/)).toBeInTheDocument();
    expect(screen.getByText(/2 due for deletion/)).toBeInTheDocument();
    expect(screen.getByText(/1 not measured yet/)).toBeInTheDocument();
    expect(screen.getByText(/with a logo or the Free mark/)).toBeInTheDocument();
  });

  it('shows each plan’s worst case against 60% of its net price, and its typical case (FO-5)', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({ ...base, '/api/admin/costs': report });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Worst case per plan (estimate)')).toBeInTheDocument();
    const creator = screen.getByTestId('worst-ESSENTIALS');
    expect(within(creator).getByText('2,000 GB')).toBeInTheDocument();
    expect(within(creator).getByText('2,923 GB')).toHaveAttribute('title', '3,846 GB without the allowance');
    expect(within(creator).getByText('₹414')).toBeInTheDocument();
    expect(within(creator).getByText('600% over 60%')).toHaveClass('text-bad');
    expect(within(creator).getByText('₹102 (25%)')).toBeInTheDocument();
    const free = screen.getByTestId('worst-FREE');
    expect(within(free).getByText('free')).toBeInTheDocument();
    expect(within(free).getByText('no data yet')).toBeInTheDocument();
    const inputs = screen.getByRole('list', { name: 'Worst case inputs' });
    expect(within(inputs).getByText(/only traffic is counted/)).toBeInTheDocument();
    expect(within(inputs).getByText(/2% gateway fee/)).toBeInTheDocument();
  });

  it('formats small per-hour amounts with enough digits', () => {
    expect(cash(0.0425, 'USD')).toBe('$0.043');
    expect(cash(12.5, 'INR')).toBe('₹12.50');
    expect(cash(null, 'INR')).toBe('–');
    expect(cash(5, 'XXX1')).toBe('5.00 XXX1');
  });
});
