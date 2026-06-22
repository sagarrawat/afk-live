import React, { useState, useEffect } from 'react';

export default function CookieBanner() {
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    const consent = localStorage.getItem('afk-cookie-consent');
    if (!consent) {
      // Small delay for better UX
      const timer = setTimeout(() => setIsVisible(true), 1000);
      return () => clearTimeout(timer);
    }
  }, []);

  const handleAccept = () => {
    localStorage.setItem('afk-cookie-consent', 'true');
    setIsVisible(false);
  };

  if (!isVisible) return null;

  return (
    <div className="fixed bottom-4 left-4 right-4 md:left-auto md:right-8 md:bottom-8 z-[99990] md:max-w-sm">
      <div className="bg-white/80 dark:bg-zinc-900/80 backdrop-blur-xl border border-gray-200 dark:border-white/10 rounded-2xl shadow-2xl p-6 text-sm transform transition-all duration-500 translate-y-0 opacity-100">
        <div className="flex items-start gap-4">
          <div className="w-10 h-10 rounded-full bg-primary/10 dark:bg-primary/20 flex items-center justify-center shrink-0 mt-0.5">
            <i className="fa-solid fa-cookie-bite text-primary text-lg"></i>
          </div>
          <div className="flex-1">
            <h3 className="text-gray-900 dark:text-white font-bold text-base mb-1">We value your privacy</h3>
            <p className="text-gray-600 dark:text-gray-400 mb-4 leading-relaxed">
              We use cookies to enhance your browsing experience, serve personalized content, and analyze our traffic. By clicking "Accept", you consent to our use of cookies.
            </p>
            <div className="flex items-center gap-3">
              <button 
                onClick={handleAccept}
                className="flex-1 bg-gray-900 dark:bg-white text-white dark:text-gray-900 font-bold py-2.5 px-4 rounded-xl hover:opacity-90 transition-opacity text-center shadow-md"
              >
                Accept
              </button>
              <button 
                onClick={() => setIsVisible(false)}
                className="flex-1 bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300 font-semibold py-2.5 px-4 rounded-xl hover:bg-gray-200 dark:hover:bg-zinc-700 transition-colors text-center"
              >
                Decline
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
