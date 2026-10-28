import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Badge, Button, ErrorNote, Field, Input, Panel, Spinner, Toggle } from '@/components/ui';
import { get, post } from '@/lib/api';
import { date } from '@/lib/format';
import { ReasonDialog } from './ReasonDialog';

/** BI-9: a launch offer as the admin sees it. */
export interface OfferRow {
  id: number;
  name: string;
  code: string | null;
  automatic: boolean;
  percentOff: number;
  monthlyPeriods: number;
  includeYearly: boolean;
  plans: string[];
  startsAt: string;
  endsAt: string;
  endedAt: string | null;
  status: 'SCHEDULED' | 'ACTIVE' | 'ENDED';
  redemptions: number;
  createdBy: string | null;
  createdReason: string | null;
  endedBy: string | null;
  endedReason: string | null;
}

const PLAN_NAMES: Record<string, string> = { ESSENTIALS: 'Creator', PRO: 'Pro', AGENCY: 'Agency' };

export interface OfferForm {
  name: string;
  code: string;
  automatic: boolean;
  percentOff: string;
  monthlyPeriods: string;
  includeYearly: boolean;
  plans: string[];
  startsAt: string;
  endsAt: string;
}

export function blankOffer(now = new Date()): OfferForm {
  const day = (d: Date) => d.toISOString().slice(0, 10);
  const end = new Date(now.getTime() + 30 * 86400_000);
  return { name: 'Launch offer', code: '', automatic: true, percentOff: '10', monthlyPeriods: '3', includeYearly: true, plans: ['ESSENTIALS', 'PRO', 'AGENCY'], startsAt: day(now), endsAt: day(end) };
}

/** What's wrong with the form (null: it can be sent). The server checks the same rules. */
export function offerProblem(f: OfferForm): string | null {
  const pct = Number(f.percentOff);
  const months = Number(f.monthlyPeriods);
  if (!f.name.trim() || f.name.trim().length > 80) return 'Give the offer a name of up to 80 characters.';
  if (!Number.isInteger(pct) || pct < 1 || pct > 50) return 'The discount is a whole percentage from 1 to 50.';
  if (!Number.isInteger(months) || months < 1 || months > 12) return 'Monthly plans get it for 1 to 12 months.';
  if (f.code.trim() && !/^[A-Za-z0-9]{3,32}$/.test(f.code.trim())) return 'A code is 3 to 32 letters or digits, e.g. LAUNCH10.';
  if (!f.automatic && !f.code.trim()) return 'An offer that isn’t automatic needs a code.';
  if (f.plans.length === 0) return 'Pick at least one plan.';
  if (!f.endsAt) return 'Every offer needs a real end date.';
  if (f.endsAt <= f.startsAt) return 'The end date must be after the start.';
  return null;
}

/** Start at the beginning of the start day, end at the end of the end day, India time. */
export function offerBody(f: OfferForm, now = new Date()) {
  const startIst = new Date(`${f.startsAt}T00:00:00+05:30`);
  return {
    name: f.name.trim(),
    code: f.code.trim() ? f.code.trim().toUpperCase() : null,
    automatic: f.automatic,
    percentOff: Number(f.percentOff),
    monthlyPeriods: Number(f.monthlyPeriods),
    includeYearly: f.includeYearly,
    plans: f.plans,
    startsAt: (startIst < now ? now : startIst).toISOString(),
    endsAt: new Date(`${f.endsAt}T23:59:59+05:30`).toISOString(),
  };
}

