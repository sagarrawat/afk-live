import { arc, dialSvg, point, slotAt, uptime, DAY } from './dial';

describe('hero dial', () => {
  it('puts midnight at the top and 6 am on the right', () => {
    expect(point(0, 10)).toEqual([0, -10]);
    expect(point(6, 10)).toEqual([10, 0]);
  });

  it('draws slots that wrap past midnight the long way only when they are long', () => {
    expect(arc(23.5, 5.5, 74)).toContain(' 0 0 1 '); // 6 hours: small arc
    expect(arc(7, 21, 74)).toContain(' 0 1 1 '); // 14 hours: large arc
  });

  it('covers every minute of the day with exactly one slot', () => {
    for (let m = 0; m < 24 * 60; m += 15) {
      const h = m / 60;
      const hits = DAY.filter((s) => (s.from < s.to ? h >= s.from && h < s.to : h >= s.from || h < s.to));
      expect(hits).toHaveLength(1);
    }
    expect(slotAt(6).label).toBe('Morning aarti');
    expect(slotAt(2).label).toBe('Lofi playlist on loop');
  });

  it('formats the on-air counter', () => {
    expect(uptime(3 * 86400 + 4 * 3600 + 5 * 60 + 9)).toBe('3d 04:05:09');
  });

  it('renders one path per slot', () => {
    expect(dialSvg().match(/dial-slot /g)).toHaveLength(DAY.length);
  });
});
