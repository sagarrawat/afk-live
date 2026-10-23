import clsx from 'clsx';
import type { ReactNode } from 'react';
import type { HelpArticle } from '@/lib/help';

/** A help article from an in-app message. Opens in a new tab so the creator keeps their place. */
export function HelpLink({ to, children, className }: { to: HelpArticle; children: ReactNode; className?: string }) {
  return (
    <a href={to} target="_blank" rel="noreferrer" className={clsx('text-accent hover:underline', className)}>
      {children}
    </a>
  );
}
