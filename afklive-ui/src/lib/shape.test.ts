import { fitNotes, frameRateNote, shapeOf, suggestedShape } from './shape';
import type { MediaRef } from './types';

const v = (over: Partial<MediaRef> = {}): MediaRef => ({ id: 1, title: 'Phone clip', width: 1080, height: 1920, contentType: 'VIDEO', status: 'READY', ...over });
const wide = (over: Partial<MediaRef> = {}) => v({ id: 2, title: 'Wide', width: 1920, height: 1080, ...over });

describe('the stream shape from its videos (ST-1)', () => {
  it('reads each video as vertical or landscape, and unmeasured ones as unknown', () => {
    expect(shapeOf(v())).toBe('VERTICAL');
    expect(shapeOf(wide())).toBe('LANDSCAPE');
    expect(shapeOf(v({ width: 1080, height: 1080 }))).toBe('LANDSCAPE');
    expect(shapeOf(v({ width: null, height: null, status: 'PROCESSING' }))).toBeNull();
    expect(shapeOf(v({ contentType: 'IMAGE' }))).toBeNull();
  });

  it('suggests Vertical only when most ready videos are vertical', () => {
    expect(suggestedShape([])).toBeNull();
    expect(suggestedShape([v({ width: null, height: null, status: 'PROCESSING' })])).toBeNull();
    expect(suggestedShape([v()])).toBe('VERTICAL');
    expect(suggestedShape([v(), v({ id: 3 }), wide()])).toBe('VERTICAL');
    expect(suggestedShape([v(), wide()])).toBe('LANDSCAPE'); // half is not most
    expect(suggestedShape([v(), wide(), wide({ id: 4 })])).toBe('LANDSCAPE');
    // Files still being checked, or that failed, don't count yet.
    expect(suggestedShape([wide(), v({ id: 5, status: 'FAILED' }), v({ id: 6, status: 'PROCESSING' })])).toBe('LANDSCAPE');
  });

  it('accepts library items, which call the kind "type"', () => {
    expect(suggestedShape([{ width: 720, height: 1280, status: 'READY', type: 'VIDEO' }])).toBe('VERTICAL');
  });
});

describe('how videos of the other shape come out (MS-10)', () => {
  it('says nothing when every video matches the stream', () => {
    expect(fitNotes([wide(), wide({ id: 3 })], 'LANDSCAPE', 'BLUR')).toEqual([]);
    expect(fitNotes([v()], 'VERTICAL', 'FIT')).toEqual([]);
  });

  it('names the fit in plain words, sides or top and bottom by the stream shape', () => {
    expect(fitNotes([v()], 'LANDSCAPE', 'BLUR')).toEqual(['This video is vertical. It plays over a blurred copy of the picture that fills the sides.']);
    expect(fitNotes([v(), v({ id: 3 })], 'LANDSCAPE', 'FIT')).toEqual(['These videos are vertical. They play with black bars at the sides.']);
    expect(fitNotes([wide()], 'VERTICAL', 'FIT')).toEqual(['This video is landscape. It plays with black bars at the top and bottom.']);
    expect(fitNotes([wide()], 'VERTICAL', 'FILL')).toEqual(['This video is landscape. It plays filling the frame, with the sides cut off.']);
  });

  it('counts the odd ones out in a mixed playlist, per fit, using a video\'s own fit first', () => {
    const notes = fitNotes([wide(), v(), v({ id: 3, fit: 'FILL' }), v({ id: 4 })], 'LANDSCAPE', 'BLUR');
    expect(notes).toEqual([
      '2 vertical videos play over a blurred copy of the picture that fills the sides.',
      '1 vertical video plays filling the frame, with the top and bottom cut off.',
    ]);
    expect(notes.join(' ')).not.toMatch(/fps|aspect|letterbox/i);
  });
});

describe('60 fps videos on a 30 fps stream', () => {
  it('explains without jargon, on a plan capped at 30', () => {
    expect(frameRateNote([v({ fps: 59.94 })], 30, 30)).toBe('One of your videos was filmed at 60 frames per second. It plays smoothly at 30 frames per second on your plan.');
    expect(frameRateNote([v({ fps: 60 }), wide({ fps: 60 })], 30)).toBe('Some of your videos were filmed at 60 frames per second. They play smoothly at 30 frames per second on your plan.');
  });

  it('mentions Settings when the plan allows more', () => {
    expect(frameRateNote([v({ fps: 60 })], 30, 60)).toContain('you can raise the stream to 60 in Settings');
  });

  it('stays quiet for videos at or below the stream rate, or not measured', () => {
    expect(frameRateNote([v({ fps: 30 }), v({ fps: 29.97 }), v({ fps: null }), v()], 30, 30)).toBeNull();
    expect(frameRateNote([v({ fps: 60 })], 60, 60)).toBeNull();
  });
});
