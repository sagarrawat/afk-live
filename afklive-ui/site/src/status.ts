/** RE-7: the public status page, filled from /api/public/status (platform-level only, no account data). */

export type Level = 'operational' | 'degraded' | 'outage' | 'no_data';

export interface StatusComponent {
  id: string;
  name: string;
  status: Level;
  detail: string;
}

export interface IncidentUpdate {
  status: string;
  message: string;
  at: string;
}

export interface Incident {
  id: number;
  title: string;
  status: string;
  startedAt: string;
  resolvedAt?: string | null;
  updates: IncidentUpdate[];
}

export interface PublicStatus {
  overall: Exclude<Level, 'no_data'>;
  checkedAt: string;
  components: StatusComponent[];
  incidents: Incident[];
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** Words always go with the colour (docs/brand.md: health keeps its own colours, always with words). */
const LEVEL: Record<Level, { word: string; dot: string; text: string }> = {
  operational: { word: 'Working', dot: 'bg-ok', text: 'text-ok' },
  degraded: { word: 'Slow or partly down', dot: 'bg-warn', text: 'text-warn' },
  outage: { word: 'Down', dot: 'bg-bad', text: 'text-bad' },
  no_data: { word: 'No recent check', dot: 'bg-muted/50', text: 'text-muted' },
};

const HEADLINE: Record<PublicStatus['overall'], string> = {
  operational: 'Everything is running',
  degraded: 'Some things aren’t working fully',
  outage: 'Something is down',
};

const STAGE: Record<string, string> = {
  investigating: 'Investigating',
  identified: 'Cause found',
  monitoring: 'Fix in place, watching',
  resolved: 'Resolved',
};

export function stage(s: string): string {
  return STAGE[s] ?? s;
}

export function when(iso: string, now = new Date()): string {
  const d = new Date(iso);
  const sameYear = d.getFullYear() === now.getFullYear();
  return d.toLocaleString(undefined, { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric', hour: 'numeric', minute: '2-digit' });
}

export function summary(s: PublicStatus): string {
  const l = LEVEL[s.overall];
  return `<div class="flex items-start gap-4">
  <span aria-hidden="true" class="mt-2 h-4 w-4 shrink-0 rounded-full ${l.dot}"></span>
  <div>
    <h2 class="text-[28px] font-extrabold leading-tight tracking-tight sm:text-[34px]">${esc(HEADLINE[s.overall])}</h2>
    <p class="mt-1 text-muted">Checked ${esc(when(s.checkedAt))}. This page updates by itself.</p>
  </div>
</div>`;
}

export function componentList(s: PublicStatus): string {
  return s.components
    .map((c) => {
      const l = LEVEL[c.status] ?? LEVEL.no_data;
      return `<li class="flex flex-wrap items-start justify-between gap-x-6 gap-y-1 px-5 py-4">
  <div class="min-w-0 flex-1 basis-56">
    <h3 class="font-semibold">${esc(c.name)}</h3>
    <p class="mt-0.5 text-[15px] text-muted">${esc(c.detail)}</p>
  </div>
  <span class="flex items-center gap-2 text-[15px] font-medium ${l.text}"><span aria-hidden="true" class="h-2.5 w-2.5 rounded-full ${l.dot}"></span>${esc(l.word)}</span>
</li>`;
    })
    .join('');
}

function incidentCard(i: Incident): string {
  const open = !i.resolvedAt;
  return `<article class="rounded-2xl border ${open ? 'border-warn/40 bg-warn/5' : 'border-line bg-panel'} p-5">
  <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
    <h3 class="text-lg font-semibold tracking-tight">${esc(i.title)}</h3>
    <span class="text-sm font-medium ${open ? 'text-warn' : 'text-ok'}">${esc(stage(i.status))}</span>
  </div>
  <p class="mt-0.5 text-sm text-muted">Started ${esc(when(i.startedAt))}${i.resolvedAt ? `, resolved ${esc(when(i.resolvedAt))}` : ''}</p>
  <ol class="mt-4 space-y-3 border-l border-line pl-4">${i.updates
    .map(
      (u) => `<li>
      <p class="text-sm"><span class="font-semibold">${esc(stage(u.status))}</span> <span class="text-muted">· ${esc(when(u.at))}</span></p>
      <p class="mt-0.5 whitespace-pre-line text-[15px]">${esc(u.message)}</p>
    </li>`,
    )
    .join('')}</ol>
</article>`;
}

export function incidentLists(s: PublicStatus): { open: string; past: string } {
  const open = s.incidents.filter((i) => !i.resolvedAt);
  const past = s.incidents.filter((i) => i.resolvedAt);
  return {
    open: open.map(incidentCard).join(''),
    past: past.length ? past.map(incidentCard).join('') : '<p class="text-muted">No incidents in the last 90 days.</p>',
  };
}

export async function loadStatus(): Promise<PublicStatus> {
  const r = await fetch('/api/public/status', { headers: { Accept: 'application/json' } });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}

/** Fills [data-status] with [data-status-summary], [data-status-components], [data-status-open], [data-status-past]. */
export function mountStatus(root: HTMLElement, load = loadStatus) {
  const part = (name: string) => root.querySelector<HTMLElement>(`[data-status-${name}]`);
  const render = async () => {
    try {
      const s = await load();
      const { open, past } = incidentLists(s);
      part('summary')!.innerHTML = summary(s);
      part('components')!.innerHTML = componentList(s);
      const openEl = part('open')!;
      openEl.innerHTML = open;
      openEl.classList.toggle('hidden', !open);
      part('past')!.innerHTML = past;
      root.removeAttribute('aria-busy');
    } catch {
      // The status API itself didn't answer: that is news.
      part('summary')!.innerHTML = `<div class="flex items-start gap-4"><span aria-hidden="true" class="mt-2 h-4 w-4 shrink-0 rounded-full bg-bad"></span><div><h2 class="text-[28px] font-extrabold leading-tight tracking-tight">We can’t check right now</h2><p class="mt-1 text-muted">AFK Live isn’t answering. If this lasts, email support@afklive.in.</p></div></div>`;
    }
  };
  render();
  window.setInterval(render, 60_000);
}
