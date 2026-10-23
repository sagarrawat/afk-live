import clsx from 'clsx';
import { Logo } from './Logo';
import { Activity, Bell, CalendarDays, FolderOpen, HelpCircle, Home, LifeBuoy, LogOut, Moon, Radio, Settings, Shield, Sun, Wrench } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { csrfToken, post } from '@/lib/api';
import { relative } from '@/lib/format';
import { appLink } from '@/lib/links';
import { keys, useInvalidate, useMe, useNotifications } from '@/lib/queries';

const nav = [
  { to: '/studio', label: 'Streams', icon: Home, end: true },
  { to: '/studio/schedule', label: 'Schedule', icon: CalendarDays },
  { to: '/studio/library', label: 'Library', icon: FolderOpen },
  { to: '/studio/destinations', label: 'Destinations', icon: Radio },
  { to: '/studio/settings', label: 'Settings', icon: Settings },
];

export function useTheme(): [string, () => void] {
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem('afk-theme') ?? 'dark';
    } catch {
      return 'dark';
    }
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem('afk-theme', theme);
    } catch {
      /* ignore */
    }
  }, [theme]);
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))];
}

function Wordmark() {
  return (
    <Link to="/studio" className="-m-1 flex items-center p-1 text-ink">
      <Logo className="h-7 w-auto" />
    </Link>
  );
}

