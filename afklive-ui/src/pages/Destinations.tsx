import { CheckCircle2, CircleAlert, Loader2, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { AddDestinationDialog } from '@/components/AddDestination';
import { PlatformIcon } from '@/components/PlatformIcon';
import { Button, Dialog, Empty, ErrorNote, IconButton, PageHeader, Spinner, useAction } from '@/components/ui';
import { del, get, post } from '@/lib/api';
import { keys, useDestinations, useInvalidate } from '@/lib/queries';
import type { Destination } from '@/lib/types';

interface TestResult {
  testId: number;
  status: string;
  ok: boolean;
  message: string;
}

/** DS-1..DS-6: saved destinations, each testable without going live. */
export function Destinations() {
  const { data, isLoading, error } = useDestinations();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Destination | null>(null);
  const [tests, setTests] = useState<Record<number, TestResult | 'running'>>({});
  const { busy, run } = useAction();
  const invalidate = useInvalidate();

  const test = async (d: Destination) => {
    setTests((t) => ({ ...t, [d.id]: 'running' }));
    try {
      const started = await post<{ testId: number }>(`/api/destinations/${d.id}/test`);
      for (let i = 0; i < 30; i++) {
        await new Promise((r) => setTimeout(r, 2000));
        const r = await get<TestResult>(`/api/destinations/tests/${started.testId}`);
        if (r.status !== 'PENDING' && r.status !== 'RUNNING') {
          setTests((t) => ({ ...t, [d.id]: r }));
          return;
        }
      }
      setTests((t) => ({ ...t, [d.id]: { testId: 0, status: 'TIMEOUT', ok: false, message: 'The test is taking too long. Try again in a minute.' } }));
    } catch (e) {
      setTests((t) => ({ ...t, [d.id]: { testId: 0, status: 'ERROR', ok: false, message: e instanceof Error ? e.message : String(e) } }));
    }
  };

  return (
    <>
      <PageHeader
        title="Destinations"
        description="Where your streams go. Paste a stream key once; every stream can use it."
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
            return (
              <li key={d.id} className="px-4 py-3">
                <div className="flex flex-wrap items-center gap-3">
                  <PlatformIcon platform={d.platform} className="h-5 w-5 text-muted" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{d.name}</span>
                    <span className="block truncate text-sm text-muted">
                      {d.server} {d.keyHint}
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
                  <IconButton label={`Remove ${d.name}`} onClick={() => setRemoving(d)}>
                    <Trash2 className="h-4 w-4" />
                  </IconButton>
                </div>
                {t && t !== 'running' && (
                  <p className={`mt-2 flex items-start gap-2 text-sm ${t.ok ? 'text-ok' : 'text-warn'}`}>
                    {t.ok ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" /> : <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />}
                    {t.message}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <p className="mt-4 max-w-prose text-sm text-muted">A test sends 10 seconds of a test picture to the platform, so you know the key works before you go live. On YouTube it shows as a short live preview only you can see in Studio.</p>
      <AddDestinationDialog open={adding} onClose={() => setAdding(false)} />
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
