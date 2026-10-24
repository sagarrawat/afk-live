/**
 * The worker stores what a run is playing as JSON ({"title": "...", "next": [...], ...}); older runs and
 * single-file streams store plain text. Returns the title to show, or null when there is nothing.
 */
export function nowPlayingTitle(raw: string | null | undefined): string | null {
  if (raw == null) return null;
  const text = raw.trim();
  if (!text) return null;
  if (text.startsWith('{')) {
    try {
      const v = JSON.parse(text) as { title?: unknown };
      return typeof v.title === 'string' && v.title.trim() ? v.title.trim() : null;
    } catch {
      return text;
    }
  }
  return text;
}
