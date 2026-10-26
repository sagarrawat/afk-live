import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import { ToastProvider } from '@/components/ui';
import type { ScheduleView, StreamView } from '@/lib/types';
import { ScheduleTab } from './ScheduleTab';

HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };

const daily: ScheduleView = {
  id: 10, kind: 'DAILY', startAt: null, endAt: null, daysOfWeek: [], startTime: '05:00:00', endTime: '07:00:00',
  timeZone: 'Asia/Kolkata', skipDates: ['2026-12-25'], enabled: true, nextStart: '2026-10-27T23:30:00Z', lastError: null, playlist: [],
} as unknown as ScheduleView;

function stream(state: 'LIVE' | 'DRAFT'): StreamView {
  return {
    id: 7, name: 'Bhajans', source: 'PLAYLIST', orientation: 'LANDSCAPE', qualityHeight: 1080, fps: 30, timeZone: 'Asia/Kolkata',
    overlays: [], scenes: [], playlist: [], music: [], destinations: [], schedules: [daily], live: { state, uptimeSeconds: 0 },
  } as unknown as StreamView;
}

let calls: { method: string; path: string; body?: Record<string, unknown> }[] = [];
beforeEach(() => {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    calls.push({ method, path: String(input), body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (method === 'DELETE') return new Response(null, { status: 204 });
    const path = String(input).split('?')[0];
    const body = path === '/api/media' ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 24 } : path.startsWith('/api/media/') ? [] : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

const show = (s: StreamView) =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <ToastProvider>
        <MemoryRouter>
          <ScheduleTab stream={s} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );

describe('deleting a schedule asks first (UX audit 8)', () => {
  it('names the schedule, keeps pause separate, and deletes only on confirm', async () => {
    show(stream('DRAFT'));
    await userEvent.click(screen.getByRole('button', { name: 'Delete this schedule' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete Every day, 05:00 to 07:00?')).toBeInTheDocument();
    expect(within(dialog).getByText(/won't start by itself at these times/)).toBeInTheDocument();
    expect(within(dialog).getByText(/use the switch to pause it/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/live now keeps running/)).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep it' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: 'Delete this schedule' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete schedule' }));
    await waitFor(() => expect(calls).toContainEqual(expect.objectContaining({ method: 'DELETE', path: '/api/streams/7/schedules/10' })));
  });

  it('says the stream on air now keeps running', async () => {
    show(stream('LIVE'));
    await userEvent.click(screen.getByRole('button', { name: 'Delete this schedule' }));
    expect(within(screen.getByRole('dialog')).getByText("The stream that's live now keeps running; it won't start again from this schedule.")).toBeInTheDocument();
  });
});

describe('skipping days of a repeating schedule (SC-2)', () => {
  it('adds and removes skipped dates in the schedule editor', async () => {
    show(stream('DRAFT'));
    expect(screen.getByText(/Skips 1 day/)).toBeInTheDocument();
    await userEvent.click(screen.getByText('Every day, 05:00 to 07:00'));
    const dialog = screen.getByRole('dialog');
    const skipped = within(dialog).getByRole('list', { name: 'Skipped days' });
    expect(within(skipped).getAllByRole('listitem')).toHaveLength(1);

    await userEvent.type(within(dialog).getByLabelText('Day to skip'), '2026-11-08');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Skip this day' }));
    expect(within(skipped).getAllByRole('listitem')).toHaveLength(2);
    // Removing one: the × next to it.
    const christmas = within(skipped).getAllByRole('button').find((b) => /25/.test(b.getAttribute('aria-label') ?? ''))!;
    await userEvent.click(christmas);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save schedule' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')).toMatchObject({
      path: '/api/streams/7/schedules/10',
      body: { kind: 'DAILY', startTime: '05:00', endTime: '07:00', timeZone: 'Asia/Kolkata', skipDates: ['2026-11-08'] },
    });
  });
});
