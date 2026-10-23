import { Panel, Spinner, Toggle, useAction } from '@/components/ui';
import { put } from '@/lib/api';
import { keys, useInvalidate, useNotifications } from '@/lib/queries';

/** NT-1: alerts always show in the app; email is optional. */
export function NotificationSettings() {
  const { data } = useNotifications();
  const { run } = useAction();
  const invalidate = useInvalidate();
  if (!data) return <Spinner />;
  return (
    <Panel title="Alerts">
      <div className="flex max-w-xl items-start justify-between gap-6">
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
    </Panel>
  );
}
