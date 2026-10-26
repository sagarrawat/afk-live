import clsx from 'clsx';
import { ChevronLeft } from 'lucide-react';
import { NavLink, Navigate, useParams } from 'react-router-dom';
import { GoLiveButton, PlannedStop, SlowNotice, StopButton } from '@/components/live-actions';
import { HealthDot, isOnAir, LiveBadge, reachProblem, stopReason, Uptime } from '@/components/status';
import { ErrorNote, Spinner } from '@/components/ui';
import { useStream } from '@/lib/queries';
import type { StreamView } from '@/lib/types';
import { AudioTab } from './stream/AudioTab';
import { DestinationsTab } from './stream/DestinationsTab';
import { OverlaysTab } from './stream/OverlaysTab';
import { OverviewTab } from './stream/OverviewTab';
import { PhoneTab } from './stream/PhoneTab';
import { PlaylistTab } from './stream/PlaylistTab';
import { ScheduleTab } from './stream/ScheduleTab';
import { SettingsTab } from './stream/SettingsTab';
import { Link } from 'react-router-dom';

export function tabsFor(s: Pick<StreamView, 'source'>) {
  return s.source === 'PHONE'
    ? [
        ['overview', 'Overview'],
        ['phone', 'Phone setup'],
        ['overlays', 'Scenes & overlays'],
        ['audio', 'Music'],
        ['destinations', 'Destinations'],
        ['schedule', 'Schedule'],
        ['settings', 'Settings'],
      ]
    : [
        ['overview', 'Overview'],
        ['playlist', 'Playlist'],
        ['audio', 'Audio'],
        ['overlays', 'Overlays'],
        ['destinations', 'Destinations'],
        ['schedule', 'Schedule'],
        ['settings', 'Settings'],
      ];
}

/** One stream: status first (the header), everything else one tab away. */
export function StreamPage() {
  const params = useParams();
  const id = Number(params.id);
  const tab = params.tab ?? 'overview';
  const { data: s, isLoading, error } = useStream(id);

  if (isLoading) return <Spinner label="Loading the stream" />;
  if (error || !s) return <ErrorNote error={error ?? new Error('Stream not found.')} />;
  const tabs = tabsFor(s);
  if (!tabs.some(([t]) => t === tab)) return <Navigate to={`/studio/streams/${id}`} replace />;
  const onAir = isOnAir(s.live.state);
  const why = onAir ? null : stopReason(s.live);

  return (
    <>
      <Link to="/studio" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ChevronLeft className="h-4 w-4" aria-hidden />
        Streams
      </Link>
      {/* The status strip: on-air state and the running clock are the first thing on the page. */}
      <header className={clsx('relative mb-6 overflow-hidden rounded-md border bg-panel', onAir ? 'border-live/40' : 'border-line')}>
        <span aria-hidden className={clsx('absolute inset-y-0 left-0 w-1.5', onAir ? 'bg-live' : 'bg-line')} />
        <div className="flex flex-col gap-4 py-4 pl-6 pr-4 md:flex-row md:items-center">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2.5">
              <LiveBadge state={s.live.state} reach={s.live.reach} size="lg" />
              {onAir && <HealthDot level={s.live.health?.overall} withText />}
            </div>
            <h1 className="mt-2 truncate text-2xl font-bold tracking-tight">{s.name}</h1>
            {onAir && reachProblem(s.live) && <p className="mt-1 text-sm text-bad">{reachProblem(s.live)}</p>}
            <PlannedStop stream={s} timeZone={s.timeZone} />
            {why && (
              <p className={clsx('mt-1 text-sm', why.problem ? 'text-bad' : 'text-muted')}>
                {why.text}
                {why.hoursUsedUp && (
                  <>
                    {' '}
                    <Link to="/studio/settings/billing" className="text-accent hover:underline">
                      Upgrade for more hours
                    </Link>
                  </>
                )}
              </p>
            )}
            {onAir && <SlowNotice live={s.live} streamId={s.id} />}
          </div>
          <div className="flex items-center gap-5">
            {onAir && (
              <div className="text-right">
                <Uptime live={s.live} className="block whitespace-nowrap text-2xl font-semibold leading-none tracking-tight sm:text-3xl" />
                <span className="text-xs text-muted">on air</span>
              </div>
            )}
            {onAir ? <StopButton stream={s} timeZone={s.timeZone} /> : <GoLiveButton stream={s} />}
          </div>
        </div>
      </header>

      <nav aria-label="Stream sections" className="-mx-4 mb-6 overflow-x-auto border-b border-line px-4 md:mx-0 md:px-0">
        <ul className="flex min-w-max gap-1">
          {tabs.map(([t, label]) => (
            <li key={t}>
              <NavLink
                to={t === 'overview' ? `/studio/streams/${id}` : `/studio/streams/${id}/${t}`}
                end
                className={({ isActive }) =>
                  clsx('-mb-px inline-block border-b-2 px-3 py-2.5 text-sm', isActive ? 'border-accent font-medium text-ink' : 'border-transparent text-muted hover:text-ink')
                }
              >
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      {tab === 'overview' && <OverviewTab stream={s} />}
      {tab === 'playlist' && <PlaylistTab stream={s} />}
      {tab === 'phone' && <PhoneTab stream={s} />}
      {tab === 'audio' && <AudioTab stream={s} />}
      {tab === 'overlays' && <OverlaysTab stream={s} />}
      {tab === 'destinations' && <DestinationsTab stream={s} />}
      {tab === 'schedule' && <ScheduleTab stream={s} />}
      {tab === 'settings' && <SettingsTab stream={s} />}
    </>
  );
}