/** BI-9: create and end launch offers. Every change needs a reason and lands in the audit log. */
export function Offers() {
  const offers = useQuery({ queryKey: ['admin', 'offers'], queryFn: () => get<OfferRow[]>('/api/admin/offers') });
  const [creating, setCreating] = useState(false);
  const [ending, setEnding] = useState<OfferRow | null>(null);
  return (
    <Panel
      title="Launch offers"
      action={
        <Button size="sm" onClick={() => setCreating(true)}>
          New offer
        </Button>
      }
    >
      <p className="text-sm text-muted">
        A real percentage off the list price for accounts that have never had a plan, once per account, between real dates. The pricing page and the app show it only while it runs. Accounts that already took it keep their discounted renewals if you end it early.
      </p>
      {offers.isLoading ? (
        <Spinner />
      ) : !offers.data ? (
        <ErrorNote error={offers.error} />
      ) : offers.data.length === 0 ? (
        <p className="mt-3 text-sm">No offers yet. Without one, everyone sees the list prices.</p>
      ) : (
        <ul className="mt-3 divide-y divide-line">
          {offers.data.map((o) => (
            <li key={o.id} className="flex flex-wrap items-start gap-3 py-3">
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 font-medium">
                  {o.name} <Badge tone={o.status === 'ACTIVE' ? 'ok' : 'neutral'}>{o.status === 'ACTIVE' ? 'Running' : o.status === 'SCHEDULED' ? 'Scheduled' : 'Ended'}</Badge>
                  {o.code && <Badge>{o.code}</Badge>}
                </p>
                <p className="text-sm text-muted">
                  {o.percentOff}% off the first {o.monthlyPeriods === 1 ? 'month' : `${o.monthlyPeriods} months`}
                  {o.includeYearly ? ' or the first year' : ''} on {o.plans.map((p) => PLAN_NAMES[p] ?? p).join(', ')}
                  {o.automatic ? ', applied by itself' : ', with the code only'}. {date(o.startsAt)} to {date(o.endedAt ?? o.endsAt)}. Taken by {o.redemptions}.
                </p>
              </div>
              {o.status !== 'ENDED' && (
                <Button size="sm" variant="ghost" onClick={() => setEnding(o)}>
                  End now
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {creating && <OfferDialog onClose={() => setCreating(false)} onSaved={() => offers.refetch()} />}
      {ending && (
        <ReasonDialog title={`End ${ending.name}?`} action="End offer" danger onClose={() => setEnding(null)} onSubmit={(reason) => post(`/api/admin/offers/${ending.id}/end`, { reason }).then(() => offers.refetch())}>
          <p className="text-sm">New subscribers pay the list price from now on. The {ending.redemptions} account(s) that took it keep the discounted renewals they were promised.</p>
        </ReasonDialog>
      )}
    </Panel>
  );
}

function OfferDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<OfferForm>(() => blankOffer());
  const problem = offerProblem(f);
  const set = (k: keyof OfferForm) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const togglePlan = (p: string) => setF({ ...f, plans: f.plans.includes(p) ? f.plans.filter((x) => x !== p) : [...f.plans, p] });
  return (
    <ReasonDialog wide title="New launch offer" action="Create offer" blocked={problem != null} onClose={onClose} onSubmit={(reason) => post('/api/admin/offers', { offer: offerBody(f), reason }).then(onSaved)}>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name customers see" htmlFor="on">
          <Input id="on" value={f.name} maxLength={80} onChange={set('name')} />
        </Field>
        <Field label="Code (optional)" htmlFor="oc" hint="Letters and digits. Never reused.">
          <Input id="oc" value={f.code} maxLength={32} onChange={set('code')} placeholder="LAUNCH10" />
        </Field>
        <Field label="Discount (%)" htmlFor="op">
          <Input id="op" type="number" min={1} max={50} value={f.percentOff} onChange={set('percentOff')} />
        </Field>
        <Field label="Monthly plans: discounted months" htmlFor="om" hint="Yearly plans get it once, on the first year.">
          <Input id="om" type="number" min={1} max={12} value={f.monthlyPeriods} onChange={set('monthlyPeriods')} />
        </Field>
        <Field label="Starts (India time)" htmlFor="os">
          <Input id="os" type="date" value={f.startsAt} onChange={set('startsAt')} />
        </Field>
        <Field label="Last day (India time)" htmlFor="oe" hint="At most 92 days after the start.">
          <Input id="oe" type="date" value={f.endsAt} onChange={set('endsAt')} />
        </Field>
        <div className="flex items-center justify-between gap-3">
          <span className="text-sm font-medium">Apply by itself (shown on the pricing page)</span>
          <Toggle label="Automatic" checked={f.automatic} onChange={(v) => setF({ ...f, automatic: v })} />
        </div>
        <div className="flex items-center justify-between gap-3">
          <span className="text-sm font-medium">Also the first year of yearly plans</span>
          <Toggle label="Yearly" checked={f.includeYearly} onChange={(v) => setF({ ...f, includeYearly: v })} />
        </div>
        <fieldset className="sm:col-span-2">
          <legend className="text-sm font-medium">Plans</legend>
          <div className="mt-2 flex flex-wrap gap-4">
            {Object.entries(PLAN_NAMES).map(([id, name]) => (
              <label key={id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={f.plans.includes(id)} onChange={() => togglePlan(id)} />
                {name}
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      {problem && (
        <p role="alert" className="text-sm text-bad">
          {problem}
        </p>
      )}
    </ReasonDialog>
  );
}

/** BI-10: the recommended limits for new sign-ups, with who they affect. Paying subscribers keep what they bought. */
export interface Limits {
  streams: number;
  destinations: number;
  maxHeight: number;
  maxFps: number;
  trafficGb: number | null;
}

export interface LimitsPreview {
  alreadyApplied: boolean;
  plans: { plan: string; name: string; current: Limits; recommended: Limits; changes: string[]; protectedSubscribers: number; affectedAccounts: number; affectedNote: string }[];
}

export function limitsText(l: Limits): string {
  return `${l.streams} stream${l.streams === 1 ? '' : 's'}, ${l.destinations} destination${l.destinations === 1 ? '' : 's'} each, ${l.maxHeight}p${l.maxFps}, ${l.trafficGb == null ? 'no data limit' : `${l.trafficGb.toLocaleString('en-US')} GB a month`}`;
}

export function NewSignupLimits({ onApplied }: { onApplied?: () => void }) {
  const preview = useQuery({ queryKey: ['admin', 'plan-limits'], queryFn: () => get<LimitsPreview>('/api/admin/plan-limits/recommended') });
  const [confirm, setConfirm] = useState(false);
  if (preview.isLoading) return <Spinner />;
  if (!preview.data) return <ErrorNote error={preview.error} />;
  const p = preview.data;
  return (
    <Panel
      title="Limits for new sign-ups"
      action={
        !p.alreadyApplied && (
          <Button size="sm" variant="primary" onClick={() => setConfirm(true)}>
            Apply recommended limits
          </Button>
        )
      }
    >
      <p className="text-sm text-muted">
        {p.alreadyApplied
          ? 'The recommended limits are in place. Subscribers who paid before keep the limits they bought, for as long as they stay on their plan.'
          : 'One step, never automatic: new sign-ups get these limits; everyone already paying keeps the limits they bought (recorded on their subscription first).'}
      </p>
      <ul className="mt-3 space-y-3">
        {p.plans.map((r) => (
          <li key={r.plan} className="text-sm">
            <p className="font-medium">{r.name}</p>
            {r.changes.length === 0 ? (
              <p className="text-muted">{limitsText(r.current)}. No change.</p>
            ) : (
              <>
                <p>
                  <span className="text-muted">Now:</span> {limitsText(r.current)}
                </p>
                <p>
                  <span className="text-muted">New sign-ups:</span> {limitsText(r.recommended)}
                </p>
                <p className="text-muted">
                  {r.protectedSubscribers} paying subscriber{r.protectedSubscribers === 1 ? '' : 's'} keep their limits. {r.affectedNote}
                </p>
              </>
            )}
          </li>
        ))}
      </ul>
      {confirm && (
        <ReasonDialog
          title="Apply the recommended limits for new sign-ups?"
          action="Apply"
          onClose={() => setConfirm(false)}
          onSubmit={(reason) =>
            post('/api/admin/plan-limits/recommended', { reason }).then(() => {
              preview.refetch();
              onApplied?.();
            })
          }
        >
          <p className="text-sm">Plan settings change for new purchases at once. Nobody who pays today loses anything. Update the static plan cards (site/src/static-plans.ts) in the next release so the no-JavaScript fallback matches.</p>
        </ReasonDialog>
      )}
    </Panel>
  );
}
