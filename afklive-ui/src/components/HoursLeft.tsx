import clsx from 'clsx';
import { Link } from 'react-router-dom';
import type { Me } from '@/lib/types';

export interface Hours {
  used: number;
  included: number;
  left: number;
  /** 80 % or more used: shown amber, with an upgrade link. */
  low: boolean;
}

/** BI-1: this month's streaming hours on plans that have a limit (Free); null when there's no limit. */
export function hoursOf(me: Pick<Me, 'plan' | 'hoursUsed'>): Hours | null {
  const included = me.plan.monthlyHours;
  if (!included || included <= 0) return null;
  const used = Math.max(0, me.hoursUsed ?? 0);
  const left = Math.max(0, included - used);
  return { used, included, left, low: used >= included * 0.8 };
}

function h(n: number): string {
  const r = Math.round(n * 10) / 10;
  return Number.isInteger(r) ? String(r) : r.toFixed(1);
}

/** "6.5 of 10 h left this month", amber with "Upgrade" when it's running out. Nothing on unlimited plans. */
export function HoursLeft({ me, className, link = true }: { me: Pick<Me, 'plan' | 'hoursUsed'>; className?: string; link?: boolean }) {
  const hours = hoursOf(me);
  if (!hours) return null;
  const text = hours.left <= 0 ? `No streaming hours left this month` : `${h(hours.left)} of ${hours.included} h left this month`;
  return (
    <span className={clsx(hours.low ? 'text-warn' : 'text-muted', className)}>
      {text}
      {hours.low && link && (
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
