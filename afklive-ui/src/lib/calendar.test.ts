import { addMonths, lanes, monthGrid, partOnDay, rangeLabel, weekStart } from './calendar';

describe('calendar dates', () => {
  it('starts weeks on Monday and sizes month grids to five or six weeks', () => {
    expect(weekStart('2026-11-04')).toBe('2026-11-02');
    expect(weekStart('2026-11-01')).toBe('2026-10-26'); // a Sunday
    expect(monthGrid('2026-02-14')).toEqual({ from: '2026-01-26', days: 35 });
    expect(monthGrid('2026-11-20')).toEqual({ from: '2026-10-26', days: 42 }); // Nov 1 is a Sunday
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-01');
  });

  it('splits an overnight run across the two days it touches', () => {
    const run = { startLocal: '2026-11-02T22:00', endLocal: '2026-11-03T02:00' };
    expect(partOnDay(run, '2026-11-02')).toEqual({ from: 1320, to: 1440 });
    expect(partOnDay(run, '2026-11-03')).toEqual({ from: 0, to: 120 });
    expect(partOnDay(run, '2026-11-04')).toBeNull();
    expect(partOnDay({ startLocal: '2026-11-02T09:00', endLocal: null }, '2026-11-02')).toEqual({ from: 540, to: 1440 });
    expect(rangeLabel('2026-11-02T22:00', '2026-11-03T02:00')).toMatch(/22:00 to .*3.*, 02:00$/);
  });

  it('puts overlapping blocks side by side', () => {
    const placed = lanes([
      { from: 600, to: 720 },
      { from: 660, to: 780 },
      { from: 800, to: 900 },
    ]);
    expect(placed.map((p) => p.lane)).toEqual([0, 1, 0]);
    expect(placed.map((p) => p.lanes)).toEqual([2, 2, 1]); // the later block overlaps nothing: full width
  });
});
