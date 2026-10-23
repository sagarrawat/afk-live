import { bytes, clock, duration, guessCurrency, money, relative, timecode } from './format';
import { appLink } from './links';

describe('format', () => {
  it('timecode keeps a fixed width', () => {
    expect(timecode(0)).toBe('00:00:00');
    expect(timecode(3661)).toBe('01:01:01');
    expect(timecode(3 * 86400 + 4 * 3600 + 5)).toBe('3d 04:00:05');
    expect(timecode(-4)).toBe('00:00:00');
  });

  it('duration reads naturally', () => {
    expect(duration(40)).toBe('40 s');
    expect(duration(12 * 60)).toBe('12 m');
    expect(duration(2 * 3600 + 5 * 60)).toBe('2 h 05 m');
    expect(duration(3 * 86400 + 4 * 3600)).toBe('3 d 4 h');
    expect(duration(null)).toBe('–');
  });

  it('clock and bytes', () => {
    expect(clock(65)).toBe('1:05');
    expect(clock(3725)).toBe('1:02:05');
    expect(bytes(512)).toBe('512 B');
    expect(bytes(1536)).toBe('1.5 KB');
    expect(bytes(25 * 1024 ** 3)).toBe('25 GB');
    expect(bytes(2 * 1024 ** 3)).toBe('2 GB');
  });

  it('money from minor units', () => {
    expect(money(49_900, 'INR')).toBe('₹499');
    expect(money(900, 'USD')).toBe('$9.00');
  });

  it('relative times', () => {
    const now = Date.parse('2026-10-23T12:00:00Z');
    expect(relative('2026-10-23T11:59:50Z', now)).toBe('just now');
    expect(relative('2026-10-23T11:00:00Z', now)).toBe('1 h ago');
  });

  it('currency guess from the time zone', () => {
    expect(guessCurrency('Asia/Kolkata')).toBe('INR');
    expect(guessCurrency('America/New_York')).toBe('USD');
  });
});

describe('appLink', () => {
  it('maps server links and pre-flight fixes to app routes', () => {
    expect(appLink('/streams/5')).toBe('/studio/streams/5');
    expect(appLink('/streams/5/schedule')).toBe('/studio/streams/5/schedule');
    expect(appLink('/settings#billing')).toBe('/studio/settings/billing');
    expect(appLink('/pricing')).toBe('/studio/settings/billing');
    expect(appLink('/library')).toBe('/studio/library');
    expect(appLink('destinations', 7)).toBe('/studio/streams/7/destinations');
    expect(appLink('music', 7)).toBe('/studio/streams/7/audio');
    expect(appLink(null)).toBeNull();
    expect(appLink('https://example.com')).toBe('https://example.com');
  });
});
