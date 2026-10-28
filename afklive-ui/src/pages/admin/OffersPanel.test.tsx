import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import { blankOffer, NewSignupLimits, offerBody, offerProblem, Offers, type LimitsPreview, type OfferRow } from './OffersPanel';

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

describe('admin: launch offers and limits for new sign-ups (BI-9, BI-10)', () => {
  let posts: { url: string; body: unknown }[] = [];
  beforeEach(() => {
    posts = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === 'POST') posts.push({ url, body: JSON.parse(String(init.body)) });
        const body = url.includes('plan-limits') ? preview : url.includes('/offers') && init?.method !== 'POST' ? [row] : row;
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
