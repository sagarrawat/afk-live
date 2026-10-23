import { Info, Smartphone } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { HelpLink } from '@/components/HelpLink';
import { isOnAir } from '@/components/status';
import { Button } from '@/components/ui';
import { patch } from '@/lib/api';
import { HELP } from '@/lib/help';
import { useMe } from '@/lib/queries';
import { fitNotes, frameRateNote, readyVideos, shapeOf, suggestedShape, VERTICAL_ON_YOUTUBE } from '@/lib/shape';
import type { MediaRef, StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

/** Playlist videos taller than wide (the size comes from probing; unknown sizes don't count). */
export function verticalVideos(playlist: MediaRef[]): MediaRef[] {
  return playlist.filter((m) => m.contentType === 'VIDEO' && shapeOf(m) === 'VERTICAL');
}

const dismissKey = (id: number) => `afk-vertical-hint-${id}`;

/** A fresh playlist gets a fresh suggestion, even if "Keep landscape" was pressed for an earlier one. */
export function resetVerticalHint(id: number) {
  try {
    window.localStorage.removeItem(dismissKey(id));
  } catch {
    // nothing stored
  }
}

function wasDismissed(id: number): boolean {
  try {
    return window.localStorage.getItem(dismissKey(id)) === '1';
  } catch {
    return false;
  }
}

/**
 * ST-1 / MS-10: a landscape stream whose videos are mostly phone-shaped shows them small, between
 * bars. Suggest the vertical shape, which the picture settings can only change while off air.
 */
export function VerticalHint({ stream: s }: { stream: StreamView }) {
  const [dismissed, setDismissed] = useState(() => wasDismissed(s.id));
  const { busy, update } = useStreamUpdate(s.id);
  if (s.source !== 'PLAYLIST' || s.orientation !== 'LANDSCAPE' || dismissed) return null;
  const playlist = s.playlist ?? [];
  if (suggestedShape(playlist) !== 'VERTICAL') return null;
  const ready = readyVideos(playlist);
  const vertical = ready.filter((m) => shapeOf(m) === 'VERTICAL');
  const one = vertical.length === 1 && ready.length === 1;
  const onAir = isOnAir(s.live.state);
  const which = one ? 'This video is vertical.' : vertical.length === ready.length ? 'These videos are vertical.' : `${vertical.length} of your ${ready.length} videos are vertical.`;
  const dismiss = () => {
    try {
      window.localStorage.setItem(dismissKey(s.id), '1');
    } catch {
      // private window: the hint just comes back next time
    }
    setDismissed(true);
  };

  return (
    <aside aria-label="Vertical videos" className="flex flex-col gap-3 rounded-md border border-line bg-panel p-4 sm:flex-row sm:items-center">
      <Smartphone className="hidden h-5 w-5 shrink-0 text-accent sm:block" aria-hidden />
      <div className="min-w-0 flex-1 text-sm">
        <p>
          {which} Switch the stream to Vertical 9:16 so {one ? 'it fills' : 'they fill'} the screen on phones.
          {onAir && <span className="text-muted"> You can change the shape once this stream is off air; it applies the next time you go live.</span>}
        </p>
        <p className="mt-1 text-muted">
          {VERTICAL_ON_YOUTUBE} <HelpLink to={HELP.shape}>Vertical or landscape?</HelpLink>
        </p>
      </div>
      <div className="flex shrink-0 gap-2">
        {!onAir && (
          <Button size="sm" busy={busy} onClick={() => update(() => patch(`/api/streams/${s.id}`, { orientation: 'VERTICAL' }), 'The stream is now Vertical 9:16.')}>
            Switch to Vertical 9:16
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={dismiss}>
          Keep landscape
        </Button>
      </div>
    </aside>
  );
}

/**
 * MS-10 / ST-1: how the playlist comes out, in plain words: videos of the other shape (bars, blurred
 * fill or crop) and videos filmed at more frames per second than the stream sends.
 */
export function ShapeNotes({ stream: s }: { stream: StreamView }) {
  const { data: me } = useMe();
  if (s.source !== 'PLAYLIST') return null;
  const playlist = s.playlist ?? [];
  const fits = fitNotes(playlist, s.orientation, s.fit ?? 'FIT');
  const fps = frameRateNote(playlist, s.fps, me?.plan.maxFps);
  const notes = fps ? [...fits, fps] : fits;
  if (notes.length === 0) return null;
  const odd = fits.length > 0;
  return (
    <aside aria-label="How your videos play" className="flex gap-3 rounded-md border border-line px-4 py-3 text-sm">
      <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted" aria-hidden />
      <div className="min-w-0">
        {notes.map((n) => (
          <p key={n} className="text-muted">
            {n}
          </p>
        ))}
        <p className="mt-1">
          {odd && (
            <>
              <Link to={`/studio/streams/${s.id}/settings`} className="text-accent hover:underline">
                Change how they fit
              </Link>
              <span className="text-muted"> · </span>
            </>
          )}
          <HelpLink to={HELP.shape}>Vertical or landscape?</HelpLink>
        </p>
      </div>
    </aside>
  );
}
