import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { get } from './api';
import type { Uptime,
  BillingOverview,
  BillingProfile,
  Destination,
  MediaItem,
  Me,
  NotificationFeed,
  Page,
  PlanView,
  QualityOption,
  RunView,
  ScheduleCalendar,
  StorageView,
  StreamSummary,
  StreamView,
  UploadView,
} from './types';
import { isOnAir } from '@/components/status';

export const keys = {
  me: ['me'] as const,
  streams: ['streams'] as const,
  stream: (id: number) => ['streams', id] as const,
  runs: (id: number) => ['streams', id, 'runs'] as const,
  uptime: (id: number) => ['streams', id, 'uptime'] as const,
  quality: ['quality-options'] as const,
  destinations: ['destinations'] as const,
  media: (q: Record<string, unknown>) => ['media', q] as const,
  storage: ['media', 'storage'] as const,
  uploads: ['media', 'uploads'] as const,
  notifications: ['notifications'] as const,
  billing: ['billing'] as const,
  plans: (currency: string) => ['plans', currency] as const,
  profile: ['billing', 'profile'] as const,
  calendar: (from: string, days: number) => ['schedules', 'calendar', from, days] as const,
};

export const useMe = () => useQuery({ queryKey: keys.me, queryFn: () => get<Me>('/api/me'), staleTime: 60_000 });

/** Home polls every 5 s so status, uptime and now playing stay current. */
export const useStreams = () =>
  useQuery({ queryKey: keys.streams, queryFn: () => get<StreamSummary[]>('/api/streams'), refetchInterval: 5_000 });

export const useStream = (id: number) =>
  useQuery({
    queryKey: keys.stream(id),
    queryFn: () => get<StreamView>(`/api/streams/${id}`),
    refetchInterval: (q) => (q.state.data && isOnAir(q.state.data.live.state) ? 3_000 : 15_000),
  });

export const useUptime = (id: number) => useQuery({ queryKey: keys.uptime(id), queryFn: () => get<Uptime>(`/api/streams/${id}/uptime`), refetchInterval: 60_000 });

export const useRuns = (id: number) => useQuery({ queryKey: keys.runs(id), queryFn: () => get<RunView[]>(`/api/streams/${id}/runs`) });

export const useQualityOptions = () => useQuery({ queryKey: keys.quality, queryFn: () => get<QualityOption[]>('/api/streams/quality-options') });

export const useDestinations = () => useQuery({ queryKey: keys.destinations, queryFn: () => get<Destination[]>('/api/destinations') });

export interface MediaQuery {
  q?: string;
  type?: string;
  status?: string;
  sort?: string;
  dir?: string;
  page?: number;
  size?: number;
}

export function mediaUrl(p: MediaQuery): string {
  const params = new URLSearchParams();
  Object.entries(p).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  });
  return `/api/media?${params}`;
}

export const useMedia = (p: MediaQuery) =>
  useQuery({
    queryKey: keys.media(p as Record<string, unknown>),
    queryFn: () => get<Page<MediaItem>>(mediaUrl(p)),
    placeholderData: keepPreviousData,
    // Keep checking while anything is still being processed.
    refetchInterval: (q) => (q.state.data?.content.some((m) => m.status === 'PROCESSING') ? 4_000 : false),
  });

export const useStorage = () => useQuery({ queryKey: keys.storage, queryFn: () => get<StorageView>('/api/media/storage') });
export const useOpenUploads = () => useQuery({ queryKey: keys.uploads, queryFn: () => get<UploadView[]>('/api/media/uploads') });

export const useNotifications = () =>
  useQuery({ queryKey: keys.notifications, queryFn: () => get<NotificationFeed>('/api/notifications'), refetchInterval: 30_000 });

export const useBilling = () => useQuery({ queryKey: keys.billing, queryFn: () => get<BillingOverview>('/api/billing') });
export const usePlans = (currency: string) =>
  useQuery({ queryKey: keys.plans(currency), queryFn: () => get<PlanView[]>(`/api/billing/plans?currency=${currency}`), staleTime: 300_000 });
export const useBillingProfile = () => useQuery({ queryKey: keys.profile, queryFn: () => get<BillingProfile>('/api/billing/profile') });

/** SC-3: every scheduled run across streams, refreshed each minute (runs start and end on their own). */
export const useScheduleCalendar = (from: string, days: number) =>
  useQuery({
    queryKey: keys.calendar(from, days),
    queryFn: () => get<ScheduleCalendar>(`/api/schedules/calendar?from=${from}&days=${days}`),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  });

export function useInvalidate() {
  const qc = useQueryClient();
  return (...k: readonly unknown[][]) => Promise.all(k.map((key) => qc.invalidateQueries({ queryKey: key })));
}
