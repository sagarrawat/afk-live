import { useState, useEffect, useCallback } from 'react';

/**
 * Fetches media library from /api/stream-library.
 * Auto-polls every 5s if any video is in PROCESSING/QUEUED state.
 * Returns { videos, isLoading, error, refresh }
 */
export function useLibrary() {
  const [videos, setVideos] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchLibrary = useCallback(() => {
    fetch('/api/library?size=100', { credentials: 'include' })
      .then(res => res.ok ? res.json() : Promise.reject('Failed'))
      .then(json => { 
        const content = json?.data?.content || [];
        const mapped = content.map(sv => ({
          id: sv.id,
          title: sv.title,
          thumbnailS3Key: sv.thumbnailS3Key,
          streamReadiness: sv.mediaAsset?.streamReadiness || 'PENDING_ANALYSIS',
          fileSize: sv.mediaAsset?.fileSize || 0,
          videoCodec: sv.mediaAsset?.videoCodec,
          audioCodec: sv.mediaAsset?.audioCodec,
          resolution: sv.mediaAsset?.resolution,
          createdAt: sv.mediaAsset?.createdAt
        }));
        setVideos(mapped); 
        setError(null); 
      })
      .catch(err => setError(err))
      .finally(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    fetchLibrary();
  }, [fetchLibrary]);

  useEffect(() => {
    const hasProcessing = videos.some(v => v.status === 'PROCESSING' || v.status === 'QUEUED');
    if (!hasProcessing) return;

    const interval = setInterval(() => {
      fetchLibrary();
    }, 5000);
    return () => clearInterval(interval);
  }, [videos, fetchLibrary]);

  return { videos, isLoading, error, refresh: fetchLibrary };
}
