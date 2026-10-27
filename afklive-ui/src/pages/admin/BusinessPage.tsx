import { useQuery } from '@tanstack/react-query';
import { ErrorNote, Panel, Spinner } from '@/components/ui';
import { get } from '@/lib/api';

/** GET /api/admin/business (BusinessMetrics.Report). Money is in rupees / dollars, without GST. */
export interface BusinessReport {
  generatedAt: string;
  weekStarts: string;
  accounts: { total: number; undated: number; wentLive: number; wentLivePercent?: number | null };
  weeks: { start: string; partial: boolean; signUps: number; signUpsWentLive: number; signUpsLiveIn24h: number; activeCreators: number; streamHours: number }[];
  conversion: {
    everPaid: number;
    everPaidPercent?: number | null;
    payingNow: number;
    payingNowPercent?: number | null;
    inGrace: number;
    endingAtPeriodEnd: number;
    givenByAdmin: number;
  };
  plans: { plan: string; name: string; cycle: string; currency: string; accounts: number; inGrace: number; monthlyEach: number; mrr: number }[];
  mrr: { inr: number; usd: number; formula: string };
  churn: { month: string; thisMonth: number; previousMonth: string; lastMonth: number; ratePercent?: number | null; formula: string };
  cohorts: { start: string; accounts: number; activePercent: (number | null)[] }[];
}

const num = (n: number) => n.toLocaleString('en-IN');
const pct = (p: number | null | undefined) => (p == null ? '–' : `${p.toLocaleString('en-US', { maximumFractionDigits: 1 })}%`);
const hours = (h: number) => `${h.toLocaleString('en-US', { maximumFractionDigits: h >= 100 ? 0 : 1 })} h`;

export function cashMajor(amount: number, currency: string): string {
  return new Intl.NumberFormat(currency === 'USD' ? 'en-US' : 'en-IN', {
    style: 'currency',
    currency: currency === 'USD' ? 'USD' : 'INR',
    maximumFractionDigits: amount >= 100 ? 0 : 2,
  }).format(amount);
}

/** "17 Mar" for a week's Monday (a plain date, shown as is). */
export function weekLabel(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, d)));
}

function monthLabel(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, 1)));
}

function Figure({ label, value, note, testId }: { label: string; value: React.ReactNode; note?: React.ReactNode; testId?: string }) {
  return (
    <div className="min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p className="tnum truncate text-xl font-semibold" data-testid={testId}>
        {value}
      </p>
      {note && <p className="tnum text-xs text-muted">{note}</p>}
    </div>
  );
}

function Estimate() {
  return <span className="ml-1.5 rounded-sm border border-line px-1 py-px align-middle text-[11px] font-medium text-muted">estimate</span>;
}

function retentionShade(p: number | null): string {
  if (p == null) return 'text-muted';
  if (p >= 50) return 'bg-accent/25 font-medium';
  if (p >= 20) return 'bg-accent/10';
  return '';
}

/**
 * Admin > Business: sign-ups, active creators, first go-live and paid conversion, MRR, churn, cohort
 * retention and stream-hours, from data that already exists. Money figures are estimates (list prices
 * without GST); the page says how each is worked out.
 */
