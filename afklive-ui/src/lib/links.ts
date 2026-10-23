/**
 * Notification and pre-flight links come from the server as short paths ("/streams/5",
 * "/settings#billing", "/pricing", or a tab name like "playlist"). This maps them to app routes.
 */
export function appLink(link: string | null | undefined, streamId?: number | null): string | null {
  if (!link) return null;
  if (link.startsWith('http://') || link.startsWith('https://')) return link;
  if (link.startsWith('/studio')) return link;
  if (link === '/pricing' || link.startsWith('/settings#billing') || link === '/billing') return '/studio/settings/billing';
  if (link.startsWith('/settings')) return '/studio/settings';
  if (link.startsWith('/streams/')) return '/studio' + link;
  if (link === '/library') return '/studio/library';
  if (link === '/support') return '/studio/help';
  if (link.startsWith('/')) return '/studio' + link;
  // A bare tab name from a pre-flight check.
  if (streamId != null) {
    const tab = link === 'destinations' ? 'destinations' : link === 'music' ? 'audio' : link;
    return `/studio/streams/${streamId}/${tab}`;
  }
  return null;
}
