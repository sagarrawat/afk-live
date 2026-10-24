import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '@/lib/api';
import type { SearchResult } from './types';

export interface Hit {
  key: string;
  group: 'Users' | 'Streams' | 'Runs';
  title: string;
  detail: string;
  to: string;
}

/** Flattens the search result into one keyboard-walkable list: users, then streams, then runs. */
export function hits(r: SearchResult | undefined): Hit[] {
  if (!r) return [];
  return [
    ...r.users.map((u) => ({ key: `u${u.id}`, group: 'Users' as const, title: u.email, detail: [u.name, u.plan].filter(Boolean).join(', '), to: `/studio/admin/users/${u.id}` })),
    ...r.streams.map((s) => ({
      key: `s${s.id}`,
      group: 'Streams' as const,
      title: s.name,
      detail: `${s.userEmail}${s.liveJobId ? ', live now' : s.lastJobId ? '' : ', never live'}`,
      to: s.liveJobId ? `/studio/admin/runs/${s.liveJobId}` : s.lastJobId ? `/studio/admin/runs/${s.lastJobId}` : `/studio/admin/users/${s.userId}`,
    })),
    ...r.runs.map((j) => ({ key: `r${j.jobId}`, group: 'Runs' as const, title: `Run #${j.jobId}${j.streamName ? `: ${j.streamName}` : ''}`, detail: `${j.userEmail}${j.live ? ', live now' : ''}`, to: `/studio/admin/runs/${j.jobId}` })),
  ];
}

function useDebounced(value: string, ms: number) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/**
 * Global search (header box, Ctrl/Cmd+K): users by email or name, streams by name, a run by id. A combobox:
 * arrows move, Enter opens, Escape closes.
 */
export function AdminSearch({ className, autoFocus, onDone }: { className?: string; autoFocus?: boolean; onDone?: () => void }) {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const id = useId();
  const term = useDebounced(q.trim(), 180);
  const res = useQuery({ queryKey: ['admin', 'search', term], queryFn: () => get<SearchResult>(`/api/admin/search?q=${encodeURIComponent(term)}`), enabled: term.length > 0, staleTime: 10_000 });
  const list = term.length > 0 ? hits(res.data) : [];

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        input.current?.focus();
        input.current?.select();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  useEffect(() => setActive(0), [term]);

  const go = (h: Hit) => {
    navigate(h.to);
    setOpen(false);
    setQ('');
    input.current?.blur();
    onDone?.();
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(list.length - 1, a + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === 'Enter' && list[active]) {
      e.preventDefault();
      go(list[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
      input.current?.blur();
      onDone?.();
    }
  };
  const showList = open && term.length > 0;
  const mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

  return (
    <div ref={box} className={clsx('relative', className)}>
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" aria-hidden />
      <input
        ref={input}
        role="combobox"
        aria-expanded={showList}
        aria-controls={`${id}-list`}
        aria-activedescendant={showList && list[active] ? `${id}-${list[active].key}` : undefined}
        aria-autocomplete="list"
        aria-label="Search users, streams and runs"
        placeholder="Search users, streams, runs"
        autoFocus={autoFocus}
        value={q}
        onChange={(e) => (setQ(e.target.value), setOpen(true))}
        onFocus={() => setOpen(true)}
        onKeyDown={onKey}
        className="h-9 w-full rounded border border-line bg-bg pl-9 pr-14 text-sm placeholder:text-muted focus:border-accent focus:outline-none"
      />
      <kbd aria-hidden className="pointer-events-none absolute right-2 top-1/2 hidden -translate-y-1/2 rounded border border-line px-1.5 text-[11px] text-muted sm:block">
        {mac ? '⌘K' : 'Ctrl K'}
      </kbd>
      {showList && (
        <ul id={`${id}-list`} role="listbox" aria-label="Search results" className="absolute left-0 right-0 z-50 mt-1 max-h-[70vh] overflow-y-auto rounded-md border border-line bg-panel py-1 shadow-xl">
          {list.length === 0 && (
            <li className="px-3 py-3 text-sm text-muted" role="presentation">
              {res.isFetching || term !== q.trim() ? 'Searching…' : 'Nothing found. Try part of an email, a stream name or a run number.'}
            </li>
          )}
          {list.map((h, i) => (
            <li
              key={h.key}
              id={`${id}-${h.key}`}
              role="option"
              aria-selected={i === active}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => (e.preventDefault(), go(h))}
              className={clsx('cursor-pointer px-3 py-2 text-sm', i === active && 'bg-raised')}
            >
              {(i === 0 || list[i - 1].group !== h.group) && <span className="mb-1 block text-xs text-muted">{h.group}</span>}
              <span className="block truncate font-medium">{h.title}</span>
              {h.detail && <span className="block truncate text-xs text-muted">{h.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
