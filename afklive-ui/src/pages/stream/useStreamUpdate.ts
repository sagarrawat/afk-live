import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { useAction } from '@/components/ui';
import { keys } from '@/lib/queries';
import type { StreamView } from '@/lib/types';

/** Runs a change to a stream; a returned StreamView replaces the cached one, otherwise it refetches. */
export function useStreamUpdate(id: number) {
  const qc = useQueryClient();
  const { busy, run } = useAction();
  const update = useCallback(
    (fn: () => Promise<unknown>, success?: string) =>
      run(async () => {
        const result = await fn();
        if (result && typeof result === 'object' && 'id' in result && 'live' in result) {
          qc.setQueryData(keys.stream(id), result as StreamView);
        } else {
          await qc.invalidateQueries({ queryKey: keys.stream(id) });
        }
        qc.invalidateQueries({ queryKey: keys.streams });
      }, success),
    [id, qc, run],
  );
  return { busy, update };
}
