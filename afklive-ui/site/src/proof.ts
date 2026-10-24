import { loadStatus, type PublicStatus } from './status';

/**
 * The homepage's proof line and reliability note, from the public status data. Real numbers only:
 * the server leaves a number out while it's small, and the page checks again, so nothing like
 * "1 stream live" or "3 hours streamed" ever shows. With no data the line stays hidden.
 */
export const MIN_LIVE = 10;
export const MIN_HOURS = 1000;

export function proofLine(p: PublicStatus['proof'] | undefined | null): string | null {
  if (!p) return null;
  const live = typeof p.liveNow === 'number' && p.liveNow >= MIN_LIVE ? p.liveNow : null;
  const hours = typeof p.hoursStreamed === 'number' && p.hoursStreamed >= MIN_HOURS ? p.hoursStreamed : null;
  const n = (v: number) => v.toLocaleString('en-IN');
  if (live && hours) return `${n(live)} streams live on AFK Live right now. ${n(hours)}+ hours streamed so far.`;
  if (live) return `${n(live)} streams live on AFK Live right now.`;
  if (hours) return `${n(hours)}+ hours streamed on AFK Live so far.`;
  return null;
}

/** Platform incidents (not one creator's stream) in the window the status page keeps. */
export function incidentNote(s: PublicStatus): string {
  const n = s.incidents.length;
  if (n === 0) return 'No platform incidents in the last 90 days.';
  return `${n} platform incident${n === 1 ? '' : 's'} in the last 90 days, each written up on the status page.`;
}

/** Fills [data-proof] and [data-incidents] from one status call. Both stay hidden if it fails. */
export async function mountProof(load = loadStatus) {
  const line = document.querySelector<HTMLElement>('[data-proof]');
  const note = document.querySelector<HTMLElement>('[data-incidents]');
  try {
    const s = await load();
    const text = proofLine(s.proof);
    if (line && text) {
      line.querySelector<HTMLElement>('[data-proof-text]')!.textContent = text;
      line.hidden = false;
    }
    if (note) {
      note.querySelector<HTMLElement>('[data-incidents-text]')!.textContent = incidentNote(s);
      note.hidden = false;
    }
  } catch {
    /* offline or down: no numbers rather than made-up ones */
  }
}
