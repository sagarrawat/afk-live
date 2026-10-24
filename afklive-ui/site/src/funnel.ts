/**
 * Homepage measurement (SiteFunnelService): a "view" when the homepage opens and a "start" when a
 * "Start free" link is followed. The beacon carries only the step's name: no id, no cookie, nothing
 * stored in the browser. Automated browsers say so (navigator.webdriver) and aren't counted.
 */
export type Step = 'view' | 'start';

export function shouldCount(nav: Pick<Navigator, 'webdriver'> = navigator): boolean {
  return !nav.webdriver;
}

export function send(step: Step, nav: Navigator = navigator): boolean {
  if (!shouldCount(nav)) return false;
  try {
    if (typeof nav.sendBeacon === 'function') return nav.sendBeacon('/api/public/funnel', new Blob([step], { type: 'text/plain' }));
    void fetch('/api/public/funnel', { method: 'POST', body: step, headers: { 'Content-Type': 'text/plain' }, keepalive: true });
    return true;
  } catch {
    return false;
  }
}

/** On a page marked [data-funnel-view]: count the view, and clicks on [data-funnel="start"] links. */
export function mountFunnel(doc: Document = document, nav: Navigator = navigator) {
  if (!doc.querySelector('[data-funnel-view]')) return;
  send('view', nav);
  doc.addEventListener('click', (e) => {
    const a = (e.target as Element | null)?.closest?.('[data-funnel="start"]');
    if (a) send('start', nav);
  });
}
