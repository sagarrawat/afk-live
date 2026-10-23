import clsx from 'clsx';
import { AlertTriangle, CheckSquare, FolderOpen, Pencil, Search, Square, Tag as TagIcon, Tags, Trash2, Upload as UploadIcon, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { MediaThumb, StatusBadge } from '@/components/MediaPicker';
import { AddTagDialog, FileTags, ManageTagsDialog, TagChips, TagFilter } from '@/components/MediaTags';
import { Badge, Button, Dialog, Empty, ErrorNote, Field, IconButton, Input, PageHeader, Segmented, Select, Spinner, useAction, useToast } from '@/components/ui';
import { patch, post } from '@/lib/api';
import { bytes, clock, date, duration } from '@/lib/format';
import { keys, useInvalidate, useMedia, useOpenUploads, useStorage } from '@/lib/queries';
import type { DeleteResult, MediaItem } from '@/lib/types';
import { useUploads, type UploadEntry } from '@/lib/uploads-context';

const ACCEPT = 'video/*,audio/*,image/jpeg,image/png,image/gif,image/bmp,.mkv,.flv,.ts,.mts';

/** ML-1..ML-6, ML-8: everything uploaded, its processing state, storage, and safe deletes. */
export function Library() {
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [tag, setTag] = useState('');
  const [sort, setSort] = useState('date');
  const [page, setPage] = useState(0);
  const [view, setView] = useState<'grid' | 'list'>(() => {
    try {
      return (localStorage.getItem('afk-library-view') as 'grid' | 'list') || 'grid';
    } catch {
      return 'grid';
    }
  });
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [preview, setPreview] = useState<MediaItem | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [tagging, setTagging] = useState(false);
  const [managingTags, setManagingTags] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const uploads = useUploads();
  const { data: storage } = useStorage();

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q);
      setPage(0);
    }, 250);
    return () => clearTimeout(t);
  }, [q]);
  useEffect(() => {
    try {
      localStorage.setItem('afk-library-view', view);
    } catch {
      /* ignore */
    }
  }, [view]);

  const { data, isLoading, error } = useMedia({ q: debounced, type, status, tag, sort, page, size: 24, dir: sort === 'name' ? 'asc' : 'desc' });
  const items = data?.content ?? [];
  const toggle = (id: number) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };
  const filtered = !!(debounced || type || status || tag);
  // The open preview follows the list, so tag changes show straight away.
  const previewed = preview ? items.find((i) => i.id === preview.id) ?? preview : null;

  return (
    <div
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes('Files')) {
          e.preventDefault();
          setDragging(true);
        }
      }}
      onDragLeave={(e) => e.currentTarget === e.target && setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        if (e.dataTransfer.files.length) uploads.add(e.dataTransfer.files);
      }}
      className="relative"
    >
      <PageHeader
        title="Library"
        description="Videos, music and images for your streams. Files are checked after upload so they're ready to play."
        actions={
          <>
            <input ref={fileInput} type="file" multiple accept={ACCEPT} className="hidden" onChange={(e) => e.target.files && (uploads.add(e.target.files), (e.target.value = ''))} />
            <Button variant="primary" icon={<UploadIcon className="h-4 w-4" />} onClick={() => fileInput.current?.click()} disabled={storage?.full}>
              Upload files
            </Button>
          </>
        }
      />
      {storage && <StorageMeter used={storage.usedBytes} limit={storage.limitBytes} warning={storage.warning} full={storage.full} />}
      <UploadQueue />
      <UnfinishedUploads />

      <div className="mb-4 mt-6 flex flex-wrap items-center gap-3">
        <div className="relative min-w-[200px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
          <Input aria-label="Search the library" placeholder="Search by name" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
        </div>
        <Select aria-label="Type" value={type} onChange={(e) => (setType(e.target.value), setPage(0))} className="w-36">
          <option value="">All types</option>
          <option value="VIDEO">Videos</option>
          <option value="AUDIO">Music</option>
          <option value="IMAGE">Images</option>
        </Select>
        <Select aria-label="Status" value={status} onChange={(e) => (setStatus(e.target.value), setPage(0))} className="w-36">
          <option value="">Any status</option>
          <option value="READY">Ready</option>
          <option value="PROCESSING">Checking</option>
          <option value="FAILED">Can't play</option>
        </Select>
        <TagFilter value={tag} onChange={(v) => (setTag(v), setPage(0))} className="w-40" />
        <Select aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value)} className="w-40">
          <option value="date">Newest first</option>
          <option value="name">Name</option>
          <option value="duration">Longest first</option>
          <option value="size">Largest first</option>
        </Select>
        <Segmented
          label="View"
          value={view}
          onChange={setView}
          options={[
            { value: 'grid', label: 'Grid' },
            { value: 'list', label: 'List' },
          ]}
        />
        <Button variant="ghost" size="sm" icon={<Tags className="h-4 w-4" />} onClick={() => setManagingTags(true)}>
          Tags
        </Button>
      </div>

      {selected.size > 0 && (
        <div className="sticky top-16 z-20 mb-4 flex flex-wrap items-center justify-between gap-3 rounded-md border border-accent/40 bg-panel px-4 py-2.5 shadow">
          <span>
            {selected.size} selected
            <button className="ml-3 text-sm text-muted hover:text-ink" onClick={() => setSelected(new Set(items.map((i) => i.id)))}>
              Select page
            </button>
          </span>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
              Clear
            </Button>
            <Button size="sm" icon={<TagIcon className="h-4 w-4" />} onClick={() => setTagging(true)}>
              Add tag
            </Button>
            <Button size="sm" variant="danger" icon={<Trash2 className="h-4 w-4" />} onClick={() => setConfirmDelete(true)}>
              Delete
            </Button>
          </div>
        </div>
      )}

      <ErrorNote error={error} />
      {isLoading ? (
        <Spinner label="Loading your library" />
      ) : items.length === 0 ? (
        filtered ? (
          <Empty title="Nothing matches">Try a different search or filter.</Empty>
        ) : (
          <Empty icon={<FolderOpen className="h-8 w-8" />} title="Your library is empty" action={<Button variant="primary" onClick={() => fileInput.current?.click()}>Upload files</Button>}>
            Drop videos, music or images anywhere on this page. Large files upload in parts and pick up where they left off if your connection drops.
          </Empty>
        )
      ) : view === 'grid' ? (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {items.map((m) => (
            <li key={m.id} className={clsx('group relative overflow-hidden rounded-md border bg-panel', selected.has(m.id) ? 'border-accent' : 'border-line')}>
              <button className="block w-full text-left" onClick={() => setPreview(m)}>
                <MediaThumb item={m} className="aspect-video w-full" />
                <span className="block px-3 py-2">
                  <span className="block truncate text-sm font-medium">{m.title}</span>
                  <span className="mt-0.5 flex items-center justify-between gap-2 text-xs text-muted">
                    <span className="tnum">{m.type === 'IMAGE' ? `${m.width ?? '?'}×${m.height ?? '?'}` : clock(m.durationSeconds)}</span>
                    <span className="tnum">{bytes(m.sizeBytes)}</span>
                  </span>
                  <span className="mt-1 flex flex-wrap gap-1">
                    <StatusBadge item={m} />
                    {m.inLiveUse && <Badge tone="live">On air</Badge>}
                  </span>
                  <TagChips tags={m.tags} max={2} className="mt-1" />
                </span>
              </button>
              <button
                aria-label={selected.has(m.id) ? `Deselect ${m.title}` : `Select ${m.title}`}
                aria-pressed={selected.has(m.id)}
                onClick={() => toggle(m.id)}
                className={clsx('absolute left-2 top-2 rounded bg-black/60 p-1 text-white', selected.size === 0 && 'opacity-0 group-hover:opacity-100 focus:opacity-100')}
              >
                {selected.has(m.id) ? <CheckSquare className="h-4 w-4" /> : <Square className="h-4 w-4" />}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <div className="overflow-x-auto rounded-md border border-line bg-panel">
          <table className="w-full text-sm">
            <thead className="border-b border-line text-left text-muted">
              <tr>
                <th className="w-10 px-3 py-2">
                  <span className="sr-only">Select</span>
                </th>
                <th className="px-3 py-2 font-medium">Name</th>
                <th className="px-3 py-2 font-medium">Length</th>
                <th className="hidden px-3 py-2 font-medium sm:table-cell">Size</th>
                <th className="hidden px-3 py-2 font-medium md:table-cell">Added</th>
                <th className="px-3 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {items.map((m) => (
                <tr key={m.id} className={selected.has(m.id) ? 'bg-accent/5' : undefined}>
                  <td className="px-3 py-2">
                    <input type="checkbox" aria-label={`Select ${m.title}`} checked={selected.has(m.id)} onChange={() => toggle(m.id)} className="accent-[rgb(var(--accent))]" />
                  </td>
                  <td className="max-w-[260px] px-3 py-2">
                    <button className="flex min-w-0 items-center gap-3 text-left hover:underline" onClick={() => setPreview(m)}>
                      <MediaThumb item={m} className="h-8 w-14 shrink-0 rounded-sm" />
                      <span className="truncate">{m.title}</span>
                    </button>
                    <TagChips tags={m.tags} max={3} className="mt-1 pl-[68px]" />
                  </td>
                  <td className="tnum px-3 py-2 text-muted">{m.type === 'IMAGE' ? '–' : clock(m.durationSeconds)}</td>
                  <td className="tnum hidden px-3 py-2 text-muted sm:table-cell">{bytes(m.sizeBytes)}</td>
                  <td className="hidden px-3 py-2 text-muted md:table-cell">{date(m.createdAt)}</td>
                  <td className="px-3 py-2">
                    {m.status === 'READY' ? <span className="text-muted">Ready</span> : <StatusBadge item={m} />}
                    {m.inLiveUse && <Badge tone="live" className="ml-1">On air</Badge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-3">
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="tnum text-sm text-muted">
            Page {page + 1} of {data.totalPages}
          </span>
          <Button size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}

      {dragging && (
        <div className="pointer-events-none fixed inset-0 z-40 grid place-items-center bg-bg/80">
          <p className="rounded-lg border-2 border-dashed border-accent px-10 py-8 text-lg font-semibold">Drop to upload</p>
        </div>
      )}

      {previewed && <PreviewDialog item={previewed} onClose={() => setPreview(null)} />}
      {tagging && <AddTagDialog ids={[...selected]} onClose={() => setTagging(false)} />}
      {managingTags && <ManageTagsDialog onClose={() => setManagingTags(false)} onDeleted={(id) => tag === String(id) && (setTag(''), setPage(0))} />}
      {confirmDelete && <DeleteDialog ids={[...selected]} onClose={() => setConfirmDelete(false)} onDone={(r) => setSelected(new Set(r.blocked.map((b) => b.id)))} />}
    </div>
  );
}

function StorageMeter({ used, limit, warning, full }: { used: number; limit: number; warning: boolean; full: boolean }) {
  const pct = limit > 0 ? Math.min(100, (used / limit) * 100) : 100;
  return (
    <div className="rounded-md border border-line bg-panel px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span>
          <span className="tnum font-medium">{bytes(used)}</span> <span className="text-muted">of {bytes(limit)} used</span>
        </span>
        {(warning || full) && (
          <Link to="/studio/settings/billing" className="text-accent hover:underline">
            {full ? 'Storage full: upgrade or delete files' : 'Running low: upgrade for more'}
          </Link>
        )}
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-raised" role="progressbar" aria-label="Storage used" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
        <div className={clsx('h-full', full ? 'bg-bad' : warning ? 'bg-warn' : 'bg-accent')} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

function eta(e: UploadEntry): string {
  const { sent, total, rate } = e.progress;
  if (rate <= 0) return '';
  return `${duration((total - sent) / rate)} left`;
}

function UploadQueue() {
  const { entries, retry, cancel, clearFinished } = useUploads();
  if (entries.length === 0) return null;
  const finished = entries.some((e) => e.progress.state === 'done');
  return (
    <section aria-label="Uploads" className="mt-4 rounded-md border border-line bg-panel">
      <header className="flex items-center justify-between border-b border-line px-4 py-2.5">
        <span className="font-medium">Uploads</span>
        {finished && (
          <button className="text-sm text-muted hover:text-ink" onClick={clearFinished}>
            Clear finished
          </button>
        )}
      </header>
      <ul className="divide-y divide-line">
        {entries.map((e) => {
          const p = e.progress;
          const pct = p.total ? Math.round((p.sent / p.total) * 100) : 0;
          return (
            <li key={e.key} className="px-4 py-3">
              <div className="flex items-center justify-between gap-3">
                <span className="min-w-0 truncate text-sm font-medium">{e.file.name}</span>
                <span className="flex shrink-0 items-center gap-2 text-sm text-muted">
                  {p.state === 'uploading' && (
                    <span className="tnum">
                      {pct}% {eta(e)}
                    </span>
                  )}
                  {p.state === 'queued' && 'Waiting'}
                  {p.state === 'finishing' && 'Finishing'}
                  {p.state === 'done' && <span className="text-ok">Uploaded, checking the file</span>}
                  {p.state === 'failed' && (
                    <Button size="sm" variant="ghost" onClick={() => retry(e.key)}>
                      Try again
                    </Button>
                  )}
                  {(p.state === 'uploading' || p.state === 'queued' || p.state === 'failed') && (
                    <IconButton label={`Cancel ${e.file.name}`} onClick={() => cancel(e.key)}>
                      <X className="h-4 w-4" />
                    </IconButton>
                  )}
                </span>
              </div>
              {p.state !== 'done' && (
                <div className="mt-2 h-1 overflow-hidden rounded-full bg-raised">
                  <div className={clsx('h-full transition-[width]', p.state === 'failed' ? 'bg-bad' : 'bg-accent')} style={{ width: `${pct}%` }} />
                </div>
              )}
              {p.error && <p className="mt-1.5 text-sm text-bad">{p.error}</p>}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function UnfinishedUploads() {
  const { data } = useOpenUploads();
  const { entries } = useUploads();
  const active = new Set(entries.map((e) => e.upload.id).filter(Boolean));
  const stale = (data ?? []).filter((u) => !active.has(u.id));
  if (stale.length === 0) return null;
  return (
    <div className="mt-4 flex items-start gap-3 rounded-md border border-warn/40 bg-warn/5 px-4 py-3 text-sm">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warn" aria-hidden />
      <p>
        {stale.length === 1 ? `${stale[0].filename} didn't finish uploading` : `${stale.length} uploads didn't finish`} ({stale.map((u) => `${Math.round((u.uploadedBytes / u.sizeBytes) * 100)}%`).join(', ')} done). Choose the same file again to continue from there.
      </p>
    </div>
  );
}

function PreviewDialog({ item: m, onClose }: { item: MediaItem; onClose: () => void }) {
  const [title, setTitle] = useState(m.title);
  const [editing, setEditing] = useState(false);
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  return (
    <Dialog open onClose={onClose} title={m.title} wide>
      <div className="space-y-4">
        {m.status === 'READY' && m.type === 'VIDEO' && <video src={m.previewUrl} controls preload="metadata" className="aspect-video w-full rounded bg-black" />}
        {m.status === 'READY' && m.type === 'AUDIO' && <audio src={m.previewUrl} controls className="w-full" />}
        {m.status === 'READY' && m.type === 'IMAGE' && <img src={m.previewUrl} alt={m.title} className="max-h-[60vh] w-full rounded object-contain" />}
        {m.status === 'PROCESSING' && <p className="text-muted">We're checking this file. It's usually ready within a minute.</p>}
        {m.status === 'FAILED' && <ErrorNote error={new Error(m.statusReason ?? "This file can't be played.")} />}
        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-4">
          <Fact label="Length" value={m.type === 'IMAGE' ? '–' : clock(m.durationSeconds)} />
          <Fact label="Size" value={bytes(m.sizeBytes)} />
          <Fact label="Picture" value={m.width ? `${m.width}×${m.height}${m.fps ? `, ${Math.round(m.fps)} fps` : ''}` : '–'} />
          <Fact label="Added" value={date(m.createdAt)} />
        </dl>
        {m.usedBy.length > 0 && <p className="text-sm text-muted">Used in {m.usedBy.join(', ')}.</p>}
        <FileTags fileId={m.id} tags={m.tags} />
        {editing ? (
          <form
            className="flex items-end gap-2"
            onSubmit={async (e) => {
              e.preventDefault();
              if (await run(() => patch(`/api/media/${m.id}`, { title }), 'Renamed.')) {
                setEditing(false);
                invalidate(['media']);
              }
            }}
          >
            <Field label="Name" htmlFor="rename">
              <Input id="rename" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} autoFocus />
            </Field>
            <Button type="submit" variant="primary" busy={busy}>
              Save
            </Button>
          </form>
        ) : (
          <Button size="sm" variant="ghost" icon={<Pencil className="h-4 w-4" />} onClick={() => setEditing(true)}>
            Rename
          </Button>
        )}
      </div>
    </Dialog>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted">{label}</dt>
      <dd className="tnum">{value}</dd>
    </div>
  );
}

function DeleteDialog({ ids, onClose, onDone }: { ids: number[]; onClose: () => void; onDone: (r: DeleteResult) => void }) {
  const [result, setResult] = useState<DeleteResult | null>(null);
  const { busy, run } = useAction();
  const toast = useToast();
  const invalidate = useInvalidate();
  const go = () =>
    run(async () => {
      const r = await post<DeleteResult>('/api/media/delete', { ids });
      await invalidate(['media'], [...keys.storage], [...keys.streams]);
      onDone(r);
      if (r.blocked.length === 0) {
        toast(`Deleted ${r.deleted.length} file${r.deleted.length === 1 ? '' : 's'}, freed ${bytes(r.freedBytes)}.`, 'ok');
        onClose();
      } else {
        setResult(r);
      }
    });
  return (
    <Dialog
      open
      onClose={onClose}
      title={result ? 'Some files were kept' : `Delete ${ids.length} file${ids.length === 1 ? '' : 's'}?`}
      footer={
        result ? (
          <Button onClick={onClose}>Close</Button>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              Keep them
            </Button>
            <Button variant="danger" busy={busy} onClick={go}>
              Delete
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="space-y-3">
          {result.deleted.length > 0 && <p>Deleted {result.deleted.length}, freed {bytes(result.freedBytes)}.</p>}
          <ul className="space-y-2">
            {result.blocked.map((b) => (
              <li key={b.id} className="text-sm">
                <span className="font-medium">{b.title}</span>: <span className="text-muted">{b.reason}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p>Files are removed from any stream that isn't live. Files a live stream is playing are kept. This can't be undone.</p>
      )}
    </Dialog>
  );
}
