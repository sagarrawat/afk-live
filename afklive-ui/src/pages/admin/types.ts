import type { Level } from '@/lib/types';

/** RE-8, per destination, as the admin API sends it (no addresses or keys). */
export interface DestinationHealth {
  name: string;
  status?: string | null;
  level?: Level | null;
  error?: string | null;
  restarts: number;
  platform?: string | null;
  reach?: 'ON' | 'CONNECTING' | 'NOT_REACHING' | null;
  message?: string | null;
}

export interface LiveRow {
  jobId: number;
  streamId?: number | null;
  streamName: string;
  userEmail: string;
  userId: number;
  plan: string;
  worker?: string | null;
  heartbeatAgeSeconds?: number | null;
  status: string;
  health: Level;
  uptimeSeconds: number;
  nowPlaying?: string | null;
  inputLive?: boolean | null;
  restarts: number;
  bitrateKbps?: number | null;
  fps?: number | null;
  speed?: number | null;
  lastError?: string | null;
  destinations?: number;
  destinationHealth?: DestinationHealth[] | null;
  slowSince?: string | null;
  /** RE-10: what's wrong and the fix, e.g. running slower than real time. */
  problem?: string | null;
  /** The stream's chosen quality, e.g. "1080p 30 fps". */
  quality?: string | null;
}

export interface WorkerRow {
  worker: string;
  streams: number;
  lastHeartbeatAgeSeconds: number;
  healthy: boolean;
  capacity?: number | null;
  cpuPercent?: number | null;
  memoryUsedBytes?: number | null;
  memoryTotalBytes?: number | null;
  /** OPS-3: a new version is taking this worker's streams over. */
  draining?: boolean;
}

export interface Attention {
  kind: string;
  level: 'red' | 'amber';
  title: string;
  detail?: string | null;
  since?: string | null;
  jobId?: number | null;
  userId?: number | null;
  link?: string | null;
}

export interface ConsoleOverview {
  attention: Attention[];
  health: { green: number; amber: number; red: number };
  liveStreams: number;
  signUpsToday: number;
  firstGoLivesToday: number;
  workers: number;
}

export interface HistoryPoint {
  at: string;
  cpu?: number | null;
  cpuPeak?: number | null;
  memory?: number | null;
  streams: number;
}

export interface ServerHistory {
  worker: string;
  memoryTotalBytes?: number | null;
  bucketSeconds: number;
  points: HistoryPoint[];
}

export interface ConsoleUserRow {
  id: number;
  email: string;
  name?: string | null;
  plan: string;
  enabled: boolean;
  role?: string | null;
  liveStreams: number;
  storageBytes: number;
  createdAt?: string | null;
  lastLiveAt?: string | null;
  hoursThisMonth: number;
  paid: { currency: string; amount: number }[];
}

export interface SearchResult {
  users: { id: number; email: string; name?: string | null; plan: string }[];
  streams: { id: number; name: string; userId: number; userEmail: string; liveJobId?: number | null; lastJobId?: number | null }[];
  runs: { jobId: number; streamName?: string | null; userId: number; userEmail: string; live: boolean; startTime?: string | null }[];
}
