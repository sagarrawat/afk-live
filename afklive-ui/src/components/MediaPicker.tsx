import clsx from 'clsx';
import { Check, Film, Image as ImageIcon, Music, Search } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { clock } from '@/lib/format';
import { useMedia } from '@/lib/queries';
import type { MediaItem } from '@/lib/types';
import { TagChips, TagFilter } from './MediaTags';
import { Badge, Button, Dialog, Empty, Input, Spinner } from './ui';

export function MediaThumb({ item, className }: { item: Pick<MediaItem, 'thumbnailUrl' | 'type'>; className?: string }) {
  const Icon = item.type === 'AUDIO' ? Music : item.type === 'IMAGE' ? ImageIcon : Film;
  return item.thumbnailUrl ? (
    <img src={item.thumbnailUrl} alt="" loading="lazy" className={clsx('bg-raised object-cover', className)} />
  ) : (
    <span className={clsx('grid place-items-center bg-raised text-muted', className)}>
      <Icon className="h-5 w-5" aria-hidden />
    </span>
  );
}

export function StatusBadge({ item }: { item: Pick<MediaItem, 'status' | 'statusReason'> }) {
  if (item.status === 'READY') return null;
  return item.status === 'PROCESSING' ? <Badge tone="accent">Checking</Badge> : <Badge tone="bad">Can't play</Badge>;
}

/** Choose files from the library (videos for a playlist, audio for music, an image for an overlay). */
export function MediaPicker({
  open,
  onClose,
  onPick,
  type,
  multiple = true,
  title,
  exclude = [],
}: {
  open: boolean;
  onClose: () => void;
  onPick: (items: MediaItem[]) => void;
  type: 'VIDEO' | 'AUDIO' | 'IMAGE' | 'VIDEO_OR_IMAGE';
  multiple?: boolean;
  title: string;
  exclude?: number[];
}) {
  const [q, setQ] = useState('');
  const [tag, setTag] = useState('');
  const [picked, setPicked] = useState<Map<number, MediaItem>>(new Map());
  const { data, isLoading } = useMedia({ q, tag, type: type === 'VIDEO_OR_IMAGE' ? undefined : type, sort: 'date', size: 60 });
  const items = (data?.content ?? []).filter((m) => (type === 'VIDEO_OR_IMAGE' ? m.type !== 'AUDIO' : true) && m.status !== 'FAILED' && !exclude.includes(m.id));
  const toggle = (m: MediaItem) => {
    const next = new Map(multiple ? picked : []);
    if (next.has(m.id)) next.delete(m.id);
    else next.set(m.id, m);
    setPicked(next);
  };
  const done = () => {
    onPick([...picked.values()]);
    setPicked(new Map());
    onClose();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      wide
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={picked.size === 0} onClick={done}>
            {multiple ? `Add ${picked.size || ''} ${picked.size === 1 ? 'file' : 'files'}`.replace('  ', ' ') : 'Use this file'}
          </Button>
        </>
      }
    >
      <div className="mb-4 flex flex-wrap gap-2">
        <div className="relative min-w-[180px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted" aria-hidden />
          <Input aria-label="Search the library" placeholder="Search by name" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
        </div>
        <TagFilter value={tag} onChange={setTag} className="w-full sm:w-44" />
      </div>
      {isLoading ? (
        <Spinner />
      ) : items.length === 0 ? (
        q || tag ? (
          <Empty title="Nothing matches">{q ? 'Try another name or tag.' : 'No files with that tag here.'}</Empty>
        ) : (
          <Empty title="Nothing here yet" action={<Link to="/studio/library" className="text-accent hover:underline">Upload to your library</Link>}>
            Upload files to your library first, then add them here.
          </Empty>
        )
      ) : (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {items.map((m) => {
            const on = picked.has(m.id);
            return (
              <li key={m.id}>
                <button
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggle(m)}
                  className={clsx('relative w-full overflow-hidden rounded border text-left', on ? 'border-accent ring-2 ring-accent/40' : 'border-line hover:border-muted/60')}
                >
                  <MediaThumb item={m} className="aspect-video w-full" />
                  {on && (
                    <span className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full bg-accent text-accent-ink">
                      <Check className="h-4 w-4" />
                    </span>
                  )}
                  <span className="block px-2 py-1.5">
                    <span className="block truncate text-sm font-medium">{m.title}</span>
                    <span className="flex items-center justify-between gap-2 text-xs text-muted">
                      <span className="tnum">{m.type === 'IMAGE' ? `${m.width ?? '?'}×${m.height ?? '?'}` : clock(m.durationSeconds)}</span>
                      <StatusBadge item={m} />
                    </span>
                    <TagChips tags={m.tags} max={2} className="mt-1" />
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </Dialog>
  );
}
