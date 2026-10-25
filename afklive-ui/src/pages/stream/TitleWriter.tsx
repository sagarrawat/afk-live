import { useQuery, useQueryClient } from '@tanstack/react-query';
import { PenLine } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { CopyButton } from '@/components/Copy';
import { Button, Input, Select, Textarea, useAction } from '@/components/ui';
import { get, post } from '@/lib/api';
import { keys } from '@/lib/queries';

/** AI-1 / GR-10: what /api/streams/{id}/ai/metadata returns (TitleWriterService). */
export interface WriterLanguage {
  code: string;
  name: string;
  nativeName: string;
}

export interface WriterUsage {
  used: number;
  limit: number;
}

export interface WriterStatus {
  available: boolean;
  language?: string | null;
  languages: WriterLanguage[];
  usage: WriterUsage;
}

export interface WriterDraft {
  titles: string[];
  description: string;
  tags: string[];
}

export interface WriterResult {
  status: 'OK' | 'FALLBACK' | 'LIMIT' | 'UNAVAILABLE';
  draft?: WriterDraft | null;
  language: string;
  usage: WriterUsage;
  cached: boolean;
  message?: string | null;
}

export const writerKey = (id: number) => [...keys.stream(id), 'title-writer'] as const;

export function useTitleWriter(streamId: number) {
  return useQuery({
    queryKey: writerKey(streamId),
    queryFn: () => get<WriterStatus>(`/api/streams/${streamId}/ai/metadata`),
    staleTime: 60_000,
  });
}

/** The creator's last choice, else their browser's language when we write in it, else English. */
export function defaultLanguage(saved: string | null | undefined, languages: readonly string[], offered: WriterLanguage[]): string {
  const codes = offered.map((l) => l.code);
  if (saved && codes.includes(saved)) return saved;
  for (const tag of languages) {
    const code = tag.toLowerCase().split(/[-_]/)[0];
    if (codes.includes(code)) return code;
  }
  return 'en';
}

const browserLanguages = () => (typeof navigator !== 'undefined' ? (navigator.languages ?? [navigator.language]) : []);

/**
 * "Write title and description": three title options, a description with the playlist's tracklist, and
 * tags, in the creator's language. Drafts only: the creator edits and copies them into YouTube Studio.
 * Hidden when AI isn't set up on the server.
 */
