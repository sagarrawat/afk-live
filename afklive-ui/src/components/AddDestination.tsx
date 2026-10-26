import { useState } from 'react';
import { post, put } from '@/lib/api';
import { keys, useInvalidate } from '@/lib/queries';
import type { Destination } from '@/lib/types';
import clsx from 'clsx';
import { HELP } from '@/lib/help';
import { HelpLink } from './HelpLink';
import { PlatformIcon } from './PlatformIcon';
import { Button, Dialog, Field, Input, useAction } from './ui';

export type Platform = 'YOUTUBE' | 'FACEBOOK' | 'TWITCH' | 'KICK' | 'INSTAGRAM' | 'TIKTOK' | 'RUMBLE' | 'CUSTOM';

/** Platforms with a fixed server: the creator only pastes the key. */
const SERVERS: Partial<Record<Platform, string>> = {
  YOUTUBE: 'rtmp://a.rtmp.youtube.com/live2',
  FACEBOOK: 'rtmps://live-api-s.facebook.com:443/rtmp',
  TWITCH: 'rtmp://live.twitch.tv/app',
};

export const PLATFORM_NAMES: Record<Platform, string> = {
  YOUTUBE: 'YouTube',
  FACEBOOK: 'Facebook',
  TWITCH: 'Twitch',
  KICK: 'Kick',
  INSTAGRAM: 'Instagram',
  TIKTOK: 'TikTok',
  RUMBLE: 'Rumble',
  CUSTOM: 'Other',
};

const WHERE: Record<Platform, string> = {
  YOUTUBE: 'In YouTube Studio, choose Go live, then Stream. Copy the stream key there. No Google sign-in needed.',
  FACEBOOK: 'In Facebook Live Producer, choose Streaming software and copy the stream key.',
  TWITCH: 'In your Twitch dashboard, Settings > Stream, copy the primary stream key.',
  KICK: 'In Kick, open your creator dashboard, then Settings > Stream URL & Key. Copy both.',
  INSTAGRAM: 'On instagram.com, choose Create > Live video and copy the stream URL and key. Instagram gives new ones each time you go live.',
  TIKTOK: 'In TikTok LIVE Producer (your account needs LIVE access), copy the server URL and stream key.',
  RUMBLE: 'In Rumble, Go live > Live stream settings, copy the stream URL and stream key.',
  CUSTOM: 'The full address from the platform, starting with rtmp://, rtmps:// or srt://, including the key.',
};

/** Platforms where the server address changes per account: two fields. */
export function needsServer(platform: Platform): boolean {
  return platform !== 'CUSTOM' && !SERVERS[platform];
}

/** A full address for the same platform, server and key together: the key is the captured group. */
const FULL_ADDRESS: Partial<Record<Platform, RegExp>> = {
  YOUTUBE: /^rtmps?:\/\/[a-z]\.rtmps?\.youtube\.com(?::\d+)?\/live2(?:\?backup=1)?\/(.*)$/i,
  FACEBOOK: /^rtmps?:\/\/live-api-s\.facebook\.com(?::\d+)?\/rtmp\/(.*)$/i,
  TWITCH: /^rtmps?:\/\/[a-z0-9.-]*twitch\.tv(?::\d+)?\/app\/(.*)$/i,
};

/** Where to find the key, for the "that's the server address" message. */
const WHERE_KEY: Partial<Record<Platform, string>> = {
  YOUTUBE: 'Copy the stream key from YouTube Studio (Go live → Stream → Stream key) instead.',
  FACEBOOK: 'Copy the stream key from Facebook Live Producer (Streaming software → Stream key) instead.',
  TWITCH: 'Copy the primary stream key from your Twitch dashboard (Settings → Stream) instead.',
};

/** Text that is a server address, or a piece of one, rather than a key. */
function looksLikeServer(k: string): boolean {
  return k.includes('://') || /[/\\]/.test(k) || /^([a-z0-9-]+\.)+[a-z]{2,}(:\d+)?$/i.test(k) || /^(live2|app|rtmps?)$/i.test(k);
}

/**
 * What the creator typed in the key box, read as a key: trimmed, taken out of a full address for the
 * same platform, or a plain-language problem when it can't be a key. (The server address pasted into
 * the key box once saved `rtmp://a.rtmp.youtube.com/live2/rtmp://a.rtmp.youtube.com/live2`.)
 */
