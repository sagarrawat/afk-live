import { Activity, ChevronRight, LifeBuoy } from 'lucide-react';
import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { ContactSupportDialog } from '@/components/ContactSupport';
import { Button, PageHeader, Panel } from '@/components/ui';
import { HELP, type HelpArticle } from '@/lib/help';
import { PolicyLinks } from '@/components/PolicyLinks';

/** MK-4: the help centre's articles (public, at /help), first, because they answer the most tickets. */
const ARTICLES: [HelpArticle, string, string][] = [
  [HELP.streamKey, 'Where to find your YouTube stream key', 'Step by step in YouTube Studio, and the two usual mix-ups.'],
  [HELP.preparing, 'YouTube says "Preparing"', 'What\'s normal, Auto-start and Auto-stop for 24/7, and what to do if it\'s stuck.'],
  [HELP.notOnYouTube, 'Live here but not on YouTube', 'Wrong key, a key from a scheduled live, Auto-start off: check these in order.'],
  [HELP.shape, 'Vertical or landscape?', 'Which shape to pick, what YouTube does with vertical lives, and mixed playlists.'],
  [HELP.phone, 'Go live from your phone', 'Larix and PRISM set up with your stream\'s own address.'],
];

const FAQ: [string, string][] = [
  ['What happens if my phone loses signal?', 'Viewers see your Be right back scene with music, and the stream stays up. When your phone reconnects, it switches back by itself.'],
  ['Why is my upload "Checking"?', 'Every file is checked after upload so it plays without problems. It usually takes under a minute. If it says "Can\'t play", the reason is shown on the file.'],
  ['Can I change the playlist while live?', 'Yes. Changes apply after the video that\'s playing, so the stream never drops.'],
  ['How do renewals work?', 'We remind you 3 days before your plan ends; renewing is one UPI or card payment. If you miss it, you get 3 days of grace before moving to Free. Nothing is deleted.'],
];

export function Help() {
  // /studio/help?contact opens the form straight away (the public /contact page links here).
  const { search } = useLocation();
  const [support, setSupport] = useState(() => new URLSearchParams(search).has('contact'));
  return (
    <>
      <PageHeader
        title="Help"
        description="Answers to the common questions. Stuck? Send us a message and we reply within a day."
        actions={
          <Button variant="primary" icon={<LifeBuoy className="h-4 w-4" />} onClick={() => setSupport(true)}>
            Contact support
          </Button>
        }
      />
      <ContactSupportDialog open={support} onClose={() => setSupport(false)} />
      <Panel title="Guides" pad={false} className="mb-4">
        <ul className="divide-y divide-line">
          {ARTICLES.map(([to, title, blurb]) => (
            <li key={to}>
              <a href={to} target="_blank" rel="noreferrer" className="flex items-center gap-3 px-4 py-3 hover:bg-raised">
                <span className="min-w-0 flex-1">
                  <span className="block font-medium">{title}</span>
                  <span className="block text-sm text-muted">{blurb}</span>
                </span>
                <ChevronRight className="h-4 w-4 shrink-0 text-muted" aria-hidden />
              </a>
            </li>
          ))}
        </ul>
        <p className="border-t border-line px-4 py-3 text-sm">
          <a href="/help" target="_blank" rel="noreferrer" className="text-accent hover:underline">
            Search all help
          </a>
        </p>
      </Panel>
      {/* RE-7: is it just me, or AFK Live? */}
      <a href="/status" className="mb-4 flex items-center gap-3 rounded-md border border-line bg-panel px-4 py-3 hover:border-muted/60">
        <Activity className="h-4 w-4 shrink-0 text-muted" aria-hidden />
        <span className="min-w-0 flex-1">
          <span className="block font-medium">Service status</span>
          <span className="block text-sm text-muted">Check whether AFK Live is working right now, and see past incidents.</span>
        </span>
      </a>
      <Panel>
        <dl className="divide-y divide-line">
          {FAQ.map(([q, a]) => (
            <div key={q} className="py-4 first:pt-0 last:pb-0">
              <dt className="font-medium">{q}</dt>
              <dd className="mt-1 max-w-prose text-muted">{a}</dd>
            </div>
          ))}
        </dl>
      </Panel>
      <PolicyLinks className="mt-6" />
    </>
  );
}
