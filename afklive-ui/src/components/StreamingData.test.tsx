import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Traffic } from '@/lib/types';
import { limitedTraffic, resetDay, StreamingDataBanner, StreamingDataLine, streamingDataNotice } from './StreamingData';

const t = (over: Partial<Traffic>): Traffic => ({
  month: '2026-10',
  usedGb: 420,
  planGb: 2000,
  grantedGb: 0,
  allowanceGb: 2000,
  percent: 21,
  step: 'NONE',
  resetsAt: '2026-11-01T00:00:00Z',
  ...over,
});

const inRouter = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('streaming data this month (FO-5)', () => {
  it('shows nothing on a plan without an allowance', () => {
    expect(limitedTraffic({ traffic: t({ allowanceGb: null, planGb: null, percent: null }) })).toBeNull();
    expect(limitedTraffic({ traffic: null })).toBeNull();
    const { container } = inRouter(<StreamingDataLine me={{ traffic: t({ allowanceGb: null }) }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('says how much is used in plain words, amber with an upgrade link from 80%', () => {
    inRouter(<StreamingDataLine me={{ traffic: t({}) }} />);
    expect(screen.getByText(/Streaming data: 420 GB of 2,000 GB this month/)).toHaveClass('text-muted');
    expect(screen.queryByRole('link', { name: 'Upgrade' })).toBeNull();
  });

  it('turns amber with an upgrade link from 80%', () => {
    inRouter(<StreamingDataLine me={{ traffic: t({ usedGb: 1700, percent: 85, step: 'WARNED' }) }} />);
    expect(screen.getByText(/1,700 GB of 2,000 GB/)).toHaveClass('text-warn');
    expect(screen.getByRole('link', { name: 'Upgrade' })).toHaveAttribute('href', '/studio/settings/billing');
  });

  it('explains each step past the allowance, and that streams stay live', () => {
    expect(resetDay(t({}))).toBe('1 Nov');
    expect(streamingDataNotice(t({ step: 'WARNED' }))).toBeNull();
    expect(streamingDataNotice(t({ step: 'EXTRAS_PAUSED' }))).toMatch(/stay live, each going to its first destination only, until 1 Nov/);
    expect(streamingDataNotice(t({ step: 'REDUCED' }))).toMatch(/start before 1 Nov go out in 720p/);
  });

  it('shows a banner with the way out once the allowance is used up', () => {
    const { rerender } = inRouter(<StreamingDataBanner me={{ traffic: t({ step: 'WARNED' }) }} />);
    expect(screen.queryByRole('status')).toBeNull();
    rerender(
      <MemoryRouter>
        <StreamingDataBanner me={{ traffic: t({ usedGb: 2100, percent: 105, step: 'EXTRAS_PAUSED' }) }} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/first destination only/);
    expect(screen.getByRole('link', { name: 'Upgrade for more' })).toHaveAttribute('href', '/studio/settings/billing');
    expect(screen.getByRole('link', { name: 'How streaming data works' })).toHaveAttribute('href', '/help/streaming-data');
  });
});
