import { useQuery, useQueryClient } from '@tanstack/react-query';
import { LayoutTemplate } from 'lucide-react';
import { useState } from 'react';
import { isOnAir } from '@/components/status';
import { Button, Dialog, Segmented, useAction } from '@/components/ui';
import { get, post } from '@/lib/api';
import { keys } from '@/lib/queries';
import type { StreamView } from '@/lib/types';

/** GR-8: a niche template (TemplateService.TemplateView). */
export interface Template {
  id: string;
  name: string;
  description: string;
  source: 'PLAYLIST' | 'PHONE' | 'ANY';
  languages: string[];
  schedules: { id: string; name: string; description: string; slots: { start: string; end: string }[] }[];
}

export interface Applied {
  template: string;
  layers: number;
  schedulesAdded: number;
}

const LANGUAGE: Record<string, string> = { en: 'English', hi: 'हिन्दी' };

export const templatesKey = ['templates'] as const;

/** Templates that fit this stream (playlist ones for playlists, the phone one for phone streams). */
export function templatesFor(all: Template[], source: StreamView['source']): Template[] {
  return all.filter((t) => t.source === 'ANY' || t.source === source);
}

/** "5:30 am to 7 am and 6:30 pm to 8 pm": a preset's slots in plain words. */
export function slotsText(slots: { start: string; end: string }[]): string {
  const t = (hhmm: string) => {
    const [h, m] = hhmm.split(':').map(Number);
    const h12 = h % 12 === 0 ? 12 : h % 12;
    return `${h12}${m ? `:${String(m).padStart(2, '0')}` : ''} ${h < 12 ? 'am' : 'pm'}`;
  };
  return slots.map((s) => `${t(s.start)} to ${t(s.end)}`).join(' and ');
}

/** The "Start from a template" button and dialog on the overlays tab. */
export function TemplatePicker({ stream: s, disabled }: { stream: StreamView; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        size="sm"
        variant="ghost"
        icon={<LayoutTemplate className="h-4 w-4" />}
        disabled={disabled}
        title={disabled ? 'Save or undo your changes first' : undefined}
        onClick={() => setOpen(true)}
      >
        Start from a template
      </Button>
      {open && <TemplateDialog stream={s} onClose={() => setOpen(false)} />}
    </>
  );
}

function TemplateDialog({ stream: s, onClose }: { stream: StreamView; onClose: () => void }) {
  const { data } = useQuery({ queryKey: templatesKey, queryFn: () => get<Template[]>('/api/templates'), staleTime: Infinity });
  const choices = templatesFor(data ?? [], s.source);
  const [picked, setPicked] = useState<string | null>(null);
  const [language, setLanguage] = useState('en');
  const [preset, setPreset] = useState<string>('');
  const { busy, run } = useAction();
  const qc = useQueryClient();
  const template = choices.find((t) => t.id === picked) ?? null;
  const onAir = isOnAir(s.live.state);

  const apply = () =>
    run(async () => {
      const done = await post<Applied>(`/api/streams/${s.id}/template`, { template: picked, language, schedulePreset: preset || null });
      await qc.invalidateQueries({ queryKey: keys.stream(s.id) });
      onClose();
      return done;
    }, onAir ? 'Template applied. Viewers see it within a second.' : 'Template applied.');

  return (
    <Dialog
      open
      onClose={onClose}
      title="Start from a template"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} disabled={!template} onClick={apply}>
            Use this template
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted">
        A template adds its own layers and colours. Your own overlays stay; layers from an earlier template are replaced. You can change everything after.
      </p>
      <ul className="mt-4 space-y-2" role="radiogroup" aria-label="Templates">
        {choices.map((t) => (
          <li key={t.id}>
            <button
              type="button"
              role="radio"
              aria-checked={picked === t.id}
              onClick={() => {
                setPicked(t.id);
                setPreset('');
              }}
              className={`w-full rounded border px-3 py-2 text-left ${picked === t.id ? 'border-accent bg-accent/10' : 'border-line hover:border-muted/60'}`}
            >
              <span className="block font-medium">{t.name}</span>
              <span className="block text-sm text-muted">{t.description}</span>
            </button>
          </li>
        ))}
      </ul>
      {template && (
        <div className="mt-4 space-y-4">
          <div>
            <p className="mb-1.5 text-sm font-medium">Words on screen in</p>
            <Segmented label="Language" value={language} onChange={setLanguage} options={template.languages.map((l) => ({ value: l, label: LANGUAGE[l] ?? l }))} />
            <p className="mt-1 text-xs text-muted">You can retype them in any Indian language afterwards; the stream draws Tamil, Telugu, Bengali and more.</p>
          </div>
          {template.schedules.length > 0 && (
            <fieldset>
              <legend className="mb-1.5 text-sm font-medium">Go live on a schedule? (optional)</legend>
              <label className="flex items-start gap-2 py-1 text-sm">
                <input type="radio" name="preset" checked={preset === ''} onChange={() => setPreset('')} className="mt-1" />
                <span>No schedule: I'll go live myself, or keep it on 24/7</span>
              </label>
              {template.schedules.map((p) => (
                <label key={p.id} className="flex items-start gap-2 py-1 text-sm">
                  <input type="radio" name="preset" checked={preset === p.id} onChange={() => setPreset(p.id)} className="mt-1" />
                  <span>
                    <span className="font-medium">{p.name}</span>
                    <span className="block text-muted">
                      Every day, {slotsText(p.slots)} ({s.timeZone}). You can change the times on the Schedule tab.
                    </span>
                  </span>
                </label>
              ))}
            </fieldset>
          )}
        </div>
      )}
    </Dialog>
  );
}
