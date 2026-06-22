import { useState, useEffect, useCallback } from 'react';

/**
 * Fetches /api/status to get the current active stream,
 * then opens an SSE connection for real-time metrics.
 */
export function useActiveStream() {
  const [activeStream, setActiveStream] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [reconnectCount, setReconnectCount] = useState(0);

  const fetchStatus = useCallback(() => {
    fetch('/api/status', { credentials: 'include' })
      .then(res => res.ok ? res.json() : null)
      .then(resData => {
        const streamList = resData?.data?.activeStreams;
        if (Array.isArray(streamList)) {
          setActiveStream(streamList);
        } else {
          setActiveStream([]);
        }
        setIsLoading(false);
      })
      .catch(() => { setActiveStream([]); setIsLoading(false); });
  }, []);

  // Initial fetch
  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  return {
    activeStreams: activeStream,
    isLoading,
    refresh: fetchStatus,
  };
}
