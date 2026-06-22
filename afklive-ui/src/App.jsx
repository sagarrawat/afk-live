import { useState, useEffect } from 'react'
import { Routes, Route, useNavigate, useLocation, Link, Navigate } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import { useAuth } from './hooks/useAuth'
import MediaLibrary from './components/library/MediaLibrary'
import StreamStudio from './components/studio/StreamStudio'
import Settings from './components/settings/Settings'
import Analytics from './components/analytics/Analytics'
import Scheduling from './components/scheduling/Scheduling'
import SupportModal from './components/support/SupportModal'
import OnboardingModal from './components/onboarding/OnboardingModal'
import ProtectedRoute from './components/common/ProtectedRoute'
import LandingPage from './components/landing/LandingPage'
import GettingStartedGuide from './components/blog/GettingStartedGuide'
import BlogIndex from './components/blog/BlogIndex'
import ReleaseNotes from './components/blog/ReleaseNotes'
import ForgotPassword from './components/auth/ForgotPassword'
import LegalPage from './components/legal/LegalPage'
import CookieBanner from './components/common/CookieBanner'
import NotificationBell from './components/common/NotificationBell'

const NAV = [
  {
    section: 'Publishing',
    items: [
      { id: 'studio',   label: 'Live Studio',    icon: 'fa-satellite-dish', requiresAuth: true },
      { id: 'library',  label: 'Media Library',  icon: 'fa-film',           requiresAuth: false },
    ],
  },
  {
    section: 'Insights',
    items: [
      { id: 'analytics',label: 'Analytics',      icon: 'fa-chart-line',     requiresAuth: true },
    ],
  },
  {
    section: 'Account',
    items: [
      { id: 'settings', label: 'Settings',       icon: 'fa-gear',           requiresAuth: true },
    ],
  },
];

