import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import { allowanceReduces, GrandfatheredPanel, moreGenerous, PlanTermsPanel, raiseBody, type Grandfathered, type TermLimits, type TermsView } from './PlanTermsPanel';

const GB = 1024 ** 3;
const pro: TermLimits = { streams: 3, destinations: 2, maxHeight: 1080, maxFps: 60, storageBytes: 50 * GB, monthlyHours: 0, trafficGb: 6000, watermark: false };
const kept: TermLimits = { ...pro, destinations: 5, trafficGb: null };

const view: TermsView = {
  applies: true,
  why: null,
  plan: 'PRO',
  planName: 'Pro',
  capturedAt: '2026-09-01T10:00:00Z',
  recorded: kept,
  current: pro,
  effective: kept,
  extras: ['5 destinations (plan: 2)', 'no streaming data limit (plan: 6000 GB)'],
  resetReduces: ['destinations 5 → 2', 'streaming data no limit → 6000 GB'],
};

const list: Grandfathered = {
  rows: [{ userId: 42, email: 'early@example.com', plan: 'PRO', cycle: 'MONTHLY', status: 'ACTIVE', capturedAt: '2026-09-01T10:00:00Z', extras: ['5 destinations (plan: 2)'] }],
  countByPlan: { PRO: 1 },
  total: 1,
  truncated: false,
};

describe('admin: a subscriber’s plan limits and the grandfathered list (BI-10)', () => {
  let posts: { url: string; body: unknown }[] = [];
  let gets: string[] = [];
  let terms: TermsView = view;
  beforeEach(() => {
    posts = [];
    gets = [];
    terms = view;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === 'POST') posts.push({ url, body: JSON.parse(String(init.body)) });
        else gets.push(url);
        const body = url.includes('grandfathered') ? list : terms;
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = (ui: React.ReactNode) =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>
          <MemoryRouter>{ui}</MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    );

  it('works out what is more generous and what a change would take away', () => {
    expect(moreGenerous('destinations', kept, pro)).toBe(true);
    expect(moreGenerous('trafficGb', kept, pro)).toBe(true); // no limit beats 6,000 GB
    expect(moreGenerous('monthlyHours', kept, pro)).toBe(false); // both no limit
    // 4 destinations isn't more than the 5 they have, so only 4K is sent.
    expect(raiseBody({ streams: '', destinations: '4', maxHeight: '2160', maxFps: '', storageGb: '', monthlyHours: '', removeWatermark: false }, kept)).toEqual({ maxHeight: 2160 });
  });

  it('only raises what is more than in effect now', () => {
    const f = { streams: '', destinations: '8', maxHeight: '2160', maxFps: '', storageGb: '10', monthlyHours: '', removeWatermark: true };
    expect(raiseBody(f, kept)).toEqual({ destinations: 8, maxHeight: 2160 }); // 10 GB is less than 50 GB; no watermark already
    expect(raiseBody({ ...f, destinations: '', maxHeight: '' }, kept)).toBeNull();
    expect(allowanceReduces(8000, view)).toBe(true); // no limit → 8,000 GB
    expect(allowanceReduces(null, view)).toBe(false);
    expect(allowanceReduces(9000, { ...view, effective: { ...kept, trafficGb: 7000 } })).toBe(false);
  });

  it('shows the limits kept from their purchase next to the plan’s today', async () => {
    show(<PlanTermsPanel id={42} />);
    expect(await screen.findByTestId('terms-kept')).toHaveTextContent(/Kept from their purchase on .*2026/);
    const table = screen.getByRole('table', { name: 'Their limits' });
    const dest = within(table).getByText('Destinations each').closest('tr')!;
    expect(dest).toHaveTextContent('Destinations each525kept'); // kept 5, Pro today 2, in effect 5 (kept)
    const hours = within(table).getByText('Hours a month').closest('tr')!;
    expect(hours).not.toHaveTextContent('kept');
    expect(screen.getByText(/More than Pro gives today: 5 destinations \(plan: 2\)/)).toBeInTheDocument();
  });

  it('raises limits with a reason (e.g. 4K)', async () => {
    show(<PlanTermsPanel id={42} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Raise limits' }));
    expect(screen.getByRole('button', { name: 'Raise' })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(/Top quality/), '2160');
    await userEvent.type(screen.getByLabelText('Reason'), 'Agency trial');
    await userEvent.click(screen.getByRole('button', { name: 'Raise' }));
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/users/42/terms/raise', body: { limits: { maxHeight: 2160 }, reason: 'Agency trial' } }));
  });

  it('resetting to the plan warns what it takes away and needs a confirmation', async () => {
    show(<PlanTermsPanel id={42} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Reset to plan' }));
    expect(screen.getByText('destinations 5 → 2')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Reason'), 'Abuse of old limits');
    const button = screen.getByRole('button', { name: 'Reset limits' });
    expect(button).toBeDisabled();
    await userEvent.click(screen.getByLabelText('I understand they lose these'));
    await userEvent.click(button);
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/users/42/terms/reset', body: { reason: 'Abuse of old limits', confirm: true } }));
  });

  it('sets a data allowance, confirming when it gives less, and can clear it', async () => {
    show(<PlanTermsPanel id={42} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Data allowance' }));
    const gb = screen.getByLabelText('GB a month');
    await userEvent.clear(gb);
    await userEvent.type(gb, '8000');
    await userEvent.type(screen.getByLabelText('Reason'), 'Fair use');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/this gives them less/));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/users/42/terms/data-allowance', body: { gb: 8000, reason: 'Fair use', confirm: true } }));
  });

  it('says why when recorded limits don’t apply (Free account) and offers no actions', async () => {
    terms = { ...view, applies: false, why: 'Free accounts have no recorded limits: they get the Free plan’s limits.', recorded: null };
    show(<PlanTermsPanel id={42} />);
    expect(await screen.findByText(/Free accounts have no recorded limits/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Raise limits' })).toBeNull();
  });

  it('lists subscribers on older limits, filterable by plan, each linking to their page', async () => {
    show(<GrandfatheredPanel />);
    const link = await screen.findByRole('link', { name: 'early@example.com' });
    expect(link).toHaveAttribute('href', '/studio/admin/users/42');
    expect(screen.getByText(/5 destinations \(plan: 2\)/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'Pro (1)' }));
    await waitFor(() => expect(gets.some((u) => u.endsWith('/api/admin/grandfathered?plan=PRO'))).toBe(true));
  });
});
