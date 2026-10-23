import { useEffect, useState } from 'react';
import { Button, Field, Input, Panel, Select, Spinner, useAction } from '@/components/ui';
import { patch } from '@/lib/api';
import { browserTimeZone, guessCurrency } from '@/lib/format';
import { keys, useInvalidate, useMe } from '@/lib/queries';

function zones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
  } catch {
    return [];
  }
}

/** ON-3: name, time zone and currency (detected, editable). */
export function ProfileSettings() {
  const { data: me } = useMe();
  const [name, setName] = useState('');
  const [tz, setTz] = useState('');
  const [currency, setCurrency] = useState<'INR' | 'USD'>('INR');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  useEffect(() => {
    if (!me) return;
    setName(me.name);
    setTz(me.timeZone ?? browserTimeZone());
    setCurrency(me.currency ?? guessCurrency());
  }, [me]);
  if (!me) return <Spinner />;
  const all = zones();
  return (
    <Panel title="Profile">
      <form
        className="max-w-md space-y-5"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await run(() => patch('/api/me', { name, timeZone: tz, currency }), 'Profile saved.')) invalidate([...keys.me]);
        }}
      >
        <Field label="Email">
          <Input value={me.email} disabled />
        </Field>
        <Field label="Name" htmlFor="p-name">
          <Input id="p-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Time zone" htmlFor="p-tz" hint="New streams and schedules start in this time zone.">
          {all.length ? (
            <Select id="p-tz" value={tz} onChange={(e) => setTz(e.target.value)}>
              {all.map((z) => (
                <option key={z} value={z}>
                  {z.replace(/_/g, ' ')}
                </option>
              ))}
            </Select>
          ) : (
            <Input id="p-tz" value={tz} onChange={(e) => setTz(e.target.value)} />
          )}
        </Field>
        <Field label="Currency" htmlFor="p-cur" hint="Prices are shown in this currency.">
          <Select id="p-cur" value={currency} onChange={(e) => setCurrency(e.target.value as 'INR' | 'USD')}>
            <option value="INR">Indian rupee (₹)</option>
            <option value="USD">US dollar ($)</option>
          </Select>
        </Field>
        <Button type="submit" variant="primary" busy={busy}>
          Save profile
        </Button>
      </form>
    </Panel>
  );
}
