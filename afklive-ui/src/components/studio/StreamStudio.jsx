import { useState, useEffect, useRef } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useActiveStream } from '../../hooks/useActiveStream';
import { useLibrary } from '../../hooks/useLibrary';
import { formatMoney } from '../../utils/formatters';
import LiveMetrics from './LiveMetrics';
import UploadDropzone from '../library/UploadDropzone';
import OverlayDesigner from './OverlayDesigner';
import toast from 'react-hot-toast';

function StreamHealthConsole({ jobId }) {
  const [logs, setLogs] = useState([]);
  const logsEndRef = useRef(null);

  useEffect(() => {
    if (!jobId) return;
    const eventSource = new EventSource(`/api/stream/${jobId}/events`, { withCredentials: true });
    
    eventSource.onmessage = (event) => {
      setLogs(prev => [...prev.slice(-49), event.data]);
    };
    
    eventSource.onerror = () => {
      eventSource.close();
    };

    return () => eventSource.close();
  }, [jobId]);

  useEffect(() => {
    if (logsEndRef.current) {
      logsEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs]);

  return (
    <div className="bg-gray-900 rounded-xl p-4 mt-6 border border-gray-800 shadow-inner h-48 overflow-y-auto font-mono text-xs text-green-400 text-left w-full max-w-2xl mx-auto">
      <div className="flex items-center gap-2 mb-2 pb-2 border-b border-gray-800 text-gray-500 font-sans text-xs uppercase tracking-widest font-bold">
        <i className="fa-solid fa-terminal"></i> Live Health Console
      </div>
      {logs.length === 0 ? (
        <p className="text-gray-600 dark:text-gray-400 italic">Waiting for broadcast connection...</p>
      ) : (
        logs.map((log, i) => (
          <div key={i} className="mb-1 opacity-90 break-all">{log}</div>
        ))
      )}
      <div ref={logsEndRef} />
    </div>
  );
}

export default function StreamStudio({ initialVideo }) {
  const { user } = useAuth();
  const { activeStreams, isLoading, refresh } = useActiveStream();
  const { videos, refresh: fetchVideos } = useLibrary();

  const [selectedVideos, setSelectedVideos]     = useState(initialVideo ? [initialVideo] : []);
  const [selectedDestKeys, setSelectedDestKeys] = useState([]);
  const [destinations, setDestinations]         = useState([]);
  const [loopCount, setLoopCount]               = useState('1');
  const [streamQuality, setStreamQuality]       = useState('720');
  const [hourlyRate, setHourlyRate]             = useState(1.00);
  const [baseRate, setBaseRate]                 = useState(1.00);
  const [discountPercent, setDiscountPercent]   = useState(0);
  const [isStarting, setIsStarting]             = useState(false);
  const [isStopping, setIsStopping]             = useState(false);
  const [showSupportModal, setShowSupportModal] = useState(false);
  const [showPreviewModal, setShowPreviewModal] = useState(false);
  const [streamToStop, setStreamToStop]         = useState(null);
  const [showAddDestModal, setShowAddDestModal] = useState(false);
  const [poweredByEnabled, setPoweredByEnabled] = useState(true);
  const [newDest, setNewDest]                   = useState({ name: '', streamKey: '', rtmpUrl: '' });
  const [savingDest, setSavingDest]             = useState(false);
  const [useOverlay, setUseOverlay]             = useState(false);
  const getOverlayImageRef                      = useRef(null);

  const [effectiveHourlyRate, setEffectiveHourlyRate] = useState(0);
  const [effectiveBaseRate, setEffectiveBaseRate]     = useState(0);

  // Update if parent passes a new initialVideo (e.g. user clicks Go Live on a different video)
  useEffect(() => { if (initialVideo) setSelectedVideos([initialVideo]); }, [initialVideo]);

  const readyVideos = videos;

  // Fetch destinations
  useEffect(() => {
    fetch('/api/destinations', { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then(setDestinations)
      .catch(() => {});
  }, []);

  // Fetch central calculated pricing dynamically when options change
  useEffect(() => {
    const destCount = Math.max(1, selectedDestKeys.length);
    fetch(`/api/pricing/calculate?quality=${streamQuality}&destCount=${destCount}&poweredBy=${poweredByEnabled}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { 
        if (d?.hourlyRate !== undefined) setEffectiveHourlyRate(d.hourlyRate); 
        if (d?.baseRate !== undefined) setEffectiveBaseRate(d.baseRate);
      })
      .catch(() => {});
  }, [streamQuality, selectedDestKeys, poweredByEnabled]);

  const getCsrfToken = () => document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || '';

  const handleGoLive = async () => {
    if (selectedVideos.length === 0 || selectedDestKeys.length === 0) return;
    setIsStarting(true);

    try {
      const formData = new FormData();
      selectedVideos.forEach(v => formData.append('videoKey', v.id));
      selectedDestKeys.forEach(k => formData.append('streamKey', k));
      formData.append('loopCount', loopCount);
      formData.append('streamQuality', streamQuality);
      formData.append('streamMode', 'original');
      formData.append('muteVideoAudio', 'false');
      formData.append('overlayEnabled', useOverlay.toString());
      formData.append('autoReplyEnabled', 'false');
      formData.append('poweredBy', poweredByEnabled.toString());

      if (useOverlay && getOverlayImageRef.current) {
        const overlayData = getOverlayImageRef.current();
        if (overlayData) {
          formData.append('overlayImage', overlayData);
        }
      }

      const res = await fetch('/api/start', { 
        method: 'POST', 
        credentials: 'include', 
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
        body: formData 
      });
      const data = await res.json();

      if (!res.ok) throw new Error(data?.message || 'Failed to start stream');
      refresh();
      toast.success('Stream started successfully!');
    } catch (e) {
      toast.error(e.message);
    } finally {
      setIsStarting(false);
    }
  };

  const handleStopStream = async () => {
    setIsStopping(true);
    try {
      const res = await fetch('/api/stop', { 
        method: 'POST', 
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() }
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.message || 'Failed to stop stream');
      setStreamToStop(null);
      refresh();
      toast.success('Stream stopped.');
    } catch (e) {
      toast.error(e.message);
    } finally {
      setIsStopping(false);
    }
  };



  // ── RENDER ────────────────────────────────────────────────────────────


  const handleAddDestination = async () => {
    if (!newDest.name || !newDest.streamKey) return;
    setSavingDest(true);
    try {
      const res = await fetch('/api/destinations', {
        method: 'POST',
        credentials: 'include',
        headers: { 
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify(newDest),
      });
      if (!res.ok) throw new Error('Failed to save destination');
      setShowAddDestModal(false);
      setNewDest({ name: '', streamKey: '', rtmpUrl: '' });
      toast.success('Destination added successfully');
      
      // Refresh destinations
      fetch('/api/destinations', { credentials: 'include' })
        .then(r => r.json())
        .then(setDestinations)
        .catch(() => {});
    } catch (e) {
      toast.error(e.message || 'Error saving destination');
    } finally {
      setSavingDest(false);
    }
  };

  const calculatedDiscount = effectiveBaseRate > 0 ? Math.round(((effectiveBaseRate - effectiveHourlyRate) / effectiveBaseRate) * 100) : 0;

  return (
    <div className="w-full min-h-full p-6 md:p-8">
      <div className="max-w-6xl pt-4">

        <header className="mb-8 flex items-center justify-between">
          <div></div>
          {activeStreams && activeStreams.length > 0 && (
            <button 
              onClick={() => document.getElementById('active-streams-modal').classList.remove('hidden')}
              className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 text-gray-800 dark:text-gray-200 px-4 py-2 rounded-lg text-sm font-bold shadow-sm hover:bg-gray-50 dark:hover:bg-zinc-800 transition-all flex items-center gap-2"
            >
              <span className="relative flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500"></span>
              </span>
              Active Streams ({activeStreams.length})
            </button>
          )}
        </header>

        {/* ACTIVE STREAMS MODAL */}
        <div id="active-streams-modal" className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100] hidden flex items-center justify-center p-4">
          <div className="bg-white dark:bg-zinc-950 border border-gray-200 dark:border-zinc-800 rounded-2xl w-full max-w-5xl max-h-[90vh] flex flex-col shadow-2xl">
            <div className="p-6 border-b border-gray-100 dark:border-zinc-800 flex items-center justify-between">
              <h2 className="text-xl font-bold text-gray-900 dark:text-white flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-full bg-red-500 animate-pulse"></span> Your Active Streams
              </h2>
              <button 
                onClick={() => document.getElementById('active-streams-modal').classList.add('hidden')}
                className="w-8 h-8 flex items-center justify-center rounded-full bg-gray-100 dark:bg-zinc-800 text-gray-500 hover:text-gray-900 dark:hover:text-white transition-colors"
              >
                <i className="fa-solid fa-xmark"></i>
              </button>
            </div>
            <div className="p-6 overflow-y-auto flex-1">
              {activeStreams && activeStreams.length > 0 ? (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  {activeStreams.map(stream => (
                    <div key={stream.id} className="bg-gray-50 dark:bg-zinc-900 rounded-2xl border border-gray-200 dark:border-zinc-700 shadow-sm p-5 flex flex-col h-full">
                      <LiveMetrics 
                        stream={stream} 
                        onStop={() => setStreamToStop(stream)} 
                        isStopping={isStopping && streamToStop?.id === stream.id} 
                      />
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-center py-12 text-gray-500">No active streams.</div>
              )}
            </div>
          </div>
        </div>

        {/* Custom Stop Modal */}
        {streamToStop && (
          <div className="modal-overlay">
            <div className="modal-box max-w-sm">
              <h3 className="text-lg font-bold text-gray-900 dark:text-white mb-2">Stop Stream?</h3>
              <p className="text-gray-500 text-sm mb-5">
                Your stream will end and billing will stop. You can start a new stream anytime.
              </p>
              <div className="flex gap-3 justify-end">
                <button className="btn btn-secondary" onClick={() => setStreamToStop(null)}>Cancel</button>
                <button className="btn btn-danger" onClick={() => {
                  setIsStopping(true);
                  fetch(`/api/stop?streamId=${streamToStop.id}`, { method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': getCsrfToken() } })
                    .then(() => { toast.success('Stream stopped'); refresh(); setStreamToStop(null); })
                    .catch(() => toast.error('Failed to stop'))
                    .finally(() => setIsStopping(false));
                }}>
                  {isStopping ? 'Stopping...' : 'Stop Stream'}
                </button>
              </div>
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* LEFT COLUMN: Preview & Source */}
          <div className="lg:col-span-2 space-y-6">
            
            {/* Unified Preview & Overlay Designer */}
            <div className="rounded-2xl overflow-hidden relative shadow-2xl ring-1 ring-gray-900/10">
              <OverlayDesigner 
                video={selectedVideos.length > 0 ? selectedVideos[0] : null}
                playlistCount={selectedVideos.length}
                isActive={useOverlay}
                onExportReady={(fn) => { getOverlayImageRef.current = fn; }}
              />
            </div>

            {/* Source Video Selection */}
            <div className="card">
              <div className="flex items-center justify-between mb-4">
                <h3 className="font-semibold text-gray-800 dark:text-gray-200 flex items-center">
                  <i className="fa-solid fa-folder-open text-blue-500 mr-2"></i> Video Sources
                </h3>
                <button 
                  onClick={() => document.getElementById('studioUploadInput').click()}
                  className="text-xs font-semibold bg-blue-50 text-blue-600 px-3 py-1.5 rounded-lg hover:bg-blue-100 transition"
                >
                  <i className="fa-solid fa-cloud-arrow-up mr-1"></i> Upload
                </button>
                <input 
                  id="studioUploadInput" 
                  type="file" 
                  accept="video/*" 
                  multiple 
                  className="hidden" 
                  onChange={(e) => { 
                    if (e.target.files.length > 0) {
                      document.dispatchEvent(new CustomEvent('upload-files', { detail: e.target.files }));
                    }
                    e.target.value = ''; 
                  }} 
                />
              </div>
              
              <div className="mb-2">
                <UploadDropzone onUploadComplete={fetchVideos} />
              </div>
              
              {readyVideos.length === 0 ? (
                <div className="empty-state py-8 bg-gray-50 dark:bg-zinc-950 rounded-lg">
                  <i className="fa-solid fa-film text-gray-400 text-3xl mb-3"></i>
                  <p className="text-gray-600 dark:text-gray-400 font-medium">No videos found</p>
                  <p className="text-gray-500 text-sm mt-1">Upload a video to begin broadcasting</p>
                </div>
              ) : (
                <div className="space-y-2 max-h-64 overflow-y-auto pr-2 custom-scrollbar">
                  {readyVideos.map(v => {
                    const isSelected = selectedVideos.some(p => p.id === v.id);
                    const index = selectedVideos.findIndex(p => p.id === v.id);
                    return (
                    <div
                      key={v.id}
                      onClick={() => setSelectedVideos(prev => 
                        prev.find(p => p.id === v.id) 
                          ? prev.filter(p => p.id !== v.id) 
                          : [...prev, v]
                      )}
                      className={`cursor-pointer p-2 border rounded-lg flex items-center transition-all ${isSelected ? 'bg-blue-50 border-blue-200 ring-1 ring-blue-500 shadow-sm' : 'bg-white dark:bg-zinc-900 border-gray-100 dark:border-zinc-800 hover:border-blue-300'}`}
                    >
                      <div className="w-16 h-10 bg-black rounded overflow-hidden flex items-center justify-center flex-shrink-0 relative border border-gray-200 dark:border-zinc-700">
                        {v.thumbnailS3Key
                          ? <img src={`/api/videos/${v.id}/thumbnail`} className="w-full h-full object-cover" alt="" />
                          : <i className="fa-solid fa-film text-gray-400 text-xs"></i>
                        }
                        {isSelected && (
                          <div className="absolute inset-0 bg-blue-500/10 border border-blue-500 rounded flex items-center justify-center font-bold text-white bg-black/40">
                            {index + 1}
                          </div>
                        )}
                      </div>
                      <div className="flex-1 min-w-0 ml-3">
                        <p className={`text-sm truncate ${isSelected ? 'text-blue-900 font-bold' : 'text-gray-900 dark:text-white font-medium'}`}>{v.title || v.originalFilename}</p>
                        <p className="text-[10px] text-gray-500 mt-0.5">{v.videoCodec?.toUpperCase() || 'H264'} · {(v.fileSize / 1024 / 1024).toFixed(1)} MB</p>
                        {v.createdAt && <p className="text-[10px] text-gray-400 mt-0.5">{new Date(v.createdAt).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</p>}
                      </div>
                      {isSelected && <i className="fa-solid fa-check-circle text-blue-500 text-xl mx-2 shadow-sm rounded-full bg-white dark:bg-zinc-900"></i>}
                    </div>
                  )})}
                </div>
              )}
            </div>


          </div>

          {/* RIGHT COLUMN: Settings & Go Live */}
          <div className="lg:col-span-1 space-y-6">
            
            {/* Broadcast Settings */}
            <div className="card">
              <h3 className="font-semibold text-gray-800 dark:text-gray-200 mb-4 flex items-center">
                <i className="fa-solid fa-sliders text-blue-500 mr-2"></i> Broadcast Settings
              </h3>
              
              <div className="space-y-4">
                <div>
                  <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">Resolution</label>
                  <select className="w-full border border-gray-200 dark:border-zinc-700 rounded-lg px-3 py-2.5 bg-gray-50 dark:bg-zinc-950 focus:bg-white dark:bg-zinc-900 focus:ring-2 focus:ring-blue-500 outline-none transition text-sm font-medium text-gray-700 dark:text-gray-300" value={streamQuality} onChange={e => setStreamQuality(e.target.value)}>
                    <option value="720">720p 60fps (Standard)</option>
                    <option value="1080">1080p 60fps (High)</option>
                    <option value="2160">4K 60fps (Ultra HD)</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">Loop Video</label>
                  <select className="w-full border border-gray-200 dark:border-zinc-700 rounded-lg px-3 py-2.5 bg-gray-50 dark:bg-zinc-950 focus:bg-white dark:bg-zinc-900 focus:ring-2 focus:ring-blue-500 outline-none transition text-sm font-medium text-gray-700 dark:text-gray-300" value={loopCount} onChange={e => setLoopCount(e.target.value)}>
                    <option value="1">Play Once (No Loop)</option>
                    <option value="2">Loop 2 times</option>
                    <option value="5">Loop 5 times</option>
                    <option value="-1">Infinite Loop 24/7</option>
                  </select>
                </div>
                
                <div className="pt-2">
                  <div 
                    className="flex items-center justify-between bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-700 rounded-lg px-3 py-2 cursor-pointer hover:bg-gray-50 dark:bg-zinc-950 transition"
                    onClick={() => setUseOverlay(!useOverlay)}
                  >
                    <div className="flex flex-col">
                      <span className="text-sm font-bold text-gray-900 dark:text-white flex items-center gap-2">
                        <i className="fa-solid fa-layer-group text-blue-500"></i> Custom Overlays
                      </span>
                      <span className="text-[10px] text-gray-500 mt-0.5 uppercase tracking-wide font-bold">Add Text & Branding</span>
                    </div>
                    <div className={`w-10 h-5 rounded-full relative transition-colors ${useOverlay ? 'bg-blue-500' : 'bg-gray-300'}`}>
                      <div className={`w-4 h-4 bg-white dark:bg-zinc-900 rounded-full absolute top-0.5 transition-all ${useOverlay ? 'left-5.5 right-0.5' : 'left-0.5'}`} style={{ left: useOverlay ? 'calc(100% - 18px)' : '2px' }}></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Destinations */}
            <div className="card flex flex-col flex-1">
              <div className="flex justify-between items-center mb-4">
                <h3 className="font-semibold text-gray-800 dark:text-gray-200 flex items-center">
                  <i className="fa-solid fa-satellite-dish text-blue-500 mr-2"></i> Destinations
                </h3>
                <div className="flex items-center gap-2">
                  <button 
                    onClick={() => setShowAddDestModal(true)}
                    className="text-xs font-semibold bg-blue-50 text-blue-600 px-3 py-1.5 rounded-lg hover:bg-blue-100 transition"
                  >
                    <i className="fa-solid fa-plus mr-1"></i> Add
                  </button>
                  <span className="text-[10px] font-bold bg-blue-100 text-blue-700 px-2 py-0.5 rounded-full uppercase tracking-wide">
                    {selectedDestKeys.length} Active
                  </span>
                </div>
              </div>
              
              {destinations.length === 0 ? (
                <div className="empty-state py-6 bg-gray-50 dark:bg-zinc-950 rounded-lg flex flex-col items-center justify-center text-center">
                  <div className="w-12 h-12 bg-blue-100 text-blue-500 rounded-full flex items-center justify-center mb-2 font-black text-xl">2</div>
                  <p className="text-sm text-gray-700 dark:text-gray-300 font-bold mb-1">Add a Destination</p>
                  <p className="text-xs text-gray-500">Connect YouTube, Twitch, or Custom RTMP.</p>
                </div>
              ) : (
                <div className="space-y-2 max-h-48 overflow-y-auto pr-2 custom-scrollbar">
                  {destinations.map(d => (
                    <div
                      key={d.id}
                      onClick={() => setSelectedDestKeys(prev => prev.includes(d.streamKey) ? prev.filter(k => k !== d.streamKey) : [...prev, d.streamKey])}
                      className={`cursor-pointer p-3 border rounded-lg flex items-center transition-all ${
                        selectedDestKeys.includes(d.streamKey) 
                        ? 'border-blue-500 bg-blue-50 shadow-sm' 
                        : 'border-gray-100 dark:border-zinc-800 bg-white dark:bg-zinc-900 hover:border-blue-300'
                      }`}
                    >
                      <div className={`w-8 h-8 rounded-lg flex items-center justify-center mr-3 ${selectedDestKeys.includes(d.streamKey) ? 'bg-blue-600 text-white shadow-inner' : 'bg-gray-100 dark:bg-zinc-800 text-gray-500 border border-gray-200 dark:border-zinc-700'}`}>
                        <i className={`fa-brands ${d.rtmpUrl?.includes('youtube') ? 'fa-youtube' : d.rtmpUrl?.includes('twitch') ? 'fa-twitch' : 'fa-signal'}`}></i>
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className={`text-sm font-bold truncate ${selectedDestKeys.includes(d.streamKey) ? 'text-blue-900' : 'text-gray-900 dark:text-white'}`}>{d.name}</p>
                        <p className={`text-[10px] truncate ${selectedDestKeys.includes(d.streamKey) ? 'text-blue-600/80' : 'text-gray-500'} font-mono mt-0.5`}>{d.rtmpUrl || 'Custom RTMP'}</p>
                      </div>
                      <div className="ml-2">
                         <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center transition-colors ${selectedDestKeys.includes(d.streamKey) ? 'border-blue-500 bg-blue-500' : 'border-gray-300 bg-transparent'}`}>
                            {selectedDestKeys.includes(d.streamKey) && <div className="w-1.5 h-1.5 bg-white dark:bg-zinc-900 rounded-full"></div>}
                         </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Pricing Block & Go Live */}
            <div className="pt-2">
              <div className="bg-gradient-to-br from-blue-50 to-indigo-50 border border-blue-200/60 rounded-xl px-5 py-4 mb-4 relative overflow-hidden shadow-sm">
                {calculatedDiscount > 0 && (
                   <div className="absolute top-0 right-0 bg-gradient-to-r from-emerald-400 to-emerald-500 text-white text-[10px] font-bold px-3 py-1 rounded-bl-xl shadow-sm uppercase tracking-wide">
                     Save {calculatedDiscount}%
                   </div>
                )}
                <div className="flex items-center justify-between relative z-10">
                  <div>
                    <p className="text-xs font-bold text-blue-800/60 uppercase tracking-widest mb-0.5">Estimated Cost</p>
                    <div className="flex items-end gap-2">
                      <p className="text-3xl font-black text-blue-700 tracking-tight">
                        {user?.currency === 'INR' ? '₹' : '$'}{formatMoney(effectiveHourlyRate)}
                        <span className="text-sm font-bold text-blue-500/70 ml-1">/hr</span>
                      </p>
                      {calculatedDiscount > 0 && (
                        <p className="text-sm font-semibold text-blue-900/30 line-through mb-1.5">
                          {user?.currency === 'INR' ? '₹' : '$'}{formatMoney(effectiveBaseRate)}
                        </p>
                      )}
                    </div>
                  </div>
                  <div className="w-12 h-12 bg-white dark:bg-zinc-900 rounded-full flex items-center justify-center shadow-sm border border-blue-100/50 text-blue-500 text-xl">
                    <i className="fa-solid fa-coins"></i>
                  </div>
                </div>
              </div>

              {/* Powered By Viral Loop Toggle */}
              <div 
                className="flex items-center justify-between bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-700 rounded-xl px-4 py-3 mb-4 cursor-pointer hover:bg-gray-50 dark:bg-zinc-950 transition"
                onClick={() => setPoweredByEnabled(!poweredByEnabled)}
              >
                <div className="flex flex-col">
                  <span className="text-sm font-bold text-gray-900 dark:text-white flex items-center gap-2">
                    <i className="fa-solid fa-bullhorn text-primary"></i> 10% Discount
                  </span>
                  <span className="text-xs text-gray-500 mt-0.5">Add "Powered by AFK.live" to stream title</span>
                </div>
                <div className={`w-10 h-5 rounded-full relative transition-colors ${poweredByEnabled ? 'bg-primary' : 'bg-gray-300'}`}>
                  <div className={`w-4 h-4 bg-white dark:bg-zinc-900 rounded-full absolute top-0.5 transition-all ${poweredByEnabled ? 'left-5.5 right-0.5' : 'left-0.5'}`} style={{ left: poweredByEnabled ? 'calc(100% - 18px)' : '2px' }}></div>
                </div>
              </div>

              <button
                id="go-live-btn"
                onClick={handleGoLive}
                disabled={selectedVideos.length === 0 || selectedDestKeys.length === 0 || isStarting}
                className={`w-full text-lg py-4 rounded-xl font-bold transition-all flex items-center justify-center tracking-wide ${
                  selectedVideos.length > 0 && selectedDestKeys.length > 0 && !isStarting
                    ? 'bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white shadow-xl shadow-blue-600/20 hover:shadow-blue-600/40 hover:-translate-y-0.5'
                    : 'bg-gray-100 dark:bg-zinc-800 text-gray-400 cursor-not-allowed border border-gray-200 dark:border-zinc-700'
                }`}
              >
                {isStarting
                  ? <><i className="fa-solid fa-circle-notch fa-spin mr-3 text-xl"></i> Starting Broadcast...</>
                  : <><i className="fa-solid fa-satellite-dish mr-3 text-xl"></i> Go Live</>
                }
              </button>
              {/* Dynamic Tooltip/Help Text for Button */}
              {(selectedVideos.length === 0 || selectedDestKeys.length === 0) && !isStarting && (
                <div className="mt-3 text-center">
                  <p className="text-xs text-red-500 font-medium bg-red-50 py-1.5 px-3 rounded-lg inline-block border border-red-100">
                    <i className="fa-solid fa-circle-exclamation mr-1.5"></i>
                    {selectedVideos.length === 0 && selectedDestKeys.length === 0 
                      ? "Select at least one video and destination." 
                      : selectedVideos.length === 0 
                        ? "Select at least one video to continue." 
                        : "Select at least one destination to continue."}
                  </p>
                </div>
              )}
            </div>

          </div>
        </div>
      </div>

      {/* Video Preview Modal */}
      {showPreviewModal && selectedVideos.length > 0 && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 sm:p-6 bg-black/80 backdrop-blur-sm">
          <div className="bg-black rounded-2xl w-full max-w-5xl overflow-hidden shadow-2xl relative border border-gray-800">
            <div className="absolute top-0 left-0 right-0 p-4 bg-gradient-to-b from-black/80 to-transparent flex justify-between items-center z-10 pointer-events-none">
              <h3 className="text-white font-bold drop-shadow-md truncate pr-8">{selectedVideos[0].title || selectedVideos[0].originalFilename} {selectedVideos.length > 1 ? `(+${selectedVideos.length - 1} more)` : ''}</h3>
            </div>
            <button 
              onClick={() => setShowPreviewModal(false)}
              className="absolute top-4 right-4 z-20 w-10 h-10 bg-black/50 hover:bg-white dark:bg-zinc-900/10 rounded-full flex items-center justify-center text-white transition-colors border border-white/10"
            >
              <i className="fa-solid fa-xmark text-lg"></i>
            </button>
            <video 
              src={`/api/library/stream/${selectedVideos[0].id}`} 
              controls 
              autoPlay 
              className="w-full aspect-video object-contain bg-black outline-none"
            />
          </div>
        </div>
      )}

      {/* Add Destination Modal */}
      {showAddDestModal && (
        <div className="modal-overlay z-[100]">
          <div className="modal-box">
            <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-4">Add Destination</h3>
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Name / Label</label>
                <input 
                  type="text" 
                  placeholder="e.g. My Twitch Channel" 
                  className="input-field w-full border px-3 py-2 rounded"
                  value={newDest.name}
                  onChange={e => setNewDest({...newDest, name: e.target.value})}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Stream Key</label>
                <input 
                  type="password" 
                  placeholder="live_..." 
                  className="input-field w-full border px-3 py-2 rounded text-sm font-mono"
                  value={newDest.streamKey}
                  onChange={e => setNewDest({...newDest, streamKey: e.target.value})}
                />
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button className="btn text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:bg-zinc-800 px-4 py-2 rounded" onClick={() => setShowAddDestModal(false)}>Cancel</button>
              <button 
                className="btn btn-primary px-4 py-2 rounded text-white" 
                onClick={handleAddDestination} 
                disabled={savingDest || !newDest.name || !newDest.streamKey}
              >
                {savingDest ? 'Saving...' : 'Save Destination'}
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
