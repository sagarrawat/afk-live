import React from 'react';
import { Link } from 'react-router-dom';

export default function LandingNavbar() {
  const toggleTheme = () => {
    const root = document.documentElement;
    if (root.classList.contains('dark')) {
      root.classList.remove('dark');
      localStorage.setItem('afk-theme', 'light');
    } else {
      root.classList.add('dark');
      localStorage.setItem('afk-theme', 'dark');
    }
  };

  return (
    <nav className="fixed top-0 w-full z-50 glass-nav transition-all duration-300 flex flex-col">
      {/* Announcement Bar */}
      <div className="bg-amber-400 text-amber-950 px-4 py-2.5 text-center text-xs md:text-sm font-bold flex items-center justify-center gap-2 shadow-sm border-b border-amber-500/20">
        <i className="fa-solid fa-triangle-exclamation animate-pulse text-amber-700"></i>
        <span>High Load Notice: Due to unprecedented demand, the free tier is temporarily disabled. Please add funds to stream.</span>
      </div>
      <div className="max-w-7xl mx-auto px-6 h-20 flex items-center justify-between w-full">
        <a href="/" className="afk-logo">
          <div className="afk-icon"><div className="afk-play"></div></div>
          <div className="afk-text">AFK<span className="afk-live">Live</span></div>
        </a>
        
        <div className="hidden md:flex items-center gap-8">
          <a href="/#how-it-works" className="text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">How it Works</a>
          <a href="/#pricing" className="text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">Pricing</a>
          <a href="/#faq" className="text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">FAQ</a>
          <Link to="/blog" className="text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">Blog</Link>
          <Link to="/releases" className="text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">Release Notes</Link>
        </div>
        
        <div className="flex items-center gap-4">
          <button onClick={toggleTheme} className="text-gray-500 dark:text-gray-400 hover:text-brand-blue transition mr-2" aria-label="Toggle Dark Mode">
            <i className="fa-solid fa-moon dark:hidden"></i>
            <i className="fa-solid fa-sun hidden dark:inline"></i>
          </button>
          <a href="/login" className="hidden sm:block text-sm font-semibold text-gray-700 dark:text-gray-300 hover:text-brand-blue transition">Log in</a>
          <a href="/register" className="text-sm font-semibold bg-gray-900 text-white px-5 py-2.5 rounded-full hover:bg-gray-800 transition shadow-lg shadow-gray-900/20 hover:-translate-y-0.5 dark:bg-primary dark:hover:bg-blue-600">Start Streaming</a>
        </div>
      </div>
    </nav>
  );
}
