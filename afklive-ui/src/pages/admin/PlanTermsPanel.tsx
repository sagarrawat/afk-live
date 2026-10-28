import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, ErrorNote, Field, Input, Panel, Segmented, Select, Spinner } from '@/components/ui';
import { get, post } from '@/lib/api';
import { bytes, date } from '@/lib/format';
import { ReasonDialog } from './ReasonDialog';

/** One set of limits (PlanTermsAdmin.Limits). monthlyHours 0 = no limit; trafficGb null = no streaming data limit. */
export interface TermLimits {
  streams: number;
  destinations: number;
  maxHeight: number;
  maxFps: number;
  storageBytes: number;
  monthlyHours: number;
  trafficGb: number | null;
  watermark: boolean;
}

/** GET /api/admin/users/{id}/terms (PlanTermsAdmin.TermsView). */
export interface TermsView {
  applies: boolean;
  why: string | null;
  plan: string;
  planName: string;
  capturedAt: string | null;
  recorded: TermLimits | null;
  current: TermLimits;
  effective: TermLimits;
  extras: string[];
  resetReduces: string[];
}

const GB = 1024 ** 3;

type Row = { key: keyof TermLimits; label: string; text: (l: TermLimits) => string };

export const TERM_ROWS: Row[] = [
  { key: 'streams', label: 'Streams at a time', text: (l) => String(l.streams) },
  { key: 'destinations', label: 'Destinations each', text: (l) => String(l.destinations) },
  { key: 'maxHeight', label: 'Top quality', text: (l) => (l.maxHeight >= 2160 ? '4K (2160p)' : `${l.maxHeight}p`) },
  { key: 'maxFps', label: 'Frame rate', text: (l) => `${l.maxFps} fps` },
  { key: 'storageBytes', label: 'Storage', text: (l) => bytes(l.storageBytes) },
  { key: 'monthlyHours', label: 'Hours a month', text: (l) => (l.monthlyHours === 0 ? 'No limit' : String(l.monthlyHours)) },
  { key: 'trafficGb', label: 'Streaming data a month', text: (l) => (l.trafficGb == null ? 'No limit' : `${l.trafficGb.toLocaleString('en-US')} GB`) },
  { key: 'watermark', label: 'Watermark', text: (l) => (l.watermark ? 'Yes' : 'No') },
];

/** Is {@code a} more generous than {@code b} for this limit? (0 hours and no data limit are the most.) */
export function moreGenerous(key: keyof TermLimits, a: TermLimits, b: TermLimits): boolean {
  if (key === 'monthlyHours') return b.monthlyHours !== 0 && (a.monthlyHours === 0 || a.monthlyHours > b.monthlyHours);
  if (key === 'trafficGb') return b.trafficGb != null && (a.trafficGb == null || a.trafficGb > b.trafficGb);
  if (key === 'watermark') return b.watermark && !a.watermark;
  return (a[key] as number) > (b[key] as number);
}

interface RaiseForm {
  streams: string;
  destinations: string;
  maxHeight: string;
  maxFps: string;
  storageGb: string;
  monthlyHours: string;
  removeWatermark: boolean;
}

/** The raise request: only limits that are filled in and more than in effect now; null when there's nothing to raise. */
export function raiseBody(f: RaiseForm, now: TermLimits): Record<string, number | boolean> | null {
  const out: Record<string, number | boolean> = {};
  const n = (v: string) => (v.trim() === '' ? null : Number(v));
  const streams = n(f.streams);
  if (streams != null && streams > now.streams) out.streams = streams;
  const dest = n(f.destinations);
  if (dest != null && dest > now.destinations) out.destinations = dest;
  const h = n(f.maxHeight);
  if (h != null && h > now.maxHeight) out.maxHeight = h;
  const fps = n(f.maxFps);
  if (fps != null && fps > now.maxFps) out.maxFps = fps;
  const gb = n(f.storageGb);
  if (gb != null && gb * GB > now.storageBytes) out.storageGb = gb;
  const hours = n(f.monthlyHours);
  if (hours != null && now.monthlyHours !== 0 && (hours === 0 || hours > now.monthlyHours)) out.monthlyHours = hours;
  if (f.removeWatermark && now.watermark) out.watermark = false;
  return Object.keys(out).length === 0 ? null : out;
}

