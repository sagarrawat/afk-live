import clsx from 'clsx';
import { Link } from 'react-router-dom';
import { HelpLink } from '@/components/HelpLink';
import { HELP } from '@/lib/help';
import type { Me, Traffic } from '@/lib/types';

/** "1,234 GB", or "12.5 GB" under 100. */
export function gbText(gb: number): string {
  return `${gb.toLocaleString('en-US', { maximumFractionDigits: gb >= 100 ? 0 : 1 })} GB`;
}

/** "1 Nov": when the month's allowance starts over (the 1st, UTC). */
export function resetDay(t: Pick<Traffic, 'resetsAt'>): string {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(t.resetsAt));
}

/** FO-5: the account's streaming data this month, only when its plan has an allowance. */
export function limitedTraffic(me: Pick<Me, 'traffic'> | null | undefined): (Traffic & { allowanceGb: number }) | null {
  const t = me?.traffic;
  if (!t || t.allowanceGb == null) return null;
  return t as Traffic & { allowanceGb: number };
}

/** "Streaming data: 1,234 of 2,000 GB this month", amber from 80%, with an upgrade link. Nothing without an allowance. */
export function StreamingDataLine({ me, className }: { me: Pick<Me, 'traffic'>; className?: string }) {
  const t = limitedTraffic(me);
  if (!t) return null;
  const low = t.step !== 'NONE';
  return (
    <span className={clsx(low ? 'text-warn' : 'text-muted', className)}>
      Streaming data: {gbText(t.usedGb)} of {gbText(t.allowanceGb)} this month.
      {low && (
        <>
          {' '}
          <Link to="/studio/settings/billing" className="text-accent hover:underline">
            Upgrade
          </Link>
        </>
      )}
    </span>
  );
}

/** What fair use is doing right now, in plain words; null under 100%. */
export function streamingDataNotice(t: Traffic): string | null {
  const until = resetDay(t);
  if (t.step === 'REDUCED') {
    return `You've used this month's streaming data. Your streams stay live, each going to its first destination only, and streams you start before ${until} go out in 720p.`;
  }
  if (t.step === 'EXTRAS_PAUSED') {
    return `You've used this month's streaming data. Your streams stay live, each going to its first destination only, until ${until}.`;
  }
  return null;
}

/** FO-5 on Home: a banner once the allowance is used up, with the way out. */
export function StreamingDataBanner({ me }: { me: Pick<Me, 'traffic'> }) {
  const t = limitedTraffic(me);
  const text = t && streamingDataNotice(t);
  if (!t || !text) return null;
  return (
    <div role="status" className="mb-4 rounded-md border border-warn/40 bg-warn/10 px-4 py-3 text-sm">
      <p>{text}</p>
      <p className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
        <Link to="/studio/settings/billing" className="font-medium text-accent hover:underline">
          Upgrade for more
        </Link>
        <HelpLink to={HELP.streamingData}>How streaming data works</HelpLink>
      </p>
    </div>
  );
}
