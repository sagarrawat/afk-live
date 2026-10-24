import { describe, expect, it } from 'vitest';
import { nowPlayingTitle } from './now-playing';

describe('nowPlayingTitle', () => {
  it('shows the title from the JSON the worker stores, not the raw JSON', () => {
    const raw = '{"mediaId":"4","title":"Minecraft Parkour.mp4","elapsed":130,"duration":600,"next":[{"title":"x","mediaId":"4"}]}';
    expect(nowPlayingTitle(raw)).toBe('Minecraft Parkour.mp4');
  });
  it('keeps plain text and handles empty or broken values', () => {
    expect(nowPlayingTitle('Lofi playlist')).toBe('Lofi playlist');
    expect(nowPlayingTitle('{"broken"')).toBe('{"broken"');
    expect(nowPlayingTitle('{"mediaId":"4"}')).toBeNull();
    expect(nowPlayingTitle('  ')).toBeNull();
    expect(nowPlayingTitle(null)).toBeNull();
  });
});
