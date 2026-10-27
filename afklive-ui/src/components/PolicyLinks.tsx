import clsx from 'clsx';

/** The public site's policy pages, linked from Billing and Help (they open in a new tab). */
export const POLICIES: [string, string][] = [
  ['/terms', 'Terms'],
  ['/privacy', 'Privacy'],
  ['/refund-policy', 'Refund policy'],
  ['/contact', 'Contact'],
];

export function PolicyLinks({ className }: { className?: string }) {
  return (
    <nav aria-label="Policies" className={clsx('flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted', className)}>
      {POLICIES.map(([href, label]) => (
        <a key={href} href={href} target="_blank" rel="noreferrer" className="hover:text-ink hover:underline">
          {label}
        </a>
      ))}
    </nav>
  );
}
