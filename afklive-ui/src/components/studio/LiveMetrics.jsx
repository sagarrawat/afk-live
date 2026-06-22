import { useState, useEffect } from 'react';
import { useAuth } from '../../hooks/useAuth';

import { formatMoney } from '../../utils/formatters';

function formatDuration(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return h > 0
    ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
    : `${m}:${String(s).padStart(2,'0')}`;
}

export default function LiveMetrics({ stream, onStop, isStopping }) {
  const [elapsed, setElapsed] = useState(0);
  const [metrics, setMetrics] = useState({
    currentFps: stream?.currentFps || null,
    currentBitrateKbps: stream?.currentBitrateKbps || null,
    droppedFrames: stream?.droppedFrames || null,
    bytesStreamed: stream?.bytesStreamed || null,
    accumulatedCost: stream?.accumulatedCost || null,
    elapsedSeconds: stream?.elapsedSeconds || 0
  });

  const { user } = useAuth();
  const symbol = user?.currency === 'INR' ? '₹' : '$';

  // Live elapsed timer
  useEffect(() => {
    if (!stream?.startTime) return;
    const startMs = new Date(stream.startTime).getTime();
    const tick = () => setElapsed(Math.floor((Date.now() - startMs) / 1000));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [stream?.startTime]);

  // SSE Connection
  useEffect(() => {
    if (!stream?.id || stream.status === 'COMPLETED' || stream.status === 'FAILED') return;

    const eventSource = new EventSource(`/api/stream/${stream.id}/events`);
    
    eventSource.addEventListener('metrics', (event) => {
      try {
        const data = JSON.parse(event.data);
        setMetrics({
          currentFps: data.fps,
          currentBitrateKbps: data.bitrateKbps,
          droppedFrames: data.droppedFrames,
          bytesStreamed: data.bytes,
          accumulatedCost: data.cost,
          elapsedSeconds: data.elapsedSeconds
        });
      } catch (e) {}
    });

    eventSource.onerror = () => {
      eventSource.close();
    };

    return () => eventSource.close();
  }, [stream?.id, stream?.status]);

  const runningCost = stream?.lockedHourlyRate
    ? formatMoney((elapsed / 3600) * stream.lockedHourlyRate)
    : '0.00';

  const isPreparing = stream?.status === 'PREPARING';

  return (
    <>
      {/* Status */}
      <div>
        <div className="flex items-center gap-3 mb-1">
          {isPreparing ? (
            <span className="text-sm font-semibold text-yellow-600 flex items-center gap-2">
              <i className="fa-solid fa-spinner fa-spin"></i> Preparing...
            </span>
          ) : (
            <span className="badge live text-sm px-3">● LIVE</span>
          )}
          <span className="text-gray-500 text-sm font-mono">{formatDuration(elapsed)}</span>
        </div>
        <p className="text-sm text-gray-500 truncate">{stream?.destinationName || 'Streaming...'}</p>
      </div>

      {/* Cost ticker */}
      <div className="bg-blue-50 rounded-xl p-4">
        <p className="text-xs text-blue-500 mb-1 font-medium">RUNNING COST</p>
        <p className="text-3xl font-bold text-blue-700">{symbol}{runningCost}</p>
        <p className="text-xs text-blue-400 mt-1">{symbol}{formatMoney(stream?.lockedHourlyRate)}/hr · billed per minute</p>
      </div>

      {/* Metrics */}
      {!isPreparing && (
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-gray-50 dark:bg-zinc-950 rounded-xl p-3 text-center">
            <p className="text-xs text-gray-400 mb-1">FPS</p>
            <p className="text-xl font-bold text-gray-800 dark:text-gray-200">{metrics.currentFps?.toFixed(1) || '—'}</p>
          </div>
          <div className="bg-gray-50 dark:bg-zinc-950 rounded-xl p-3 text-center">
            <p className="text-xs text-gray-400 mb-1">BITRATE</p>
            <p className="text-xl font-bold text-gray-800 dark:text-gray-200">
              {metrics.currentBitrateKbps ? `${(metrics.currentBitrateKbps / 1000).toFixed(1)}M` : '—'}
            </p>
          </div>
          <div className="bg-gray-50 dark:bg-zinc-950 rounded-xl p-3 text-center">
            <p className="text-xs text-gray-400 mb-1">DROPPED</p>
            <p className={`text-xl font-bold ${(metrics.droppedFrames || 0) > 0 ? 'text-red-600' : 'text-gray-800 dark:text-gray-200'}`}>
              {metrics.droppedFrames ?? '—'}
            </p>
          </div>
          <div className="bg-gray-50 dark:bg-zinc-950 rounded-xl p-3 text-center">
            <p className="text-xs text-gray-400 mb-1">SENT</p>
            <p className="text-xl font-bold text-gray-800 dark:text-gray-200">
              {metrics.bytesStreamed ? `${(metrics.bytesStreamed / (1024 * 1024)).toFixed(0)}MB` : '—'}
            </p>
          </div>
        </div>
      )}

      {/* Stop button */}
      <div className="mt-auto pt-4">
        <button
          onClick={onStop}
          disabled={isStopping}
          className="btn btn-danger btn-block text-sm py-3 rounded-xl"
        >
          {isStopping
            ? <><i className="fa-solid fa-spinner fa-spin mr-2"></i> Stopping...</>
            : <><i className="fa-solid fa-stop mr-2"></i> Stop Stream</>
          }
        </button>
      </div>
    </>
  );
}
