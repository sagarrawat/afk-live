import { Facebook, Instagram, Radio, Twitch, Youtube } from 'lucide-react';
import type { ReactNode } from 'react';

/** Lucide's platform glyphs where it has them; a letter badge in the same stroke style otherwise. */
function Letter({ children, label, className }: { children: ReactNode; label: string; className: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} role="img" aria-label={label}>
      <rect x="3" y="3" width="18" height="18" rx="5" fill="none" stroke="currentColor" strokeWidth="2" />
      <text x="12" y="16.2" textAnchor="middle" fontSize="11" fontWeight="700" fill="currentColor" fontFamily="Inter Variable, system-ui, sans-serif">
        {children}
      </text>
    </svg>
  );
}

export function PlatformIcon({ platform, className = 'h-4 w-4' }: { platform: string; className?: string }) {
  switch (platform) {
    case 'YOUTUBE':
      return <Youtube className={className} aria-label="YouTube" />;
    case 'FACEBOOK':
      return <Facebook className={className} aria-label="Facebook" />;
    case 'INSTAGRAM':
      return <Instagram className={className} aria-label="Instagram" />;
    case 'TWITCH':
      return <Twitch className={className} aria-label="Twitch" />;
    case 'KICK':
      return <Letter label="Kick" className={className}>K</Letter>;
    case 'TIKTOK':
      return <Letter label="TikTok" className={className}>T</Letter>;
    case 'RUMBLE':
      return <Letter label="Rumble" className={className}>R</Letter>;
    default:
      return <Radio className={className} aria-label="Custom server" />;
  }
}
