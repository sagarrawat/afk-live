import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CopyField } from '@/components/Copy';
import { Panel, Spinner, Toggle, useAction } from '@/components/ui';
import { get, put } from '@/lib/api';

/** GR-18: ReferralService.Summary. */
export interface ReferralSummary {
  link: string;
  joined: number;
  waitingForFirstLive: number;
  credited: number;
  creditLine: boolean;
  creditText: string;
  payoutsLive: boolean;
  /** Free days earned while on Free, added to the next paid period. */
  freeDaysSaved?: number;
}

export const referralsKey = ['referrals'] as const;

/** Invite other creators, see who joined, and opt in to the description credit line. */
export function InviteSettings() {
  const { data } = useQuery({ queryKey: referralsKey, queryFn: () => get<ReferralSummary>('/api/referrals') });
  const qc = useQueryClient();
  const { busy, run } = useAction();
  if (!data) return <Spinner label="Loading your invite link" />;

  const setLine = (enabled: boolean) =>
    run(async () => qc.setQueryData(referralsKey, await put<ReferralSummary>('/api/referrals/credit-line', { enabled })), enabled ? 'The line is on.' : 'The line is off.');

  return (
    <div className="max-w-2xl space-y-6">
      <Panel title="Invite creators">
        <p className="text-sm text-muted">
          Know someone who'd like to stay live without a PC? Share your link. When they've signed up and their first live has reached YouTube, you each get a free month.
        </p>
        <div className="mt-4">
          <CopyField label="Your invite link" value={data.link} />
        </div>
        <dl className="mt-4 grid grid-cols-3 gap-3 text-center">
          <div className="rounded border border-line p-3">
            <dt className="text-xs text-muted">Joined</dt>
            <dd className="tnum text-xl font-semibold">{data.joined}</dd>
          </div>
          <div className="rounded border border-line p-3">
            <dt className="text-xs text-muted">Waiting for first live</dt>
            <dd className="tnum text-xl font-semibold">{data.waitingForFirstLive}</dd>
          </div>
          <div className="rounded border border-line p-3">
            <dt className="text-xs text-muted">Free months earned</dt>
            <dd className="tnum text-xl font-semibold">{data.credited}</dd>
          </div>
        </dl>
        {data.payoutsLive ? (
          <p className="mt-3 text-sm text-muted">
            On a paid plan, a free month is added to your plan right away. On Free, it's saved and added when you start a paid plan.
            {!!data.freeDaysSaved && ` You have ${data.freeDaysSaved} free days saved.`}
          </p>
        ) : (
          <p className="mt-3 text-sm text-muted">
            Free months are added once we can confirm the creator's first live reached YouTube. That check is coming soon; people who join through your link now still count.
          </p>
        )}
      </Panel>

      <Panel title="Credit line in descriptions">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 text-sm">
            <p>Add the line below at the end of descriptions AFK Live writes for you.</p>
            <p className="mt-2 text-muted">Off unless you turn it on. It's never shown on your stream. With a stream key, AFK Live doesn't write descriptions: copy the line into YouTube Studio yourself if you like.</p>
          </div>
          <Toggle label="Add the credit line" checked={data.creditLine} disabled={busy} onChange={setLine} />
        </div>
        <div className="mt-3">
          <CopyField label="The line" value={data.creditText} />
        </div>
      </Panel>
    </div>
  );
}
