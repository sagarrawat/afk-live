import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { ErrorNote, Panel, Spinner } from '@/components/ui';
import { get } from '@/lib/api';
import { bytes } from '@/lib/format';
import { Meter } from '../Admin';

/** FO-3: one group of runs (a plan or a quality); money in the cost currency, null when unknown. */
export interface CostGroup {
  key: string;
  hours: number;
  gb: number;
  gbPerHour?: number | null;
  serverPerHour?: number | null;
  trafficPerHour?: number | null;
  marginalTrafficPerHour?: number | null;
  totalPerHour?: number | null;
}
export interface CostMargin {
  plan: string;
  name: string;
  accounts: number;
  netPrice?: number | null;
  hoursPerAccount: number;
  costPerAccount?: number | null;
  marginPerAccount?: number | null;
  marginPercent?: number | null;
  fullUseHours: number;
  fullUseCost?: number | null;
  fullUseMargin?: number | null;
}
export interface CostReport {
  cost: {
    inputs: { monthlyServerCost?: number | null; currency: string; includedGb: number; includedDefault: boolean; pricePerGb?: number | null; priceDefault: boolean };
    month: string;
    monthElapsed: number;
    totals: {
      hours: number;
      gb: number;
      projectedHours: number;
      projectedGb: number;
      usedPercent?: number | null;
      projectedPercent?: number | null;
      serverPerHour?: number | null;
      trafficCostMonth?: number | null;
      trafficPerHour?: number | null;
      marginalTrafficPerHour?: number | null;
      totalPerHour?: number | null;
      projectedCostMonth?: number | null;
    };
    byPlan: CostGroup[];
    byQuality: CostGroup[];
    margins: CostMargin[];
  };
  cpu: { percentPerStream?: number | null; averageStreams?: number | null; samples: number };
  topAccounts: { key: string; userId?: number | null; plan?: string | null; hours: number; gb: number }[];
  byServer: { key: string; hours: number; gb: number }[];
}

/** Money in the cost currency; small per-hour amounts keep enough digits to compare. */
export function cash(amount: number | null | undefined, currency: string, digits?: number): string {
  if (amount == null || !Number.isFinite(amount)) return '–';
  const d = digits ?? (Math.abs(amount) < 1 ? 3 : 2);
  try {
    return new Intl.NumberFormat(currency === 'INR' ? 'en-IN' : 'en-US', { style: 'currency', currency, minimumFractionDigits: d, maximumFractionDigits: d }).format(amount);
  } catch {
    return `${amount.toFixed(d)} ${currency}`;
  }
}

const gbText = (gb: number) => `${gb.toLocaleString('en-US', { maximumFractionDigits: gb >= 100 ? 0 : 1 })} GB`;
const hoursText = (h: number) => `${h.toLocaleString('en-US', { maximumFractionDigits: h >= 100 ? 0 : 1 })} h`;

