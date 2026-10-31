import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ClipboardCheck, ExternalLink, ImagePlus, Music, PenLine } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { HelpLink } from '@/components/HelpLink';
import { MediaPicker } from '@/components/MediaPicker';
import { Button, Input, Panel, buttonClass, useAction } from '@/components/ui';
import { del, get, post, put } from '@/lib/api';
import { HELP, type HelpArticle, YOUTUBE_STUDIO_LIVE } from '@/lib/help';
import { keys } from '@/lib/queries';
import type { StreamView } from '@/lib/types';
import { TitleWriterPanel, useTitleWriter } from './TitleWriter';

/** GR-9: what the check endpoint returns (GoLiveCheckService.CheckView). */
/** NONE: nothing to change here, the flag only explains (AFK Live never replies in chat by itself, AI-3). */
export type CheckFix = 'ADD_OVERLAYS' | 'USE_TITLE' | 'OPEN_PLAYLIST' | 'EDIT_TITLE' | 'EDIT_THUMBNAIL' | 'NONE';

export interface CheckFlag {
  id: string;
  kind: 'POLICY' | 'FOUND';
  title: string;
  detail: string;
  fix: CheckFix;
  fixLabel: string;
  suggestion?: string | null;
  help: string;
  dismissed: boolean;
}

export interface CheckView {
  flags: CheckFlag[];
  open: number;
  checking: boolean;
  musicNote?: string | null;
  musicHelp: string;
  youtubeTitle?: string | null;
  thumbnail?: { mediaId: number; title: string; url: string } | null;
}

export const checkKey = (id: number) => [...keys.stream(id), 'check'] as const;

/** The help article for a flag (the server names it; unknown ones get no link rather than a broken one). */
export function checkHelp(path: string | null | undefined): HelpArticle | null {
  const known: HelpArticle[] = [HELP.loops, HELP.titles, HELP.music];
  return known.find((h) => h === path) ?? null;
}

/** Fixes that change something here in one tap; the rest open the place to fix it. */
const SERVER_FIXES: CheckFix[] = ['ADD_OVERLAYS', 'USE_TITLE'];

/** Title flags that also offer "Suggest a better title" (AI-1) when the writer is set up. */
const TITLE_FLAGS = ['title-no-phrase', 'title-live'];

/** How the title writer opens: from a flag it starts writing at once. */
type WriterMode = 'auto' | 'open' | null;

/**
 * GR-9: the stream check, on the stream's page. It flags only what we can see for certain, each with
 * a one-tap fix or a way to keep it as it is. It's advice: going live never waits for it.
 */
