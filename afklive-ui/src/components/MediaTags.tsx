import clsx from 'clsx';
import { Check, Pencil, Plus, Tag as TagIcon, Trash2, X } from 'lucide-react';
import { useId, useState } from 'react';
import { del, patch, post } from '@/lib/api';
import { useInvalidate, useMediaTags } from '@/lib/queries';
import type { MediaTag, TagRef } from '@/lib/types';
import { Button, Dialog, Empty, IconButton, Input, Select, Spinner, useAction, useToast } from './ui';

/** ML-8: tags a creator puts on library files, by show or mood. */

export const MAX_TAG_NAME = 40;

/** A file's tags as small chips; with {@code onRemove}, each chip gets a remove button. */
export function TagChips({ tags, max, onRemove, className }: { tags?: TagRef[]; max?: number; onRemove?: (t: TagRef) => void; className?: string }) {
  if (!tags || tags.length === 0) return null;
  const shown = max && tags.length > max ? tags.slice(0, max) : tags;
  const more = tags.length - shown.length;
  return (
    <span className={clsx('flex min-w-0 flex-wrap gap-1', className)}>
      {shown.map((t) => (
        <span key={t.id} className="inline-flex min-w-0 max-w-full items-center gap-1 rounded-sm border border-line bg-raised px-1.5 py-0.5 text-xs text-muted">
          <TagIcon className="h-3 w-3 shrink-0" aria-hidden />
          <span className="truncate">{t.name}</span>
          {onRemove && (
            <button type="button" aria-label={`Remove tag ${t.name}`} onClick={() => onRemove(t)} className="-mr-0.5 rounded-sm p-0.5 hover:bg-line hover:text-ink">
              <X className="h-3 w-3" />
            </button>
          )}
        </span>
      ))}
      {more > 0 && <span className="px-1 py-0.5 text-xs text-muted">+{more}</span>}
    </span>
  );
}

/** "All tags / No tags / each tag": shown only once the creator has tags (or a tag filter is on). */
export function TagFilter({ value, onChange, className }: { value: string; onChange: (v: string) => void; className?: string }) {
  const { data: tags } = useMediaTags();
  if (!tags || (tags.length === 0 && !value)) return null;
  return (
    <Select aria-label="Tag" value={value} onChange={(e) => onChange(e.target.value)} className={className}>
      <option value="">All tags</option>
      <option value="none">No tags</option>
      {tags.map((t) => (
        <option key={t.id} value={String(t.id)}>
          {t.name} ({t.fileCount})
        </option>
      ))}
    </Select>
  );
}

/** Finds a tag by name (ignoring case) or creates it, then puts it on the files. */
export async function addTagToFiles(name: string, ids: number[], existing: MediaTag[]): Promise<{ tag: TagRef; changed: number }> {
  const clean = name.replace(/\s+/g, ' ').trim();
  const tag = existing.find((t) => t.name.toLowerCase() === clean.toLowerCase()) ?? (await post<MediaTag>('/api/media/tags', { name: clean }));
  const r = await post<{ changed: number }>(`/api/media/tags/${tag.id}/files`, { ids });
  return { tag, changed: r.changed };
}

/** Bulk "Add tag" for the files selected in the library. */
export function AddTagDialog({ ids, onClose }: { ids: number[]; onClose: () => void }) {
  const { data: tags, isLoading } = useMediaTags();
  const [name, setName] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const toast = useToast();
  const files = `${ids.length} file${ids.length === 1 ? '' : 's'}`;
  const apply = (n: string) =>
    run(async () => {
      const { tag } = await addTagToFiles(n, ids, tags ?? []);
      await invalidate(['media']);
      toast(`Tagged ${files} "${tag.name}".`, 'ok');
      onClose();
    });
  return (
    <Dialog open onClose={onClose} title={`Add a tag to ${files}`}>
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) apply(name);
        }}
      >
        <Input aria-label="New tag" placeholder="New tag, like a show or a mood" value={name} onChange={(e) => setName(e.target.value)} maxLength={MAX_TAG_NAME} autoFocus />
        <Button type="submit" variant="primary" busy={busy} disabled={!name.trim()}>
          Add
        </Button>
      </form>
      {isLoading ? (
        <Spinner />
      ) : tags && tags.length > 0 ? (
        <>
          <p className="mb-2 mt-4 text-sm text-muted">Or pick one you have</p>
          <ul className="flex flex-wrap gap-2">
            {tags.map((t) => (
              <li key={t.id}>
                <Button size="sm" icon={<TagIcon className="h-3.5 w-3.5" />} disabled={busy} onClick={() => apply(t.name)}>
                  {t.name}
                </Button>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </Dialog>
  );
}

/** Create, rename and delete tags. */
export function ManageTagsDialog({ onClose, onDeleted }: { onClose: () => void; onDeleted?: (id: number) => void }) {
  const { data: tags, isLoading } = useMediaTags();
  const [name, setName] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  return (
    <Dialog open onClose={onClose} title="Tags" footer={<Button onClick={onClose}>Done</Button>}>
      <p className="mb-3 text-sm text-muted">Group files by show or mood, then filter your library by them. Deleting a tag keeps the files.</p>
      <form
        className="mb-4 flex gap-2"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await run(() => post('/api/media/tags', { name }), 'Tag added.')) {
            setName('');
            invalidate(['media']);
          }
        }}
      >
        <Input aria-label="New tag name" placeholder="New tag" value={name} onChange={(e) => setName(e.target.value)} maxLength={MAX_TAG_NAME} />
        <Button type="submit" busy={busy} disabled={!name.trim()} icon={<Plus className="h-4 w-4" />}>
          Add
        </Button>
      </form>
      {isLoading ? (
        <Spinner />
      ) : !tags || tags.length === 0 ? (
        <Empty icon={<TagIcon className="h-6 w-6" />} title="No tags yet">
          Add one here, or select files in your library and choose Add tag.
        </Empty>
      ) : (
        <ul className="divide-y divide-line rounded-md border border-line">
          {tags.map((t) => (
            <TagRow key={t.id} tag={t} onDeleted={onDeleted} />
          ))}
        </ul>
      )}
    </Dialog>
  );
}

