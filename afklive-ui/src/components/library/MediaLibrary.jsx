import { useLibrary } from '../../hooks/useLibrary';
import { useAuth } from '../../hooks/useAuth';
import VideoGrid from './VideoGrid';
import UploadDropzone from './UploadDropzone';

export default function MediaLibrary({ onGoLive }) {
  const { videos, isLoading, refresh } = useLibrary();
  const { user } = useAuth();

  return (
    <div className="w-full min-h-full p-6 md:p-8">
      <div className="max-w-[1600px] mx-auto flex flex-col">

        {/* Header */}
        <header className="mb-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div></div>
          
          <div className="flex items-center gap-6">
            {/* Retention Policy UI */}
            <div className="hidden md:flex items-center gap-2 px-3 py-1.5 bg-gray-50 dark:bg-zinc-900 rounded-lg border border-gray-200 dark:border-zinc-800 text-xs font-medium text-gray-500 dark:text-gray-400">
              <i className="fa-regular fa-clock text-blue-500"></i>
              <span>30-Day Auto Retention</span>
            </div>

            <div className="flex items-center gap-3">
              <button
                onClick={() => document.getElementById('bulkUploadInput').click()}
                className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-700 text-gray-700 dark:text-gray-300 font-medium py-2 px-4 rounded-lg hover:bg-gray-50 dark:bg-zinc-950 transition flex items-center gap-2 shadow-sm"
              >
                <i className="fa-solid fa-cloud-arrow-up text-blue-600"></i> Upload Video
              </button>
              <input
                type="file"
                id="bulkUploadInput"
                multiple
                hidden
                accept="video/*"
                onChange={e => {
                  // Handled inside UploadDropzone via the same input id
                  document.dispatchEvent(new CustomEvent('upload-files', { detail: e.target.files }));
                  e.target.value = '';
                }}
              />
            </div>
          </div>
        </header>

        {/* Upload Dropzone */}
        <UploadDropzone onUploadComplete={refresh} />

        {/* Content */}
        <div className="flex-1 mt-6">
          {isLoading ? (
            <div className="text-center py-20">
              <div className="afk-loader mb-4"><div className="afk-loader-play"></div></div>
              <p className="text-gray-500">Loading your library...</p>
            </div>
          ) : videos.length === 0 ? (
            <div className="text-center py-24 bg-white dark:bg-zinc-900 rounded-3xl border border-dashed border-gray-300">
              <div className="w-20 h-20 bg-gray-50 dark:bg-zinc-950 text-gray-400 rounded-full flex items-center justify-center mx-auto mb-6">
                <i className="fa-solid fa-film text-3xl"></i>
              </div>
              <h3 className="text-xl font-semibold text-gray-900 dark:text-white">Library is empty</h3>
              <p className="text-gray-500 mt-2 mb-6">Upload a video to start streaming.</p>
              <button
                onClick={() => document.getElementById('bulkUploadInput').click()}
                className="bg-blue-600 hover:bg-blue-700 text-white font-medium py-2 px-6 rounded-full transition shadow-lg shadow-blue-500/30"
              >
                Upload Video
              </button>
            </div>
          ) : (
            <VideoGrid videos={videos} onRefresh={refresh} onGoLive={onGoLive} />
          )}
        </div>
      </div>
    </div>
  );
}