export function GoLiveCheckCard({ stream: s }: { stream: StreamView }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: checkKey(s.id),
    queryFn: () => get<CheckView>(`/api/streams/${s.id}/check`),
  });
  const { busy, run } = useAction();
  // ON-9: "Write a YouTube title" on Home links here with ?title=write: the title editor opens with the writer.
  const [search] = useSearchParams();
  const fromLink = search.get('title') === 'write';
  const [editing, setEditing] = useState(fromLink);
  const [showKept, setShowKept] = useState(false);
  const [focus, setFocus] = useState<'title' | 'thumbnail' | null>(fromLink ? 'title' : null);
  const [writer, setWriter] = useState<WriterMode>(fromLink ? 'open' : null);
  const canWrite = !!useTitleWriter(s.id).data?.available;

  if (!data) return null;
  const open = data.flags.filter((f) => !f.dismissed);
  const kept = data.flags.filter((f) => f.dismissed);

  const apply = (fn: () => Promise<CheckView>, success?: string) =>
    run(async () => {
      const next = await fn();
      qc.setQueryData(checkKey(s.id), next);
      // Overlay and title fixes change the stream too.
      await qc.invalidateQueries({ queryKey: keys.stream(s.id), exact: true });
    }, success);

  const fix = (f: CheckFlag) => {
    if (SERVER_FIXES.includes(f.fix)) {
      const done = f.fix === 'ADD_OVERLAYS' ? 'Added a clock and now playing.' : 'Title updated. Copy it into YouTube Studio.';
      return apply(() => post<CheckView>(`/api/streams/${s.id}/check/${f.id}/fix`), done);
    }
    setFocus(f.fix === 'EDIT_THUMBNAIL' ? 'thumbnail' : 'title');
    setEditing(true);
  };

  return (
    <Panel
      title={
        <span className="flex items-center gap-2">
          <ClipboardCheck className="h-4 w-4 text-muted" aria-hidden />
          Stream check
        </span>
      }
      action={<span className="text-sm text-muted">{open.length === 0 ? 'Nothing to fix' : `${open.length} to look at`}</span>}
    >
      {open.length === 0 ? (
        <p className="flex items-start gap-2 text-sm">
          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-muted" aria-hidden />
          <span>
            Nothing we can see would hurt this stream on YouTube.
            {!data.youtubeTitle && <span className="text-muted"> Add your YouTube title below and we'll check it too.</span>}
          </span>
        </p>
      ) : (
        <ul className="space-y-4">
          {open.map((f) => (
            <FlagRow
              key={f.id}
              flag={f}
              stream={s}
              busy={busy}
              onFix={() => fix(f)}
              onSuggest={
                canWrite && TITLE_FLAGS.includes(f.id)
                  ? () => {
                      setFocus(null);
                      setWriter('auto');
                      setEditing(true);
                    }
                  : undefined
              }
              onKeep={() => apply(() => post<CheckView>(`/api/streams/${s.id}/check/${f.id}/dismiss`), f.fix === 'NONE' ? undefined : 'Kept as it is.')}
            />
          ))}
        </ul>
      )}
      {data.checking && <p className="mt-3 text-sm text-muted">Still looking at your videos. This takes a few minutes after an upload.</p>}

      {kept.length > 0 && (
        <div className="mt-4 text-sm">
          <button type="button" className="text-muted hover:text-ink" aria-expanded={showKept} onClick={() => setShowKept((v) => !v)}>
            {kept.length} kept as {kept.length === 1 ? 'it is' : 'they are'} · {showKept ? 'Hide' : 'Show'}
          </button>
          {showKept && (
            <ul className="mt-2 space-y-2">
              {kept.map((f) => (
                <li key={f.id} className="flex flex-wrap items-center justify-between gap-2">
                  <span className="min-w-0 text-muted">{f.title}</span>
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => apply(() => del<CheckView>(`/api/streams/${s.id}/check/${f.id}/dismiss`))}>
                    Check it again
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="mt-4 border-t border-line pt-4">
        {editing ? (
          <TitleEditor
            stream={s}
            check={data}
            focus={focus}
            writer={canWrite ? writer : null}
            canWrite={canWrite}
            onDone={() => {
              setEditing(false);
              setWriter(null);
            }}
            onSaved={(v) => {
              qc.setQueryData(checkKey(s.id), v);
            }}
          />
        ) : (
          <TitleSummary
            check={data}
            onEdit={() => {
              setFocus('title');
              setEditing(true);
            }}
          />
        )}
      </div>

      {data.musicNote && (
        <p className="mt-4 flex gap-2 border-t border-line pt-4 text-sm text-muted">
          <Music className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span>
            {data.musicNote} <HelpLink to={HELP.music}>Music on your live</HelpLink>
          </span>
        </p>
      )}
    </Panel>
  );
}

function FlagRow({
  flag: f,
  stream: s,
  busy,
  onFix,
  onKeep,
  onSuggest,
}: {
  flag: CheckFlag;
  stream: StreamView;
  busy: boolean;
  onFix: () => void;
  onKeep: () => void;
  onSuggest?: () => void;
}) {
  const help = checkHelp(f.help);
  return (
    <li>
      <p className="font-medium">{f.title}</p>
      <p className="mt-0.5 text-sm text-muted">{f.detail}</p>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2">
        {f.fix === 'NONE' ? null : f.fix === 'OPEN_PLAYLIST' ? (
          <Link to={`/studio/streams/${s.id}/playlist`} className={buttonClass('secondary', 'sm')}>
            {f.fixLabel}
          </Link>
        ) : (
          // A suggested title can be long: let it wrap at 375 px instead of running off the screen.
          <Button size="sm" disabled={busy} onClick={onFix} className="h-auto min-h-8 whitespace-normal py-1.5 text-left">
            {f.fixLabel}
          </Button>
        )}
        {onSuggest && (
          <Button size="sm" variant="ghost" icon={<PenLine className="h-4 w-4" />} disabled={busy} onClick={onSuggest}>
            Suggest a better title
          </Button>
        )}
        {/* An explain-only flag: "Got it" files it with the kept ones. */}
        <Button size="sm" variant={f.fix === 'NONE' ? 'secondary' : 'ghost'} disabled={busy} onClick={onKeep}>
          {f.fix === 'NONE' ? f.fixLabel || 'Got it' : 'Keep as it is'}
        </Button>
        {help && (
          <HelpLink to={help} className="text-sm">
            Why this matters
          </HelpLink>
        )}
      </div>
    </li>
  );
}

function TitleSummary({ check, onEdit }: { check: CheckView; onEdit: () => void }) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
      {check.thumbnail && <img src={check.thumbnail.url} alt="Your thumbnail" className="aspect-video w-28 shrink-0 rounded-sm bg-raised object-cover" />}
      <div className="min-w-0 flex-1 text-sm">
        <p className="text-muted">Your YouTube title</p>
        <p className="truncate font-medium">{check.youtubeTitle ?? 'Not added yet'}</p>
      </div>
      <Button size="sm" variant="ghost" onClick={onEdit}>
        {check.youtubeTitle || check.thumbnail ? 'Change' : 'Add title and thumbnail'}
      </Button>
    </div>
  );
}

/** The title and thumbnail the creator uses on YouTube, so the check can read them. Nothing is sent to YouTube. */
function TitleEditor({
  stream: s,
  check,
  focus,
  writer,
  canWrite,
  onDone,
  onSaved,
}: {
  stream: StreamView;
  check: CheckView;
  focus: 'title' | 'thumbnail' | null;
  writer?: WriterMode;
  canWrite?: boolean;
  onDone: () => void;
  onSaved: (v: CheckView) => void;
}) {
  const [title, setTitle] = useState(check.youtubeTitle ?? '');
  const [picking, setPicking] = useState(false);
  const [writing, setWriting] = useState<WriterMode>(writer ?? null);
  useEffect(() => {
    if (writer) setWriting(writer);
  }, [writer]);
  const { busy, run } = useAction();
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (focus === 'title') input.current?.focus();
    if (focus === 'thumbnail') setPicking(true);
  }, [focus]);

  const save = (
    body: {
      youtubeTitle?: string;
      thumbnailMediaId?: number;
      clearThumbnail?: boolean;
    },
    success?: string,
  ) => run(async () => onSaved(await put<CheckView>(`/api/streams/${s.id}/check/title`, body)), success);

  return (
    <div className="space-y-4">
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (await save({ youtubeTitle: title }, 'Saved. We checked it.')) onDone();
        }}
        className="space-y-3"
      >
        <div>
          <label htmlFor={`yt-title-${s.id}`} className="text-sm font-medium">
            Your YouTube title
          </label>
          <Input
            id={`yt-title-${s.id}`}
            ref={input}
            value={title}
            maxLength={100}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Lofi beats to study and relax"
            className="mt-1 w-full"
          />
          <p className="mt-1 flex justify-between gap-2 text-xs text-muted">
            <span>The one you set in YouTube Studio. We only read it here; we don't change anything on YouTube.</span>
            <span className="tnum shrink-0">{title.length}/100</span>
          </p>
          {canWrite && !writing && (
            <Button type="button" size="sm" variant="ghost" icon={<PenLine className="h-4 w-4" />} className="mt-2" onClick={() => setWriting('open')}>
              Write title and description
            </Button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {check.thumbnail ? (
            <>
              <img src={check.thumbnail.url} alt="Your thumbnail" className="aspect-video w-28 rounded-sm bg-raised object-cover" />
              <Button size="sm" variant="ghost" type="button" onClick={() => setPicking(true)}>
                Choose another picture
              </Button>
              <Button size="sm" variant="ghost" type="button" disabled={busy} onClick={() => save({ clearThumbnail: true })}>
                Remove
              </Button>
            </>
          ) : (
            <Button size="sm" variant="ghost" type="button" icon={<ImagePlus className="h-4 w-4" />} onClick={() => setPicking(true)}>
              Add your thumbnail
            </Button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" size="sm" busy={busy}>
            Save and check
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
          <a href={YOUTUBE_STUDIO_LIVE} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm text-accent hover:underline">
            Open YouTube Studio
            <ExternalLink className="h-3.5 w-3.5" aria-hidden />
          </a>
          <HelpLink to={HELP.titles} className="text-sm">
            Titles and thumbnails that get found
          </HelpLink>
        </div>
        {picking && (
          <MediaPicker
            open
            onClose={() => setPicking(false)}
            type="IMAGE"
            multiple={false}
            title="Choose your thumbnail"
            onPick={(items) => items[0] && save({ thumbnailMediaId: items[0].id }, 'Thumbnail checked.')}
          />
        )}
      </form>
      {canWrite && writing && (
        <TitleWriterPanel
          streamId={s.id}
          autoStart={writing === 'auto'}
          onUseTitle={(t) => {
            setTitle(t.slice(0, 100));
            input.current?.focus();
          }}
        />
      )}
    </div>
  );
}
