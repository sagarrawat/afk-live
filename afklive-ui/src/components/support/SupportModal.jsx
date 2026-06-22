import { useState, useRef } from 'react';

export default function SupportModal({ onClose }) {
  const [category, setCategory] = useState('Technical Issue');
  const [message, setMessage] = useState('');
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('idle'); // idle, submitting, success, error
  const [errorText, setErrorText] = useState('');
  const fileInputRef = useRef(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!message.trim()) return;

    setStatus('submitting');
    try {
      const formData = new FormData();
      formData.append('category', category);
      formData.append('message', message);
      if (file) formData.append('file', file);

      const getCsrfToken = () => document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || '';

      const res = await fetch('/api/support/ticket', {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
        body: formData,
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Failed to submit ticket');

      setStatus('success');
      setTimeout(() => {
        onClose();
      }, 2000);
    } catch (err) {
      setErrorText(err.message);
      setStatus('error');
    }
  };

  const handleFileChange = (e) => {
    const selected = e.target.files[0];
    if (selected) {
      if (selected.size > 10 * 1024 * 1024) {
        alert('File size must be less than 10MB');
        return;
      }
      setFile(selected);
    }
  };

  return (
    <div className="fixed inset-0 z-[100] bg-gray-900/50 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-white dark:bg-zinc-900 rounded-2xl shadow-2xl w-full max-w-lg flex flex-col relative overflow-hidden" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="p-6 border-b border-gray-100 dark:border-zinc-800 flex justify-between items-center bg-gray-50 dark:bg-zinc-950/50">
          <div>
            <h3 className="font-bold text-gray-900 dark:text-white text-lg">Contact Support</h3>
            <p className="text-xs text-gray-500 mt-0.5">We typically reply within 24 hours.</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:text-gray-400 transition">
            <i className="fa-solid fa-xmark text-xl"></i>
          </button>
        </div>

        {status === 'success' ? (
          <div className="p-10 text-center">
            <div className="w-16 h-16 bg-green-100 text-green-600 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fa-solid fa-check text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-900 dark:text-white mb-2">Ticket Submitted!</h4>
            <p className="text-gray-500 text-sm">We've received your request and will be in touch shortly.</p>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-6 space-y-5">
            {status === 'error' && (
              <div className="p-3 bg-red-50 border border-red-100 text-red-600 text-sm rounded-lg flex items-start gap-2">
                <i className="fa-solid fa-circle-exclamation mt-0.5"></i>
                <span>{errorText}</span>
              </div>
            )}

            <div>
              <label className="block text-sm font-semibold text-gray-700 dark:text-gray-300 mb-1.5">Category</label>
              <select 
                value={category} 
                onChange={e => setCategory(e.target.value)}
                className="w-full px-4 py-2.5 bg-gray-50 dark:bg-zinc-950 border border-gray-200 dark:border-zinc-700 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition"
              >
                <option>Technical Issue</option>
                <option>Billing Question</option>
                <option>Feature Request</option>
                <option>Other</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-semibold text-gray-700 dark:text-gray-300 mb-1.5">Message</label>
              <textarea 
                required
                value={message}
                onChange={e => setMessage(e.target.value)}
                rows="4" 
                className="w-full px-4 py-3 bg-gray-50 dark:bg-zinc-950 border border-gray-200 dark:border-zinc-700 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition resize-none"
                placeholder="Please describe your issue in detail..."
              ></textarea>
            </div>

            <div>
              <label className="block text-sm font-semibold text-gray-700 dark:text-gray-300 mb-1.5">Attachment (Optional)</label>
              <div 
                className="border-2 border-dashed border-gray-300 rounded-xl p-4 text-center hover:bg-gray-50 dark:bg-zinc-950 transition cursor-pointer relative"
                onClick={() => fileInputRef.current?.click()}
              >
                <input 
                  type="file" 
                  ref={fileInputRef}
                  className="hidden" 
                  onChange={handleFileChange}
                />
                {file ? (
                  <div className="flex items-center justify-between bg-blue-50 p-2 rounded-lg border border-blue-100">
                    <div className="flex items-center gap-2 truncate">
                      <i className="fa-solid fa-paperclip text-blue-500"></i>
                      <span className="text-sm font-medium text-blue-700 truncate">{file.name}</span>
                    </div>
                    <button 
                      type="button" 
                      onClick={(e) => { e.stopPropagation(); setFile(null); }}
                      className="text-gray-400 hover:text-red-500 p-1"
                    >
                      <i className="fa-solid fa-xmark"></i>
                    </button>
                  </div>
                ) : (
                  <div>
                    <i className="fa-solid fa-cloud-arrow-up text-gray-400 text-xl mb-1"></i>
                    <p className="text-xs text-gray-500">Click to upload screenshot or video (Max 10MB)</p>
                  </div>
                )}
              </div>
            </div>

            <div className="pt-2 flex gap-3">
              <button 
                type="button" 
                onClick={onClose}
                className="flex-1 px-4 py-2.5 bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300 font-bold rounded-xl hover:bg-gray-200 transition"
              >
                Cancel
              </button>
              <button 
                type="submit" 
                disabled={status === 'submitting' || !message.trim()}
                className="flex-1 px-4 py-2.5 bg-blue-600 text-white font-bold rounded-xl hover:bg-blue-700 transition shadow-lg shadow-blue-500/30 disabled:opacity-50 disabled:shadow-none flex justify-center items-center gap-2"
              >
                {status === 'submitting' ? (
                  <><i className="fa-solid fa-spinner fa-spin"></i> Sending...</>
                ) : (
                  <><i className="fa-solid fa-paper-plane"></i> Send Ticket</>
                )}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
