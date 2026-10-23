import { Radio, Youtube, Facebook, Twitch } from 'lucide-react';
import type { Destination } from '@/lib/types';

export function PlatformIcon({ platform, className = 'h-4 w-4' }: { platform: Destination['platform'] | string; className?: string }) {
  if (platform === 'YOUTUBE') return <Youtube className={className} aria-label="YouTube" />;
  if (platform === 'FACEBOOK' || platform === 'INSTAGRAM') return <Facebook className={className} aria-label="Facebook" />;
  if (platform === 'TWITCH') return <Twitch className={className} aria-label="Twitch" />;
  return <Radio className={className} aria-label="Custom server" />;
}