function GroupTable({ title, rows, currency, label }: { title: string; rows: CostGroup[]; currency: string; label?: (key: string) => string }) {
  return (
    <div className="min-w-0">
      <h3 className="mb-1.5 text-sm font-medium">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-muted">No streams this month yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[520px] text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="py-1.5 pr-3 font-medium" />
                <th className="py-1.5 pr-3 text-right font-medium">Hours</th>
                <th className="py-1.5 pr-3 text-right font-medium">GB per hour</th>
                <th className="py-1.5 pr-3 text-right font-medium">Server</th>
                <th className="py-1.5 pr-3 text-right font-medium">Traffic</th>
                <th className="py-1.5 text-right font-medium">Per stream-hour</th>
              </tr>
            </thead>
            <tbody className="tnum">
              {rows.map((g) => (
                <tr key={g.key} className="border-t border-line">
                  <td className="py-1.5 pr-3">{label ? label(g.key) : g.key}</td>
                  <td className="py-1.5 pr-3 text-right">{hoursText(g.hours)}</td>
                  <td className="py-1.5 pr-3 text-right">{g.gbPerHour == null ? '–' : g.gbPerHour.toFixed(2)}</td>
                  <td className="py-1.5 pr-3 text-right">{cash(g.serverPerHour, currency)}</td>
                  <td className="py-1.5 pr-3 text-right" title={`Once the included transfer is used up: ${cash(g.marginalTrafficPerHour, currency)} per hour`}>
                    {cash(g.trafficPerHour, currency)}
                  </td>
                  <td className="py-1.5 text-right font-medium">{cash(g.totalPerHour, currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/**
 * FO-3 / FO-5 on admin Billing: cost per stream-hour (overall, by plan, by quality), traffic against the
 * included transfer, the month-end projection and each plan's margin. Every number is an estimate from
 * measured bytes and stream-hours and the settings shown under "How this is worked out".
 */
export function CostPanel() {
  const q = useQuery({ queryKey: ['admin', 'costs'], queryFn: () => get<CostReport>('/api/admin/costs'), refetchInterval: 300000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const { cost, cpu, topAccounts, byServer } = q.data;
  const { inputs, totals } = cost;
  const cur = inputs.currency;
  const planName = (key: string) => cost.margins.find((m) => m.plan === key)?.name ?? key;
  const noTraffic = totals.hours > 0 && totals.gb === 0;
  return (
    <Panel title="Cost per stream-hour (estimate)">
      <div className="space-y-6">
        <div className="grid gap-4 sm:grid-cols-3">
          <div className="min-w-0">
            <p className="text-sm text-muted">Per stream-hour this month</p>
            <p className="tnum text-xl font-semibold" data-testid="cost-per-hour">{cash(totals.totalPerHour, cur)}</p>
            <p className="tnum text-xs text-muted">
              server {cash(totals.serverPerHour, cur)} + traffic {cash(totals.trafficPerHour, cur)}
            </p>
          </div>
          <div className="min-w-0">
            <p className="text-sm text-muted">Sent this month</p>
            <p className="tnum text-xl font-semibold">{gbText(totals.gb)}</p>
            <p className="tnum text-xs text-muted">over {hoursText(totals.hours)} of streaming</p>
          </div>
          <div className="min-w-0">
            <p className="text-sm text-muted">By month end (projected)</p>
            <p className="tnum text-xl font-semibold">{cash(totals.projectedCostMonth, cur, 0)}</p>
            <p className="tnum text-xs text-muted">
              {gbText(totals.projectedGb)}, {hoursText(totals.projectedHours)}
              {totals.trafficCostMonth != null && totals.trafficCostMonth > 0 ? `, ${cash(totals.trafficCostMonth, cur, 0)} of it traffic` : ''}
            </p>
          </div>
        </div>

        {noTraffic && (
          <p className="rounded-md border border-warn/40 bg-warn/10 px-3 py-2 text-sm">
            No traffic recorded yet: workers report bytes sent from the release that measures them. Traffic costs show as zero until then.
          </p>
        )}

        {inputs.includedGb > 0 ? (
          <div>
            <Meter
              label={`Included transfer, ${cost.month}`}
              pct={totals.usedPercent ?? 0}
              text={`${gbText(totals.gb)} of ${gbText(inputs.includedGb)} (${Math.floor(totals.usedPercent ?? 0)}%)`}
            />
            <p className="tnum mt-1 text-xs text-muted">
              At this pace, {Math.round(totals.projectedPercent ?? 0)}% by month end. Admins are alerted at 70% and 90%.
            </p>
          </div>
        ) : (
          <p className="text-sm text-muted">No transfer is included in the server bill: every GB is paid.</p>
        )}

        <div className="grid gap-6 xl:grid-cols-2">
          <GroupTable title="By plan" rows={cost.byPlan} currency={cur} label={planName} />
          <GroupTable title="By quality" rows={cost.byQuality} currency={cur} />
        </div>

        <div className="min-w-0">
          <h3 className="mb-1.5 text-sm font-medium">Margin per plan</h3>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1.5 pr-3 font-medium">Plan</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Accounts</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Price before GST</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Cost per account</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Margin</th>
                  <th className="py-1.5 text-right font-medium">At full use</th>
                </tr>
              </thead>
              <tbody className="tnum">
                {cost.margins.map((m) => (
                  <tr key={m.plan} className="border-t border-line">
                    <td className="py-1.5 pr-3">{m.name}</td>
                    <td className="py-1.5 pr-3 text-right">{m.accounts}</td>
                    <td className="py-1.5 pr-3 text-right">{cash(m.netPrice, cur, 0)}</td>
                    <td className="py-1.5 pr-3 text-right" title={`${hoursText(m.hoursPerAccount)} per account this month (projected)`}>
                      {cash(m.costPerAccount, cur)}
                    </td>
                    <td className={clsx('py-1.5 pr-3 text-right', m.marginPerAccount != null && m.marginPerAccount < 0 && 'text-bad')}>
                      {cash(m.marginPerAccount, cur)}
                      {m.marginPercent != null ? ` (${m.marginPercent}%)` : ''}
                    </td>
                    <td className={clsx('py-1.5 text-right', m.fullUseMargin != null && m.fullUseMargin < 0 && 'text-bad')} title={`${hoursText(m.fullUseHours)} at this plan's cost per hour`}>
                      {cash(m.fullUseMargin, cur)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-1 text-xs text-muted">
            Cost per account: the plan's projected hours this month over its accounts. At full use: every included hour (24/7 for 30 days when unlimited) at the plan's cost per hour.
          </p>
        </div>

        {(topAccounts.length > 0 || byServer.length > 0) && (
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="min-w-0">
              <h3 className="mb-1.5 text-sm font-medium">Most traffic this month</h3>
              <ul className="space-y-1 text-sm">
                {topAccounts.map((a) => (
                  <li key={a.key} className="tnum flex justify-between gap-3">
                    <span className="min-w-0 truncate">{a.key}</span>
                    <span className="shrink-0 text-muted">{gbText(a.gb)}</span>
                  </li>
                ))}
              </ul>
            </div>
            <div className="min-w-0">
              <h3 className="mb-1.5 text-sm font-medium">Traffic by server</h3>
              <ul className="space-y-1 text-sm">
                {byServer.map((s) => (
                  <li key={s.key} className="tnum flex justify-between gap-3">
                    <span className="min-w-0 truncate">{s.key}</span>
                    <span className="shrink-0 text-muted">{gbText(s.gb)}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-1 text-xs text-muted">A run counts on the server that ran it last.</p>
            </div>
          </div>
        )}

        <details className="text-sm">
          <summary className="cursor-pointer text-muted">How this is worked out</summary>
          <ul className="tnum mt-2 space-y-1 text-muted" aria-label="Inputs">
            <li>
              Server bill: {inputs.monthlyServerCost == null ? <>not set (<code>APP_MONTHLY_SERVER_COST</code>), so server cost is unknown</> : `${cash(inputs.monthlyServerCost, cur, 0)} a month`}, shared equally per stream-hour.
            </li>
            <li>
              Included transfer: {gbText(inputs.includedGb)} a month {inputs.includedDefault ? '(default: one DigitalOcean 4 vCPU / 8 GB droplet; set APP_INCLUDED_TRANSFER_GB)' : '(APP_INCLUDED_TRANSFER_GB)'}.
            </li>
            <li>
              Beyond it: {inputs.pricePerGb == null ? <>unknown for {cur} (set <code>APP_TRANSFER_PRICE_PER_GB</code>)</> : `${cash(inputs.pricePerGb, cur, inputs.pricePerGb < 0.1 ? 4 : 2)} per GB`}
              {inputs.priceDefault && inputs.pricePerGb != null ? ' (default: DigitalOcean’s $0.01 per GB; set APP_TRANSFER_PRICE_PER_GB)' : ''}. Traffic costs are shared by the GB each group sent; hover a traffic figure for the cost once the included transfer is used up.
            </li>
            <li>
              Traffic: bytes each destination actually sent, as workers report them. Hours: every run this month. Projections assume the rest of the month goes like the {Math.round(cost.monthElapsed * 100)}% so far.
            </li>
            <li>
              Measured CPU: {cpu.percentPerStream == null ? 'no server samples in the last 7 days' : `about ${cpu.percentPerStream.toFixed(1)}% of a server per stream, ${(cpu.averageStreams ?? 0).toFixed(1)} streams per server on average (last 7 days)`}. CPU isn’t measured per stream, so server cost isn’t split by quality.
            </li>
          </ul>
        </details>
      </div>
    </Panel>
  );
}

/** FO-5: one plan at full use (fair use applied) and for its average streaming account; money in the cost currency. */
export interface PlanCase {
  plan: string;
  name: string;
  streams: number;
  destinations: number;
  quality: string;
  videoKbps: number;
  streamHours: number;
  trafficGb?: number | null;
  fullUseGb: number;
  worstGb: number;
  netPrice?: number | null;
  trafficCost?: number | null;
  serverCost?: number | null;
  worstCost?: number | null;
  worstMargin?: number | null;
  costShare?: number | null;
  meetsRule?: boolean | null;
  typicalAccounts?: number | null;
  typicalGb?: number | null;
  typicalHours?: number | null;
  typicalCost?: number | null;
  typicalMargin?: number | null;
  typicalShare?: number | null;
}
export interface WorstCase {
  month: string;
  inputs: { currency: string; pricePerGb?: number | null; serverPerStreamHour?: number | null; serverSource: string; gatewayFeePercent: number };
  maxCostShare: number;
  plans: PlanCase[];
  suggestedAllowanceGb: Record<string, number>;
}

const pct = (share: number | null | undefined) => (share == null ? '–' : `${Math.round(share * 100)}%`);

const SERVER_SOURCE: Record<string, string> = {
  APP_SERVER_COST_PER_STREAM_MONTH: 'APP_SERVER_COST_PER_STREAM_MONTH',
  measured: 'this month’s measured server cost per stream-hour',
  unset: 'not known (set APP_MONTHLY_SERVER_COST or APP_SERVER_COST_PER_STREAM_MONTH), so only traffic is counted',
  'no-hours': 'not known yet (no stream-hours this month), so only traffic is counted',
};

/**
 * FO-5 on admin Billing: what each plan can cost at worst (every stream all month, every destination, top quality,
 * with fair use applied past its allowance) and for the average account that streamed this month, against the net
 * price. Gate A: the worst case must stay within 60% of the net price.
 */
export function WorstCasePanel() {
  const q = useQuery({ queryKey: ['admin', 'worst-case'], queryFn: () => get<WorstCase>('/api/admin/costs/worst-case'), refetchInterval: 300000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const { inputs, plans, maxCostShare, suggestedAllowanceGb } = q.data;
  const cur = inputs.currency;
  const rule = Math.round(maxCostShare * 100);
  return (
    <Panel title="Worst case per plan (estimate)">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Each plan used to the full: every stream all month, to every destination, at its top quality. Past a plan’s streaming data allowance, streams keep one destination each (fair use never stops a stream). The rule: the worst case costs at most {rule}% of the price before GST and the gateway fee.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="py-1.5 pr-3 font-medium">Plan</th>
                <th className="py-1.5 pr-3 font-medium">At full use</th>
                <th className="py-1.5 pr-3 text-right font-medium">Allowance</th>
                <th className="py-1.5 pr-3 text-right font-medium">Traffic</th>
                <th className="py-1.5 pr-3 text-right font-medium">Net price</th>
                <th className="py-1.5 pr-3 text-right font-medium">Worst cost</th>
                <th className="py-1.5 pr-3 text-right font-medium">Of price</th>
                <th className="py-1.5 text-right font-medium">Typical cost</th>
              </tr>
            </thead>
            <tbody className="tnum">
              {plans.map((p) => (
                <tr key={p.plan} className="border-t border-line" data-testid={`worst-${p.plan}`}>
                  <td className="py-1.5 pr-3 font-medium">{p.name}</td>
                  <td className="py-1.5 pr-3 text-muted">
                    {p.streams} stream{p.streams === 1 ? '' : 's'} × {p.destinations} destination{p.destinations === 1 ? '' : 's'}, {p.quality}, {hoursText(p.streamHours)}
                  </td>
                  <td className="py-1.5 pr-3 text-right">{p.trafficGb == null ? 'none' : gbText(p.trafficGb)}</td>
                  <td className="py-1.5 pr-3 text-right" title={p.worstGb < p.fullUseGb ? `${gbText(p.fullUseGb)} without the allowance` : undefined}>
                    {gbText(p.worstGb)}
                  </td>
                  <td className="py-1.5 pr-3 text-right">{p.netPrice ? cash(p.netPrice, cur, 0) : 'free'}</td>
                  <td className="py-1.5 pr-3 text-right" title={`traffic ${cash(p.trafficCost, cur, 0)} + servers ${cash(p.serverCost, cur, 0)}`}>
                    {cash(p.worstCost, cur, 0)}
                  </td>
                  <td className={clsx('py-1.5 pr-3 text-right font-medium', p.meetsRule === false && 'text-bad', p.meetsRule === true && 'text-ok')}>
                    {p.meetsRule == null ? '–' : `${pct(p.costShare)} ${p.meetsRule ? 'ok' : `over ${rule}%`}`}
                  </td>
                  <td className="py-1.5 text-right" title={p.typicalAccounts ? `${p.typicalAccounts} account(s) streamed this month: ${gbText(p.typicalGb ?? 0)}, ${hoursText(p.typicalHours ?? 0)} each on average (projected)` : undefined}>
                    {p.typicalAccounts ? `${cash(p.typicalCost, cur, 0)} (${pct(p.typicalShare)})` : 'no data yet'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {Object.keys(suggestedAllowanceGb).length > 0 && (
          <p className="text-sm">
            Largest allowance that meets the rule at these prices:{' '}
            {Object.entries(suggestedAllowanceGb)
              .map(([plan, gb]) => `${plans.find((p) => p.plan === plan)?.name ?? plan} ${gbText(gb)}`)
              .join(', ')}
            .
          </p>
        )}
        <details className="text-sm">
          <summary className="cursor-pointer text-muted">How this is worked out</summary>
          <ul className="tnum mt-2 space-y-1 text-muted" aria-label="Worst case inputs">
            <li>Traffic per destination: the plan’s video bitrate at its top quality, plus 128 kbps audio and 4% overhead.</li>
            <li>
              Every GB at {inputs.pricePerGb == null ? 'an unknown price (set APP_TRANSFER_PRICE_PER_GB)' : `${cash(inputs.pricePerGb, cur, inputs.pricePerGb < 0.1 ? 4 : 2)} per GB`}, the price beyond the included transfer: at worst, other streams have used it up.
            </li>
            <li>
              Servers: {inputs.serverPerStreamHour == null ? SERVER_SOURCE[inputs.serverSource] ?? 'not known' : `${cash(inputs.serverPerStreamHour * 720, cur, 0)} per stream-month, from ${SERVER_SOURCE[inputs.serverSource] ?? inputs.serverSource}`}.
            </li>
            <li>Net price: the monthly price without 18% GST, less a {inputs.gatewayFeePercent}% gateway fee (APP_GATEWAY_FEE_PERCENT).</li>
            <li>Typical: the average account on the plan that streamed this month, projected to month end.</li>
            <li>Running streams keep their quality past 110% (changing it would reconnect them), so the worst case doesn’t count the 720p step.</li>
          </ul>
        </details>
      </div>
    </Panel>
  );
}

/** FO-6 on admin Servers: prepared copies of library videos and what they take up. */
export interface CopyStats {
  kinds: { kind: string; ready: number; bytes: number; unknownSize: number; waiting: number; failed: number; dueForDeletion: number }[];
  ready: number;
  bytes: number;
  keepDays: number;
}

const KIND = {
  PLAYOUT: 'Smaller copies (studio)',
  PROFILE: 'Stream-ready copies (copy mode)',
  PROFILE_OVERLAY: 'Stream-ready copies with a logo or the Free mark (copy mode, one set per look)',
} as Record<string, string>;

export function CopiesPanel() {
  const q = useQuery({ queryKey: ['admin', 'copies'], queryFn: () => get<CopyStats>('/api/admin/copies'), refetchInterval: 300000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const s = q.data;
  return (
    <Panel title="Prepared copies">
      <p className="tnum text-sm">
        <span className="font-medium">{s.ready}</span> ready, {bytes(s.bytes)} in storage. Copies nobody plays for {s.keepDays} days are deleted and made again when a stream needs them; never one a live stream plays.
      </p>
      {s.kinds.length > 0 && (
        <ul className="tnum mt-3 space-y-1 text-sm text-muted">
          {s.kinds.map((k) => (
            <li key={k.kind}>
              <span className="text-ink">{KIND[k.kind] ?? k.kind}</span>: {k.ready} ready, {bytes(k.bytes)}
              {k.unknownSize > 0 ? ` (${k.unknownSize} not measured yet)` : ''}
              {k.waiting > 0 ? `, ${k.waiting} being made` : ''}
              {k.failed > 0 ? `, ${k.failed} failed` : ''}
              {k.dueForDeletion > 0 ? `, ${k.dueForDeletion} due for deletion` : ''}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
