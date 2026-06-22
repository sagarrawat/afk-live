import React from 'react';
import LandingNavbar from '../landing/LandingNavbar';
import LandingFooter from '../landing/LandingFooter';

export default function ReleaseNotes() {
  const releases = [
    {
      version: "v2.4.0",
      date: "June 20, 2026",
      tag: "Major Update",
      title: "Full HD 1080p Support & Centralized Billing",
      content: (
        <>
          <p>We are thrilled to announce Full HD 1080p streaming support and a completely revamped billing engine!</p>
          <ul>
            <li><strong>Full HD Streaming:</strong> You can now stream at pristine 1080p resolution. Available now in the Studio.</li>
            <li><strong>Centralized Billing Engine:</strong> We completely rewrote our pricing engine. It now calculates costs dynamically based on the quality tier you select.</li>
            <li><strong>Upgraded Dashboards:</strong> The admin dashboard now features dedicated tabs for monitoring stream quotas and revenue.</li>
            <li><strong>Dark Mode Consistency:</strong> Fixed an issue where dark mode was not persisting across marketing pages.</li>
          </ul>
        </>
      )
    },
    {
      version: "v2.3.1",
      date: "June 15, 2026",
      tag: "Improvement",
      title: "UI Performance Upgrades",
      content: (
        <>
          <p>This minor release focuses on React component optimizations and quality of life improvements.</p>
          <ul>
            <li><strong>Infinite Scroll:</strong> The media library now supports infinite scroll for users with hundreds of video assets.</li>
            <li><strong>Reduced Bundle Size:</strong> Stripped out unused Tailwind CSS utility classes reducing initial load times by 40%.</li>
            <li><strong>Bug Fix:</strong> Fixed a bug where stream keys containing special characters would fail validation.</li>
          </ul>
        </>
      )
    },
    {
      version: "v2.3.0",
      date: "June 01, 2026",
      tag: "Feature",
      title: "Multi-Destination Streaming",
      content: (
        <>
          <p>Our most requested feature is finally here. You can now stream to multiple destinations simultaneously!</p>
          <ul>
            <li><strong>Simulcast:</strong> Stream the same looping video to YouTube, Twitch, and Kick at the same time.</li>
            <li><strong>Unified Metrics:</strong> View aggregate viewer counts across all platforms in the Analytics tab.</li>
            <li><strong>Pay-as-you-go Pricing:</strong> Additional destinations apply a transparent 1.5x multiplier to your hourly cost. No expensive monthly commitments required.</li>
          </ul>
        </>
      )
    }
  ];

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950 text-gray-900 dark:text-gray-100 font-sans relative overflow-hidden transition-colors duration-200 flex flex-col">
      <div className="absolute top-0 right-1/4 w-96 h-96 bg-accent/10 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[100px] opacity-70 pointer-events-none z-0"></div>
      
      <LandingNavbar />

      <main className="max-w-4xl mx-auto px-6 pt-40 pb-20 relative z-10 flex-1 w-full">
        <div className="mb-16">
          <h1 className="text-4xl md:text-5xl font-extrabold tracking-tight mb-4 text-gray-900 dark:text-white">
            Release Notes
          </h1>
          <p className="text-xl text-gray-500 dark:text-gray-400">
            Stay up to date with the latest features, improvements, and bug fixes for AFK Live.
          </p>
        </div>

        <div className="space-y-16 relative">
          {/* Vertical Timeline Line */}
          <div className="absolute left-4 md:left-[120px] top-4 bottom-0 w-px bg-gray-200 dark:bg-zinc-800 hidden md:block"></div>

          {releases.map((release, idx) => (
            <div key={idx} className="relative flex flex-col md:flex-row gap-6 md:gap-12">
              
              {/* Date Sidebar */}
              <div className="md:w-[120px] shrink-0 pt-2 relative">
                {/* Timeline Dot */}
                <div className="hidden md:block absolute right-[-28px] top-4 w-3 h-3 rounded-full bg-primary ring-4 ring-white dark:ring-zinc-950"></div>
                <div className="text-sm font-bold text-gray-400 dark:text-gray-500">{release.date}</div>
              </div>

              {/* Content Card */}
              <div className="flex-1 bg-white dark:bg-zinc-900/50 border border-gray-200 dark:border-white/10 rounded-3xl p-8 shadow-sm">
                <div className="flex items-center gap-3 mb-4">
                  <span className="px-3 py-1 bg-gray-100 dark:bg-zinc-800 text-gray-800 dark:text-gray-200 text-xs font-bold rounded-lg font-mono">
                    {release.version}
                  </span>
                  <span className={`px-3 py-1 text-xs font-bold rounded-lg ${
                    release.tag === 'Major Update' ? 'bg-primary/10 text-primary' : 
                    release.tag === 'Feature' ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' : 
                    'bg-gray-100 text-gray-600 dark:bg-zinc-800 dark:text-gray-400'
                  }`}>
                    {release.tag}
                  </span>
                </div>
                
                <h2 className="text-2xl font-bold text-gray-900 dark:text-white mb-6">
                  {release.title}
                </h2>
                
                <div className="prose prose-gray dark:prose-invert max-w-none text-gray-600 dark:text-gray-400">
                  {release.content}
                </div>
              </div>
            </div>
          ))}
        </div>
      </main>

      <LandingFooter />
    </div>
  );
}
