import clsx from 'clsx';
import { Check, Download } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Badge, Button, Dialog, ErrorNote, Field, Input, Panel, Segmented, Spinner, useAction, useToast } from '@/components/ui';
import { get, post, put } from '@/lib/api';
import { bytes, date, money } from '@/lib/format';
import { keys, useBilling, useBillingProfile, useInvalidate, useMe, usePlans } from '@/lib/queries';
import type { BillingOverview, PlanView, Quote } from '@/lib/types';

/** BI-1..BI-6: plan, usage against it, next charge, invoices, and changing plan. */
export function BillingSettings() {
  const { data: billing, isLoading, error } = useBilling();
  const { data: me } = useMe();
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const invalidate = useInvalidate();

  // Back from the payment page: confirm the order with the provider.
  useEffect(() => {
    const order = params.get('payment_order');
    if (!order) return;
    setParams({}, { replace: true });
    get<{ status: string }>(`/api/payment/status?orderId=${encodeURIComponent(order)}`)
      .then((r) => {
        if (r.status === 'COMPLETED') toast('Payment received. Your plan is active.', 'ok');
        else if (r.status === 'FAILED') toast('The payment didn’t go through. No money was taken.', 'bad');
        else toast('Your payment is still processing. We’ll update your plan as soon as it’s confirmed.');
        invalidate([...keys.billing], [...keys.me]);
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (isLoading || !me) return <Spinner />;
  if (error || !billing) return <ErrorNote error={error} />;
  return (
    <div className="space-y-6">
      <CurrentPlan billing={billing} />
      <Plans billing={billing} currency={me.currency ?? 'INR'} />
      <Invoices billing={billing} />
      <BillingProfileForm />
    </div>
  );
}

function Meter({ label, used, max, format, neutral }: { label: string; used: number; max: number; format: (n: number) => string; neutral?: boolean }) {
  const unlimited = max <= 0;
  const pct = unlimited ? 0 : Math.min(100, (used / max) * 100);
  return (
    <div>
      <div className="flex justify-between text-sm">
        <span>{label}</span>
        <span className="tnum text-muted">
          {format(used)} {unlimited ? '(unlimited)' : `of ${format(max)}`}
        </span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-raised">
        <div className={clsx('h-full', neutral ? 'bg-accent' : pct >= 100 ? 'bg-bad' : pct >= 80 ? 'bg-warn' : 'bg-accent')} style={{ width: `${unlimited ? 0 : pct}%` }} />
      </div>
    </div>
  );
}

function CurrentPlan({ billing: b }: { billing: BillingOverview }) {
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const paid = b.plan !== 'FREE';
  return (
    <Panel title="Your plan">
      <div className="grid gap-6 md:grid-cols-2">
        <div>
          <p className="flex flex-wrap items-center gap-2 text-lg font-semibold">
            {b.planName}
            {b.status === 'PAST_DUE' && <Badge tone="warn">Payment due</Badge>}
            {b.cancelAtPeriodEnd && <Badge>Ends {date(b.periodEnd)}</Badge>}
          </p>
          {paid && b.periodEnd && (
            <p className="mt-1 text-muted">
              {b.cycle === 'YEARLY' ? 'Yearly' : 'Monthly'}, current period ends {date(b.periodEnd)}.
            </p>
          )}
          {b.pendingPlan && <p className="mt-1 text-muted">Changes to {b.pendingPlan.charAt(0) + b.pendingPlan.slice(1).toLowerCase()} at the end of the period.</p>}
          {b.nextCharge && (
            <p className="mt-3">
              Next payment <span className="tnum font-medium">{money(b.nextCharge.amount, b.nextCharge.currency)}</span> on {date(b.nextCharge.date)}.{' '}
              <span className="text-muted">We'll remind you 3 days before; renewal is a one-tap UPI or card payment.</span>
            </p>
          )}
          {b.unpaidBalance > 0 && <p className="mt-3 text-warn">Unpaid balance from earlier usage: ₹{b.unpaidBalance.toFixed(2)}.</p>}
          {paid && (
            <div className="mt-4 flex gap-2">
              {b.cancelAtPeriodEnd || b.pendingPlan ? (
                <Button size="sm" busy={busy} onClick={() => run(() => post('/api/billing/resume'), 'Your plan will renew as usual.').then(() => invalidate([...keys.billing]))}>
                  Keep my plan
                </Button>
              ) : (
                <Button size="sm" variant="ghost" onClick={() => setConfirmCancel(true)}>
                  Cancel plan
                </Button>
              )}
            </div>
          )}
        </div>
        <div className="space-y-4">
          <Meter label="Streams live now" neutral used={b.usage.streamsLive} max={b.usage.streamsMax} format={(n) => String(n)} />
          <Meter label="Streaming this month" used={b.usage.hoursUsed} max={b.usage.hoursIncluded} format={(n) => `${n.toFixed(n < 10 ? 1 : 0)} h`} />
          <Meter label="Storage" used={b.usage.storageUsed} max={b.usage.storageMax} format={bytes} />
        </div>
      </div>
      <Dialog
        open={confirmCancel}
        onClose={() => setConfirmCancel(false)}
        title="Cancel your plan?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmCancel(false)}>
              Keep my plan
            </Button>
            <Button
              variant="danger"
              busy={busy}
              onClick={async () => {
                if (await run(() => post('/api/billing/cancel'), 'Your plan ends at the end of this period.')) {
                  invalidate([...keys.billing]);
                  setConfirmCancel(false);
                }
              }}
            >
              Cancel at period end
            </Button>
          </>
        }
      >
        <p>You keep {b.planName} until {date(b.periodEnd)}, then move to Free: 720p, 10 hours a month, one stream. Nothing is deleted, but uploads stop if you're over 2 GB.</p>
      </Dialog>
    </Panel>
  );
}

function Plans({ billing: b, currency: initial }: { billing: BillingOverview; currency: 'INR' | 'USD' }) {
  const [cycle, setCycle] = useState<'MONTHLY' | 'YEARLY'>(b.cycle ?? 'MONTHLY');
  const [currency, setCurrency] = useState<'INR' | 'USD'>(initial);
  const { data: plans } = usePlans(currency);
  const [quote, setQuote] = useState<{ plan: PlanView; quote?: Quote; downgrade?: boolean } | null>(null);
  const { busy, run } = useAction();
  const toast = useToast();
  const invalidate = useInvalidate();
  const rank = (id: string) => ['FREE', 'ESSENTIALS', 'PRO', 'AGENCY'].indexOf(id);

  const choose = async (p: PlanView) => {
    if (rank(p.id) < rank(b.plan) || (p.id === b.plan && cycle === 'MONTHLY' && b.cycle === 'YEARLY')) {
      setQuote({ plan: p, downgrade: true });
      return;
    }
    try {
      const q = await get<Quote>(`/api/billing/quote?plan=${p.id}&cycle=${cycle}`);
      setQuote({ plan: p, quote: q });
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'bad');
    }
  };

  return (
    <Panel
      title="Plans"
      action={
        <div className="flex flex-wrap gap-2">
          <Segmented label="Currency" value={currency} onChange={setCurrency} options={[{ value: 'INR', label: '₹' }, { value: 'USD', label: '$' }]} />
          <Segmented label="Billing cycle" value={cycle} onChange={setCycle} options={[{ value: 'MONTHLY', label: 'Monthly' }, { value: 'YEARLY', label: 'Yearly, 2 months free' }]} />
        </div>
      }
    >
      <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {(plans ?? []).map((p) => {
          const current = p.id === b.plan;
          const price = cycle === 'YEARLY' ? p.yearlyLabel : p.monthlyLabel;
          return (
            <li key={p.id} className={clsx('flex flex-col rounded-md border p-4', current ? 'border-accent' : 'border-line')}>
              <p className="flex items-center justify-between font-semibold">
                {p.name}
                {current && <Badge tone="accent">Current</Badge>}
              </p>
              <p className="mt-2">
                <span className="tnum text-2xl font-semibold">{price}</span>
                {p.monthly > 0 && <span className="text-muted"> / {cycle === 'YEARLY' ? 'year' : 'month'}</span>}
              </p>
              <ul className="mt-4 flex-1 space-y-1.5 text-sm">
                {[
                  `${p.concurrentStreams} stream${p.concurrentStreams === 1 ? '' : 's'} at once`,
                  `Up to ${p.maxHeight === 2160 ? '4K' : `${p.maxHeight}p`} at ${p.maxFps} fps`,
                  p.monthlyHours > 0 ? `${p.monthlyHours} hours a month` : 'Unlimited hours, 24/7',
                  `${p.destinationsPerStream} destination${p.destinationsPerStream === 1 ? '' : 's'} per stream`,
                  `${bytes(p.storageBytes)} storage`,
                  p.watermark ? 'Small AFK Live mark on stream' : 'No watermark',
                ].map((f) => (
                  <li key={f} className="flex gap-2">
                    <Check className="mt-0.5 h-4 w-4 shrink-0 text-muted" aria-hidden />
                    {f}
                  </li>
                ))}
              </ul>
              {p.id !== 'FREE' && !(current && (b.cycle ?? 'MONTHLY') === cycle && b.status === 'ACTIVE') && (
                <Button className="mt-4" variant={rank(p.id) > rank(b.plan) ? 'primary' : 'secondary'} onClick={() => choose(p)}>
                  {current ? (b.status === 'PAST_DUE' ? 'Pay now' : 'Switch cycle') : rank(p.id) > rank(b.plan) ? `Upgrade to ${p.name}` : `Move to ${p.name}`}
                </Button>
              )}
              {current && b.status === 'ACTIVE' && (b.cycle ?? 'MONTHLY') === cycle && p.id !== 'FREE' && b.periodEnd && new Date(b.periodEnd).getTime() - Date.now() < 7 * 86400_000 && (
                <Button className="mt-4" variant="primary" onClick={() => choose(p)}>
                  Renew now
                </Button>
              )}
            </li>
          );
        })}
      </ul>
      {currency === 'USD' && <p className="mt-4 text-sm text-muted">Card payments in US dollars are coming soon. For now, checkout charges the rupee price.</p>}
      <p className="mt-2 text-sm text-muted">Prices include GST. Every payment comes with a GST invoice.</p>

      <Dialog
        open={!!quote}
        onClose={() => setQuote(null)}
        title={quote?.downgrade ? `Move to ${quote.plan.name}?` : quote?.quote?.kind === 'RENEW' ? `Renew ${quote.plan.name}` : `Upgrade to ${quote?.plan.name}`}
        footer={
          quote && (
            <>
              <Button variant="ghost" onClick={() => setQuote(null)}>
                Not now
              </Button>
              {quote.downgrade ? (
                <Button
                  variant="primary"
                  busy={busy}
                  onClick={async () => {
                    if (await run(() => post('/api/billing/downgrade', { plan: quote.plan.id, cycle }), 'Change scheduled for the end of this period.')) {
                      invalidate([...keys.billing]);
                      setQuote(null);
                    }
                  }}
                >
                  Change at period end
                </Button>
              ) : (
                <Button
                  variant="primary"
                  busy={busy}
                  onClick={() =>
                    run(async () => {
                      const c = await post<{ checkoutUrl: string }>('/api/billing/checkout', { plan: quote.plan.id, cycle });
                      window.location.href = c.checkoutUrl;
                    })
                  }
                >
                  Pay {quote.quote ? money(quote.quote.amount, quote.quote.currency) : ''}
                </Button>
              )}
            </>
          )
        }
      >
        {quote?.downgrade && <p>You keep {b.planName} until {date(b.periodEnd)}, then move to {quote.plan.name}. Nothing is charged now.</p>}
        {quote?.quote && (
          <dl className="space-y-2">
            <Row label={`${quote.plan.name}, ${cycle === 'YEARLY' ? 'one year' : 'one month'}`} value={money(quote.quote.price, quote.quote.currency)} />
            {quote.quote.credit > 0 && <Row label="Credit for unused time on your current plan" value={`− ${money(quote.quote.credit, quote.quote.currency)}`} />}
            <Row label="Pay now" value={money(quote.quote.amount, quote.quote.currency)} strong />
            <p className="pt-2 text-sm text-muted">
              {quote.quote.kind === 'RENEW' ? 'Your next period' : 'Your new plan starts now and'} runs until {date(quote.quote.periodEnd)}. Pay by UPI or card on the next page.
            </p>
          </dl>
        )}
      </Dialog>
    </Panel>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={clsx('flex justify-between gap-4', strong && 'border-t border-line pt-2 font-semibold')}>
      <dt>{label}</dt>
      <dd className="tnum">{value}</dd>
    </div>
  );
}

function Invoices({ billing: b }: { billing: BillingOverview }) {
  return (
    <Panel title="Invoices" pad={false}>
      {b.invoices.length === 0 ? (
        <p className="p-4 text-muted">Invoices appear here after your first payment.</p>
      ) : (
        <ul className="divide-y divide-line">
          {b.invoices.map((i) => (
            <li key={i.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <span className="min-w-0 flex-1">
                <span className="block font-medium">{i.number}</span>
                <span className="block truncate text-sm text-muted">
                  {date(i.issuedAt)}, {i.description}
                </span>
              </span>
              <span className="tnum">{money(i.total, i.currency)}</span>
              <a href={`/api/billing/invoices/${i.id}.pdf`} className="inline-flex items-center gap-1.5 text-sm text-accent hover:underline">
                <Download className="h-4 w-4" aria-hidden />
                PDF
              </a>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function BillingProfileForm() {
  const { data } = useBillingProfile();
  const [form, setForm] = useState({ legalName: '', addressLine: '', city: '', postalCode: '', stateCode: '', gstin: '' });
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  useEffect(() => {
    if (data) setForm({ legalName: data.legalName ?? '', addressLine: data.addressLine ?? '', city: data.city ?? '', postalCode: data.postalCode ?? '', stateCode: data.stateCode ?? '', gstin: data.gstin ?? '' });
  }, [data]);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [k]: e.target.value }));
  return (
    <Panel title="Invoice details">
      <form
        className="grid max-w-2xl gap-4 sm:grid-cols-2"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await run(() => put('/api/billing/profile', form), 'Invoice details saved.')) invalidate([...keys.profile]);
        }}
      >
        <p className="text-sm text-muted sm:col-span-2">Used on your next invoices. Add a GSTIN to claim input tax credit.</p>
        <Field label="Name on invoice" htmlFor="bp-name">
          <Input id="bp-name" value={form.legalName} onChange={set('legalName')} />
        </Field>
        <Field label="GSTIN (optional)" htmlFor="bp-gstin">
          <Input id="bp-gstin" value={form.gstin} onChange={set('gstin')} placeholder="29ABCDE1234F1Z5" />
        </Field>
        <Field label="Address" htmlFor="bp-addr">
          <Input id="bp-addr" value={form.addressLine} onChange={set('addressLine')} />
        </Field>
        <Field label="City" htmlFor="bp-city">
          <Input id="bp-city" value={form.city} onChange={set('city')} />
        </Field>
        <Field label="PIN code" htmlFor="bp-pin">
          <Input id="bp-pin" value={form.postalCode} onChange={set('postalCode')} />
        </Field>
        <Field label="State code" htmlFor="bp-state" hint="Two digits, e.g. 27 for Maharashtra. Taken from your GSTIN if you add one.">
          <Input id="bp-state" value={form.stateCode} onChange={set('stateCode')} maxLength={2} />
        </Field>
        <div className="sm:col-span-2">
          <Button type="submit" variant="primary" busy={busy}>
            Save invoice details
          </Button>
        </div>
      </form>
    </Panel>
  );
}
