import { useEffect, useState } from 'react';
import { Panel, Spinner, Toggle, useAction } from '@/components/ui';
import { get, put } from '@/lib/api';
import { currentSubscription, disablePush, enablePush, pushInfo, pushSupported, type PushInfo } from '@/lib/push';
import { keys, useInvalidate, useNotifications } from '@/lib/queries';

/** NT-1: alerts always show in the app; email and push on this device are optional. */
export function NotificationSettings() {
  const { data } = useNotifications();
  const { run } = useAction();
  const invalidate = useInvalidate();
  if (!data) return <Spinner />;
  return (
    <Panel title="Alerts">
      <div className="max-w-xl space-y-6">
        <div className="flex items-start justify-between gap-6">
          <span>
            <span className="block font-medium">Email me when something needs attention</span>
            <span className="text-sm text-muted">A stream goes down for more than a minute, a schedule fails to start, storage is nearly full, or a payment fails. One email per problem, not one per minute.</span>
          </span>
          <Toggle
            label="Email alerts"
            checked={data.emailAlerts}
            onChange={async (v) => {
              if (await run(() => put('/api/notifications/settings', { emailAlerts: v }), v ? 'Email alerts on.' : 'Email alerts off.')) invalidate([...keys.notifications], [...keys.me]);
            }}
          />
        </div>
        <PushSetting />
      </div>
      <AlertKinds emailOn={data.emailAlerts} />
    </Panel>
  );
}

interface Preference {
  id: string;
  label: string;
  description: string;
  email: boolean;
  push: boolean;
}

/** NT-3: per kind of alert, by email and on devices. In the app they always show. */
export function AlertKinds({ emailOn }: { emailOn: boolean }) {
  const [prefs, setPrefs] = useState<Preference[] | null>(null);
  const [pushAvailable, setPushAvailable] = useState(false);
  const { run } = useAction();
  useEffect(() => {
    get<Preference[]>('/api/notifications/preferences').then(setPrefs).catch(() => {});
    pushInfo().then((i) => setPushAvailable(i.available)).catch(() => {});
  }, []);
  if (!prefs) return null;
  const save = (id: string, channel: 'email' | 'push', value: boolean) => {
    const p = prefs.find((x) => x.id === id)!;
    const next = { email: p.email, push: p.push, [channel]: value };
    run(async () => setPrefs(await put<Preference[]>('/api/notifications/preferences', { [id]: next })), 'Saved.');
  };
  return (
    <div className="mt-8 border-t border-line pt-6">
      <h3 className="font-medium">Which alerts</h3>
      <p className="text-sm text-muted">Every alert shows here in the app. Choose which also reach you by email{pushAvailable ? ' and on your devices' : ''}.</p>
      <table className="mt-4 w-full max-w-2xl text-sm">
        <thead>
          <tr className="text-left text-muted">
            <th className="py-2 font-normal">Alert</th>
            <th className="w-16 py-2 text-center font-normal">Email</th>
            {pushAvailable && <th className="w-16 py-2 text-center font-normal">Devices</th>}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {prefs.map((p) => (
            <tr key={p.id}>
              <td className="py-3 pr-4">
                <span className="block font-medium">{p.label}</span>
                <span className="block text-muted">{p.description}</span>
              </td>
              <td className="text-center">
                <input type="checkbox" className="h-4 w-4 accent-[rgb(var(--accent))]" aria-label={`Email: ${p.label}`} checked={p.email} disabled={!emailOn} onChange={(e) => save(p.id, 'email', e.target.checked)} />
              </td>
              {pushAvailable && (
                <td className="text-center">
                  <input type="checkbox" className="h-4 w-4 accent-[rgb(var(--accent))]" aria-label={`Devices: ${p.label}`} checked={p.push} onChange={(e) => save(p.id, 'push', e.target.checked)} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {!emailOn && <p className="mt-2 text-sm text-muted">Email alerts are off above, so none go by email.</p>}
    </div>
  );
}

/** MB-2: the same alerts as notifications on this phone or computer. */
export function PushSetting() {
  const [info, setInfo] = useState<PushInfo | null>(null);
  const [on, setOn] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const { busy, run } = useAction();
  const supported = pushSupported();

  useEffect(() => {
    let live = true;
    pushInfo().then((i) => live && setInfo(i)).catch(() => {});
    currentSubscription().then((s) => live && setOn(Boolean(s))).catch(() => {});
    if (supported) setBlocked(Notification.permission === 'denied');
    return () => {
      live = false;
    };
  }, [supported]);

  if (!info?.available) return null;
  const note = !supported
    ? "This browser can't show alerts. On iPhone, add AFK Live to your Home Screen first (Share, then Add to Home Screen) and open it from there."
    : blocked
      ? 'Notifications are blocked for AFK Live in this browser. Allow them in the site settings, then try again.'
      : `Shows an alert on this device too, even when AFK Live isn't open.${info.devices > 0 ? ` On for ${info.devices} device${info.devices === 1 ? '' : 's'}.` : ''}`;
  return (
    <div className="flex items-start justify-between gap-6">
      <span>
        <span className="block font-medium">Alerts on this device</span>
        <span className="text-sm text-muted">{note}</span>
      </span>
      <Toggle
        label="Alerts on this device"
        checked={on}
        disabled={!supported || blocked || busy}
        onChange={async (v) => {
          let granted = true;
          const ok = await run(async () => {
            if (v) granted = await enablePush(info.publicKey!);
            else await disablePush();
          }, v ? 'Alerts on for this device.' : 'Alerts off for this device.');
          if (ok && granted) setOn(v);
          if (!granted) setBlocked(Notification.permission === 'denied');
          pushInfo().then(setInfo).catch(() => {});
        }}
      />
    </div>
  );
}
