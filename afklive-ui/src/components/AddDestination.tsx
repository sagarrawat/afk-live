import { useState } from 'react';
import { post } from '@/lib/api';
import { keys, useInvalidate } from '@/lib/queries';
import type { Destination } from '@/lib/types';
import clsx from 'clsx';
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

const HELP: Record<Platform, string> = {
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

/** The address a destination is saved as: a full URL with the key at the end. */
export function destinationUrl(platform: Platform, key: string, server = ''): string {
  const k = key.trim();
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
  const submit = async () => {
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
          <Button variant="primary" busy={busy} disabled={!key.trim() || (twoFields && !/^(rtmps?|srt):\/\//i.test(server.trim()))} onClick={submit}>
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
        <p className="text-sm text-muted">{HELP[platform]}</p>
        {twoFields && (
          <Field label="Stream URL" htmlFor="dest-server">
            <Input id="dest-server" value={server} autoComplete="off" spellCheck={false} placeholder="rtmps://…" onChange={(e) => setServer(e.target.value)} />
          </Field>
        )}
        <Field label={platform === 'CUSTOM' ? 'Stream address' : 'Stream key'} htmlFor="dest-key">
          <Input
            id="dest-key"
            value={key}
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
