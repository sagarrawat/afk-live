/**
 * MK-4: the public help articles (afklive-ui/site/help.py, served at /help/<slug>). In-app messages
 * link here; site/src/help-pages.test.ts checks every one exists, is in the sitemap and is used.
 */
export const HELP = {
  streamKey: '/help/youtube-stream-key',
  preparing: '/help/youtube-preparing',
  notOnYouTube: '/help/live-here-not-on-youtube',
  shape: '/help/vertical-or-landscape',
  phone: '/help/phone-streaming',
  loops: '/help/loops-and-youtube-rules',
  titles: '/help/titles-and-thumbnails',
  music: '/help/music-on-your-live',
} as const;

export type HelpArticle = (typeof HELP)[keyof typeof HELP];

/** YouTube Studio's live dashboard ("UC" opens the signed-in channel's own). */
export const YOUTUBE_STUDIO_LIVE = 'https://studio.youtube.com/channel/UC/livestreaming';

/**
 * The article for a problem message about a destination (a failed test, a destination's error while
 * live), or null when none fits. Key problems go to where to find the key; anything else on YouTube
 * goes to the "live here but not on YouTube" checklist.
 */
export function helpForProblem(message: string | null | undefined, platform?: string | null): HelpArticle | null {
  const m = (message ?? '').toLowerCase();
  // Our side or a passing hiccup: nothing for the creator to fix on YouTube.
  if (!m || /our side|try again|interrupted|too long|live right now/.test(m)) return null;
  if (/key|refused|unauthori[sz]ed|forbidden/.test(m)) return platform && platform !== 'YOUTUBE' ? null : HELP.streamKey;
  if (!platform || platform === 'YOUTUBE') return HELP.notOnYouTube;
  return null;
}
