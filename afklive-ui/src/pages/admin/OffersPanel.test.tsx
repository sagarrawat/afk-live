import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import { blankOffer, formOf, istDay, NewSignupLimits, offerBody, offerChanges, offerProblem, Offers, type LimitsPreview, type OfferResults, type OfferRow } from './OffersPanel';

const preview: LimitsPreview = {
  alreadyApplied: false,
  plans: [
    {
      plan: 'ESSENTIALS',
      name: 'Creator',
      current: { streams: 1, destinations: 2, maxHeight: 1080, maxFps: 30, trafficGb: null },
      recommended: { streams: 1, destinations: 1, maxHeight: 1080, maxFps: 30, trafficGb: 2000 },
      changes: ['destinations 2 → 1', 'trafficGb unlimited → 2000'],
      protectedSubscribers: 4,
      affectedAccounts: 0,
      affectedNote: 'Nobody who pays today is affected: their limits are recorded and kept.',
    },
  ],
};

const row: OfferRow = {
  id: 7,
  name: 'Launch offer',
  code: 'LAUNCH10',
  automatic: true,
  percentOff: 10,
  monthlyPeriods: 3,
  includeYearly: true,
  plans: ['ESSENTIALS', 'PRO', 'AGENCY'],
  startsAt: '2026-11-01T00:00:00Z',
  endsAt: '2026-11-30T18:29:59Z',
  endedAt: null,
  status: 'ACTIVE',
  redemptions: 12,
  createdBy: 'owner@afklive.in',
  createdReason: 'Launch',
  endedBy: null,
  endedReason: null,
};

const results: OfferResults = {
  offerId: 7,
  name: 'Launch offer',
  status: 'ACTIVE',
  from: '2026-11-01T00:00:00Z',
  to: '2026-11-20T00:00:00Z',
  redemptions: 3,
  byPlan: [
    { plan: 'ESSENTIALS', cycle: 'MONTHLY', accounts: 2 },
    { plan: 'PRO', cycle: 'YEARLY', accounts: 1 },
  ],
  discount: [{ currency: 'INR', payments: 4, discountWithGst: 230_000, discountWithoutGst: 194_916, paid: 2_070_000 }],
  inDiscountedPeriod: 2,
  discountedPaymentsToCome: 1,
  finishedOffer: 2,
  renewedAtFullPrice: 1,
  upgradedAfter: 0,
  signUps: 4,
  signUpsTookOffer: 3,
  conversionPercent: 75,
  signUpsPaidAnyPlan: 3,
  codeOnly: false,
};

