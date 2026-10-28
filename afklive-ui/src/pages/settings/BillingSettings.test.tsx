import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import type { BillingOverview, PlanView, PublicOffer, Quote } from '@/lib/types';
import { BillingSettings } from './BillingSettings';

const overview: BillingOverview = {
  plan: 'FREE',
  planName: 'Free',
  cycle: null,
  status: 'FREE',
  periodEnd: null,
  cancelAtPeriodEnd: false,
  pendingPlan: null,
  usage: { hoursUsed: 1, hoursIncluded: 10, storageUsed: 0, storageMax: 2 * 1024 ** 3, streamsLive: 0, streamsMax: 1 },
  nextCharge: null,
  invoices: [],
  unpaidBalance: 0,
  offer: null,
};

const plan = (over: Partial<PlanView>): PlanView => ({
  id: 'ESSENTIALS',
  name: 'Creator',
  concurrentStreams: 1,
  maxHeight: 1080,
  maxFps: 30,
  storageBytes: 25 * 1024 ** 3,
  monthlyHours: 0,
  watermark: false,
  destinationsPerStream: 1,
  currency: 'INR',
  monthly: 49900,
  yearly: 499000,
  monthlyLabel: '₹499',
  yearlyLabel: '₹4,990',
  ...over,
});

const plans = [plan({ id: 'FREE', name: 'Free', monthly: 0, yearly: 0, monthlyLabel: 'Free', yearlyLabel: 'Free' }), plan({})];

const offer: PublicOffer = {
  name: 'Launch offer',
  percentOff: 10,
  monthlyPeriods: 3,
  includeYearly: true,
  startsAt: '2026-11-01T00:00:00Z',
  endsAt: '2026-11-30T18:29:59Z',
  currency: 'INR',
  plans: [{ id: 'ESSENTIALS', name: 'Creator', listMonthly: 49900, offerMonthly: 44900, listMonthlyLabel: '₹499', offerMonthlyLabel: '₹449', listYearly: 499000, offerYearly: 449100, listYearlyLabel: '₹4,990', offerYearlyLabel: '₹4,491' }],
  eligible: true,
};

const quote: Quote = {
  kind: 'NEW',
  plan: 'ESSENTIALS',
  cycle: 'MONTHLY',
  currency: 'INR',
  price: 44900,
  credit: 0,
  amount: 44900,
  periodStart: '2026-11-02T00:00:00Z',
  periodEnd: '2026-12-02T00:00:00Z',
  listPrice: 49900,
  discount: 5000,
  offer: { id: 1, name: 'Launch offer', percentOff: 10, payments: 3, paymentsLeft: 2 },
  renewalPrice: 49900,
  gst: 6849,
};

function serve(currentOffer: PublicOffer | null) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'Content-Type': 'application/json' } });
      if (url.startsWith('/api/billing/offer')) return currentOffer ? json(currentOffer) : new Response(null, { status: 204 });
      if (url.startsWith('/api/billing/plans')) return json(plans);
      if (url.startsWith('/api/billing/quote')) return json(quote);
      if (url.startsWith('/api/billing/profile')) return json({});
      if (url.startsWith('/api/billing')) return json(overview);
      if (url.startsWith('/api/me')) return json({ email: 'a@b.c', currency: 'INR', traffic: null });
      return json({});
    }),
  );
}

const show = () =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <BillingSettings />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('Billing plans with and without a launch offer (BI-9)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows only list prices, GST included, when no offer runs', async () => {
    serve(null);
    show();
    expect(await screen.findByText('₹499')).toBeInTheDocument();
    expect(screen.getAllByText(/incl\. GST/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Launch offer/)).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Yearly, 2 months free (save 17%)' })).toBeInTheDocument();
  });

  it('shows the real list price struck next to the offer price, and a quote with the discount and what it renews at', async () => {
    serve(offer);
    show();
    expect(await screen.findByText('Launch offer: 10% off your first 3 months — ₹449/month, then ₹499.')).toBeInTheDocument();
    expect(screen.getByText('For new subscribers, until 30 Nov 2026.')).toBeInTheDocument();
    expect(screen.getByText('₹449')).toBeInTheDocument();
    expect(screen.getByText('List price')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Upgrade to Creator' }));
    expect(await screen.findByText('Launch offer, 10% off')).toBeInTheDocument();
    expect(screen.getByText('− ₹50')).toBeInTheDocument();
    expect(screen.getByText('Pay now (incl. GST)')).toBeInTheDocument();
    expect(screen.getByText(/Includes ₹68\.49 GST/)).toBeInTheDocument();
    expect(screen.getByText('10% off your first 3 months: ₹449 now and at each of the next 2 renewals, then ₹499 incl. GST a month.')).toBeInTheDocument();
  });

  it('hides the offer from an account that has had a plan', async () => {
    serve({ ...offer, eligible: false });
    show();
    expect(await screen.findByText('₹499')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText(/Launch offer:/)).not.toBeInTheDocument());
  });
});
