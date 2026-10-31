import clsx from 'clsx';
import { ArrowLeft, Banknote, BarChart3, Gauge, LifeBuoy, Moon, Radio, ScrollText, Search, Server, Shield, Sun, TrendingUp, Users, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { Logo } from '@/components/Logo';
import { useTheme } from '@/components/Shell';
import { Spinner } from '@/components/ui';
import { useMe } from '@/lib/queries';
import { Audit, FunnelPanel, Plans, RevenuePanel, TicketPage, Tickets, UserPage } from '../Admin';
import { AdminSearch } from './AdminSearch';
import { BusinessPage } from './BusinessPage';
import { CostPanel, WorstCasePanel } from './CostPanel';
import { AiSpendPanel } from './AiSpendPanel';
import { LivePage } from './LivePage';
import { OverviewPage, useAttention } from './OverviewPage';
import { ServersPage } from './ServersPage';
import { RunPage } from './Support';
import { UsersPage } from './UsersPage';

export const ADMIN_NAV = [
  { to: 'overview', label: 'Overview', icon: Gauge },
  { to: 'live', label: 'Live', icon: Radio },
  { to: 'users', label: 'Users', icon: Users },
  { to: 'support', label: 'Support', icon: LifeBuoy },
  { to: 'billing', label: 'Billing', icon: Banknote },
  { to: 'business', label: 'Business', icon: BarChart3 },
  { to: 'servers', label: 'Servers', icon: Server },
  { to: 'growth', label: 'Growth', icon: TrendingUp },
  { to: 'audit', label: 'Audit', icon: ScrollText },
] as const;

/** Which nav item a section belongs to (a run belongs to Live, a ticket to Support). */
function navFor(section: string): string {
  if (section === 'runs') return 'live';
  return section;
}

/** Red count of problems on the Overview item, so the nav says when something's wrong. */
function AttentionCount() {
  const o = useAttention();
  const items = o.data?.attention ?? [];
  if (items.length === 0) return null;
  const red = items.some((a) => a.level === 'red');
  return (
    <span className={clsx('tnum ml-auto rounded-full px-1.5 text-[11px] font-semibold leading-5', red ? 'bg-bad text-white' : 'bg-warn/20 text-warn')} aria-label={`${items.length} need attention`}>
      {items.length}
    </span>
  );
}

function Nav({ current, phone }: { current: string; phone?: boolean }) {
  return (
    <nav aria-label="Admin" className={phone ? 'flex gap-1 overflow-x-auto border-b border-line px-3 md:hidden' : 'flex flex-col gap-0.5'}>
      {ADMIN_NAV.map(({ to, label, icon: Icon }) => {
        const active = navFor(current) === to;
        return (
          <NavLink
            key={to}
            to={`/studio/admin/${to}`}
            aria-current={active ? 'page' : undefined}
            className={clsx(
              phone
                ? clsx('-mb-px flex shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-2.5 py-2.5 text-sm', active ? 'border-accent font-medium text-ink' : 'border-transparent text-muted')
                : clsx('flex items-center gap-2.5 rounded px-2.5 py-1.5 text-sm', active ? 'bg-raised font-medium text-ink' : 'text-muted hover:bg-raised hover:text-ink'),
            )}
          >
            <Icon className="h-4 w-4 shrink-0" aria-hidden />
            {label}
            {to === 'overview' && <AttentionCount />}
          </NavLink>
        );
      })}
    </nav>
  );
}

function Section() {
  const { section = 'overview', id } = useParams();
  const n = id == null ? null : Number(id);
  switch (section) {
    case 'overview':
      return <OverviewPage />;
    case 'live':
      return <LivePage />;
    case 'runs':
      return n ? <RunPage id={n} /> : <Navigate to="/studio/admin/live" replace />;
    case 'users':
      return n ? <UserPage id={n} /> : <UsersPage />;
    case 'support':
      return n ? <TicketPage id={n} /> : (
        <div className="space-y-4">
          <h1 className="font-display text-xl font-bold">Support</h1>
          <Tickets />
        </div>
      );
    case 'billing':
      return (
        <div className="space-y-6">
          <h1 className="font-display text-xl font-bold">Billing</h1>
          <RevenuePanel />
          <CostPanel />
          <WorstCasePanel />
          <AiSpendPanel />
          <Plans />
        </div>
      );
    case 'business':
      return <BusinessPage />;
    case 'servers':
      return <ServersPage />;
    case 'growth':
      return (
        <div className="space-y-6">
          <h1 className="font-display text-xl font-bold">Growth</h1>
          <FunnelPanel />
        </div>
      );
    case 'audit':
      return (
        <div className="space-y-4">
          <h1 className="font-display text-xl font-bold">Audit</h1>
          <p className="text-sm text-muted">Every admin action, with who did it and why.</p>
          <Audit />
        </div>
      );
    default:
      return <Navigate to="/studio/admin/overview" replace />;
  }
}

/**
 * The admin console: its own layout, separate from the creator app (no creator nav or plan card), full
 * width with dense tables, a clear Admin marker and a way back. Old tab addresses redirect.
 */
export function AdminConsole() {
  const { data: me, isLoading } = useMe();
  const location = useLocation();
  const [theme, toggleTheme] = useTheme();
  const [searching, setSearching] = useState(false);
  const section = location.pathname.replace(/^\/studio\/admin\/?/, '').split('/')[0] || 'overview';
  useEffect(() => {
    window.scrollTo(0, 0);
    setSearching(false);
  }, [location.pathname]);
  useEffect(() => {
    const prev = document.title;
    document.title = 'AFK Live admin';
    return () => {
      document.title = prev;
    };
  }, []);
  if (isLoading) return <Spinner />;
  if (!me || !me.admin) return <Navigate to="/studio" replace />;
  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-30 border-b border-line border-t-2 border-t-accent bg-bg/95 backdrop-blur">
        <div className="flex h-14 items-center gap-3 px-3 md:px-5">
          <Link to="/studio/admin/overview" className="flex shrink-0 items-center gap-2.5" aria-label="Admin console home">
            <Logo className="h-6 w-auto" />
          </Link>
          <span className="inline-flex shrink-0 items-center gap-1 rounded-sm border border-accent/60 px-1.5 py-0.5 text-xs font-semibold text-accent">
            <Shield className="h-3.5 w-3.5" aria-hidden />
            Admin
          </span>
          <AdminSearch className="ml-2 hidden max-w-md flex-1 md:block" />
          <span className="flex-1 md:hidden" />
          <button
            type="button"
            aria-label="Search"
            onClick={() => setSearching(true)}
            className="inline-flex h-9 w-9 items-center justify-center rounded text-muted hover:bg-raised hover:text-ink md:hidden"
          >
            <Search className="h-[18px] w-[18px]" />
          </button>
          <button
            type="button"
            aria-label={theme === 'dark' ? 'Light theme' : 'Dark theme'}
            onClick={toggleTheme}
            className="hidden h-9 w-9 items-center justify-center rounded text-muted hover:bg-raised hover:text-ink sm:inline-flex md:ml-auto"
          >
            {theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
          </button>
          <Link to="/studio" className="inline-flex shrink-0 items-center gap-1.5 rounded px-2 py-1.5 text-sm text-muted hover:bg-raised hover:text-ink">
            <ArrowLeft className="h-4 w-4" aria-hidden />
            <span className="hidden sm:inline">Creator app</span>
            <span className="sm:hidden">App</span>
          </Link>
        </div>
        {searching && (
          <div className="flex items-center gap-2 border-t border-line px-3 py-2 md:hidden">
            <AdminSearch className="flex-1" autoFocus onDone={() => setSearching(false)} />
            <button type="button" aria-label="Close search" onClick={() => setSearching(false)} className="inline-flex h-9 w-9 items-center justify-center rounded text-muted hover:bg-raised">
              <X className="h-4 w-4" />
            </button>
          </div>
        )}
        <Nav current={section} phone />
      </header>
      <div className="flex">
        <aside className="sticky top-14 hidden h-[calc(100vh-3.5rem)] w-48 shrink-0 border-r border-line px-2 py-4 md:block">
          <Nav current={section} />
          <p className="mt-6 px-2.5 text-xs leading-relaxed text-muted">Every change asks for a reason and goes in the audit log.</p>
        </aside>
        <main className="min-w-0 flex-1 px-3 pb-16 pt-5 md:px-6">
          <Routes>
            <Route index element={<Navigate to="/studio/admin/overview" replace />} />
            <Route path="platform" element={<Navigate to="/studio/admin/overview" replace />} />
            <Route path="plans" element={<Navigate to="/studio/admin/billing" replace />} />
            <Route path=":section" element={<Section />} />
            <Route path=":section/:id" element={<Section />} />
            <Route path="*" element={<Navigate to="/studio/admin/overview" replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}