export function readKey(platform: Platform, input: string, server = ''): { key: string; problem: string | null } {
  const k = input.trim();
  if (!k) return { key: '', problem: null };
  if (platform === 'CUSTOM') {
    const nested = k.indexOf('://') !== k.lastIndexOf('://');
    return { key: k, problem: nested ? 'This address has another address inside it. Paste the server address and the stream key only once.' : null };
  }
  const fixed = SERVERS[platform];
  if (!fixed) {
    // Two fields: the creator may have pasted server + key here; take the key off the end.
    const base = server.trim().replace(/\/+$/, '');
    const rest = base && k.toLowerCase().startsWith(base.toLowerCase() + '/') ? k.slice(base.length + 1).trim() : k;
    const bad = !rest || rest.includes('://');
    return { key: rest, problem: bad ? 'This looks like the server address. Paste it in Stream URL above, and only the stream key here.' : null };
  }
  const serverProblem = `This looks like the server address. ${WHERE_KEY[platform] ?? 'Copy the stream key instead.'}`;
  const full = FULL_ADDRESS[platform]?.exec(k);
  const key = full ? full[1].trim() : k;
  return { key, problem: !key || looksLikeServer(key) ? serverProblem : null };
}

/** The address a destination is saved as: a full URL with the key at the end. */
export function destinationUrl(platform: Platform, key: string, server = ''): string {
  const k = readKey(platform, key, server).key;
  if (platform === 'CUSTOM') return k;
  const base = (SERVERS[platform] ?? server.trim()).replace(/\/+$/, '');
  return `${base}/${k}`;
}

/** DS-1 / DS-2: YouTube by stream key, other platforms by key, or any RTMP/SRT server. */
export function AddDestinationDialog({ open, onClose, onAdded }: { open: boolean; onClose: () => void; onAdded?: (d: Destination) => void }) {
  const [platform, setPlatform] = useState<Platform>('YOUTUBE');
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [server, setServer] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const label = platform === 'CUSTOM' ? 'Custom server' : PLATFORM_NAMES[platform];
  const twoFields = needsServer(platform);
  const { problem } = readKey(platform, key, server);
  const submit = async () => {
    if (problem) return;
    let saved: Destination | undefined;
    const ok = await run(async () => {
      saved = await post<Destination>('/api/destinations', { name: name.trim() || label, key: destinationUrl(platform, key, server), type: 'RTMP', selected: true });
    }, `${name.trim() || label} added.`);
    if (ok && saved) {
      await invalidate([...keys.destinations]);
      onAdded?.(saved);
      setKey('');
      setServer('');
      setName('');
      onClose();
    }
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Add a destination"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} disabled={!key.trim() || !!problem || (twoFields && !/^(rtmps?|srt):\/\//i.test(server.trim()))} onClick={submit}>
            Add destination
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <div role="radiogroup" aria-label="Platform" className="grid grid-cols-4 gap-2">
          {(Object.keys(PLATFORM_NAMES) as Platform[]).map((p) => (
            <button
              key={p}
              type="button"
              role="radio"
              aria-checked={platform === p}
              onClick={() => setPlatform(p)}
              className={clsx(
                'flex h-16 flex-col items-center justify-center gap-1 rounded-md border text-xs',
                platform === p ? 'border-accent bg-accent/10 font-medium text-ink' : 'border-line text-muted hover:text-ink',
              )}
            >
              <PlatformIcon platform={p} className="h-5 w-5" />
              {PLATFORM_NAMES[p]}
            </button>
          ))}
        </div>
        <p className="text-sm text-muted">
          {WHERE[platform]}
          {platform === 'YOUTUBE' && (
            <>
              {' '}
              <HelpLink to={HELP.streamKey}>Step by step</HelpLink>
            </>
          )}
        </p>
        {twoFields && (
          <Field label="Stream URL" htmlFor="dest-server">
            <Input id="dest-server" value={server} autoComplete="off" spellCheck={false} placeholder="rtmps://…" onChange={(e) => setServer(e.target.value)} />
          </Field>
        )}
        <Field label={platform === 'CUSTOM' ? 'Stream address' : 'Stream key'} htmlFor="dest-key" error={problem}>
          <Input
            id="dest-key"
            value={key}
            aria-invalid={!!problem}
            autoComplete="off"
            spellCheck={false}
            placeholder={platform === 'CUSTOM' ? 'rtmp://live.example.com/app/your-key' : 'xxxx-xxxx-xxxx-xxxx-xxxx'}
            onChange={(e) => setKey(e.target.value)}
          />
        </Field>
        <Field label="Name" htmlFor="dest-name" hint="So you can tell your channels apart.">
          <Input id="dest-name" value={name} placeholder={`My ${label} channel`} onChange={(e) => setName(e.target.value)} />
        </Field>
      </div>
    </Dialog>
  );
}

