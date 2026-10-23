import { nowPlayingOverlay } from './OverlaysTab';

describe('now playing overlay (OV-2)', () => {
  it('names the music track when the stream has music, else the video', () => {
    expect(nowPlayingOverlay(true).text).toBe('♪ {track}');
    expect(nowPlayingOverlay(false).text).toBe('Now playing: {title}');
    expect(nowPlayingOverlay(true).type).toBe('TEXT');
  });
});
