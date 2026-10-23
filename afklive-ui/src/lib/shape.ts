import type { Fit, MediaRef } from './types';

/**
 * ST-1 / MS-10: the stream's shape from its videos, and plain words for how videos of another shape
 * and frame rate come out. Sizes and frame rates come from probing; videos not measured yet don't count.
 */

export type Orientation = 'LANDSCAPE' | 'VERTICAL';
type Video = Pick<MediaRef, 'width' | 'height' | 'status'> & { contentType?: string; type?: string; fit?: Fit | null; fps?: number | null };

const isVideo = (m: Video) => (m.contentType ?? m.type) === 'VIDEO';
const measured = (m: Video) => isVideo(m) && !!m.width && !!m.height;

/** A video's shape: taller than wide is vertical, anything else (square too) landscape. */
export function shapeOf(m: Video): Orientation | null {
  if (!measured(m)) return null;
  return m.height! > m.width! ? 'VERTICAL' : 'LANDSCAPE';
}

/** Ready, measured videos (still-processing files have no size yet). */
export function readyVideos<T extends Video>(videos: T[]): T[] {
  return videos.filter((m) => measured(m) && m.status === 'READY');
}

/** Vertical when most ready videos are vertical, landscape when most aren't, null with nothing measured. */
export function suggestedShape(videos: Video[]): Orientation | null {
  const ready = readyVideos(videos);
  if (ready.length === 0) return null;
  const vertical = ready.filter((m) => shapeOf(m) === 'VERTICAL').length;
  return vertical * 2 > ready.length ? 'VERTICAL' : 'LANDSCAPE';
}

/** One sentence on what YouTube does with a vertical live (shown with every vertical suggestion). */
export const VERTICAL_ON_YOUTUBE =
  'YouTube shows vertical lives full screen to people watching on phones, and can show them in the Shorts feed; on a computer they have bars at the sides.';

const FIT_WORDS: Record<Orientation, Record<Fit, string>> = {
  // A landscape stream: the odd ones out are narrower (vertical).
  LANDSCAPE: {
    BLUR: 'over a blurred copy of the picture that fills the sides',
    FIT: 'with black bars at the sides',
    FILL: 'filling the frame, with the top and bottom cut off',
  },
  // A vertical stream: the odd ones out are wider (landscape).
  VERTICAL: {
    BLUR: 'over a blurred copy of the picture that fills the top and bottom',
    FIT: 'with black bars at the top and bottom',
    FILL: 'filling the frame, with the sides cut off',
  },
};

/**
 * How videos that aren't the stream's shape are shown, in plain words, one sentence per fit in use
 * (a video's own fit, or the stream's). Empty when every measured video matches the stream.
 */
export function fitNotes(videos: Video[], orientation: Orientation, streamFit: Fit): string[] {
  const odd = videos.filter((m) => shapeOf(m) !== null && shapeOf(m) !== orientation);
  if (odd.length === 0) return [];
  const byFit = new Map<Fit, number>();
  for (const m of odd) {
    const f = m.fit ?? streamFit;
    byFit.set(f, (byFit.get(f) ?? 0) + 1);
  }
  const other = orientation === 'LANDSCAPE' ? 'vertical' : 'landscape';
  // Every video is the other shape and shown one way: talk about "these videos".
  const together = odd.length === videos.filter(measured).length && byFit.size === 1;
  return Array.from(byFit.entries()).map(([fit, n]) => {
    const how = FIT_WORDS[orientation][fit];
    if (together) return n === 1 ? `This video is ${other}. It plays ${how}.` : `These videos are ${other}. They play ${how}.`;
    return `${n} ${other} video${n === 1 ? ' plays' : 's play'} ${how}.`;
  });
}

/**
 * Videos filmed at a higher frame rate than the stream (60 fps clips on a 30 fps plan), explained
 * without jargon; null when none are. {@code planMaxFps} says whether the plan could go higher.
 */
export function frameRateNote(videos: Video[], streamFps: number, planMaxFps?: number): string | null {
  const faster = videos.filter((m) => isVideo(m) && (m.fps ?? 0) > streamFps + 1);
  if (faster.length === 0) return null;
  const top = Math.round(Math.max(...faster.map((m) => m.fps ?? 0)));
  const which = faster.length === 1 ? `One of your videos was filmed at ${top} frames per second. It plays` : `Some of your videos were filmed at ${top} frames per second. They play`;
  if (planMaxFps !== undefined && planMaxFps > streamFps) {
    return `${which} smoothly at ${streamFps} frames per second; you can raise the stream to ${planMaxFps} in Settings.`;
  }
  return `${which} smoothly at ${streamFps} frames per second on your plan.`;
}
