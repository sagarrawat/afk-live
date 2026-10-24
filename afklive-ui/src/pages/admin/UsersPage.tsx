import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Badge, Button, ErrorNote, Input, Segmented } from '@/components/ui';
import { get } from '@/lib/api';
import { date, money, relative } from '@/lib/format';
import type { Page } from '@/lib/types';
import type { ConsoleUserRow } from './types';

export type UserFilter = '' | 'paying' | 'live' | 'never';

/** Money paid per currency, e.g. "₹1,500, $24.00"; a dash when nothing. */
export function paidText(paid: ConsoleUserRow['paid']): string {
  return paid.length === 0 ? '–' : paid.map((p) => money(p.amount, p.currency)).join(', ');
}

function hours(h: number): string {
  return h === 0 ? '–' : `${h.toLocaleString(undefined, { maximumFractionDigits: 1 })} h`;
}

/** AD-3: every account with what support asks first: plan, when they joined, when they last went live, what they paid. */
export function UsersPage() {
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<UserFilter>('');
  const [page, setPage] = useState(0);
  const navigate = useNavigate();
  const users = useQuery({
    queryKey: ['admin', 'users', q, filter, page],
    queryFn: () => get<Page<ConsoleUserRow>>(`/api/admin/users?q=${encodeURIComponent(q)}&filter=${filter}&page=${page}`),
  });
  const rows = users.data?.content ?? [];
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-display text-xl font-bold">
          Users <span className="tnum font-sans text-md font-normal text-muted">{users.data ? users.data.totalElements.toLocaleString() : ''}</span>
        </h1>
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
          <Input className="pl-9" placeholder="Email or name" value={q} onChange={(e) => (setQ(e.target.value), setPage(0))} aria-label="Search users" />
        </div>
      </div>
      <div className="overflow-x-auto">
        <Segmented
          label="Show"
          value={filter}
          onChange={(v) => (setFilter(v), setPage(0))}
          options={[
            { value: '', label: 'Everyone' },
            { value: 'paying', label: 'Paying' },
            { value: 'live', label: 'Live now' },
            { value: 'never', label: 'Never went live' },
          ]}
        />
      </div>
      <ErrorNote error={users.error} />
      <div className="overflow-x-auto rounded-md border border-line bg-panel">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="border-b border-line text-left text-xs text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">Account</th>
              <th className="px-3 py-2 font-medium">Plan</th>
              <th className="px-3 py-2 font-medium">Signed up</th>
              <th className="px-3 py-2 font-medium">Last live</th>
              <th className="px-3 py-2 text-right font-medium">Hours this month</th>
              <th className="px-3 py-2 text-right font-medium">Paid to date</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((u) => (
              <tr key={u.id} tabIndex={0} className="cursor-pointer hover:bg-raised/60" onClick={() => navigate(`/studio/admin/users/${u.id}`)} onKeyDown={(e) => e.key === 'Enter' && navigate(`/studio/admin/users/${u.id}`)}>
                <td className="max-w-[18rem] px-3 py-2">
                  <Link to={`/studio/admin/users/${u.id}`} onClick={(e) => e.stopPropagation()} className="block truncate font-medium hover:underline">
                    {u.email}
                  </Link>
                  {u.name && <span className="block truncate text-xs text-muted">{u.name}</span>}
                </td>
                <td className="whitespace-nowrap px-3 py-2">
                  <span className="flex items-center gap-1.5">
                    {u.plan}
                    {!u.enabled && <Badge tone="bad">Disabled</Badge>}
                  </span>
                </td>
                <td className="tnum whitespace-nowrap px-3 py-2 text-muted">{u.createdAt ? date(u.createdAt) : '–'}</td>
                <td className="tnum whitespace-nowrap px-3 py-2">
                  {u.liveStreams > 0 ? <Badge tone="live">Live now{u.liveStreams > 1 ? ` (${u.liveStreams})` : ''}</Badge> : u.lastLiveAt ? relative(u.lastLiveAt) : <span className="text-muted">Never</span>}
                </td>
                <td className="tnum whitespace-nowrap px-3 py-2 text-right">{hours(u.hoursThisMonth)}</td>
                <td className="tnum whitespace-nowrap px-3 py-2 text-right">{paidText(u.paid)}</td>
              </tr>
            ))}
            {users.data && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-muted">
                  No accounts match.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted">Accounts made before sign-up dates were recorded show “–”. Paid to date counts completed payments; refunded ones aren't counted. Paying means on a paid plan now.</p>
      {users.data && users.data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="tnum text-sm text-muted">
            Page {page + 1} of {users.data.totalPages}
          </span>
          <Button size="sm" disabled={page + 1 >= users.data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}
    </div>
  );
}
