import clsx from 'clsx';
import { NavLink, Navigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/ui';
import { BillingSettings } from './settings/BillingSettings';
import { DataSettings } from './settings/DataSettings';
import { InviteSettings } from './settings/InviteSettings';
import { NotificationSettings } from './settings/NotificationSettings';
import { ProfileSettings } from './settings/ProfileSettings';
import { SecuritySettings } from './settings/SecuritySettings';

const SECTIONS = [
  ['profile', 'Profile'],
  ['billing', 'Plan & billing'],
  ['notifications', 'Notifications'],
  ['security', 'Sign-in & security'],
  ['invite', 'Invite creators'],
  ['data', 'Your data'],
] as const;

export function Settings() {
  const { section = 'profile' } = useParams();
  if (!SECTIONS.some(([s]) => s === section)) return <Navigate to="/studio/settings" replace />;
  return (
    <>
      <PageHeader title="Settings" />
      <nav aria-label="Settings sections" className="-mx-4 mb-6 overflow-x-auto border-b border-line px-4 md:mx-0 md:px-0">
        <ul className="flex min-w-max gap-1">
          {SECTIONS.map(([s, label]) => (
            <li key={s}>
              <NavLink
                to={s === 'profile' ? '/studio/settings' : `/studio/settings/${s}`}
                end
                className={({ isActive }) => clsx('-mb-px inline-block border-b-2 px-3 py-2.5 text-sm', isActive ? 'border-accent font-medium' : 'border-transparent text-muted hover:text-ink')}
              >
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      {section === 'profile' && <ProfileSettings />}
      {section === 'billing' && <BillingSettings />}
      {section === 'notifications' && <NotificationSettings />}
      {section === 'security' && <SecuritySettings />}
      {section === 'invite' && <InviteSettings />}
      {section === 'data' && <DataSettings />}
    </>
  );
}