function TagRow({ tag, onDeleted }: { tag: MediaTag; onDeleted?: (id: number) => void }) {
  const [mode, setMode] = useState<'view' | 'rename' | 'delete'>('view');
  const [name, setName] = useState(tag.name);
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const inputId = useId();
  const files = `${tag.fileCount} file${tag.fileCount === 1 ? '' : 's'}`;
  if (mode === 'rename') {
    return (
      <li className="px-3 py-2">
        <form
          className="flex items-center gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            if (await run(() => patch(`/api/media/tags/${tag.id}`, { name }), 'Tag renamed.')) {
              setMode('view');
              invalidate(['media']);
            }
          }}
        >
          <label htmlFor={inputId} className="sr-only">
            New name for {tag.name}
          </label>
          <Input id={inputId} value={name} onChange={(e) => setName(e.target.value)} maxLength={MAX_TAG_NAME} autoFocus />
          <IconButton label="Save name" type="submit" disabled={busy || !name.trim()}>
            <Check className="h-4 w-4" />
          </IconButton>
          <IconButton label="Cancel" type="button" onClick={() => (setName(tag.name), setMode('view'))}>
            <X className="h-4 w-4" />
          </IconButton>
        </form>
      </li>
    );
  }
  if (mode === 'delete') {
    return (
      <li className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
        <span className="min-w-0 text-sm">
          Delete <span className="font-medium">{tag.name}</span>? {tag.fileCount === 1 ? 'Its file stays in your library.' : tag.fileCount > 1 ? `Its ${files} stay in your library.` : ''}
        </span>
        <span className="flex gap-2">
          <Button size="sm" variant="ghost" onClick={() => setMode('view')}>
            Keep
          </Button>
          <Button
            size="sm"
            variant="danger"
            busy={busy}
            onClick={async () => {
              if (await run(() => del(`/api/media/tags/${tag.id}`), 'Tag deleted.')) {
                onDeleted?.(tag.id);
                invalidate(['media']);
              }
            }}
          >
            Delete tag
          </Button>
        </span>
      </li>
    );
  }
  return (
    <li className="flex items-center gap-2 px-3 py-2">
      <TagIcon className="h-4 w-4 shrink-0 text-muted" aria-hidden />
      <span className="min-w-0 flex-1 truncate">{tag.name}</span>
      <span className="tnum shrink-0 text-sm text-muted">{files}</span>
      <IconButton label={`Rename ${tag.name}`} onClick={() => setMode('rename')}>
        <Pencil className="h-4 w-4" />
      </IconButton>
      <IconButton label={`Delete ${tag.name}`} onClick={() => setMode('delete')}>
        <Trash2 className="h-4 w-4" />
      </IconButton>
    </li>
  );
}

/** One file's tags with remove buttons, and a box to add one (preview dialog). */
export function FileTags({ fileId, tags }: { fileId: number; tags?: TagRef[] }) {
  const { data: all } = useMediaTags();
  const [name, setName] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const listId = useId();
  const have = new Set((tags ?? []).map((t) => t.id));
  return (
    <div>
      <p className="mb-1.5 text-sm text-muted">Tags</p>
      <TagChips
        tags={tags}
        className="mb-2"
        onRemove={(t) => run(async () => {
          await post(`/api/media/tags/${t.id}/files/remove`, { ids: [fileId] });
          await invalidate(['media']);
        })}
      />
      <form
        className="flex gap-2"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!name.trim()) return;
          if (await run(async () => {
            await addTagToFiles(name, [fileId], all ?? []);
            await invalidate(['media']);
          })) setName('');
        }}
      >
        <Input aria-label="Add a tag" placeholder="Add a tag" list={listId} value={name} onChange={(e) => setName(e.target.value)} maxLength={MAX_TAG_NAME} className="h-8 text-sm" />
        <datalist id={listId}>
          {(all ?? []).filter((t) => !have.has(t.id)).map((t) => (
            <option key={t.id} value={t.name} />
          ))}
        </datalist>
        <Button size="sm" type="submit" busy={busy} disabled={!name.trim()}>
          Add
        </Button>
      </form>
    </div>
  );
}
