import { useState } from 'react';
import toast from 'react-hot-toast';

const STATUS_CONFIG = {
  STREAM_READY:        { label: 'Ready',       color: 'bg-green-100 text-green-700',  icon: 'fa-circle-check' },
  ANALYZING:           { label: 'Ready',       color: 'bg-green-100 text-green-700',  icon: 'fa-circle-check' },
  REQUIRES_OPTIMIZATION: { label: 'Ready', color: 'bg-green-100 text-green-700', icon: 'fa-circle-check' },
  OPTIMIZATION_FAILED: { label: 'Ready',       color: 'bg-green-100 text-green-700',  icon: 'fa-circle-check' },
  PENDING_ANALYSIS:    { label: 'Ready',       color: 'bg-green-100 text-green-700',  icon: 'fa-circle-check' },
};

function formatBytes(bytes) {
  if (!bytes) return '';
  const mb = bytes / (1024 * 1024);
  return mb >= 1000 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(1)} MB`;
}

function formatDuration(seconds) {
  if (!seconds) return '';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
    : `${m}:${String(s).padStart(2,'0')}`;
}

export default function VideoCard({ video, onGoLive, onRefresh }) {
  const [deleting, setDeleting] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [newTitle, setNewTitle] = useState(video.title || video.originalFilename);

  const status = STATUS_CONFIG[video.streamReadiness] || STATUS_CONFIG.STREAM_READY;
  const isReady = true;
  const needsAttention = false;

  const getCsrfToken = () => document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || '';

  const handleRename = async () => {
    setEditingTitle(false);
    if (!newTitle.trim() || newTitle === (video.title || video.originalFilename)) {
      setNewTitle(video.title || video.originalFilename);
      return;
    }
    try {
      const res = await fetch(`/api/library/${video.id}/title`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        },
        credentials: 'include',
        body: JSON.stringify({ title: newTitle.trim() })
      });
      if (res.ok) {
        toast.success('Title updated');
        onRefresh?.();
      } else {
        toast.error('Failed to update title');
        setNewTitle(video.title || video.originalFilename);
      }
    } catch (e) {
      toast.error('Failed to update title');
      setNewTitle(video.title || video.originalFilename);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await fetch(`/api/library/${video.id}`, {
        method: 'DELETE', credentials: 'include',
      });
      toast.success('Video deleted');
      onRefresh?.();
    } catch (e) {
      toast.error('Failed to delete video.');
    } finally {
      setDeleting(false);
      setShowDeleteModal(false);
    }
  };

  return (
    <div className={`group relative bg-white dark:bg-zinc-900 rounded-2xl border shadow-sm hover:shadow-xl transition-all duration-300 hover:-translate-y-1 overflow-hidden ${needsAttention ? 'border-orange-300' : 'border-gray-200 dark:border-zinc-700'}`}>

      {/* Thumbnail */}
      <div className="aspect-video bg-gray-100 dark:bg-zinc-800 relative overflow-hidden">
        {video.thumbnailS3Key ? (
          <img
            src={`/api/videos/${video.id}/thumbnail`}
            className="w-full h-full object-cover transition duration-500 group-hover:scale-105"
            alt={video.title || video.originalFilename}
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-gray-300">
            <i className="fa-solid fa-clapperboard text-3xl opacity-50"></i>
          </div>
        )}

        {/* Duration badge */}
        {video.durationSeconds > 0 && (
          <div className="absolute bottom-2 right-2 bg-black/60 text-white text-[10px] font-bold px-1.5 py-0.5 rounded">
            {formatDuration(video.durationSeconds)}
          </div>
        )}

        {/* Delete button (hover) */}
        <button
          onClick={handleDelete}
          disabled={deleting}
          className="absolute top-2 right-2 w-7 h-7 bg-black/50 hover:bg-red-600 text-white rounded-full opacity-0 group-hover:opacity-100 transition-all flex items-center justify-center text-xs"
          title="Delete"
        >
          <i className="fa-solid fa-trash"></i>
        </button>
      </div>

      {/* Info */}
      <div className="p-3">
        <div className="flex items-center justify-between mb-1 group/title">
          {editingTitle ? (
            <input
              type="text"
              autoFocus
              className="text-sm font-semibold text-gray-900 dark:text-white border-b border-blue-500 outline-none w-full mr-2 px-1"
              value={newTitle}
              onChange={e => setNewTitle(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter') handleRename();
                if (e.key === 'Escape') { setEditingTitle(false); setNewTitle(video.title || video.originalFilename); }
              }}
              onBlur={handleRename}
            />
          ) : (
            <p className="text-sm font-semibold text-gray-900 dark:text-white truncate" title={video.title || video.originalFilename}>
              {video.title || video.originalFilename}
            </p>
          )}
          {!editingTitle && (
            <button onClick={() => setEditingTitle(true)} className="text-gray-400 hover:text-blue-500 opacity-0 group-hover/title:opacity-100 transition-opacity p-1">
              <i className="fa-solid fa-pencil text-[10px]"></i>
            </button>
          )}
        </div>
        
        <div className="flex items-center justify-between mb-3">
          <div className="flex flex-col">
            <span className="text-xs text-gray-400">{formatBytes(video.fileSize)}</span>
            {video.createdAt && (
              <span className="text-[9px] text-gray-400/80 mt-0.5" title={new Date(video.createdAt).toLocaleString()}>
                {new Date(video.createdAt).toLocaleDateString()}
              </span>
            )}
          </div>
          <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full flex items-center gap-1 h-fit ${status.color}`}>
            <i className={`fa-solid ${status.icon} text-[9px]`}></i>
            {status.label}
          </span>
        </div>



        {/* Go Live button */}
        <button
          onClick={() => onGoLive?.(video)}
          disabled={!isReady}
          className={`w-full py-1.5 rounded-lg text-sm font-semibold transition ${
            isReady
              ? 'bg-blue-600 hover:bg-blue-700 text-white shadow shadow-blue-500/30 cursor-pointer'
              : 'bg-gray-100 dark:bg-zinc-800 text-gray-400 cursor-not-allowed'
          }`}
        >
          {isReady ? (
            <><i className="fa-solid fa-satellite-dish mr-1"></i> Go Live</>
          ) : (
            status.label === 'Processing' || status.label === 'Analyzing' ? 'Processing...' : 'Not Ready'
          )}
        </button>
      </div>

      {/* Delete Confirmation Modal */}
      {showDeleteModal && (
        <div className="modal-overlay z-[100]">
          <div className="modal-box max-w-sm w-full bg-white dark:bg-zinc-900 rounded-xl shadow-xl">
            <div className="modal-header px-0 pt-0 border-b border-gray-100 dark:border-zinc-800 pb-3 mb-4 flex justify-between items-center">
              <h2 className="text-xl font-bold text-gray-900 dark:text-white text-red-600">Delete Video</h2>
              <button className="btn btn-text text-gray-400 hover:text-gray-600 dark:text-gray-400 p-1" onClick={() => setShowDeleteModal(false)}>
                <i className="fa-solid fa-xmark text-lg"></i>
              </button>
            </div>
            <div className="space-y-4">
              <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">
                Are you sure you want to delete <span className="font-bold">{video.title || video.originalFilename}</span>? This action cannot be undone.
              </p>
            </div>
            <div className="modal-footer px-0 pb-0 mt-6 pt-4 border-t border-gray-100 dark:border-zinc-800 flex gap-3">
              <button className="btn bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200 flex-1 py-2 rounded-lg font-medium" onClick={() => setShowDeleteModal(false)}>Cancel</button>
              <button 
                className="btn bg-red-600 hover:bg-red-700 text-white flex-1 shadow-md flex justify-center items-center gap-2 py-2 rounded-lg font-medium" 
                onClick={handleDelete} 
                disabled={deleting}
              >
                {deleting ? <i className="fa-solid fa-spinner fa-spin"></i> : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
