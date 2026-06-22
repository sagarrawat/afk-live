import { useState, useEffect, useRef } from 'react';
import toast from 'react-hot-toast';

export default function NotificationBell() {
  const [notifications, setNotifications] = useState([]);
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef(null);

  // Fetch notifications
  const fetchNotifications = async () => {
    try {
      const res = await fetch('/api/notifications?unreadOnly=true');
      if (res.ok) {
        const data = await res.json();
        
        // Check for new critical billing alerts to trigger a toast
        if (notifications.length > 0) {
          const newAlerts = data.filter(d => !notifications.find(n => n.id === d.id));
          newAlerts.forEach(alert => {
            if (alert.type === 'BILLING_WARNING' || alert.type === 'BILLING_ALERT') {
              toast.error(alert.message, {
                duration: 8000,
                position: 'top-right',
                icon: '⚠️',
              });
              
              // Trigger browser notification if permitted
              if (Notification.permission === 'granted') {
                new Notification(alert.title, { body: alert.message });
              }
            }
          });
        }
        
        setNotifications(data);
      }
    } catch (err) {
      console.error('Failed to fetch notifications', err);
    }
  };

  useEffect(() => {
    // Request permission for browser notifications
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission();
    }

    fetchNotifications();
    const interval = setInterval(fetchNotifications, 30000); // poll every 30s
    return () => clearInterval(interval);
  }, []);

  // Close dropdown on click outside
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const markAsRead = async (id) => {
    try {
      await fetch(`/api/notifications/${id}/read`, { method: 'PUT' });
      setNotifications(prev => prev.filter(n => n.id !== id));
    } catch (err) {
      console.error(err);
    }
  };

  const markAllAsRead = async () => {
    try {
      await fetch('/api/notifications/read-all', { method: 'PUT' });
      setNotifications([]);
      setIsOpen(false);
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <div className="relative" ref={dropdownRef}>
      <button 
        onClick={() => setIsOpen(!isOpen)}
        className={`w-10 h-10 rounded-full flex items-center justify-center transition-colors relative
          ${isOpen 
            ? 'bg-brand-blue text-white' 
            : 'bg-gray-100 dark:bg-zinc-900 text-gray-500 dark:text-gray-400 hover:text-brand-blue'}`}
      >
        <i className="fa-regular fa-bell"></i>
        {notifications.length > 0 && (
          <span className="absolute top-2 right-2 w-2 h-2 bg-red-500 rounded-full border-2 border-white dark:border-zinc-900"></span>
        )}
      </button>

      {isOpen && (
        <div className="absolute top-12 right-0 w-80 bg-white dark:bg-zinc-900 shadow-2xl rounded-2xl border border-gray-100 dark:border-zinc-800 z-50 overflow-hidden backdrop-blur-xl bg-opacity-95 dark:bg-opacity-95">
          <div className="p-4 border-b border-gray-100 dark:border-zinc-800 flex justify-between items-center">
            <h3 className="font-bold text-gray-900 dark:text-white">Notifications</h3>
            {notifications.length > 0 && (
              <button 
                onClick={markAllAsRead}
                className="text-xs text-brand-blue hover:text-blue-700 font-medium"
              >
                Mark all read
              </button>
            )}
          </div>
          
          <div className="max-h-96 overflow-y-auto">
            {notifications.length === 0 ? (
              <div className="p-8 text-center text-gray-500 dark:text-gray-400 text-sm">
                <i className="fa-regular fa-bell-slash text-2xl mb-2 opacity-50"></i>
                <p>No new notifications</p>
              </div>
            ) : (
              <div className="divide-y divide-gray-100 dark:divide-zinc-800/50">
                {notifications.map(n => (
                  <div key={n.id} className="p-4 hover:bg-gray-50 dark:hover:bg-white/5 transition-colors group relative cursor-pointer" onClick={() => markAsRead(n.id)}>
                    <div className="flex gap-3">
                      <div className={`mt-1 flex-shrink-0 w-2 h-2 rounded-full ${n.type.includes('BILLING') ? 'bg-red-500' : 'bg-brand-blue'}`}></div>
                      <div>
                        <h4 className="text-sm font-bold text-gray-900 dark:text-white mb-1">{n.title}</h4>
                        <p className="text-xs text-gray-600 dark:text-gray-400 leading-relaxed">{n.message}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