export default function App() {
  const { user, isAuthenticated, isLoading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen]     = useState(false);
  const [initialVideo, setInitialVideo]   = useState(null);
  const [showSupportModal, setShowSupportModal] = useState(false);
  const [theme, setTheme] = useState(localStorage.getItem('afk-theme') === 'light' ? 'light' : 'dark');

  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
    localStorage.setItem('afk-theme', theme);
  }, [theme]);

  let activeView = location.pathname.split('/')[1];
  if (!activeView) activeView = 'library';

  const handleNav = (view, requiresAuth) => {
    if (requiresAuth && !isAuthenticated) {
      window.location.href = '/login';
      return;
    }
    navigate(`/${view}`);
    setSidebarOpen(false);
  };

  const handleGoLive = (video) => {
    if (!isAuthenticated) { window.location.href = '/login'; return; }
    setInitialVideo(video);
    navigate('/studio');
    setSidebarOpen(false);
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-screen bg-gray-50">
        <div className="afk-loader"><div className="afk-loader-play"></div></div>
      </div>
    );
  }

  const isDashboardRoute = ['/library', '/studio', '/scheduling', '/analytics', '/settings'].some(path => location.pathname.startsWith(path));
  const isFullScreenPage = !isDashboardRoute;

  return (
    <div className={isFullScreenPage ? "flex h-screen w-screen overflow-hidden bg-white dark:bg-zinc-950 transition-colors duration-200" : "app-layout"}>
      <Toaster position="top-right" />

      {/* Mobile backdrop */}
      {!isFullScreenPage && sidebarOpen && (
        <div className="sidebar-overlay active" onClick={() => setSidebarOpen(false)}></div>
      )}

      {/* Sidebar */}
      {!isFullScreenPage && (
      <nav className={`sub-sidebar ${sidebarOpen ? 'open' : ''} shrink-0`}>
        {/* Logo */}
        <div className="sidebar-header">
          <Link className="afk-logo" to="/">
            <div className="afk-icon"><div className="afk-play"></div></div>
            <span className="afk-text">AFK<span className="afk-live"> Live</span></span>
          </Link>
        </div>

        {/* Nav items */}
        <div className="menu">
          {NAV.map((group, idx) => (
            <div key={idx}>
              <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest px-3 mb-1 mt-3">
                {group.section}
              </p>
              {group.items.map(item => (
                <Link
                  key={item.id}
                  to={item.id === 'library' ? '/library' : `/${item.id}`}
                  className={`menu-item w-full text-left ${activeView === item.id ? 'active' : ''}`}
                  onClick={() => setSidebarOpen(false)}
                >
                  <i className={`fa-solid ${item.icon}`}></i> {item.label}
                  {item.requiresAuth && !isAuthenticated && (
                    <i className="fa-solid fa-lock text-xs text-gray-300 ml-auto"></i>
                  )}
                </Link>
              ))}
            </div>
          ))}
        </div>

        <div className="menu-divider"></div>
        {isAuthenticated ? (
          <div>
            <button onClick={() => setShowSupportModal(true)} className="menu-item w-full text-left">
              <i className="fa-regular fa-life-ring"></i> Support
            </button>
            


            <div className="px-3 py-2 mb-1 mt-2 flex items-center justify-between">
              <p className="text-xs font-semibold text-gray-700 dark:text-gray-300 truncate">{user?.email || user?.username}</p>
              <a href="/logout" className="text-gray-400 hover:text-red-500 transition-colors" title="Log out">
                <i className="fa-solid fa-right-from-bracket"></i>
              </a>
            </div>
          </div>
        ) : (
          <div>
            <button onClick={() => setShowSupportModal(true)} className="menu-item w-full text-left">
              <i className="fa-regular fa-life-ring"></i> Support
            </button>
            


            <a href="/login" className="menu-item mt-4 font-semibold" style={{ color: 'var(--primary)' }}>
              <i className="fa-solid fa-right-to-bracket"></i> Log in
            </a>
          </div>
        )}
      </nav>
      )}

      {/* Main content */}
      <div className={isFullScreenPage ? "flex flex-col flex-1 min-w-0 overflow-hidden relative" : "content-area relative flex flex-col"}>
        
        {/* Desktop Top Header Bar */}
        {!isFullScreenPage && (
          <div className="hidden md:flex h-16 border-b border-gray-200 dark:border-zinc-800 bg-white dark:bg-zinc-950 items-center justify-end px-6 shrink-0 z-20">
            <div className="flex items-center gap-4">
              <button 
                onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} 
                className="w-10 h-10 rounded-full bg-gray-100 dark:bg-zinc-900 flex items-center justify-center text-gray-500 dark:text-gray-400 hover:text-brand-blue transition-colors"
              >
                {theme === 'dark' ? <i className="fa-solid fa-sun"></i> : <i className="fa-solid fa-moon"></i>}
              </button>
              
              
              <NotificationBell />
              
              
              <div className="flex items-center gap-2 pl-4 border-l border-gray-200 dark:border-zinc-800">
                <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-brand-blue to-blue-400 text-white flex items-center justify-center text-sm font-bold ml-2">
                  {user?.username?.charAt(0).toUpperCase() || 'U'}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Mobile header */}
        {!isFullScreenPage && (
        <div className="mobile-header">
          <button className="hamburger" onClick={() => setSidebarOpen(true)}>
            <i className="fa-solid fa-bars"></i>
          </button>
          <span className="mobile-title">
            {NAV.flatMap(g => g.items).find(i => i.id === activeView)?.label || 'AFK Live'}
          </span>
        </div>
        )}

        {/* Views Container */}
        <main className={isFullScreenPage ? "flex-1 overflow-y-auto overflow-x-hidden w-full h-full" : "view-section"}>
          <Routes>
            {/* Public Route */}
            <Route path="/" element={isAuthenticated ? <Navigate to="/library" replace /> : <LandingPage />} />
            <Route path="/blog" element={<BlogIndex />} />
            <Route path="/releases" element={<ReleaseNotes />} />
            <Route path="/blog/how-to-set-up-24-7-live-stream-on-youtube" element={<GettingStartedGuide />} />
            <Route path="/forgot-password" element={<ForgotPassword />} />
            <Route path="/terms" element={<LegalPage type="terms" />} />
            <Route path="/privacy" element={<LegalPage type="privacy" />} />
            <Route path="/refund-policy" element={<LegalPage type="refund-policy" />} />
            <Route path="/disclaimer" element={<LegalPage type="disclaimer" />} />
            
            {/* Protected Routes */}
            <Route path="/library" element={<ProtectedRoute><MediaLibrary onGoLive={handleGoLive} /></ProtectedRoute>} />
            <Route path="/studio/*" element={<ProtectedRoute><StreamStudio initialVideo={initialVideo} /></ProtectedRoute>} />
            <Route path="/scheduling" element={<ProtectedRoute><Scheduling /></ProtectedRoute>} />
            <Route path="/analytics" element={<ProtectedRoute><Analytics /></ProtectedRoute>} />
            <Route path="/settings" element={<ProtectedRoute><Settings /></ProtectedRoute>} />
            
            {/* Fallback 404 Route */}
            <Route path="*" element={
              <div className="flex flex-col items-center justify-center min-h-screen bg-gray-50 dark:bg-zinc-950 text-gray-900 dark:text-gray-100">
                <i className="fa-solid fa-triangle-exclamation text-4xl text-brand-blue mb-4"></i>
                <h1 className="text-3xl font-bold mb-2">Page Not Found</h1>
                <p className="text-gray-500 mb-6">The page you are looking for doesn't exist or has been moved.</p>
                <a href="/" className="bg-brand-blue text-white px-6 py-2 rounded-lg font-bold hover:bg-blue-700 transition">Go Home</a>
              </div>
            } />
          </Routes>
        </main>
      </div>

      {showSupportModal && <SupportModal onClose={() => setShowSupportModal(false)} />}
      <OnboardingModal />
      <CookieBanner />
    </div>
  );
}
