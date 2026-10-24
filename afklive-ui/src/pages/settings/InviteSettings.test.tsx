import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import { InviteSettings, type ReferralSummary } from './InviteSettings';

describe('Invite creators (GR-18)', () => {
  let summary: ReferralSummary;
  let puts: unknown[] = [];
  beforeEach(() => {
    puts = [];
    summary = { link: 'https://afklive.in/?ref=abcd2345', joined: 2, waitingForFirstLive: 2, credited: 0, creditLine: false, creditText: 'Streamed 24/7 with AFK Live: https://afklive.in/?ref=abcd2345', payoutsLive: false };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          const body = JSON.parse(String(init.body));
          puts.push(body);
          summary = { ...summary, creditLine: body.enabled };
        }
        return new Response(JSON.stringify(summary), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  const show = () =>
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>
          <InviteSettings />
        </ToastProvider>
      </QueryClientProvider>,
    );

  it('shows the link and who joined, and is honest that free months wait for a confirmed first live', async () => {
    show();
    expect(await screen.findByText('Invite creators')).toBeInTheDocument();
    expect(screen.getByText('https://afklive.in/?ref=abcd2345')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy Your invite link' })).toBeInTheDocument();
    expect(screen.getByText('Waiting for first live')).toBeInTheDocument();
    expect(screen.getByText(/added once we can confirm the creator's first live reached YouTube/)).toBeInTheDocument();
  });

  it('keeps the credit line off until the creator turns it on, and says it is never on screen', async () => {
    show();
    const toggle = await screen.findByRole('switch', { name: 'Add the credit line' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText(/never shown on your stream/)).toBeInTheDocument();
    await userEvent.click(toggle);
    await waitFor(() => expect(puts).toEqual([{ enabled: true }]));
  });
});
