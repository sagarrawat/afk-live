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
  /** GL-6: tips dismissed for this account (on every device). */
  dismissedHints?: string[];
  /** GL-6: how many times this account has gone live (1 during the first). */
  goLives?: number;
}

export type Level = 'green' | 'amber' | 'red';

export interface Metric {
  name: string;
  value: string;
  level: Level;
  explanation: string;
}

/** RE-8: is this platform receiving the stream? */
export type Reach = 'ON' | 'CONNECTING' | 'NOT_REACHING';

export interface DestinationHealth {
  name: string;
  status: string;
  level: Level;
  error?: string | null;
  restarts: number;
  /** "YouTube", "Twitch", ...; null for a server we don't know. */
  platform?: string | null;
  reach?: Reach | null;
  /** In plain words: "On YouTube", "Not reaching YouTube. YouTube refused the stream key. ..." */
  message?: string | null;
}

/** RE-8: the stream badge. ALL: Live; SOME: Live with problems; NONE: Not reaching anyone. */
export type StreamReach = 'ALL' | 'SOME' | 'NONE' | 'CONNECTING';

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
  /** RE-8, while LIVE: whether the platforms are receiving it. */
  reach?: StreamReach | null;
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
  /** PL-6: this item's own fit; null follows the stream. */
  fit?: Fit | null;
  /** Frames per second, from probing (ST-1: 60 fps videos on a 30 fps stream). */
  fps?: number | null;
}

/** PL-6: FIT = whole picture with bars, FILL = fill and crop, BLUR = whole picture on a blurred copy. */
export type Fit = 'FIT' | 'FILL' | 'BLUR';

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
  /** SC-4: this slot's own videos; empty plays the stream's playlist. */
  playlist?: { id: number; title: string }[];
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
  fit: Fit;
  crossfadeSeconds: number;
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
  /** ML-8: the creator's tags on this file, by name. */
  tags?: TagRef[];
}

export interface TagRef {
  id: number;
  name: string;
}

/** ML-8: a tag with how many files carry it. */
export interface MediaTag extends TagRef {
  fileCount: number;
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

/** RE-4: share of on-air time without an outage; percent is null when the stream wasn't on air in that window. */
export interface Uptime {
  windows: { label: string; onAirSeconds: number; downSeconds: number; percent: number | null }[];
  outages: { startedAt: string; resolvedAt?: string | null }[];
}

/** RE-9: one line on a stream's timeline (newest first). */
export interface TimelineEvent {
  id: number;
  jobId: number;
  at: string;
  lastAt: string;
  kind: 'REACHED' | 'RECONNECTING' | 'NOT_REACHING' | 'RECOVERED';
  name?: string | null;
  message: string;
  repeats: number;
  level: Level;
}

/** GL-1: one destination's test before going live. */
export interface DestinationCheck {
  destinationId: number;
  name: string;
  platform?: string | null;
  state: 'PASSED' | 'TESTING' | 'FAILED' | 'SKIPPED';
  message: string;
}

export interface DestinationChecks {
  ready: boolean;
  checks: DestinationCheck[];
}

/** SC-3: GET /api/schedules/calendar. Local times are wall-clock in the account's time zone. */
export interface CalendarOccurrence {
  streamId: number;
  streamName: string;
  scheduleId: number;
  kind: 'ONCE' | 'DAILY' | 'WEEKLY';
  start: string;
  end?: string | null;
  startLocal: string;
  endLocal?: string | null;
  scheduleTimeZone: string;
  movable: boolean;
}

export interface CalendarClash {
  start: string;
  end: string;
  startLocal: string;
  endLocal: string;
  streams: number;
  streamIds: number[];
  streamNames: string[];
  message: string;
}

export interface ScheduleCalendar {
  from: string;
  days: number;
  timeZone: string;
  limit: number;
  planName: string;
  streams: { id: number; name: string }[];
  occurrences: CalendarOccurrence[];
  clashes: CalendarClash[];
}
