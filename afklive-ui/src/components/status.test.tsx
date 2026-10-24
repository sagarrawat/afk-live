import { render, screen } from '@testing-library/react';
import { badgeLabel, LiveBadge, isOnAir, reachProblem } from './status';
import { cssColor } from '@/pages/stream/OverlaysTab';
import { defaultQuality } from '@/pages/Home';
import { describe as describeSchedule, localInZone } from '@/pages/stream/ScheduleTab';

describe('status', () => {
  it('names states the way a creator would', () => {
    render(<LiveBadge state="LIVE" />);
    expect(screen.getByText('Live')).toBeInTheDocument();
    render(<LiveBadge state="DRAFT" />);
    expect(screen.getByText('Off air')).toBeInTheDocument();
  });

  it('says Live only while a platform is receiving the stream (RE-8)', () => {
    expect(badgeLabel('LIVE', 'ALL')).toBe('Live');
    expect(badgeLabel('LIVE', 'SOME')).toBe('Live with problems');
    expect(badgeLabel('LIVE', 'NONE')).toBe('Not reaching anyone');
    expect(badgeLabel('LIVE', 'CONNECTING')).toBe('Connecting');
    expect(badgeLabel('LIVE', null)).toBe('Live'); // an older server: the state alone
    expect(badgeLabel('STARTING', null)).toBe('Starting');
    expect(badgeLabel('STOPPING', 'ALL')).toBe('Stopping');
  });

  it('keeps red for on air: a stream nobody receives is shown as a problem, not live', () => {
    const { rerender } = render(<LiveBadge state="LIVE" reach="SOME" />);
    expect(screen.getByText('Live with problems')).toHaveClass('bg-live');
    rerender(<LiveBadge state="LIVE" reach="NONE" />);
    expect(screen.getByText('Not reaching anyone')).not.toHaveClass('bg-live');
    expect(screen.getByText('Not reaching anyone')).toHaveClass('text-bad');
    rerender(<LiveBadge state="LIVE" reach="CONNECTING" />);
    expect(screen.getByText('Connecting')).not.toHaveClass('bg-live');
  });

  it('names the destination that is not reaching its platform', () => {
    const destinations = [
      { name: 'Main', status: 'LIVE', level: 'green' as const, restarts: 0, reach: 'ON' as const, message: 'On YouTube' },
      { name: 'Twitch', status: 'RECONNECTING', level: 'red' as const, restarts: 3, reach: 'NOT_REACHING' as const, message: 'Not reaching Twitch. Twitch refused the stream key.' },
    ];
    const live = { state: 'LIVE' as const, uptimeSeconds: 60, reach: 'SOME' as const, health: { overall: 'red' as const, metrics: [], destinations } };
    expect(reachProblem(live)).toBe('Not reaching Twitch. Twitch refused the stream key.');
    expect(reachProblem({ ...live, reach: 'ALL' })).toBeNull();
    expect(reachProblem({ ...live, state: 'STOPPING' })).toBeNull();
  });

  it('knows which states are on air', () => {
    expect(isOnAir('LIVE')).toBe(true);
    expect(isOnAir('RECONNECTING')).toBe(true);
    expect(isOnAir('ENDED')).toBe(false);
    expect(isOnAir('ERROR')).toBe(false);
  });
});

describe('helpers', () => {
  it('turns ARGB colours into CSS', () => {
    expect(cssColor('#99000000')).toBe('rgba(0, 0, 0, 0.60)');
    expect(cssColor('#ff8800')).toBe('#ff8800');
    expect(cssColor(null, 'x')).toBe('x');
  });

  it('picks 1080p30 when allowed, otherwise the best 30 fps option', () => {
    expect(defaultQuality([{ height: 720, fps: 30, allowed: true }, { height: 1080, fps: 30, allowed: true }])).toBe('1080x30');
    expect(defaultQuality([{ height: 720, fps: 30, allowed: true }, { height: 1080, fps: 30, allowed: false }])).toBe('720x30');
    expect(defaultQuality([])).toBe('720x30');
  });

  it('describes schedules in words, in the schedule time zone', () => {
    const weekdays = { id: 1, kind: 'WEEKLY' as const, daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '06:00:00', endTime: '22:00:00', timeZone: 'Asia/Kolkata', skipDates: [], enabled: true };
    expect(describeSchedule(weekdays, 'Asia/Kolkata')).toBe('Weekdays, 06:00 to 22:00');
    expect(describeSchedule({ ...weekdays, kind: 'DAILY' }, 'Asia/Kolkata')).toBe('Every day, 06:00 to 22:00');
    expect(localInZone('2026-10-23T00:30:00Z', 'Asia/Kolkata')).toBe('2026-10-23T06:00');
  });
});
