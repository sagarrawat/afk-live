import { useEffect, useState } from 'react';
import { Panel, Spinner, Toggle, useAction } from '@/components/ui';
import { put } from '@/lib/api';
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
    </Panel>
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
