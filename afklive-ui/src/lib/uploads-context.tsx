import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { keys } from './queries';
import { httpTransport, localStore, Upload, type Progress } from './upload';

export interface UploadEntry {
  key: string;
  file: File;
  progress: Progress;
  upload: Upload;
}

interface Ctx {
  entries: UploadEntry[];
  add: (files: FileList | File[]) => void;
  retry: (key: string) => void;
  cancel: (key: string) => void;
  clearFinished: () => void;
}

const UploadsContext = createContext<Ctx | null>(null);

/** Uploads live above the router so they keep going while the creator moves around the app. */
export function UploadsProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<UploadEntry[]>([]);
  const qc = useQueryClient();
  const seq = useRef(0);

  const start = useCallback(
    (key: string, file: File) => {
      const upload = new Upload(file, httpTransport, localStore);
      const entry: UploadEntry = { key, file, upload, progress: upload.progress };
      setEntries((es) => [...es.filter((e) => e.key !== key), entry]);
      upload.onChange((p) => {
        setEntries((es) => es.map((e) => (e.key === key ? { ...e, progress: p } : e)));
        if (p.state === 'done' || p.state === 'failed' || p.state === 'cancelled') {
          qc.invalidateQueries({ queryKey: ['media'] });
          qc.invalidateQueries({ queryKey: keys.storage });
        }
      });
      upload.run();
    },
    [qc],
  );

  const value = useMemo<Ctx>(
    () => ({
      entries,
      add: (files) => {
        Array.from(files).forEach((f) => start(`u${++seq.current}`, f));
        qc.invalidateQueries({ queryKey: keys.storage });
      },
      retry: (key) => {
        const e = entries.find((x) => x.key === key);
        if (e) start(key, e.file);
      },
      cancel: (key) => {
        entries.find((x) => x.key === key)?.upload.cancel();
        setEntries((es) => es.filter((x) => x.key !== key));
        setTimeout(() => qc.invalidateQueries({ queryKey: keys.storage }), 500);
      },
      clearFinished: () => setEntries((es) => es.filter((e) => e.progress.state !== 'done' && e.progress.state !== 'cancelled')),
    }),
    [entries, start, qc],
  );
  return <UploadsContext.Provider value={value}>{children}</UploadsContext.Provider>;
}

export function useUploads(): Ctx {
  const c = useContext(UploadsContext);
  if (!c) throw new Error('UploadsProvider missing');
  return c;
}
