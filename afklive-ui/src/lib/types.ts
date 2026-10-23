// Shapes returned by the Spring API (see StreamDtos, MediaService, BillingService, MeController).

export interface Plan {
  id: 'FREE' | 'ESSENTIALS' | 'PRO' | 'AGENCY';
  name: string;
  streams: number;
  maxHeight: number;
  maxFps: number;
  storageBytes: number;
  monthlyHours: number;
  destinations: number;
  watermark: boolean;
}

export interface Me {
  id: number;
  email: string;
  name: string;
  picture?: string | null;
  admin: boolean;
  plan: Plan;
  timeZone?: string | null;
  currency?: 'INR' | 'USD' | null;
  onboarded: boolean;
  emailAlerts: boolean;
}

export type Level = 'green' | 'amber' | 'red';

export interface Metric {
  name: string;
  value: string;
  level: Level;
  explanation: string;
}

export interface DestinationHealth {
  name: string;
  status: string;
  level: Level;
  error?: string | null;
  restarts: number;
}

export interface Health {
  overall: Level;
  metrics: Metric[];
  destinations: DestinationHealth[];
}

export type LiveStateName = 'DRAFT' | 'STARTING' | 'LIVE' | 'STOPPING' | 'ENDED' | 'ERROR' | 'RECONNECTING';

export interface LiveState {
  state: LiveStateName;
  jobId?: number | null;
  startedAt?: string | null;
  uptimeSeconds: number;
  stopAt?: string | null;
  scene?: string | null;
  inputLive?: boolean | null;
  nowPlaying?: { mediaId?: number; title?: string; elapsed?: number; duration?: number; next?: { mediaId?: number; title?: string }[] | null } | null;
  health?: Health | null;
  endReason?: string | null;
}

export interface StreamSummary {
  id: number;
  name: string;
  source: 'PLAYLIST' | 'PHONE';
  orientation: 'LANDSCAPE' | 'VERTICAL';
  qualityHeight: number;
  playlistCount: number;
  live: LiveState;
}

export interface MediaRef {
  id: number;
  title: string;
  durationSeconds?: number | null;
  width?: number | null;
  height?: number | null;
  contentType: 'VIDEO' | 'AUDIO' | 'IMAGE';
  status: 'READY' | 'PROCESSING' | 'FAILED';
}

export interface TargetView {
  destinationId: number;
  name: string;
  type: string;
  enabled: boolean;
}

export interface Overlay {
  id: string;
  type: 'IMAGE' | 'TEXT' | 'CLOCK' | 'COUNTDOWN' | 'TICKER';
  visible: boolean;
  x: number;
  y: number;
  width: number;
  size: number;
  text?: string | null;
  imageMediaId?: number | null;
  imageKey?: string | null;
  color?: string | null;
  background?: string | null;
  opacity: number;
  timeZone?: string | null;
  target?: string | null;
  speed: number;
  scene?: string | null;
}

export interface Scene {
  name: string;
  backgroundMediaId?: number | null;
  backgroundKey?: string | null;
  backgroundType?: string | null;
  color?: string | null;
  music: boolean;
}

export interface ScheduleView {
  id: number;
  kind: 'ONCE' | 'WEEKLY' | 'DAILY';
  startAt?: string | null;
  endAt?: string | null;
  daysOfWeek: string[];
  startTime?: string | null;
  endTime?: string | null;
  timeZone: string;
  skipDates: string[];
  enabled: boolean;
  nextStart?: string | null;
  lastError?: string | null;
}

export interface StreamView {
  id: number;
  name: string;
  source: 'PLAYLIST' | 'PHONE';
  orientation: 'LANDSCAPE' | 'VERTICAL';
  qualityHeight: number;
  fps: number;
  playbackOrder: 'IN_ORDER' | 'SHUFFLE';
  loopCount: number;
  musicOrder: 'IN_ORDER' | 'SHUFFLE';
  audioMode: 'KEEP' | 'MIX' | 'MUTE';
  videoVolume: number;
  musicVolume: number;
  timeZone: string;
  overlays: Overlay[];
  scenes: Scene[];
  playlist: MediaRef[];
  music: MediaRef[];
  totalRuntimeSeconds: number;
  destinations: TargetView[];
  schedules: ScheduleView[];
  live: LiveState;
  phone?: { endOnInputLoss: boolean; inputGraceSeconds: number; srtLatencyMs: number; lastConnectedAt?: string | null } | null;
}

