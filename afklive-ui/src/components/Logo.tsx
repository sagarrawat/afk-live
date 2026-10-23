import { DOT, MOON, WORD, WORD_WIDTH } from './brand-paths';

/** The AFK Live mark: a crescent moon holding an on-air dot (docs/brand.md). */
export function Mark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={className} aria-hidden="true">
      <path d={MOON} fill="rgb(var(--sun))" />
      <circle {...DOT} fill="rgb(var(--live))" />
    </svg>
  );
}

/** Mark and wordmark; the wordmark takes the text colour. */
export function Logo({ className }: { className?: string }) {
  const cap = 26;
  const width = 64 + 14 + WORD_WIDTH * cap;
  return (
    <svg viewBox={`0 0 ${width.toFixed(2)} 64`} className={className} role="img" aria-label="AFK Live">
      <path d={MOON} fill="rgb(var(--sun))" />
      <circle {...DOT} fill="rgb(var(--live))" />
      <path d={WORD} fill="currentColor" transform={`translate(78 ${32 + cap / 2}) scale(${cap})`} />
    </svg>
  );
}
