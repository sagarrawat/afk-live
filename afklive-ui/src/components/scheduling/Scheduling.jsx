import React, { useState, useEffect } from 'react';
import { useLibrary } from '../../hooks/useLibrary';
import { useAuth } from '../../hooks/useAuth';
import toast from 'react-hot-toast';

export default function Scheduling() {
  const { videos } = useLibrary();
  const { user } = useAuth();
  
  const [scheduledStreams, setScheduledStreams] = useState([]);
  const [loading, setLoading] = useState(true);
  
  // Form state
  const [selectedVideoId, setSelectedVideoId] = useState('');
  const [scheduledTime, setScheduledTime] = useState('');
  const [streamQuality, setStreamQuality] = useState('0');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const readyVideos = videos;

  const fetchScheduled = () => {
    fetch('/api/stream/scheduled', { credentials: 'include' })
      .then(res => res.json())
      .then(data => {
        if (data.data) {
          setScheduledStreams(data.data);
        }
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchScheduled();
  }, []);

  const handleSchedule = async (e) => {
    e.preventDefault();
    if (!selectedVideoId || !scheduledTime) return;
    
    setIsSubmitting(true);
    
    const d = new Date(scheduledTime);
    const scheduleIso = d.toISOString();

    const payload = {
      mediaAsset: { id: parseInt(selectedVideoId) },
      scheduledTime: scheduleIso,
      streamQuality: parseInt(streamQuality) || 0,
      streamMode: 'original',
      muteVideoAudio: false,
      loopCount: 1
    };

    try {
      const res = await fetch('/api/stream/schedule', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || ''
        },
        credentials: 'include',
        body: JSON.stringify(payload)
      });
      
      if (!res.ok) throw new Error('Failed to schedule stream');
      
      toast.success('Stream scheduled successfully!');
      setSelectedVideoId('');
      setScheduledTime('');
      fetchScheduled();
    } catch (err) {
      toast.error(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCancel = async (id) => {
    try {
      const res = await fetch(`/api/stream/scheduled/${id}`, {
        method: 'DELETE',
        headers: {
          'X-XSRF-TOKEN': document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || ''
        },
        credentials: 'include'
      });
      if (!res.ok) throw new Error('Failed to cancel');
      toast.success('Scheduled stream cancelled');
      fetchScheduled();
    } catch (err) {
      toast.error(err.message);
    }
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="afk-loader"><div className="afk-loader-play"></div></div>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-5xl w-full h-full overflow-y-auto">


      <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
        {/* Schedule Form */}
        <div className="md:col-span-1">
          <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800">
            <h3 className="font-semibold text-gray-700 dark:text-gray-300 mb-4">New Scheduled Stream</h3>
            <form onSubmit={handleSchedule} className="space-y-4">
              <div>
                <label htmlFor="videoSource" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Video Source</label>
                <select 
                  id="videoSource"
                  required
                  value={selectedVideoId} 
                  onChange={e => setSelectedVideoId(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg p-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
                >
                  <option value="">Select a video...</option>
                  {readyVideos.map(v => (
                    <option key={v.id} value={v.id}>{v.title || v.originalFilename}</option>
                  ))}
                </select>
              </div>
              
              <div>
                <label htmlFor="dateTime" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Date & Time</label>
                <input 
                  id="dateTime"
                  type="datetime-local" 
                  required
                  value={scheduledTime}
                  onChange={e => setScheduledTime(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg p-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
                />
              </div>

              <button 
                type="submit" 
                disabled={isSubmitting || !selectedVideoId || !scheduledTime}
                className="w-full bg-blue-600 hover:bg-blue-700 text-white font-medium py-2 rounded-lg transition-colors disabled:opacity-50"
              >
                {isSubmitting ? 'Scheduling...' : 'Schedule Stream'}
              </button>
            </form>
          </div>
        </div>

        {/* Upcoming Streams */}
        <div className="md:col-span-2">
          <h3 className="font-semibold text-gray-700 dark:text-gray-300 mb-4">Upcoming Streams</h3>
          {scheduledStreams.length === 0 ? (
            <div className="bg-gray-50 dark:bg-zinc-950 rounded-xl border border-dashed border-gray-300 p-8 text-center text-gray-500">
              <i className="fa-regular fa-calendar-xmark text-3xl mb-2"></i>
              <p>No streams scheduled.</p>
            </div>
          ) : (
            <div className="space-y-3">
              {scheduledStreams.map(stream => {
                const date = new Date(stream.scheduledTime);
                const video = videos.find(v => v.id === stream.mediaAsset?.id);
                return (
                  <div key={stream.id} className="bg-white dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 p-4 shadow-sm flex items-center justify-between">
                    <div className="flex items-center gap-4">
                      <div className="bg-blue-50 text-blue-600 rounded-lg p-3 text-center min-w-[70px]">
                        <p className="text-xs font-bold uppercase">{date.toLocaleString('default', { month: 'short' })}</p>
                        <p className="text-xl font-black leading-none">{date.getDate()}</p>
                      </div>
                      <div>
                        <p className="font-semibold text-gray-900 dark:text-white">{video?.title || video?.originalFilename || 'Unknown Video'}</p>
                        <p className="text-sm text-gray-500">
                          {date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})} · {stream.status}
                        </p>
                      </div>
                    </div>
                    <button 
                      onClick={() => handleCancel(stream.id)}
                      className="text-red-500 hover:bg-red-50 p-2 rounded-lg transition-colors"
                      title="Cancel Stream"
                    >
                      <i className="fa-solid fa-trash"></i>
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
