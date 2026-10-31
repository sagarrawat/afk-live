import { act, render, screen } from '@testing-library/react';
import { get } from '@/lib/api';
import { UpdatingNotice } from './UpdatingNotice';

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('UpdatingNotice', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('shows "Updating, reconnecting…" while the API answers "updating", and goes away when it is back', async () => {
    vi.useFakeTimers();
    let back = false;
    vi.stubGlobal('fetch', vi.fn(async () => (back ? json({ ok: true }) : json({ error: 'updating', retryAfter: 30 }, 503))));
    render(<UpdatingNotice />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    const pending = get('/api/streams');
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByRole('status')).toHaveTextContent('Updating, reconnecting…');
    expect(screen.getByRole('status')).toHaveTextContent('Your live streams keep running.');

    back = true;
    await act(() => vi.advanceTimersByTimeAsync(3_000));
    await pending;
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
