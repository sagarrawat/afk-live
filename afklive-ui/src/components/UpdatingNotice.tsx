import { Loader2 } from 'lucide-react';
import { useSyncExternalStore } from 'react';
import { onUpdatingChange, updatingNow } from '@/lib/api';

/**
 * While the web app restarts (a deploy), the API answers "updating" (Caddy, afklive-scripts/caddy/Caddyfile)
 * and lib/api.ts keeps retrying reads. This says so, calmly, instead of errors all over the page; it goes
 * away by itself on the first normal answer. Streams aren't affected: they run on the stream servers.
 */
export function UpdatingNotice() {
  const updating = useSyncExternalStore(onUpdatingChange, updatingNow, updatingNow);
  if (!updating) return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center px-4">
      <div role="status" className="pointer-events-auto flex max-w-md items-start gap-3 rounded border border-line bg-panel px-4 py-3 text-sm text-ink shadow-lg">
        <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin text-muted" aria-hidden />
        <p>
          <span className="font-medium">Updating, reconnecting…</span>{' '}
          <span className="text-muted">AFK Live is installing an update. Your live streams keep running.</span>
        </p>
      </div>
    </div>
  );
}
