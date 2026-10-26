import { createContext, useCallback, useContext, useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import { useBlocker } from 'react-router-dom';
import { Button, Dialog } from './ui';

/**
 * UX audit #9: edits that wait for a Save button (playlist order, overlays, scenes) are never lost
 * without a word. A screen reports its unsaved changes with {@link useUnsavedChanges}; one
 * {@link LeaveGuard} for the whole app asks before another page or tab replaces it, and the browser
 * asks before the page is closed or reloaded.
 */

type Register = (id: string, what: string | null) => void;

const UnsavedContext = createContext<{ register: Register; pending: string[] } | null>(null);

export function UnsavedChangesProvider({ children }: { children: ReactNode }) {
  const [byId, setById] = useState<Record<string, string>>({});
  const register = useCallback<Register>((id, what) => {
    setById((m) => {
      if (what === null) {
        if (!(id in m)) return m;
        const next = { ...m };
        delete next[id];
        return next;
      }
      return m[id] === what ? m : { ...m, [id]: what };
    });
  }, []);
  const value = useMemo(() => ({ register, pending: Object.values(byId) }), [register, byId]);
  return <UnsavedContext.Provider value={value}>{children}</UnsavedContext.Provider>;
}

/** Marks this screen as having unsaved changes to {@code what} (e.g. "the playlist order") while {@code dirty}. */
export function useUnsavedChanges(dirty: boolean, what: string) {
  const ctx = useContext(UnsavedContext);
  const id = useId();
  const register = ctx?.register;
  useEffect(() => {
    if (!register) return;
    register(id, dirty ? what : null);
    return () => register(id, null);
  }, [register, id, dirty, what]);
}

/** "a, b and c" */
export function listText(items: string[]): string {
  const unique = [...new Set(items)];
  if (unique.length <= 1) return unique[0] ?? '';
  return `${unique.slice(0, -1).join(', ')} and ${unique[unique.length - 1]}`;
}

/**
 * The Save and Undo buttons for edits that wait for Save, shown only while there's something to save.
 * Sticks to the bottom of the screen (above the phone nav), so it's in reach wherever the edit was made.
 */
export function SaveBar({ dirty, busy, what, onSave, onUndo }: { dirty: boolean; busy?: boolean; what: string; onSave: () => void; onUndo: () => void }) {
  if (!dirty) return null;
  return (
    <div
      role="region"
      aria-label="Unsaved changes"
      className="sticky bottom-[calc(4.5rem+env(safe-area-inset-bottom))] z-20 flex flex-wrap items-center justify-between gap-3 rounded-md border border-accent/50 bg-panel px-4 py-3 shadow-xl md:bottom-4"
    >
      <p className="text-sm">
        <span className="font-medium">Unsaved changes</span> <span className="text-muted">to {what}</span>
      </p>
      <div className="flex gap-2">
        <Button size="sm" variant="ghost" onClick={onUndo} disabled={busy}>
          Undo
        </Button>
        <Button size="sm" variant="primary" busy={busy} onClick={onSave}>
          Save changes
        </Button>
      </div>
    </div>
  );
}

/** Asks before leaving a page or tab with unsaved changes. Render once, inside a data router. */
export function LeaveGuard() {
  const ctx = useContext(UnsavedContext);
  const pending = ctx?.pending ?? [];
  const dirty = pending.length > 0;
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname);

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  // Saved (or undone) while the question was open: nothing to lose any more.
  useEffect(() => {
    if (blocker.state === 'blocked' && !dirty) blocker.proceed();
  }, [blocker, dirty]);

  if (blocker.state !== 'blocked') return null;
  return (
    <Dialog
      open
      onClose={() => blocker.reset()}
      title="Leave without saving?"
      footer={
        <>
          <Button variant="ghost" onClick={() => blocker.proceed()}>
            Leave without saving
          </Button>
          <Button variant="primary" onClick={() => blocker.reset()}>
            Keep editing
          </Button>
        </>
      }
    >
      <p>You have unsaved changes to {listText(pending)}. They&apos;re lost if you leave now.</p>
    </Dialog>
  );
}
