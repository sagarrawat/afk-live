import { Loader2, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AddDestinationDialog, destinationSummary, EditDestinationDialog } from '@/components/AddDestination';
import { TestNote, useDestinationTests } from '@/components/DestinationTest';
import { PlatformIcon } from '@/components/PlatformIcon';
import { Button, Dialog, Empty, ErrorNote, IconButton, PageHeader, Spinner, useAction } from '@/components/ui';
import { del } from '@/lib/api';
import { keys, useDestinations, useInvalidate } from '@/lib/queries';
import type { Destination } from '@/lib/types';

/** DS-1..DS-6: saved destinations, each testable without going live. */
export function Destinations() {
  const { data, isLoading, error } = useDestinations();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Destination | null>(null);
  const { tests, test } = useDestinationTests();
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  // DS-8: ?edit=<id> opens that destination's edit straight away ("Fix key", "Check the stream key").
  const [params, setParams] = useSearchParams();
  const editId = Number(params.get('edit'));
  const editing = editId ? data?.find((d) => d.id === editId) : undefined;
  const edit = (d: Destination | null) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (d) next.set('edit', String(d.id));
        else next.delete('edit');
        return next;
      },
      { replace: true },
    );

  return (
    <>
      <PageHeader
        title="Destinations"
        description="Where your streams go. Paste a stream key once; every stream can use it. Change it here when the platform gives you a new one."
        actions={
          <Button variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setAdding(true)}>
            Add destination
          </Button>
        }
      />
      <ErrorNote error={error} />
      {isLoading ? (
        <Spinner />
      ) : !data || data.length === 0 ? (
        <Empty title="No destinations yet" action={<Button variant="primary" onClick={() => setAdding(true)}>Add YouTube</Button>}>
          Add your YouTube stream key, or any platform that takes RTMP or SRT.
        </Empty>
      ) : (
        <ul className="divide-y divide-line rounded-md border border-line bg-panel">
          {data.map((d) => {
            const t = tests[d.id];
            const live = d.liveIn ?? [];
            return (
              <li key={d.id} className="px-4 py-3">
                <div className="flex flex-wrap items-center gap-3">
                  <PlatformIcon platform={d.platform} className="h-5 w-5 text-muted" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{d.name}</span>
                    <span className="block truncate text-sm text-muted" title={d.server}>
                      {destinationSummary(d)}
                    </span>
                  </span>
                  <Button size="sm" variant="ghost" disabled={t === 'running'} onClick={() => test(d)}>
                    {t === 'running' ? (
                      <>
                        <Loader2 className="h-4 w-4 animate-spin" /> Testing
                      </>
                    ) : (
                      'Send a test'
                    )}
                  </Button>
                  <Button size="sm" variant="ghost" icon={<Pencil className="h-4 w-4" />} aria-label={`Edit ${d.name}`} onClick={() => edit(d)}>
                    Edit
                  </Button>
                  <IconButton label={`Remove ${d.name}`} disabled={live.length > 0} onClick={() => setRemoving(d)}>
                    <Trash2 className="h-4 w-4" />
                  </IconButton>
                </div>
                {live.length > 0 && (
                  <p className="mt-1 text-sm text-muted">
                    Sending to {live.join(', ')} now. To remove it, stop the stream or turn this destination off for it first.
                  </p>
                )}
                <TestNote state={t} platform={d.platform} />
              </li>
            );
          })}
        </ul>
      )}
      <p className="mt-4 max-w-prose text-sm text-muted">
        A test sends 10 seconds of a test picture to the platform, so you know the key works before you go live. New destinations are tested as soon as you add them. On YouTube it shows as a short live preview only you can see in Studio.
      </p>
      {/* MS-6: test a new key straight away, without holding up the save. */}
      <AddDestinationDialog open={adding} onClose={() => setAdding(false)} onAdded={(d) => void test(d)} />
      {/* DS-8: a changed key is tested straight away, like a new one. */}
      {editing && <EditDestinationDialog key={editing.id} destination={editing} onClose={() => edit(null)} onSaved={(d, keyChanged) => keyChanged && void test(d)} />}
      <Dialog
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={`Remove ${removing?.name}?`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              busy={busy}
              onClick={async () => {
                if (removing && (await run(() => del(`/api/destinations/${removing.id}`), 'Destination removed.'))) {
                  invalidate([...keys.destinations], [...keys.streams]);
                  setRemoving(null);
                }
              }}
            >
              Remove
            </Button>
          </>
        }
      >
        <p>Streams stop sending here the next time they start. The stream key is deleted from AFK Live.</p>
      </Dialog>
    </>
  );
}
