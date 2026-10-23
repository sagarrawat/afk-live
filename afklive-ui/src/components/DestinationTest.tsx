import { CheckCircle2, CircleAlert, Loader2 } from 'lucide-react';
import { useCallback, useState } from 'react';
import { get, post } from '@/lib/api';
import { HELP, helpForProblem } from '@/lib/help';
import type { Destination } from '@/lib/types';
import { PLATFORM_NAMES, type Platform } from './AddDestination';
import { HelpLink } from './HelpLink';

export interface TestResult {
  testId: number;
  status: string;
  ok: boolean;
  message: string;
}

export type TestState = TestResult | 'running';

/** How often and how many times a running test is polled; tests shorten the wait. */
export const testPolling = { everyMs: 2000, attempts: 30 };

/**
 * MS-6: runs the connection test for destinations (a worker sends 10 s of a test picture) and keeps
 * each one's latest result. Used by the Send a test button, and right after a key is saved so a
 * wrong key shows up now rather than hours into a stream that reaches nobody.
 */
export function useDestinationTests() {
  const [tests, setTests] = useState<Record<number, TestState>>({});
  const test = useCallback(async (d: Pick<Destination, 'id'>) => {
    const set = (r: TestState) => setTests((t) => ({ ...t, [d.id]: r }));
    set('running');
    try {
      const started = await post<{ testId: number }>(`/api/destinations/${d.id}/test`);
      for (let i = 0; i < testPolling.attempts; i++) {
        await new Promise((r) => setTimeout(r, testPolling.everyMs));
        const r = await get<TestResult>(`/api/destinations/tests/${started.testId}`);
        if (r.status !== 'PENDING' && r.status !== 'RUNNING') {
          set(r);
          return;
        }
      }
      set({ testId: 0, status: 'TIMEOUT', ok: false, message: 'The test is taking too long. Try again in a minute.' });
    } catch (e) {
      set({ testId: 0, status: 'ERROR', ok: false, message: e instanceof Error ? e.message : String(e) });
    }
  }, []);
  return { tests, test };
}

/** A destination's test, in words: checking, connected, or what to fix. */
export function TestNote({ state, platform, className = 'mt-2' }: { state?: TestState; platform: Platform; className?: string }) {
  if (!state) return null;
  const where = platform === 'CUSTOM' ? 'the server' : PLATFORM_NAMES[platform];
  if (state === 'running') {
    return (
      <p role="status" className={`${className} flex items-start gap-2 text-sm text-muted`}>
        <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin" aria-hidden />
        Checking the connection to {where}. This takes about 15 seconds.
      </p>
    );
  }
  return (
    <p role="status" className={`${className} flex items-start gap-2 text-sm ${state.ok ? 'text-ok' : 'text-warn'}`}>
      {state.ok ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" aria-hidden /> : <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />}
      <span>
        {state.ok ? `Connected to ${where}. The stream key works.` : state.message}
        {!state.ok && <TestHelp message={state.message} platform={platform} />}
      </span>
    </p>
  );
}

/** MK-4: a failed test links to the article that fixes it. */
function TestHelp({ message, platform }: { message: string; platform: Platform }) {
  const to = helpForProblem(message, platform);
  if (!to) return null;
  return (
    <>
      {' '}
      <HelpLink to={to}>{to === HELP.streamKey ? 'Where to find your YouTube stream key' : 'What to check'}</HelpLink>
    </>
  );
}