export function BusinessPage() {
  const q = useQuery({ queryKey: ['admin', 'business'], queryFn: () => get<BusinessReport>('/api/admin/business'), refetchInterval: 300000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const r = q.data;
  const thisWeek = r.weeks[r.weeks.length - 1];
  const weeksNewestFirst = [...r.weeks].reverse();
  const cohortWeeks = r.cohorts[0]?.activePercent.length ?? 0;
  const noAccounts = r.accounts.total === 0;
  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-xl font-bold">Business</h1>
        <p className="mt-1 text-sm text-muted">
          Weeks start {r.weekStarts}. Admin accounts are left out. Money is without GST and marked as an estimate.
        </p>
      </div>

      {noAccounts && <p className="rounded border border-line px-3 py-2 text-sm text-muted">No creator accounts yet, so there's nothing to count.</p>}

      <Panel title="This week">
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <Figure label="Accounts" value={num(r.accounts.total)} note={`${num(thisWeek?.signUps ?? 0)} new this week`} testId="biz-accounts" />
          <Figure label="Active creators" value={num(thisWeek?.activeCreators ?? 0)} note="on air this week" testId="biz-active" />
          <Figure
            label="Monthly revenue"
            value={
              <>
                {cashMajor(r.mrr.inr, 'INR')}
                <Estimate />
              </>
            }
            note={r.mrr.usd > 0 ? `+ ${cashMajor(r.mrr.usd, 'USD')} from USD plans` : 'MRR, without GST'}
            testId="biz-mrr"
          />
          <Figure label="Paying accounts" value={num(r.conversion.payingNow)} note={`${pct(r.conversion.payingNowPercent)} of accounts`} testId="biz-paying" />
        </div>
      </Panel>

      <Panel title="Conversion">
        <div className="grid gap-4 sm:grid-cols-3">
          <Figure
            label="Went live at least once"
            value={pct(r.accounts.wentLivePercent)}
            note={`${num(r.accounts.wentLive)} of ${num(r.accounts.total)} accounts`}
            testId="biz-went-live"
          />
          <Figure
            label="Paid at least once"
            value={pct(r.conversion.everPaidPercent)}
            note={`${num(r.conversion.everPaid)} accounts (Free → paid)`}
            testId="biz-ever-paid"
          />
          <Figure
            label="On a paid plan now"
            value={pct(r.conversion.payingNowPercent)}
            note={`${num(r.conversion.payingNow)} accounts`}
          />
        </div>
        <ul className="mt-4 space-y-0.5 border-t border-line pt-3 text-xs text-muted">
          <li>{num(r.conversion.inGrace)} in their grace days (period ended, not renewed yet; not in revenue).</li>
          <li>{num(r.conversion.endingAtPeriodEnd)} set to move to Free at the end of their period (still in revenue until then).</li>
          <li>{num(r.conversion.givenByAdmin)} on a plan an admin gave without a payment (not counted as paying).</li>
          {r.accounts.undated > 0 && <li>{num(r.accounts.undated)} older accounts have no sign-up date: in the totals, in no week.</li>}
        </ul>
      </Panel>

      <Panel
        title={
          <>
            Monthly recurring revenue
            <Estimate />
          </>
        }
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Figure label="INR, without GST" value={cashMajor(r.mrr.inr, 'INR')} />
          <Figure label="USD (no GST on exports)" value={cashMajor(r.mrr.usd, 'USD')} note="Kept apart: no exchange rate applied" />
        </div>
        {r.plans.length === 0 ? (
          <p className="mt-4 text-sm text-muted">No paying accounts yet.</p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full min-w-[480px] text-sm">
              <caption className="sr-only">Paying accounts by plan</caption>
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1.5 pr-3 font-medium">Plan</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Accounts</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Each, per month</th>
                  <th className="py-1.5 text-right font-medium">Revenue per month</th>
                </tr>
              </thead>
              <tbody className="tnum">
                {r.plans.map((p) => (
                  <tr key={`${p.plan}-${p.cycle}-${p.currency}`} className="border-t border-line">
                    <td className="py-1.5 pr-3">
                      {p.name} <span className="text-muted">· {p.cycle === 'YEARLY' ? 'yearly' : 'monthly'}{p.currency === 'USD' ? ' · USD' : ''}</span>
                    </td>
                    <td className="py-1.5 pr-3 text-right">
                      {num(p.accounts)}
                      {p.inGrace > 0 && <span className="text-xs text-warn"> ({num(p.inGrace)} in grace)</span>}
                    </td>
                    <td className="py-1.5 pr-3 text-right">{cashMajor(p.monthlyEach, p.currency)}</td>
                    <td className="py-1.5 text-right font-medium">{cashMajor(p.mrr, p.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <details className="mt-3 text-xs text-muted">
          <summary className="cursor-pointer">How this is worked out</summary>
          <p className="mt-1">{r.mrr.formula}</p>
          <p className="mt-1">It uses today's list prices, so discounts, upgrade credit and older prices aren't in it.</p>
        </details>
      </Panel>

      <Panel title="Churn">
        <div className="grid gap-4 sm:grid-cols-3">
          <Figure label={`Left in ${monthLabel(r.churn.month)} so far`} value={num(r.churn.thisMonth)} note="paid plans that moved to Free" testId="biz-churn" />
          <Figure
            label="Churn rate"
            value={
              <>
                {pct(r.churn.ratePercent)}
                <Estimate />
              </>
            }
          />
          <Figure label={`Left in ${monthLabel(r.churn.previousMonth)}`} value={num(r.churn.lastMonth)} />
        </div>
        <p className="mt-3 text-xs text-muted">{r.churn.formula} Someone who left and paid again in the same month isn't counted.</p>
      </Panel>

      <Panel title="Week by week">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[600px] text-sm">
            <caption className="sr-only">Sign-ups, first go-lives, active creators and stream-hours per week</caption>
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="py-1.5 pr-3 font-medium">Week of</th>
                <th className="py-1.5 pr-3 text-right font-medium">Sign-ups</th>
                <th className="py-1.5 pr-3 text-right font-medium">Of them went live</th>
                <th className="py-1.5 pr-3 text-right font-medium">Within 24 h</th>
                <th className="py-1.5 pr-3 text-right font-medium">Active creators</th>
                <th className="py-1.5 text-right font-medium">Stream-hours</th>
              </tr>
            </thead>
            <tbody className="tnum">
              {weeksNewestFirst.map((w) => (
                <tr key={w.start} className="border-t border-line">
                  <td className="py-1.5 pr-3">
                    {weekLabel(w.start)}
                    {w.partial && <span className="text-xs text-muted"> (so far)</span>}
                  </td>
                  <td className="py-1.5 pr-3 text-right">{num(w.signUps)}</td>
                  <td className="py-1.5 pr-3 text-right">{num(w.signUpsWentLive)}</td>
                  <td className="py-1.5 pr-3 text-right">
                    {num(w.signUpsLiveIn24h)}
                    {w.signUps > 0 && <span className="text-xs text-muted"> ({pct(Math.round((w.signUpsLiveIn24h * 1000) / w.signUps) / 10)})</span>}
                  </td>
                  <td className="py-1.5 pr-3 text-right">{num(w.activeCreators)}</td>
                  <td className="py-1.5 text-right">{hours(w.streamHours)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-2 text-xs text-muted">Within 24 h: sign-ups whose first stream reached a platform within a day (the activation target in the product spec is 40%). Active creators: accounts with a
          stream on air for any part of the week. Stream-hours are counted like the Costs panel.</p>
      </Panel>

      <Panel title="Retention by sign-up week">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[620px] text-sm">
            <caption className="sr-only">Share of each sign-up week's accounts on air in each week after</caption>
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="py-1.5 pr-3 font-medium">Signed up</th>
                <th className="py-1.5 pr-3 text-right font-medium">Accounts</th>
                {Array.from({ length: cohortWeeks }, (_, n) => (
                  <th key={n} className="px-1 py-1.5 text-right font-medium">
                    {n === 0 ? 'Week 0' : `Wk ${n}`}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="tnum">
              {r.cohorts.map((c) => (
                <tr key={c.start} className="border-t border-line">
                  <td className="py-1.5 pr-3">{weekLabel(c.start)}</td>
                  <td className="py-1.5 pr-3 text-right">{num(c.accounts)}</td>
                  {c.activePercent.map((p, n) => (
                    <td key={n} className={`px-1 py-1.5 text-right ${retentionShade(p)}`}>
                      {pct(p)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-2 text-xs text-muted">
          The share of each week's new accounts that were on air in week 0 (the week they signed up), week 1 and so on. A dash means that week hasn't come yet or
          nobody signed up.
        </p>
      </Panel>
    </div>
  );
}