/**
 * DS-8: change a saved destination's name, server or stream key. The saved key never reaches the
 * browser, so the key box starts empty and stays write-only: left empty, the saved key is kept. The
 * platform can't change (add a new destination for another platform).
 */
export function EditDestinationDialog({
  destination: d,
  onClose,
  onSaved,
}: {
  destination: Destination;
  onClose: () => void;
  /** keyChanged: the address changed, so it should be tested again */
  onSaved?: (d: Destination, keyChanged: boolean) => void;
}) {
  const platform = (d.platform in PLATFORM_NAMES ? d.platform : 'CUSTOM') as Platform;
  const [name, setName] = useState(d.name);
  const [key, setKey] = useState('');
  const [server, setServer] = useState(needsServer(platform) ? d.server : '');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const twoFields = needsServer(platform);
  const { problem } = readKey(platform, key, server);
  const serverChanged = twoFields && server.trim().replace(/\/+$/, '') !== d.server.replace(/\/+$/, '');
  const needKey = serverChanged && !key.trim() ? 'Paste the stream key again when you change the stream URL.' : null;
  const badServer = twoFields && !/^(rtmps?|srt):\/\//i.test(server.trim());
  const keyChanged = !!key.trim();
  const live = d.liveIn ?? [];
  const what = platform === 'CUSTOM' ? 'stream address' : 'stream key';
  const ending = d.keyHint.replace(/•/g, '');
  const submit = async () => {
    if (problem || needKey) return;
    let saved: Destination | undefined;
    const body: Record<string, string> = { name: name.trim() || d.name };
    if (keyChanged) body.key = destinationUrl(platform, key, server);
    const ok = await run(async () => {
      saved = await put<Destination>(`/api/destinations/${d.id}`, body);
    }, `${body.name} saved.`);
    if (ok && saved) {
      await invalidate([...keys.destinations], [...keys.streams]);
      onSaved?.(saved, keyChanged);
      onClose();
    }
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Edit ${d.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" busy={busy} disabled={!!problem || !!needKey || badServer} onClick={submit}>
            Save changes
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <p className="flex items-center gap-2 text-sm text-muted">
          <PlatformIcon platform={platform} className="h-5 w-5" />
          {platform === 'CUSTOM' ? 'Custom server' : PLATFORM_NAMES[platform]}
        </p>
        {live.length > 0 && (
          <p role="note" className="rounded-md border border-warn/40 bg-warn/5 px-3 py-2 text-sm">
            {live.join(', ')} {live.length === 1 ? 'is' : 'are'} live with this destination now. A new {what} applies the next time the stream goes live; the stream on air now keeps going as it is.
          </p>
        )}
        {twoFields && (
          <Field label="Stream URL" htmlFor="edit-server">
            <Input id="edit-server" value={server} autoComplete="off" spellCheck={false} placeholder="rtmps://…" onChange={(e) => setServer(e.target.value)} />
          </Field>
        )}
        <Field
          label={platform === 'CUSTOM' ? 'New stream address' : 'New stream key'}
          htmlFor="edit-key"
          error={problem ?? needKey}
          hint={`Leave empty to keep the saved one${ending ? ` (ending ${ending})` : ''}. ${WHERE[platform]}`}
        >
          <Input
            id="edit-key"
            value={key}
            aria-invalid={!!(problem ?? needKey)}
            autoComplete="off"
            spellCheck={false}
            placeholder={platform === 'CUSTOM' ? 'rtmp://live.example.com/app/your-key' : 'Paste the new key'}
            onChange={(e) => setKey(e.target.value)}
          />
        </Field>
        <Field label="Name" htmlFor="edit-name" hint="So you can tell your channels apart.">
          <Input id="edit-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
      </div>
    </Dialog>
  );
}
