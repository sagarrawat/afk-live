import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { LiveState, Me } from '@/lib/types';
import { HoursLeft, hoursOf } from './HoursLeft';
import { PlannedStop } from './live-actions';
import { stopReason } from './status';
import { ToastProvider } from './ui';

let calls: string[] = [];
beforeEach(() => {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push(`${init?.method ?? 'GET'} ${String(input)}`);
    return init?.method === 'DELETE' && String(input).endsWith('/stop-at')
      ? new Response(null, { status: 204 })
      : new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

const show = (live: LiveState) =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <PlannedStop stream={{ id: 7, live }} timeZone="Asia/Kolkata" />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('a planned stop can be seen and taken back (GL-3)', () => {
  it('cancels a stop time', async () => {
    show({ state: 'LIVE', uptimeSeconds: 60, stopAt: '2026-10-26T17:30:00Z' });
    expect(screen.getByText(/^Stops /)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel the scheduled stop' }));
    await waitFor(() => expect(calls).toContain('DELETE /api/streams/7/stop-at'));
    expect(await screen.findByText('Scheduled stop cancelled. The stream keeps going.')).toBeInTheDocument();
  });

  it('shows "after this playlist pass" and cancels it', async () => {
    show({ state: 'LIVE', uptimeSeconds: 60, finishAfterPass: true });
    expect(screen.getByText('Ends after this playlist pass')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel ending after this playlist pass' }));
    await waitFor(() => expect(calls).toContain('DELETE /api/streams/7/finish-after-pass'));
  });

  it('shows nothing when no stop is planned, or the stream is already stopping', () => {
    const { rerender } = show({ state: 'LIVE', uptimeSeconds: 60 });
    expect(screen.queryByRole('button', { name: /^Cancel/ })).not.toBeInTheDocument();
    rerender(
      <QueryClientProvider client={makeQueryClient()}>
        <ToastProvider>
          <MemoryRouter>
            <PlannedStop stream={{ id: 7, live: { state: 'STOPPING', uptimeSeconds: 60, finishAfterPass: true } }} />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    );
    expect(screen.queryByText('Ends after this playlist pass')).not.toBeInTheDocument();
  });
});

describe('why a stream stopped', () => {
  it('says it in plain words, red only for problems', () => {
    expect(stopReason({ state: 'ENDED', endReason: "This month's free streaming hours are used up" })).toEqual({
      text: "Stopped because this month's free streaming hours are used up.",
      problem: false,
      hoursUsedUp: true,
    });
    expect(stopReason({ state: 'ENDED', endReason: 'Stopped by user' })?.text).toBe('Stopped by you.');
    expect(stopReason({ state: 'ENDED', endReason: 'Scheduled stop' })?.text).toBe('Stopped at its scheduled time.');
    expect(stopReason({ state: 'ENDED', endReason: 'Playlist finished' })?.text).toBe('Stopped after the playlist played through.');
    expect(stopReason({ state: 'ENDED', endReason: 'Phone input lost for too long' })?.text).toMatch(/phone was disconnected/);
    expect(stopReason({ state: 'ENDED', endReason: 'Stopped by AFK Live support: abuse' })).toMatchObject({ text: 'Stopped by AFK Live support: abuse', problem: false });
    expect(stopReason({ state: 'ERROR', endReason: 'Stream kept failing: connection refused' })).toMatchObject({ problem: true });
    expect(stopReason({ state: 'ERROR', endReason: null })?.text).toBe('Stopped with a problem');
    expect(stopReason({ state: 'DRAFT', endReason: null })).toBeNull();
    expect(stopReason({ state: 'LIVE', endReason: 'Stopped by user' })).toBeNull();
  });
});

describe('hours left this month (BI-1)', () => {
  const plan = (monthlyHours: number): Me['plan'] => ({ id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours, destinations: 1, watermark: false });

  it('only on plans with an hour limit, amber with an upgrade link from 80%', () => {
    expect(hoursOf({ plan: plan(0), hoursUsed: 50 })).toBeNull();
    expect(hoursOf({ plan: plan(10), hoursUsed: 3.5 })).toEqual({ used: 3.5, included: 10, left: 6.5, low: false });
    expect(hoursOf({ plan: plan(10), hoursUsed: 8 })?.low).toBe(true);

    const { rerender } = render(<MemoryRouter><HoursLeft me={{ plan: plan(10), hoursUsed: 3.5 }} /></MemoryRouter>);
    expect(screen.getByText('6.5 of 10 h left this month')).toHaveClass('text-muted');
    expect(screen.queryByRole('link', { name: 'Upgrade' })).not.toBeInTheDocument();
    rerender(<MemoryRouter><HoursLeft me={{ plan: plan(10), hoursUsed: 8.5 }} /></MemoryRouter>);
    expect(screen.getByText(/1\.5 of 10 h left this month/)).toHaveClass('text-warn');
    expect(screen.getByRole('link', { name: 'Upgrade' })).toHaveAttribute('href', '/studio/settings/billing');
    rerender(<MemoryRouter><HoursLeft me={{ plan: plan(10), hoursUsed: 10.2 }} /></MemoryRouter>);
    expect(screen.getByText(/No streaming hours left this month/)).toBeInTheDocument();
    rerender(<MemoryRouter><HoursLeft me={{ plan: plan(0), hoursUsed: 99 }} /></MemoryRouter>);
    expect(screen.queryByText(/left this month/)).not.toBeInTheDocument();
  });
});
