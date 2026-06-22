import { useState, useRef, useEffect } from 'react';

export default function UploadDropzone({ onUploadComplete }) {
  const [uploads, setUploads] = useState([]); // [{ name, progress, status }]
  const [isDragging, setIsDragging] = useState(false);
  const dropRef = useRef(null);
  const timersRef = useRef([]);

  // Listen for file input from the header button
  useEffect(() => {
    const handler = e => uploadFiles(e.detail);
    document.addEventListener('upload-files', handler);
    return () => {
      document.removeEventListener('upload-files', handler);
      timersRef.current.forEach(clearTimeout);
    };
  }, []);

  const uploadFiles = (fileList) => {
    const files = Array.from(fileList);
    files.forEach(uploadSingleFile);
  };

  const uploadSingleFile = async (file) => {
    const id = Date.now() + Math.random();
    setUploads(prev => [...prev, { id, name: file.name, progress: 0, status: 'uploading' }]);

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload');
    xhr.withCredentials = true;

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded / e.total) * 100);
        setUploads(prev => prev.map(u => u.id === id ? { ...u, progress: pct } : u));
      }
    };

    xhr.onload = () => {
      const success = xhr.status >= 200 && xhr.status < 300;
      setUploads(prev => prev.map(u => u.id === id ? { ...u, status: success ? 'done' : 'error', progress: 100 } : u));
      if (success) {
        onUploadComplete?.();
        // Auto-remove after 3s
        const tId = setTimeout(() => setUploads(prev => prev.filter(u => u.id !== id)), 3000);
        timersRef.current.push(tId);
      }
    };

    xhr.onerror = () => {
      setUploads(prev => prev.map(u => u.id === id ? { ...u, status: 'error' } : u));
    };

    xhr.send(formData);
  };

  const onDrop = (e) => {
    e.preventDefault();
    setIsDragging(false);
    uploadFiles(e.dataTransfer.files);
  };

  if (uploads.length === 0) return null;

  return (
    <div className="space-y-2">
      {uploads.map(u => (
        <div key={u.id} className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-700 rounded-xl px-4 py-3 flex items-center gap-4">
          <i className={`fa-solid fa-file-video text-blue-500 text-lg`}></i>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-gray-900 dark:text-white truncate">{u.name}</p>
            <div className="progress-track mt-1">
              <div
                className={`progress-fill transition-all ${u.status === 'error' ? 'danger' : u.status === 'done' ? 'bg-green-500' : ''}`}
                style={{ width: `${u.progress}%` }}
              ></div>
            </div>
          </div>
          <span className={`text-xs font-semibold ${u.status === 'done' ? 'text-green-600' : u.status === 'error' ? 'text-red-600' : 'text-gray-500'}`}>
            {u.status === 'done' ? '✓ Done' : u.status === 'error' ? '✗ Failed' : `${u.progress}%`}
          </span>
        </div>
      ))}
    </div>
  );
}
