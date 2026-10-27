import { Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AddDestinationDialog, destinationSummary } from '@/components/AddDestination';
import { TestNote, useDestinationTests } from '@/components/DestinationTest';
import { PlatformIcon } from '@/components/PlatformIcon';
import { isOnAir } from '@/components/status';
import { Button, Empty, Panel, Spinner, Toggle } from '@/components/ui';
import { put } from '@/lib/api';
import { useDestinations, useMe } from '@/lib/queries';
import type { Destination, StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

/** DS-3 / BI-8: which saved destinations this stream sends to. */
export function DestinationsTab({ stream: s }: { stream: StreamView }) {
  const { data: all, isLoading } = useDestinations();
  const { data: me } = useMe();
  const [adding, setAdding] = useState(false);
  const { tests, test } = useDestinationTests();
  const { busy, update } = useStreamUpdate(s.id);
  const enabled = new Set(s.destinations.filter((d) => d.enabled).map((d) => d.destinationId));
  const limit = me?.plan.destinations ?? 1;
  const onAir = isOnAir(s.live.state);

  /** added: a destination just saved, which the list this render saw doesn't have yet (the first one ever). */
  const setEnabled = (id: number, on: boolean, added?: Destination) => {
    const next = new Set(enabled);
    if (on) next.add(id);
    else next.delete(id);
    const known = all ?? [];
    const list = added && !known.some((d) => d.id === added.id) ? [...known, added] : known;
    const body = list.filter((d) => next.has(d.id) || s.destinations.some((t) => t.destinationId === d.id)).map((d) => ({ destinationId: d.id, enabled: next.has(d.id) }));
    // DS-5: while live the change is on air within seconds, and the other destinations stay connected.
    const name = list.find((d) => d.id === id)?.name ?? 'it';
    update(() => put(`/api/streams/${s.id}/destinations`, body), onAir ? (on ? `Sending to ${name} now.` : `Stopped sending to ${name}.`) : undefined);
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
              const last = on && onAir && enabled.size === 1; // a live stream needs somewhere to go
              return (
                <li key={d.id} className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <PlatformIcon platform={d.platform} className="h-5 w-5 text-muted" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium">{d.name}</span>
                      <span className="block truncate text-sm text-muted" title={d.server}>
                        {destinationSummary(d)}
                      </span>
                    </span>
                    <Link to={`/studio/destinations?edit=${d.id}`} aria-label={`Edit ${d.name}`} className="text-sm text-accent hover:underline">
                      Edit
                    </Link>
                    <Toggle label={`Send to ${d.name}`} checked={on} disabled={busy || full || last} onChange={(v) => setEnabled(d.id, v)} />
                  </div>
                  {/* UX audit #13: why the switch can't be used, on the page (a tooltip never shows on a phone). */}
                  {full && (
                    <p className="mt-1 pl-8 text-sm text-muted">
                      Your plan sends to {limit} destination{limit === 1 ? '' : 's'} at once. Turn one off or{' '}
                      <Link to="/studio/settings/billing" className="text-accent hover:underline">
                        upgrade
                      </Link>
                      .
                    </p>
                  )}
                  {last && <p className="mt-1 pl-8 text-sm text-muted">A live stream needs one destination. Stop the stream to turn this off.</p>}
                  <TestNote state={tests[d.id]} platform={d.platform} />
                </li>
              );
            })}
          </ul>
          <p className="border-t border-line px-4 py-3 text-sm text-muted">
            {onAir && 'Changes reach the live stream in a few seconds; the other destinations stay connected. '}
            Your {me?.plan.name} plan sends each stream to {limit} destination{limit === 1 ? '' : 's'} at once.{' '}
            {limit < 5 && (
              <Link to="/studio/settings/billing" className="text-accent hover:underline">
                Upgrade for more
              </Link>
            )}
          </p>
        </>
      )}
      <AddDestinationDialog open={adding} onClose={() => setAdding(false)} onAdded={(d) => {
          const sendNow = enabled.size < limit;
          if (sendNow) setEnabled(d.id, true, d);
          // MS-6: check the new key straight away; unless the live stream is already sending to it
          // (a test on the same key would fight the stream, and its health shows the result anyway).
          if (!(onAir && sendNow)) void test(d);
        }}
      />
    </Panel>
  );
}
