import { Smartphone } from 'lucide-react';
import { useState } from 'react';
import { isOnAir } from '@/components/status';
import { Button } from '@/components/ui';
import { patch } from '@/lib/api';
import type { MediaRef, StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

/** Playlist videos taller than wide (the size comes from probing; unknown sizes don't count). */
export function verticalVideos(playlist: MediaRef[]): MediaRef[] {
  return playlist.filter((m) => m.contentType === 'VIDEO' && !!m.width && !!m.height && m.height > m.width);
}

const dismissKey = (id: number) => `afk-vertical-hint-${id}`;

function wasDismissed(id: number): boolean {
  try {
    return window.localStorage.getItem(dismissKey(id)) === '1';
  } catch {
    return false;
  }
}

/**
 * A landscape stream playing phone-shaped videos shows them small, between bars. Suggest the
 * vertical shape, which the picture settings can only change while the stream is off air.
 */
export function VerticalHint({ stream: s }: { stream: StreamView }) {
  const [dismissed, setDismissed] = useState(() => wasDismissed(s.id));
  const { busy, update } = useStreamUpdate(s.id);
  if (s.source !== 'PLAYLIST' || s.orientation !== 'LANDSCAPE' || dismissed) return null;
  const playlist = s.playlist ?? [];
  const vertical = verticalVideos(playlist);
  if (vertical.length === 0) return null;
  const videos = playlist.filter((m) => m.contentType === 'VIDEO').length;
  const onAir = isOnAir(s.live.state);
  const which = vertical.length === 1 && videos === 1 ? 'This video is vertical.' : vertical.length === videos ? 'These videos are vertical.' : `${vertical.length} of your videos ${vertical.length === 1 ? 'is' : 'are'} vertical.`;
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
      <p className="min-w-0 flex-1 text-sm">
        {which} Switch the stream to Vertical 9:16 so {vertical.length === 1 && videos === 1 ? 'it fills' : 'they fill'} the screen on phones.
        {onAir && <span className="text-muted"> You can change the shape once this stream is off air; it applies the next time you go live.</span>}
      </p>
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