describe('admin: launch offers and limits for new sign-ups (BI-9, BI-10)', () => {
  let posts: { url: string; body: unknown }[] = [];
  let current: OfferRow = row;
  beforeEach(() => {
    posts = [];
    current = row;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === 'POST') posts.push({ url, body: JSON.parse(String(init.body)) });
        const body = url.includes('plan-limits') ? preview : url.includes('/results') ? results : url.includes('/offers') && init?.method !== 'POST' ? [current] : current;
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = (ui: React.ReactNode) =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>{ui}</ToastProvider>
      </QueryClientProvider>,
    );

  it('checks an offer before sending it: real end date, sane discount, a code when not automatic', () => {
    const f = blankOffer(new Date('2026-11-01T06:00:00Z'));
    expect(offerProblem(f)).toBeNull();
    expect(offerProblem({ ...f, endsAt: '' })).toMatch(/end date/);
    expect(offerProblem({ ...f, percentOff: '80' })).toMatch(/1 to 50/);
    expect(offerProblem({ ...f, automatic: false })).toMatch(/needs a code/);
    expect(offerProblem({ ...f, plans: [] })).toMatch(/at least one plan/);
    const body = offerBody({ ...f, code: 'launch10', endsAt: '2026-11-30' }, new Date('2026-11-01T06:00:00Z'));
    expect(body.code).toBe('LAUNCH10');
    expect(body.endsAt).toBe('2026-11-30T18:29:59.000Z'); // the end of the last day, India time
    expect(body.startsAt).toBe('2026-11-01T06:00:00.000Z'); // never in the past
  });

  it('lists offers and ends one with a reason', async () => {
    show(<Offers />);
    expect(await screen.findByText(/10% off the first 3 months or the first year on Creator, Pro, Agency, applied by itself/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'End now' }));
    expect(screen.getByText(/keep the discounted renewals they were promised/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Reason'), 'Enough sign-ups');
    await userEvent.click(screen.getByRole('button', { name: 'End offer' }));
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/offers/7/end', body: { reason: 'Enough sign-ups' } }));
  });

  it('sends only what changed when editing an offer, with India-time days', () => {
    const f = formOf(row);
    expect(f.startsAt).toBe(istDay(row.startsAt));
    expect(offerChanges(row, f)).toEqual({});
    expect(offerChanges(row, { ...f, name: ' Diwali offer ', code: '' })).toEqual({ name: 'Diwali offer', code: '' });
    expect(offerChanges(row, { ...f, plans: ['AGENCY', 'PRO', 'ESSENTIALS'] })).toEqual({}); // same plans, another order
    expect(offerChanges(row, { ...f, percentOff: '20', plans: ['PRO'] })).toEqual({ percentOff: 20, plans: ['PRO'] });
    expect(offerChanges(row, { ...f, endsAt: '2026-12-15' })).toEqual({ endsAt: '2026-12-15T18:29:59.000Z' });
    expect(offerChanges(row, { ...f, endsAt: '' })).toEqual({}); // cleared while typing: never an invalid time
    expect(offerProblem({ ...f, endsAt: '' })).toMatch(/end date/);
  });

  it('edits a running offer with a reason; once someone paid with it the discount, plans and months are locked and say why', async () => {
    current = { ...row, termsLocked: '12 accounts have paid with this offer, so the discount, plans and months they were promised are fixed.' };
    show(<Offers />);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(screen.getByTestId('offer-locked')).toHaveTextContent('12 accounts have paid with this offer');
    expect(screen.getByLabelText('Discount (%)')).toBeDisabled();
    expect(screen.getByLabelText('Monthly plans: discounted months')).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Pro' })).toBeDisabled();
    expect(screen.getByLabelText('Starts (India time)')).toBeDisabled(); // it has started
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled(); // nothing changed yet
    const end = screen.getByLabelText('Last day (India time)');
    await userEvent.clear(end);
    await userEvent.type(end, '2027-01-15');
    await userEvent.type(screen.getByLabelText('Reason'), 'Extend for the new year');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() =>
      expect(posts).toContainEqual({ url: '/api/admin/offers/7', body: { offer: { endsAt: '2027-01-15T18:29:59.000Z' }, reason: 'Extend for the new year' } }),
    );
  });

  it('a scheduled offer that nobody took can change everything, including its start', async () => {
    current = { ...row, status: 'SCHEDULED', startsAt: '2099-01-01T00:00:00Z', endsAt: '2099-01-31T18:29:59Z', redemptions: 0, termsLocked: null };
    show(<Offers />);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(screen.queryByTestId('offer-locked')).toBeNull();
    expect(screen.getByLabelText('Starts (India time)')).toBeEnabled();
    const pct = screen.getByLabelText('Discount (%)');
    await userEvent.clear(pct);
    await userEvent.type(pct, '15');
    await userEvent.type(screen.getByLabelText('Reason'), 'Better launch');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/offers/7', body: { offer: { percentOff: 15 }, reason: 'Better launch' } }));
  });

  it('shows how an offer did: redemptions by plan, discount with and without GST, renewals and conversion', async () => {
    show(<Offers />);
    await userEvent.click(await screen.findByRole('button', { name: 'Results' }));
    const box = await screen.findByTestId('offer-results-7');
    expect(box).toHaveTextContent('3 accounts');
    expect(box).toHaveTextContent('Creator monthly: 2 · Pro yearly: 1');
    expect(box).toHaveTextContent('₹2,300 with GST, ₹1,949.16 without over 4 payments');
    expect(box).toHaveTextContent('2 in a discounted period now; 1 with discounted renewals still to come');
    expect(box).toHaveTextContent('1 of 2 who used it up renewed at full price (50%)');
    expect(box).toHaveTextContent('75% took it: 3 of 4 new accounts');
  });

  it('previews who new limits affect and applies them only with a reason', async () => {
    show(<NewSignupLimits />);
    expect(await screen.findByText(/4 paying subscribers keep their limits/)).toBeInTheDocument();
    expect(screen.getByText(/1 stream, 1 destination each, 1080p30, 2,000 GB a month/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Apply recommended limits' }));
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Margins for new sign-ups');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(posts).toContainEqual({ url: '/api/admin/plan-limits/recommended', body: { reason: 'Margins for new sign-ups' } }));
  });
});
