import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
import { UptimePanel } from './OverviewTab';

const uptime = {
  windows: [
    { label: '24 hours', onAirSeconds: 36000, downSeconds: 1800, percent: 95 },
    { label: '7 days', onAirSeconds: 36000, downSeconds: 1800, percent: 95 },
    { label: '30 days', onAirSeconds: 0, downSeconds: 0, percent: null },
  ],
  outages: [{ startedAt: '2026-10-23T06:00:00Z', resolvedAt: '2026-10-23T06:30:00Z' }],
};

describe('UptimePanel (RE-4)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the share per window and the last outage', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(uptime), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    render(<QueryClientProvider client={makeQueryClient()}><UptimePanel stream={{ id: 3, timeZone: 'Asia/Kolkata' } as StreamView} /></QueryClientProvider>);
    expect(await screen.findAllByText('95%')).toHaveLength(2);
    expect(screen.getByText('–')).toBeInTheDocument();
    expect(screen.getByText(/back after 30/)).toBeInTheDocument();
  });
});
