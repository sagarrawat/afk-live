import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import LandingNavbar from '../landing/LandingNavbar';
import LandingFooter from '../landing/LandingFooter';

export default function GettingStartedGuide() {
  const [activeSection, setActiveSection] = useState('');

  useEffect(() => {
    window.scrollTo(0, 0);

    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          setActiveSection(entry.target.id);
        }
      });
    }, { rootMargin: '-20% 0px -70% 0px' });

    document.querySelectorAll('h2[id]').forEach((section) => {
      observer.observe(section);
    });

    return () => observer.disconnect();
  }, []);

  const toc = [
    { id: 'prerequisites', title: 'Prerequisites' },
    { id: 'step-1', title: 'Get Your Stream Key' },
    { id: 'step-2', title: 'Upload Your Media' },
    { id: 'step-3', title: 'Configure Studio' },
    { id: 'step-4', title: 'Go Live!' }
  ];

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950 text-gray-900 dark:text-gray-100 font-sans relative overflow-hidden transition-colors duration-200 flex flex-col">
      {/* Background Glows */}
      <div className="absolute top-0 left-1/4 w-96 h-96 bg-primary/20 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[100px] opacity-70 animate-blob pointer-events-none z-0"></div>
      <div className="absolute top-1/4 right-1/4 w-96 h-96 bg-accent/10 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[100px] opacity-50 animate-blob animation-delay-2000 pointer-events-none z-0"></div>

      <LandingNavbar />

      <main className="max-w-7xl mx-auto px-6 pt-40 pb-20 relative z-10 flex-1 w-full grid grid-cols-1 lg:grid-cols-[200px_minmax(0,1fr)_200px] gap-12">
        
        {/* Left Sidebar: Author Info */}
        <div className="hidden lg:block relative">
          <div className="sticky top-32">
            <div className="flex flex-col gap-4">
              <div className="w-16 h-16 rounded-full bg-gradient-to-tr from-primary to-blue-500 p-1">
                <img src="/avatar.jpg" alt="Author" className="w-full h-full rounded-full object-cover border-2 border-white dark:border-zinc-950 bg-gray-200" onError={(e) => { e.target.src = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="%23ccc"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>' }} />
              </div>
              <div>
                <p className="font-bold text-gray-900 dark:text-white mb-0.5">Monika</p>
                <p className="text-sm text-gray-500 dark:text-gray-400">Content & Growth</p>
              </div>
              <div className="h-px w-8 bg-gray-200 dark:bg-zinc-800 my-2"></div>
              <p className="text-sm text-gray-500 dark:text-gray-400 flex items-center gap-2">
                <i className="fa-solid fa-calendar-day"></i> Jun 22, 2026
              </p>
              <p className="text-sm text-gray-500 dark:text-gray-400 flex items-center gap-2">
                <i className="fa-solid fa-clock"></i> 5 min read
              </p>
            </div>
          </div>
        </div>

        {/* Center Content */}
        <article className="max-w-3xl w-full">
          <div className="mb-12">
            <div className="inline-flex items-center gap-2 mb-6 px-4 py-1.5 rounded-full bg-primary/10 dark:bg-primary/20 text-primary text-xs font-semibold tracking-wide uppercase">
              Guide
            </div>
            <h1 className="text-4xl md:text-5xl lg:text-6xl font-extrabold tracking-tight mb-6 text-gray-900 dark:text-white leading-[1.1]">
              How to Set Up a 24/7 YouTube Live Stream in 2026
            </h1>
            <p className="text-xl text-gray-600 dark:text-gray-400 font-medium">
              Set up a continuous 24/7 YouTube live stream using AFK Live's cloud infrastructure. No dedicated PC, VPS, or complex OBS setups required.
            </p>
          </div>

          <div className="prose prose-lg dark:prose-invert max-w-none text-gray-700 dark:text-gray-300">
            <p className="text-xl leading-relaxed mb-8">
              Running a 24/7 live stream on YouTube used to mean leaving a gaming PC on around the clock or renting expensive, hard-to-configure Virtual Private Servers (VPS). Today, cloud playout platforms like AFK Live have completely automated this process.
            </p>

            <h2 id="prerequisites" className="text-2xl font-bold text-gray-900 dark:text-white mt-12 mb-6 border-b border-gray-100 dark:border-zinc-800 pb-4 scroll-mt-24">
              Prerequisites
            </h2>
            <ul className="space-y-3 mb-10 list-disc list-inside">
              <li>A verified YouTube Channel (enabled for live streaming).</li>
              <li>An MP4 video file you want to loop.</li>
              <li>An AFK Live account.</li>
            </ul>

            <h2 id="step-1" className="text-2xl font-bold text-gray-900 dark:text-white mt-12 mb-6 border-b border-gray-100 dark:border-zinc-800 pb-4 scroll-mt-24">
              Step 1: Get Your YouTube Stream Key
            </h2>
            <p className="mb-4">
              First, we need to tell YouTube to expect a video feed, and we need the secure key that allows AFK Live to send video to your specific channel.
            </p>
            <ol className="list-decimal list-outside ml-6 space-y-4 mb-10 marker:text-gray-400 dark:marker:text-gray-600 font-medium">
              <li>Go to the <a href="https://studio.youtube.com" target="_blank" rel="noreferrer" className="text-primary hover:underline">YouTube Creator Studio</a>.</li>
              <li>Click the <strong>Create</strong> button in the top right, then select <strong>Go live</strong>.</li>
              <li>In the YouTube Live Control Room, copy the <strong>Stream Key</strong>. Do not share this key with anyone.</li>
              <li>Copy the <strong>Stream URL</strong> (usually <code>rtmp://a.rtmp.youtube.com/live2</code>).</li>
            </ol>

            <h2 id="step-2" className="text-2xl font-bold text-gray-900 dark:text-white mt-12 mb-6 border-b border-gray-100 dark:border-zinc-800 pb-4 scroll-mt-24">
              Step 2: Upload Your Media
            </h2>
            <p className="mb-4">
              Instead of running OBS on your laptop, you'll upload your video directly to our high-bandwidth servers.
            </p>
            <ol className="list-decimal list-outside ml-6 space-y-4 mb-10 marker:text-gray-400 dark:marker:text-gray-600 font-medium">
              <li>Log into your <Link to="/login" className="text-primary hover:underline">AFK Live Dashboard</Link>.</li>
              <li>Navigate to the <strong>Media Library</strong>.</li>
              <li>Drag and drop your MP4 file. Our servers will optimize it for continuous playback.</li>
            </ol>

            <h2 id="step-3" className="text-2xl font-bold text-gray-900 dark:text-white mt-12 mb-6 border-b border-gray-100 dark:border-zinc-800 pb-4 scroll-mt-24">
              Step 3: Configure Your Live Studio
            </h2>
            <ol className="list-decimal list-outside ml-6 space-y-4 mb-10 marker:text-gray-400 dark:marker:text-gray-600 font-medium">
              <li>Go to the <strong>Live Studio</strong>.</li>
              <li>Select your uploaded video.</li>
              <li>Click <strong>+ Add Destination</strong>.</li>
              <li>Paste the <strong>Stream URL</strong> and <strong>Stream Key</strong> from YouTube.</li>
              <li>Select your streaming quality (e.g., 1080p).</li>
            </ol>

            <div className="bg-blue-50 dark:bg-blue-900/10 border border-blue-200 dark:border-blue-900/30 rounded-2xl p-6 mb-12">
              <h4 className="text-blue-800 dark:text-blue-400 font-bold mb-2 flex items-center gap-2 mt-0">
                <i className="fa-solid fa-lightbulb"></i> Pro Tip: Infinite Looping
              </h4>
              <p className="text-blue-700 dark:text-blue-300 text-sm mb-0">
                When using AFK Live, your video will automatically loop infinitely. You don't need to edit a 24-hour long video file. Just upload a seamless 10-minute clip!
              </p>
            </div>

            <h2 id="step-4" className="text-2xl font-bold text-gray-900 dark:text-white mt-12 mb-6 border-b border-gray-100 dark:border-zinc-800 pb-4 scroll-mt-24">
              Step 4: Go Live!
            </h2>
            <p className="mb-8">
              Click the massive <strong>Go Live</strong> button. Within seconds, our cloud instances will begin pushing a pristine feed directly to YouTube.
            </p>

            <div className="bg-gray-900 dark:bg-zinc-900 text-white rounded-3xl p-10 text-center shadow-xl border border-gray-800 mt-16">
              <h3 className="text-2xl font-bold mb-4 mt-0 text-white">Ready to build your 24/7 radio station?</h3>
              <p className="text-gray-400 mb-8 max-w-lg mx-auto">
                Join thousands of creators who use AFK Live to multistream and loop their content effortlessly.
              </p>
              <Link to="/login" className="inline-block bg-gradient-to-r from-primary to-blue-500 hover:from-primary/90 hover:to-blue-500/90 text-white px-8 py-4 rounded-full font-bold text-lg transition shadow-lg shadow-primary/25 hover:-translate-y-0.5 no-underline">
                Start Streaming for Free
              </Link>
            </div>
          </div>
        </article>

        {/* Right Sidebar: Sticky Table of Contents */}
        <div className="hidden lg:block relative">
          <div className="sticky top-32">
            <h4 className="text-sm font-bold uppercase tracking-wider text-gray-900 dark:text-white mb-4">On this page</h4>
            <nav className="flex flex-col gap-3">
              {toc.map(item => (
                <a 
                  key={item.id} 
                  href={`#${item.id}`}
                  className={`text-sm font-medium transition-colors pl-3 border-l-2 py-1 ${activeSection === item.id ? 'text-primary border-primary' : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white border-transparent hover:border-gray-300 dark:hover:border-zinc-700'}`}
                >
                  {item.title}
                </a>
              ))}
            </nav>
          </div>
        </div>

      </main>

      <LandingFooter />
    </div>
  );
}
