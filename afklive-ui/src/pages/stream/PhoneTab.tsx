import { useQuery } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { CopyField } from '@/components/Copy';
import { Button, Dialog, ErrorNote, Field, Input, Panel, Segmented, Spinner, Toggle } from '@/components/ui';
import { get, patch, post } from '@/lib/api';
import { dateTime } from '@/lib/format';
import type { IngestSetup, StreamView } from '@/lib/types';
import { useStreamUpdate } from './useStreamUpdate';

type App = 'larix' | 'prism' | 'streamlabs' | 'srt';

/** MS-1: a private address per stream; scan a QR code into the phone app and you're set up. */
export function PhoneTab({ stream: s }: { stream: StreamView }) {
  const setup = useQuery({ queryKey: ['ingest', s.id], queryFn: () => get<IngestSetup>(`/api/streams/${s.id}/ingest`) });
  const [app, setApp] = useState<App>('larix');
  const [qrVersion, setQrVersion] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const { busy, update } = useStreamUpdate(s.id);

  if (setup.isLoading) return <Spinner label="Preparing your phone address" />;
  if (setup.error || !setup.data) return <ErrorNote error={setup.error} />;
  const d = setup.data;
  const qrType = app === 'larix' ? 'larix' : app === 'srt' ? 'srt' : 'rtmp';

  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <Panel title="Connect your phone app">
        <Segmented
          label="Phone app"
          value={app}
          onChange={setApp}
          options={[
            { value: 'larix', label: 'Larix' },
            { value: 'prism', label: 'PRISM' },
            { value: 'streamlabs', label: 'Streamlabs' },
            { value: 'srt', label: 'Other (SRT)' },
          ]}
        />
        <p className="mt-4 max-w-prose">{d.guides[app]}</p>
        <div className="mt-5 flex flex-col gap-6 sm:flex-row">
          <img
            src={`/api/streams/${s.id}/ingest/qr.png?type=${qrType}&v=${qrVersion}`}
            alt={`QR code for ${app === 'larix' ? 'Larix' : 'your phone app'}`}
            className="h-44 w-44 shrink-0 rounded bg-white p-2"
          />
          <div className="min-w-0 flex-1 space-y-4">
            {app === 'larix' && (
              <p className="text-sm text-muted">
                Reading this on your phone? <a className="text-accent hover:underline" href={d.larixLink}>Open in Larix</a>.
              </p>
            )}
            {app === 'srt' ? (
              <CopyField label="SRT address" value={d.srtUrl} secret />
            ) : (
              <>
                <CopyField label="Server" value={d.rtmpServer} />
                <CopyField label="Stream key" value={d.streamKey} secret />
              </>
            )}
          </div>
        </div>
        <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
          <span className="text-sm text-muted">{d.lastConnectedAt ? `Last connected ${dateTime(d.lastConnectedAt, s.timeZone)}` : 'Your phone hasn’t connected yet.'}</span>
          <Button size="sm" variant="ghost" icon={<RefreshCw className="h-4 w-4" />} onClick={() => setConfirm(true)}>
            New stream key
          </Button>
        </div>
      </Panel>

      <PhoneSettings stream={s} />

      <Dialog
        open={confirm}
        onClose={() => setConfirm(false)}
        title="Make a new stream key?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirm(false)}>
              Keep the current key
            </Button>
            <Button
              variant="danger"
              busy={busy}
              onClick={async () => {
                const ok = await update(() => post(`/api/streams/${s.id}/ingest/regenerate`), 'New key ready. Set it up in your phone app again.');
                if (ok) {
                  setup.refetch();
                  setQrVersion((v) => v + 1);
                  setConfirm(false);
                }
              }}
            >
              Make a new key
            </Button>
          </>
        }
      >
        <p>The old key stops working right away. Use this if someone else has seen your key.</p>
      </Dialog>
    </div>
  );
}

function PhoneSettings({ stream: s }: { stream: StreamView }) {
  const p = s.phone ?? { endOnInputLoss: false, inputGraceSeconds: 120, srtLatencyMs: 1000 };
  const [grace, setGrace] = useState(String(Math.round(p.inputGraceSeconds / 60)));
  const [latency, setLatency] = useState(String(p.srtLatencyMs));
  const { update } = useStreamUpdate(s.id);
  useEffect(() => setGrace(String(Math.round(p.inputGraceSeconds / 60))), [p.inputGraceSeconds]);
  return (
    <Panel title="When your signal drops">
      <div className="space-y-5">
        <p className="text-muted">Viewers see your BRB scene with music; the stream stays up and switches back the moment your phone reconnects.</p>
        <div className="flex items-center justify-between gap-4">
          <span>
            <span className="block font-medium">End the stream if the phone stays away</span>
            <span className="text-sm text-muted">Off keeps BRB on air until you stop the stream.</span>
          </span>
          <Toggle label="End the stream if the phone stays away" checked={p.endOnInputLoss} onChange={(v) => update(() => patch(`/api/streams/${s.id}`, { endOnInputLoss: v }))} />
        </div>
        {p.endOnInputLoss && (
          <Field label="End after" htmlFor="grace">
            <div className="flex items-center gap-2">
              <Input
                id="grace"
                type="number"
                min={1}
                max={240}
                className="w-24"
                value={grace}
                onChange={(e) => setGrace(e.target.value)}
                onBlur={() => update(() => patch(`/api/streams/${s.id}`, { inputGraceSeconds: Math.max(1, Math.min(240, Number(grace) || 2)) * 60 }), 'Saved.')}
              />
              <span className="text-muted">minutes</span>
            </div>
          </Field>
        )}
        <Field label="SRT buffer" htmlFor="latency" hint="More buffer rides out worse signal but adds delay. 1000 ms suits most 4G.">
          <div className="flex items-center gap-2">
            <Input
              id="latency"
              type="number"
              min={120}
              max={8000}
              step={100}
              className="w-28"
              value={latency}
              onChange={(e) => setLatency(e.target.value)}
              onBlur={() => update(() => patch(`/api/streams/${s.id}`, { srtLatencyMs: Number(latency) || 1000 }), 'Saved.')}
            />
            <span className="text-muted">ms</span>
          </div>
        </Field>
      </div>
    </Panel>
  );
}