export interface Check {
  id: string;
  ok: boolean;
  message: string;
  fix?: string | null;
}

export interface Preflight {
  ok: boolean;
  checks: Check[];
}

export interface QualityOption {
  height: number;
  fps: number;
  allowed: boolean;
  reason?: string | null;
}

export interface RunView {
  jobId: number;
  startedAt: string;
  endedAt?: string | null;
  status: string;
  endReason?: string | null;
  restarts: number;
}

export interface IngestSetup {
  rtmpServer: string;
  streamKey: string;
  rtmpUrl: string;
  srtUrl: string;
  srtLatencyMs: number;
  larixLink: string;
  larixSrtLink: string;
  lastConnectedAt?: string | null;
  guides: Record<string, string>;
}

export interface Destination {
  id: number;
  name: string;
  type: string;
  platform: 'YOUTUBE' | 'FACEBOOK' | 'INSTAGRAM' | 'TWITCH' | 'KICK' | 'TIKTOK' | 'RUMBLE' | 'CUSTOM';
  server: string;
  keyHint: string;
  selected: boolean;
}

export interface MediaItem {
  id: number;
  title: string;
  type: 'VIDEO' | 'AUDIO' | 'IMAGE';
  status: 'READY' | 'PROCESSING' | 'FAILED';
  statusReason?: string | null;
  sizeBytes: number;
  durationSeconds?: number | null;
  width?: number | null;
  height?: number | null;
  hasAudio?: boolean | null;
  fps?: number | null;
  createdAt: string;
  thumbnailUrl?: string | null;
  previewUrl: string;
  usedBy: string[];
  inLiveUse: boolean;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface StorageView {
  usedBytes: number;
  limitBytes: number;
  percent: number;
  warning: boolean;
  full: boolean;
  plan: string;
}

export interface UploadView {
  id: string;
  filename: string;
  type: string;
  sizeBytes: number;
  partSize: number;
  partCount: number;
  uploadedParts: number[];
  uploadedBytes: number;
  status: 'UPLOADING' | 'COMPLETED' | 'ABORTED';
  mediaId?: number | null;
  expiresAt: string;
}

export interface PartTarget {
  partNumber: number;
  url: string;
  method: string;
  size: number;
  direct: boolean;
}

export interface DeleteResult {
  deleted: number[];
  blocked: { id: number; title: string; reason: string }[];
  freedBytes: number;
}

export interface NotificationItem {
  id: number;
  type: string;
  level: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';
  title: string;
  body?: string | null;
  link?: string | null;
  streamId?: number | null;
  createdAt: string;
  readAt?: string | null;
}

export interface NotificationFeed {
  unread: number;
  emailAlerts: boolean;
  items: NotificationItem[];
}

export interface PlanView {
  id: Plan['id'];
  name: string;
  concurrentStreams: number;
  maxHeight: number;
  maxFps: number;
  storageBytes: number;
  monthlyHours: number;
  watermark: boolean;
  destinationsPerStream: number;
  currency: 'INR' | 'USD';
  monthly: number;
  yearly: number;
  monthlyLabel: string;
  yearlyLabel: string;
}

export interface BillingOverview {
  plan: Plan['id'];
  planName: string;
  cycle?: 'MONTHLY' | 'YEARLY' | null;
  status: string;
  periodEnd?: string | null;
  cancelAtPeriodEnd: boolean;
  pendingPlan?: string | null;
  usage: { hoursUsed: number; hoursIncluded: number; storageUsed: number; storageMax: number; streamsLive: number; streamsMax: number };
  nextCharge?: { date: string; amount: number; currency: string; description: string } | null;
  invoices: { id: number; number: string; description: string; total: number; currency: string; issuedAt: string }[];
  unpaidBalance: number;
}

export interface Quote {
  kind: 'NEW' | 'UPGRADE' | 'RENEW';
  plan: Plan['id'];
  cycle: 'MONTHLY' | 'YEARLY';
  currency: string;
  price: number;
  credit: number;
  amount: number;
  periodStart: string;
  periodEnd: string;
}

export interface BillingProfile {
  legalName?: string | null;
  addressLine?: string | null;
  city?: string | null;
  postalCode?: string | null;
  country?: string | null;
  stateCode?: string | null;
  gstin?: string | null;
}