/** Would setting the subscriber's own data allowance to {@code gb} (null: none) leave them with less? */
export function allowanceReduces(gb: number | null, v: TermsView): boolean {
  const planGb = v.current.trafficGb;
  const after = planGb == null || gb == null ? null : Math.max(planGb, gb);
  const before = v.effective.trafficGb;
  return after != null && (before == null || after < before);
}

/**
 * BI-10 on the admin user page: the limits kept from their purchase next to what their plan gives today, and the
 * audited actions: raise limits, reset to the plan's (confirm when it takes anything away), set or clear a data allowance.
 */
export function PlanTermsPanel({ id, onChanged }: { id: number; onChanged?: () => void }) {
  const q = useQuery({ queryKey: ['admin', 'user', id, 'terms'], queryFn: () => get<TermsView>(`/api/admin/users/${id}/terms`) });
  const [act, setAct] = useState<'raise' | 'reset' | 'data' | null>(null);
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const v = q.data;
  const done = () => {
    q.refetch();
    onChanged?.();
  };
  return (
    <Panel
      title="Plan limits"
      action={
        v.applies && (
          <div className="flex flex-wrap gap-1">
            <Button size="sm" variant="ghost" onClick={() => setAct('raise')}>
              Raise limits
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setAct('data')}>
              Data allowance
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setAct('reset')}>
              Reset to plan
            </Button>
          </div>
        )
      }
    >
      {!v.applies ? (
        <p className="text-sm text-muted">{v.why}</p>
      ) : (
        <>
          <p className="text-sm text-muted" data-testid="terms-kept">
            {v.recorded && v.capturedAt
              ? `Kept from their purchase on ${date(v.capturedAt)}. They get the more generous of these and what ${v.planName} gives today.`
              : `Nothing recorded yet: ${v.planName}'s current limits apply.`}
          </p>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[320px] text-sm">
              <caption className="sr-only">Their limits</caption>
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1.5 pr-3 font-medium">Limit</th>
                  <th className="py-1.5 pr-3 font-medium">Kept</th>
                  <th className="py-1.5 pr-3 font-medium">{v.planName} today</th>
                  <th className="py-1.5 font-medium">In effect</th>
                </tr>
              </thead>
              <tbody className="tnum">
                {TERM_ROWS.map((r) => {
                  const extra = moreGenerous(r.key, v.effective, v.current);
                  return (
                    <tr key={r.key} className="border-t border-line">
                      <td className="py-1.5 pr-3 text-muted">{r.label}</td>
                      <td className="py-1.5 pr-3">{v.recorded ? r.text(v.recorded) : '–'}</td>
                      <td className="py-1.5 pr-3">{r.text(v.current)}</td>
                      <td className={extra ? 'py-1.5 font-semibold' : 'py-1.5'}>
                        {r.text(v.effective)}
                        {extra && <span className="ml-1 text-xs font-normal text-muted">kept</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {v.extras.length > 0 && <p className="mt-2 text-xs text-muted">More than {v.planName} gives today: {v.extras.join('; ')}.</p>}
        </>
      )}
      {act === 'raise' && <RaiseDialog id={id} view={v} onClose={() => setAct(null)} onDone={done} />}
      {act === 'reset' && <ResetDialog id={id} view={v} onClose={() => setAct(null)} onDone={done} />}
      {act === 'data' && <DataAllowanceDialog id={id} view={v} onClose={() => setAct(null)} onDone={done} />}
    </Panel>
  );
}

function RaiseDialog({ id, view, onClose, onDone }: { id: number; view: TermsView; onClose: () => void; onDone: () => void }) {
  const e = view.effective;
  const [f, setF] = useState<RaiseForm>({ streams: '', destinations: '', maxHeight: '', maxFps: '', storageGb: '', monthlyHours: '', removeWatermark: false });
  const body = raiseBody(f, e);
  const set = (k: keyof RaiseForm) => (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: ev.target.value });
  return (
    <ReasonDialog wide title="Raise their limits" action="Raise" blocked={body == null} onClose={onClose} onSubmit={(reason) => post(`/api/admin/users/${id}/terms/raise`, { limits: body, reason }).then(onDone)}>
      <p className="text-sm text-muted">Only what you fill in changes, and only upwards. It stays through renewals and carries into an upgrade. To lower anything, reset to the plan.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={`Streams at a time (now ${e.streams})`} htmlFor="rs">
          <Input id="rs" type="number" min={e.streams + 1} max={100} value={f.streams} onChange={set('streams')} />
        </Field>
        <Field label={`Destinations each (now ${e.destinations})`} htmlFor="rd">
          <Input id="rd" type="number" min={e.destinations + 1} max={20} value={f.destinations} onChange={set('destinations')} />
        </Field>
        <Field label={`Top quality (now ${e.maxHeight}p)`} htmlFor="rh">
          <Select id="rh" value={f.maxHeight} onChange={set('maxHeight')}>
            <option value="">No change</option>
            {[1080, 1440, 2160]
              .filter((h) => h > e.maxHeight)
              .map((h) => (
                <option key={h} value={h}>
                  {h === 2160 ? '4K (2160p)' : `${h}p`}
                </option>
              ))}
          </Select>
        </Field>
        <Field label={`Frame rate (now ${e.maxFps} fps)`} htmlFor="rf">
          <Select id="rf" value={f.maxFps} onChange={set('maxFps')}>
            <option value="">No change</option>
            {e.maxFps < 60 && <option value="60">60 fps</option>}
          </Select>
        </Field>
        <Field label={`Storage in GB (now ${bytes(e.storageBytes)})`} htmlFor="rg">
          <Input id="rg" type="number" min={1} max={10000} value={f.storageGb} onChange={set('storageGb')} />
        </Field>
        <Field label={`Hours a month (now ${e.monthlyHours === 0 ? 'no limit' : e.monthlyHours})`} htmlFor="rhm" hint="0 = no limit.">
          <Input id="rhm" type="number" min={0} max={744} value={f.monthlyHours} disabled={e.monthlyHours === 0} onChange={set('monthlyHours')} />
        </Field>
      </div>
      {e.watermark && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={f.removeWatermark} onChange={(ev) => setF({ ...f, removeWatermark: ev.target.checked })} />
          Remove the watermark
        </label>
      )}
      {body && <p className="text-sm">Raising: {Object.keys(body).join(', ')}.</p>}
    </ReasonDialog>
  );
}

function ResetDialog({ id, view, onClose, onDone }: { id: number; view: TermsView; onClose: () => void; onDone: () => void }) {
  const [sure, setSure] = useState(false);
  const reduces = view.resetReduces;
  return (
    <ReasonDialog
      title={`Reset to ${view.planName}'s limits?`}
      action="Reset limits"
      danger={reduces.length > 0}
      blocked={reduces.length > 0 && !sure}
      onClose={onClose}
      onSubmit={(reason) => post(`/api/admin/users/${id}/terms/reset`, { reason, confirm: sure }).then(onDone)}
    >
      <p className="text-sm">Their recorded limits are replaced with what {view.planName} gives today. Streams on air keep running; the new limits apply to what they start or change next.</p>
      {reduces.length === 0 ? (
        <p className="text-sm text-muted">Nothing is taken away.</p>
      ) : (
        <div role="alert" className="space-y-2 rounded border border-bad/40 p-3 text-sm">
          <p className="font-medium text-bad">This takes away:</p>
          <ul className="list-disc pl-5">
            {reduces.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={sure} onChange={(e) => setSure(e.target.checked)} />
            I understand they lose these
          </label>
        </div>
      )}
    </ReasonDialog>
  );
}

function DataAllowanceDialog({ id, view, onClose, onDone }: { id: number; view: TermsView; onClose: () => void; onDone: () => void }) {
  const [mode, setMode] = useState<'set' | 'none'>(view.recorded?.trafficGb == null ? 'set' : 'none');
  const [gb, setGb] = useState(String(view.recorded?.trafficGb ?? view.current.trafficGb ?? 2000));
  const [sure, setSure] = useState(false);
  const value = mode === 'none' ? null : Number(gb);
  const invalid = mode === 'set' && (!Number.isInteger(value) || (value ?? 0) < 1 || (value ?? 0) > 1_000_000);
  const reduces = !invalid && allowanceReduces(value, view);
  return (
    <ReasonDialog
      title="Their streaming data allowance"
      action="Save"
      danger={reduces}
      blocked={invalid || (reduces && !sure)}
      onClose={onClose}
      onSubmit={(reason) => post(`/api/admin/users/${id}/terms/data-allowance`, { gb: value, reason, confirm: sure }).then(onDone)}
    >
      <p className="text-sm text-muted">
        Their own monthly allowance. They get the larger of it and {view.planName}'s ({view.current.trafficGb == null ? 'no limit' : `${view.current.trafficGb.toLocaleString('en-US')} GB`}). For a one-off top-up this month, use Grant streaming data instead.
      </p>
      <Segmented
        label="Allowance"
        value={mode}
        onChange={setMode}
        options={[
          { value: 'set', label: 'Set an allowance' },
          { value: 'none', label: 'No limit of their own' },
        ]}
      />
      {mode === 'set' && (
        <Field label="GB a month" htmlFor="tgb">
          <Input id="tgb" type="number" min={1} max={1000000} value={gb} onChange={(e) => setGb(e.target.value)} />
        </Field>
      )}
      {reduces && (
        <label role="alert" className="flex items-center gap-2 rounded border border-bad/40 p-3 text-sm">
          <input type="checkbox" checked={sure} onChange={(e) => setSure(e.target.checked)} />
          They have {view.effective.trafficGb == null ? 'no data limit' : `${view.effective.trafficGb.toLocaleString('en-US')} GB`} now: this gives them less. I understand.
        </label>
      )}
    </ReasonDialog>
  );
}

/** GET /api/admin/grandfathered (PlanTermsAdmin.Grandfathered). */
export interface Grandfathered {
  rows: { userId: number; email: string; plan: string; cycle: string; status: string; capturedAt: string | null; extras: string[] }[];
  countByPlan: Record<string, number>;
  total: number;
  truncated: boolean;
}

const PLAN_FILTERS = [
  { value: '', label: 'All' },
  { value: 'ESSENTIALS', label: 'Creator' },
  { value: 'PRO', label: 'Pro' },
  { value: 'AGENCY', label: 'Agency' },
];

/** BI-10: everyone whose recorded limits give more than their plan does today, by plan. */
export function GrandfatheredPanel() {
  const [plan, setPlan] = useState('');
  const q = useQuery({ queryKey: ['admin', 'grandfathered', plan], queryFn: () => get<Grandfathered>(`/api/admin/grandfathered${plan ? `?plan=${plan}` : ''}`) });
  return (
    <Panel title="Subscribers on older, more generous limits">
      <p className="text-sm text-muted">They keep what they bought for as long as they stay on their plan. Open one to raise or reset their limits.</p>
      <div className="mt-3">
        <Segmented
          label="Plan"
          value={plan}
          onChange={setPlan}
          options={PLAN_FILTERS.map((p) => ({ ...p, label: p.value && q.data?.countByPlan ? `${p.label} (${q.data.countByPlan[p.value] ?? 0})` : p.label }))}
        />
      </div>
      {q.isLoading ? (
        <Spinner />
      ) : !q.data ? (
        <ErrorNote error={q.error} />
      ) : q.data.rows.length === 0 ? (
        <p className="mt-3 text-sm">Nobody: everyone {plan ? 'on this plan ' : ''}has exactly what their plan gives today.</p>
      ) : (
        <>
          <ul className="mt-3 divide-y divide-line" aria-label="Subscribers on older limits">
            {q.data.rows.map((r) => (
              <li key={r.userId} className="py-2 text-sm">
                <Link to={`/studio/admin/users/${r.userId}`} className="block truncate font-medium hover:underline">
                  {r.email}
                </Link>
                <span className="text-muted">
                  {PLAN_FILTERS.find((p) => p.value === r.plan)?.label ?? r.plan} {r.cycle === 'YEARLY' ? 'yearly' : 'monthly'}
                  {r.capturedAt ? `, since ${date(r.capturedAt)}` : ''}: {r.extras.join('; ')}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-muted">
            {q.data.total} in all{q.data.truncated ? `; showing the first ${q.data.rows.length}` : ''}.
          </p>
        </>
      )}
    </Panel>
  );
}
