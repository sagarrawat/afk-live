import { Activity } from 'lucide-react';
import { PageHeader, Panel } from '@/components/ui';

const FAQ: [string, string][] = [
  ['My stream says Live but YouTube shows nothing', 'YouTube can take 10 to 20 seconds to show a new stream. If it still says "waiting for stream", check the destination\'s stream key in Destinations and use Send a test.'],
  ['What happens if my phone loses signal?', 'Viewers see your Be right back scene with music, and the stream stays up. When your phone reconnects, it switches back by itself.'],
  ['Why is my upload "Checking"?', 'Every file is checked after upload so it plays without problems. It usually takes under a minute. If it says "Can\'t play", the reason is shown on the file.'],
  ['Can I change the playlist while live?', 'Yes. Changes apply after the video that\'s playing, so the stream never drops.'],
  ['How do renewals work?', 'We remind you 3 days before your plan ends; renewing is one UPI or card payment. If you miss it, you get 3 days of grace before moving to Free. Nothing is deleted.'],
];

export function Help() {
  return (
    <>
      <PageHeader title="Help" description="Answers to the common questions. Stuck? Email support@afklive.in and we reply within a day." />
      {/* RE-7: is it just me, or AFK Live? */}
      <a href="/status" className="mb-4 flex items-center gap-3 rounded-md border border-line bg-panel px-4 py-3 hover:border-muted/60">
        <Activity className="h-4 w-4 shrink-0 text-muted" aria-hidden />
        <span className="min-w-0 flex-1">
          <span className="block font-medium">Service status</span>
          <span className="block text-sm text-muted">Check whether AFK Live is working right now, and see past incidents.</span>
        </span>
      </a>
      <Panel>
        <dl className="divide-y divide-line">
          {FAQ.map(([q, a]) => (
            <div key={q} className="py-4 first:pt-0 last:pb-0">
              <dt className="font-medium">{q}</dt>
              <dd className="mt-1 max-w-prose text-muted">{a}</dd>
            </div>
          ))}
        </dl>
      </Panel>
    </>
  );
}
