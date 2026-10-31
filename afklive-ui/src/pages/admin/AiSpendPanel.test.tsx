import { render, screen } from '@testing-library/react';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { usd, type AiSpend } from './AiSpendPanel';

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

function mockApi(routes: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
      const body = routes[path];
      return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const base = {
  '/api/me': admin,
  '/api/notifications': { unread: 0, emailAlerts: true, items: [] },
  '/api/admin/overview': { revenue: [] },
  '/api/admin/plans': [],
};

const spend: AiSpend = {
  month: '2026-10',
  provider: 'anthropic',
  model: 'claude-haiku-5-5',
  spentUsd: 8.4,
  budgetUsd: 10,
  percent: 84,
  stopped: false,
  inputPerMillion: 0.1,
  outputPerMillion: 0.5,
  rows: [{ provider: 'anthropic', model: 'claude-haiku-5-5', calls: 12000, inputTokens: 24000000, outputTokens: 12000000, costUsd: 8.4 }],
};

describe('Admin billing: AI spend', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows this month’s AI spend against the cap, by model, with the prices used', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({ ...base, '/api/admin/ai-spend': spend });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('AI spend')).toBeInTheDocument();
    expect(screen.getByText('Claude (Anthropic)')).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'This month (2026-10, UTC)' })).toHaveAttribute('aria-valuenow', '84');
    expect(screen.getByText('$8.40 of $10.00 (84%)')).toBeInTheDocument();
    expect(screen.getByText('12,000')).toBeInTheDocument();
    expect(screen.getByText('24,000,000')).toBeInTheDocument();
    expect(screen.getByText(/alerted at 80% and 100%/)).toBeInTheDocument();
    expect(screen.queryByTestId('ai-stopped')).not.toBeInTheDocument();
  });

  it('says when AI is off and when the cap stopped it', async () => {
    window.history.pushState({}, '', '/studio/admin/billing');
    mockApi({ ...base, '/api/admin/ai-spend': { ...spend, provider: null, model: null, spentUsd: 10.02, percent: 100.2, stopped: true } });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText(/AI writing is off/)).toBeInTheDocument();
    expect(screen.getByTestId('ai-stopped')).toHaveTextContent('until the month ends');
  });

  it('formats dollars with enough digits for small amounts', () => {
    expect(usd(0.0027)).toBe('$0.0027');
    expect(usd(8.4)).toBe('$8.40');
    expect(usd(12.345)).toBe('$12.35');
  });
});
