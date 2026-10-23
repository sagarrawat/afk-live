import { Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AddDestinationDialog } from '@/components/AddDestination';
import { PlatformIcon } from '@/components/PlatformIcon';
import { isOnAir } from '@/components/status';
import { Button, Empty, Panel, Spinner, Toggle } from '@/components/ui';
import { put } from '@/lib/api';
import { useDestinations, useMe } from '@/lib/queries';
import type { StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

/** DS-3 / BI-8: which saved destinations this stream sends to. */
export function DestinationsTab({ stream: s }: { stream: StreamView }) {
  const { data: all, isLoading } = useDestinations();
  const { data: me } = useMe();
  const [adding, setAdding] = useState(false);
  const { busy, update } = useStreamUpdate(s.id);
  const enabled = new Set(s.destinations.filter((d) => d.enabled).map((d) => d.destinationId));
  const limit = me?.plan.destinations ?? 1;
  const onAir = isOnAir(s.live.state);

  const setEnabled = (id: number, on: boolean) => {
    const next = new Set(enabled);
    if (on) next.add(id);
    else next.delete(id);
    const body = (all ?? []).filter((d) => next.has(d.id) || s.destinations.some((t) => t.destinationId === d.id)).map((d) => ({ destinationId: d.id, enabled: next.has(d.id) }));
    update(() => put(`/api/streams/${s.id}/destinations`, body), onAir ? 'Saved. It applies the next time the stream starts.' : undefined);
  };

  if (isLoading) return <Spinner />;
  return (
    <Panel
      title="Send this stream to"
      pad={false}
      action={
        <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={() => setAdding(true)}>
          Add destination
        </Button>
      }
    >
      {!all || all.length === 0 ? (
        <div className="p-4">
          <Empty title="No destinations yet" action={<Button variant="primary" onClick={() => setAdding(true)}>Add YouTube</Button>}>
            Paste your YouTube stream key once and every stream can use it.
          </Empty>
        </div>
      ) : (
        <>
          <ul className="divide-y divide-line">
            {all.map((d) => {
              const on = enabled.has(d.id);
              const full = !on && enabled.size >= limit;
              return (
                <li key={d.id} className="flex items-center gap-3 px-4 py-3">
                  <PlatformIcon platform={d.platform} className="h-5 w-5 text-muted" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{d.name}</span>
                    <span className="block truncate text-sm text-muted">
                      {d.server} {d.keyHint}
                    </span>
                  </span>
                  <Toggle label={`Send to ${d.name}`} checked={on} disabled={busy || full} onChange={(v) => setEnabled(d.id, v)} />
                </li>
              );
            })}
          </ul>
          <p className="border-t border-line px-4 py-3 text-sm text-muted">
            Your {me?.plan.name} plan sends each stream to {limit} destination{limit === 1 ? '' : 's'} at once.{' '}
            {limit < 5 && (
              <Link to="/studio/settings/billing" className="text-accent hover:underline">
                Upgrade for more
              </Link>
            )}
          </p>
        </>
      )}
      <AddDestinationDialog open={adding} onClose={() => setAdding(false)} onAdded={(d) => enabled.size < limit && setEnabled(d.id, true)} />
    </Panel>
  );
}
