import { useState } from 'react';
import { post } from '@/lib/api';
import { keys, useInvalidate } from '@/lib/queries';
import type { Destination } from '@/lib/types';
import { Button, Dialog, Field, Input, Segmented, useAction } from './ui';

type Platform = 'YOUTUBE' | 'FACEBOOK' | 'TWITCH' | 'CUSTOM';

const SERVERS: Record<Exclude<Platform, 'CUSTOM'>, string> = {
  YOUTUBE: 'rtmp://a.rtmp.youtube.com/live2',
  FACEBOOK: 'rtmps://live-api-s.facebook.com:443/rtmp',
  TWITCH: 'rtmp://live.twitch.tv/app',
};

const HELP: Record<Platform, string> = {
  YOUTUBE: 'In YouTube Studio, choose Go live, then Stream. Copy the stream key there. No Google sign-in needed.',
  FACEBOOK: 'In Facebook Live Producer, choose Streaming software and copy the stream key.',
  TWITCH: 'In your Twitch dashboard, Settings > Stream, copy the primary stream key.',
  CUSTOM: 'The full address from the platform, starting with rtmp://, rtmps:// or srt://, including the key.',
};

/** The address a destination is saved as: a full URL with the key at the end. */
export function destinationUrl(platform: Platform, key: string): string {
  const k = key.trim();
  if (platform === 'CUSTOM') return k;
  return `${SERVERS[platform]}/${k}`;
}

/** DS-1 / DS-2: YouTube by stream key, other platforms by key, or any RTMP/SRT server. */
export function AddDestinationDialog({ open, onClose, onAdded }: { open: boolean; onClose: () => void; onAdded?: (d: Destination) => void }) {
  const [platform, setPlatform] = useState<Platform>('YOUTUBE');
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const { busy, run } = useAction();
  const invalidate = useInvalidate();
  const label = { YOUTUBE: 'YouTube', FACEBOOK: 'Facebook', TWITCH: 'Twitch', CUSTOM: 'Custom server' }[platform];
  const submit = async () => {
    let saved: Destination | undefined;
    const ok = await run(async () => {
      saved = await post<Destination>('/api/destinations', { name: name.trim() || label, key: destinationUrl(platform, key), type: 'RTMP', selected: true });
    }, `${name.trim() || label} added.`);
    if (ok && saved) {
      await invalidate([...keys.destinations]);
      onAdded?.(saved);
      setKey('');
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
          <Button variant="primary" busy={busy} disabled={!key.trim()} onClick={submit}>
            Add destination
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <Segmented
          label="Platform"
          value={platform}
          onChange={setPlatform}
          options={[
            { value: 'YOUTUBE', label: 'YouTube' },
            { value: 'FACEBOOK', label: 'Facebook' },
            { value: 'TWITCH', label: 'Twitch' },
            { value: 'CUSTOM', label: 'Custom' },
          ]}
        />
        <p className="text-sm text-muted">{HELP[platform]}</p>
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