function signOut() {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/logout';
  const token = csrfToken();
  if (token) {
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = '_csrf';
    input.value = token;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
}

function Notifications() {
  const { data } = useNotifications();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const invalidate = useInvalidate();
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const unread = data?.unread ?? 0;
  return (
    <div className="relative" ref={ref}>
      <button
        aria-label={unread ? `Notifications, ${unread} unread` : 'Notifications'}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="relative inline-flex h-9 w-9 items-center justify-center rounded text-muted hover:bg-raised hover:text-ink"
      >
        <Bell className="h-[18px] w-[18px]" />
        {unread > 0 && <span className="tnum absolute right-1 top-1 min-w-[16px] rounded-full bg-accent px-1 text-[10px] font-semibold leading-4 text-accent-ink">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <div className="absolute right-0 z-40 mt-2 w-[min(22rem,calc(100vw-2rem))] overflow-hidden rounded-md border border-line bg-panel shadow-xl">
          <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
            <span className="font-semibold">Notifications</span>
            {unread > 0 && (
              <button
                className="text-sm text-accent hover:underline"
                onClick={async () => {
                  await post('/api/notifications/read-all');
                  invalidate([...keys.notifications]);
                }}
              >
                Mark all read
              </button>
            )}
          </div>
          <ul className="max-h-96 overflow-y-auto">
            {(data?.items ?? []).length === 0 && <li className="px-4 py-6 text-center text-muted">Nothing yet. Alerts about your streams show up here.</li>}
            {(data?.items ?? []).map((n) => (
              <li key={n.id}>
                <button
                  className={clsx('block w-full border-b border-line/60 px-4 py-3 text-left hover:bg-raised', !n.readAt && 'bg-accent/5')}
                  onClick={async () => {
                    if (!n.readAt) {
                      await post(`/api/notifications/${n.id}/read`);
                      invalidate([...keys.notifications]);
                    }
                    const to = appLink(n.link, n.streamId);
                    setOpen(false);
                    if (to) to.startsWith('http') ? (window.location.href = to) : navigate(to);
                  }}
                >
                  <span className="flex items-start gap-2">
                    <span
                      aria-hidden
                      className={clsx(
                        'mt-1.5 h-2 w-2 shrink-0 rounded-full',
                        n.level === 'ERROR' ? 'bg-bad' : n.level === 'WARNING' ? 'bg-warn' : n.level === 'SUCCESS' ? 'bg-ok' : 'bg-accent',
                      )}
                    />
                    <span className="min-w-0">
                      <span className="block font-medium">{n.title}</span>
                      {n.body && <span className="mt-0.5 block text-sm text-muted">{n.body}</span>}
                      <span className="mt-1 block text-xs text-muted">{relative(n.createdAt)}</span>
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function UserMenu() {
  const { data: me } = useMe();
  const [theme, toggle] = useTheme();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const initial = (me?.name ?? me?.email ?? '?').trim().charAt(0).toUpperCase();
  return (
    <div className="relative" ref={ref}>
      <button aria-label="Account menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} className="flex h-9 items-center gap-2 rounded px-1.5 hover:bg-raised">
        {me?.picture ? (
          <img src={me.picture} alt="" className="h-7 w-7 rounded-full" referrerPolicy="no-referrer" />
        ) : (
          <span className="grid h-7 w-7 place-items-center rounded-full bg-raised text-sm font-semibold">{initial}</span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 z-40 mt-2 w-60 overflow-hidden rounded-md border border-line bg-panel py-1 shadow-xl">
          <div className="border-b border-line px-4 py-2.5">
            <div className="truncate font-medium">{me?.name}</div>
            <div className="truncate text-sm text-muted">{me?.email}</div>
            {me && <div className="mt-1 text-xs text-muted">{me.plan.name} plan</div>}
          </div>
          <MenuItem onClick={toggle} icon={theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}>
            {theme === 'dark' ? 'Light theme' : 'Dark theme'}
          </MenuItem>
          <MenuItem href="/classic" icon={<Wrench className="h-4 w-4" />}>
            YouTube upload tools
          </MenuItem>
          <MenuItem href="/studio/help" icon={<HelpCircle className="h-4 w-4" />}>
            Help
          </MenuItem>
          <MenuItem href="mailto:support@afklive.in" icon={<LifeBuoy className="h-4 w-4" />}>
            Contact support
          </MenuItem>
          <MenuItem href="/status" icon={<Activity className="h-4 w-4" />}>
            Service status
          </MenuItem>
          <MenuItem onClick={signOut} icon={<LogOut className="h-4 w-4" />}>
            Sign out
          </MenuItem>
        </div>
      )}
    </div>
  );
}

function MenuItem({ children, icon, onClick, href }: { children: ReactNode; icon: ReactNode; onClick?: () => void; href?: string }) {
  const cls = 'flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-raised';
  return href ? (
    <a href={href} className={cls}>
      {icon}
      {children}
    </a>
  ) : (
    <button onClick={onClick} className={cls}>
      {icon}
      {children}
    </button>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const location = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [location.pathname]);
  const items = me?.admin ? [...nav, { to: '/studio/admin', label: 'Admin', icon: Shield }] : nav;
  return (
    <div className="flex min-h-full">
      <aside className="sticky top-0 hidden h-screen w-56 shrink-0 flex-col border-r border-line bg-panel px-3 py-4 md:flex">
        <div className="px-2">
          <Wordmark />
        </div>
        <nav aria-label="Main" className="mt-8 flex flex-col gap-0.5">
          {items.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) => clsx('flex items-center gap-3 rounded px-2.5 py-2 text-sm', isActive ? 'bg-raised font-medium text-ink' : 'text-muted hover:bg-raised hover:text-ink')}
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
        {me && (
          <Link to="/studio/settings/billing" className="mt-auto block rounded border border-line px-3 py-2.5 text-sm hover:border-muted/60">
            <span className="block font-medium">{me.plan.name} plan</span>
            <span className="text-xs text-muted">
              {me.plan.streams} stream{me.plan.streams === 1 ? '' : 's'} at once, up to {me.plan.maxHeight}p
            </span>
          </Link>
        )}
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center justify-between gap-3 border-b border-line bg-bg/90 px-4 backdrop-blur md:justify-end md:px-8">
          <div className="md:hidden">
            <Wordmark />
          </div>
          <div className="flex items-center gap-1">
            <Notifications />
            <UserMenu />
          </div>
        </header>
        <main className="mx-auto w-full min-w-0 max-w-6xl flex-1 px-4 pb-24 pt-6 md:px-8 md:pb-12">{children}</main>
      </div>
      <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-30 flex border-t border-line bg-panel pb-[env(safe-area-inset-bottom)] md:hidden">
        {items.slice(0, 5).map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => clsx('flex flex-1 flex-col items-center gap-0.5 py-2 text-[11px]', isActive ? 'text-ink' : 'text-muted')}>
            <Icon className="h-5 w-5" aria-hidden />
            {label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
