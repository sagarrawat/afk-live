import clsx from 'clsx';
import { BellRing } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui';
import { currentSubscription, enablePush, pushInfo, pushSupported } from '@/lib/push';

const DISMISSED = 'afk-push-prompt-dismissed';

function dismissedHere(): boolean {
  try {
    return localStorage.getItem(DISMISSED) === '1';
  } catch {
    return false;
  }
}

type State = 'hidden' | 'ask' | 'on';

/**
 * NT-1: invites the creator to turn on alerts on this device, where they'll see it (the bell menu, the
 * moment a first live is confirmed). Shows only when push is set up on the server, this browser can do
 * it, it isn't on here already, the browser hasn't blocked it, and the creator didn't say "Not now"
 * here. Permission is asked only when they tap "Turn on", never on its own.
 */
export function PushPrompt({ className, compact }: { className?: string; compact?: boolean }) {
  const [state, setState] = useState<State>('hidden');
  const [publicKey, setPublicKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    let live = true;
    if (!pushSupported() || dismissedHere() || Notification.permission === 'denied') return;
    Promise.all([pushInfo(), currentSubscription()])
      .then(([info, sub]) => {
        if (!live || !info.available || !info.publicKey || sub) return;
        setPublicKey(info.publicKey);
        setState('ask');
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  if (state === 'hidden') return null;
  if (state === 'on') {
    return (
      <p role="status" className={clsx('text-sm text-ok', className)}>
        Alerts are on for this device.
      </p>
    );
  }
  const dismiss = () => {
    try {
      localStorage.setItem(DISMISSED, '1');
    } catch {
      /* ignore */
    }
    setState('hidden');
  };
  const turnOn = async () => {
    setBusy(true);
    try {
      const granted = await enablePush(publicKey!);
      if (granted) setState('on');
      else setRefused(true);
    } catch {
      setRefused(true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className={clsx('rounded-md border border-accent/40 bg-accent/5', compact ? 'p-3' : 'p-4', className)}>
      <p className="flex items-center gap-2 font-medium">
        <BellRing className="h-4 w-4 shrink-0 text-accent" aria-hidden /> Get alerts on this device
      </p>
      <p className="mt-1 text-sm text-muted">
        {refused
          ? "Your browser didn't allow it. You can turn it on later in Settings, Notifications."
          : "If your stream stops or comes back, you'll know right away, even when AFK Live isn't open."}
      </p>
      {!refused && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button size="sm" variant="primary" busy={busy} onClick={turnOn}>
            Turn on
          </Button>
          <Button size="sm" variant="ghost" onClick={dismiss}>
            Not now
          </Button>
        </div>
      )}
    </div>
  );
}