export function TitleWriterPanel({ streamId, onUseTitle, autoStart }: { streamId: number; onUseTitle?: (title: string) => void; autoStart?: boolean }) {
  const qc = useQueryClient();
  const { data: status } = useTitleWriter(streamId);
  const { busy, run } = useAction();
  const [language, setLanguage] = useState<string | null>(null);
  const [brief, setBrief] = useState('');
  const [result, setResult] = useState<WriterResult | null>(null);
  const [titles, setTitles] = useState<string[]>([]);
  const [description, setDescription] = useState('');
  const [tags, setTags] = useState('');
  const [started, setStarted] = useState(false);
  const ids = useId();

  const lang = language ?? (status ? defaultLanguage(status.language, browserLanguages(), status.languages) : 'en');

  const write = () =>
    run(async () => {
      const r = await post<WriterResult>(`/api/streams/${streamId}/ai/metadata`, { language: lang, brief: brief.trim() || null });
      setResult(r);
      if (r.draft) {
        setTitles(r.draft.titles);
        setDescription(r.draft.description);
        setTags(r.draft.tags.join(', '));
      }
      qc.setQueryData<WriterStatus>(writerKey(streamId), (s) => (s ? { ...s, usage: r.usage, language: r.language } : s));
    });

  useEffect(() => {
    if (autoStart && status?.available && !started) {
      setStarted(true);
      void write();
    }
    // Only the first time the panel opens from "Suggest a better title".
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoStart, status?.available]);

  if (!status?.available) return null;

  const usage = result?.usage ?? status.usage;
  const left = Math.max(0, usage.limit - usage.used);
  const atLimit = result?.status === 'LIMIT' || left === 0;

  return (
    <section aria-labelledby={`${ids}-h`} className="space-y-4 rounded border border-line bg-bg/40 p-3 sm:p-4">
      <div className="flex items-start gap-2">
        <PenLine className="mt-0.5 h-4 w-4 shrink-0 text-accent" aria-hidden />
        <div className="min-w-0">
          <h3 id={`${ids}-h`} className="text-sm font-medium">
            Title, description and tags
          </h3>
          <p className="text-sm text-muted">Written from your stream's name and playlist. Edit them, then copy them into YouTube Studio. We never post anything.</p>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)]">
        <div>
          <label htmlFor={`${ids}-lang`} className="text-sm font-medium">
            Language
          </label>
          <Select id={`${ids}-lang`} value={lang} onChange={(e) => setLanguage(e.target.value)} className="mt-1">
            {status.languages.map((l) => (
              <option key={l.code} value={l.code}>
                {l.code === 'en' ? l.name : `${l.name} · ${l.nativeName}`}
              </option>
            ))}
          </Select>
        </div>
        <div>
          <label htmlFor={`${ids}-brief`} className="text-sm font-medium">
            Anything to add? <span className="font-normal text-muted">(optional)</span>
          </label>
          <Input
            id={`${ids}-brief`}
            value={brief}
            maxLength={200}
            onChange={(e) => setBrief(e.target.value)}
            placeholder="Morning aarti from Varanasi"
            className="mt-1"
          />
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Button type="button" variant="primary" size="sm" busy={busy} disabled={atLimit && !busy} onClick={() => void write()}>
          {result?.draft ? 'Write again' : 'Write title and description'}
        </Button>
        <span className="tnum text-sm text-muted">
          {left} of {usage.limit} left today
        </span>
      </div>

      {result?.status === 'LIMIT' || (atLimit && !result) ? (
        <p role="status" className="rounded border border-line px-3 py-2 text-sm">
          {result?.message ?? `You've written ${usage.limit} drafts today, your plan's daily limit. You can write more tomorrow.`}
        </p>
      ) : null}
      {result?.status === 'UNAVAILABLE' && (
        <p role="status" className="text-sm text-muted">
          {result.message ?? "Writing titles isn't available right now."}
        </p>
      )}
      {result?.status === 'FALLBACK' && result.message && (
        <p role="status" className="rounded border border-warn/40 px-3 py-2 text-sm">
          {result.message}
        </p>
      )}

      {result?.draft && (result.status === 'OK' || result.status === 'FALLBACK') && (
        <div className="space-y-4">
          {titles.length > 0 && (
            <fieldset className="min-w-0 space-y-2">
              <legend className="text-sm font-medium">Titles</legend>
              {titles.map((t, i) => (
                <div key={i} className="space-y-1">
                  <div className="flex items-start gap-1">
                    {/* Two lines, so a long title is read in full on a phone. */}
                    <Textarea
                      aria-label={`Title option ${i + 1}`}
                      value={t}
                      rows={2}
                      maxLength={100}
                      onChange={(e) => setTitles((all) => all.map((x, j) => (j === i ? e.target.value.replace(/\n/g, ' ') : x)))}
                      className="min-h-0 min-w-0 flex-1 resize-none"
                    />
                    <CopyButton text={t} label={`title option ${i + 1}`} />
                  </div>
                  <div className="flex items-center justify-between gap-2 text-xs text-muted">
                    <span className="tnum">{t.length}/100</span>
                    {onUseTitle && (
                      <button type="button" className="text-sm text-accent hover:underline" onClick={() => onUseTitle(t)}>
                        Use this title
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </fieldset>
          )}

          <div className="space-y-1">
            <div className="flex items-center justify-between gap-2">
              <label htmlFor={`${ids}-desc`} className="text-sm font-medium">
                Description
              </label>
              <CopyButton text={description} label="description" />
            </div>
            <Textarea id={`${ids}-desc`} value={description} rows={8} maxLength={5000} onChange={(e) => setDescription(e.target.value)} />
          </div>

          <div className="space-y-1">
            <div className="flex items-center justify-between gap-2">
              <label htmlFor={`${ids}-tags`} className="text-sm font-medium">
                Tags
              </label>
              <CopyButton text={tags} label="tags" />
            </div>
            <Input id={`${ids}-tags`} value={tags} onChange={(e) => setTags(e.target.value)} />
            <p className="text-xs text-muted">Paste into Tags in YouTube Studio's video details.</p>
          </div>
        </div>
      )}
    </section>
  );
}
