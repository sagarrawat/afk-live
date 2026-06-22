import React from 'react';
import { Link } from 'react-router-dom';
import LandingNavbar from './LandingNavbar';
import LandingFooter from './LandingFooter';

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950 text-gray-900 dark:text-white flex flex-col font-sans relative overflow-hidden transition-colors duration-200">
      {/* Background Glows */}
      <div className="absolute top-0 left-1/4 w-96 h-96 bg-primary/20 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[100px] opacity-70 animate-blob pointer-events-none z-0"></div>
      <div className="absolute top-1/4 right-1/4 w-96 h-96 bg-accent/10 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[100px] opacity-50 animate-blob animation-delay-2000 pointer-events-none z-0"></div>
      <div className="absolute -bottom-32 left-1/2 w-[800px] h-96 bg-primary/10 rounded-full mix-blend-multiply dark:mix-blend-screen filter blur-[120px] opacity-60 pointer-events-none z-0"></div>

      <LandingNavbar />

      {/* Hero Section */}
      <main className="flex-1 flex flex-col items-center justify-center text-center px-4 pt-32 pb-20 relative z-10 w-full max-w-7xl mx-auto">
        <div className="max-w-4xl mx-auto">
          <div className="inline-flex items-center gap-2 mb-6 px-4 py-1.5 rounded-full bg-white dark:bg-zinc-900/5 border border-white/10 text-accent text-xs font-semibold tracking-wide uppercase shadow-lg backdrop-blur-sm">
            <span className="w-2 h-2 rounded-full bg-accent animate-pulse"></span>
            Now in Public Beta
          </div>
          
          <h1 className="text-5xl md:text-7xl font-extrabold tracking-tight leading-[1.1] mb-6">
            Stream 24/7.<br/>
            <span className="text-transparent bg-clip-text bg-gradient-to-r from-gray-900 via-gray-600 to-gray-400 dark:from-white dark:via-gray-200 dark:to-gray-500">
              While you sleep.
            </span>
          </h1>
          
          <p className="text-lg md:text-xl text-gray-600 dark:text-gray-400 mb-10 max-w-2xl mx-auto font-medium">
            Upload your videos once and let our cloud servers stream them endlessly to YouTube and Twitch without keeping your PC on.
          </p>
          <div className="flex flex-col sm:flex-row gap-4 justify-center items-center">
            <Link to="/login" className="relative group">
              <div className="absolute -inset-1 bg-gradient-to-r from-primary to-accent rounded-full blur opacity-60 group-hover:opacity-100 transition duration-500"></div>
              <div className="relative bg-primary text-white text-lg px-8 py-4 rounded-full font-bold transition flex items-center gap-2">
                Start Streaming Free <i className="fa-solid fa-arrow-right"></i>
              </div>
            </Link>
            <Link to="/blog/how-to-set-up-24-7-live-stream-on-youtube" className="text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-white text-lg px-8 py-4 font-medium transition flex items-center gap-2">
              <i className="fa-solid fa-book-open text-primary"></i> Read the Guide
            </Link>
          </div>
        </div>

        {/* Dashboard Mockup */}
        <div className="w-full max-w-5xl mx-auto mt-20 relative animate-float">
          <div className="absolute -inset-1 bg-gradient-to-b from-primary/30 to-transparent rounded-2xl blur-xl opacity-50"></div>
          <div className="relative rounded-2xl overflow-hidden border border-white/10 shadow-2xl bg-gray-100 dark:bg-zinc-900">
            <div className="h-8 bg-gray-200/50 dark:bg-zinc-800/50 border-b border-gray-200 dark:border-white/5 flex items-center px-4 gap-2">
              <div className="w-3 h-3 rounded-full bg-red-500/80"></div>
              <div className="w-3 h-3 rounded-full bg-yellow-500/80"></div>
              <div className="w-3 h-3 rounded-full bg-green-500/80"></div>
            </div>
            <img src="/dashboard-mockup.jpg" alt="AFK Live Dashboard" className="w-full h-auto" />
          </div>
        </div>

        {/* Feature Grid */}
        <div id="features" className="w-full grid grid-cols-1 md:grid-cols-3 gap-6 mt-32 text-left">
          <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 hover:border-white/10 transition group">
            <div className="w-12 h-12 bg-primary/20 text-primary rounded-xl flex items-center justify-center text-2xl mb-6 border border-primary/20 group-hover:scale-110 transition">
              <i className="fa-solid fa-cloud-arrow-up"></i>
            </div>
            <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-3">100% Cloud Based</h3>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed">No expensive hardware required. Upload your video once and we broadcast it directly from our high-bandwidth servers.</p>
          </div>
          
          <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 hover:border-white/10 transition group">
            <div className="w-12 h-12 bg-accent/20 text-accent rounded-xl flex items-center justify-center text-2xl mb-6 border border-accent/20 group-hover:scale-110 transition">
              <i className="fa-solid fa-infinity"></i>
            </div>
            <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-3">Infinite Looping</h3>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed">Build a 24/7 radio station or constant replay stream. Set your video to loop infinitely without dropping a single frame.</p>
          </div>
          
          <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 hover:border-white/10 transition group">
            <div className="w-12 h-12 bg-purple-500/20 text-purple-400 rounded-xl flex items-center justify-center text-2xl mb-6 border border-purple-500/20 group-hover:scale-110 transition">
              <i className="fa-solid fa-bolt"></i>
            </div>
            <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-3">Multistreaming</h3>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed">
              Reach all your audiences at once. Send your live feed to YouTube, Twitch, Facebook, and custom RTMP destinations simultaneously.
            </p>
          </div>
        </div>

        {/* Testimonials */}
        <div className="w-full mt-32">
          <h2 className="text-3xl font-bold text-center mb-12">Loved by Creators</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5">
              <div className="flex items-center gap-4 mb-4">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-primary to-accent flex items-center justify-center text-white font-bold shadow-lg">JD</div>
                <div>
                  <h4 className="font-bold text-white">John Doe</h4>
                  <p className="text-sm text-gray-600 dark:text-gray-400">Gaming Creator</p>
                </div>
              </div>
              <p className="text-gray-300 leading-relaxed">"AFK Live completely changed my channel. I run 24/7 lo-fi streams without keeping my PC on. It's flawless."</p>
            </div>
            
            <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5">
              <div className="flex items-center gap-4 mb-4">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-accent to-blue-500 flex items-center justify-center text-white font-bold shadow-lg">AS</div>
                <div>
                  <h4 className="font-bold text-white">Alice Smith</h4>
                  <p className="text-sm text-gray-600 dark:text-gray-400">Radio Host</p>
                </div>
              </div>
              <p className="text-gray-300 leading-relaxed">"The multistreaming feature is incredible. I hit YouTube and Twitch simultaneously with zero latency."</p>
            </div>
            
            <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 md:col-span-2 lg:col-span-1">
              <div className="flex items-center gap-4 mb-4">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center text-white font-bold shadow-lg">MR</div>
                <div>
                  <h4 className="font-bold text-white">Mike Ross</h4>
                  <p className="text-sm text-gray-600 dark:text-gray-400">Podcaster</p>
                </div>
              </div>
              <p className="text-gray-300 leading-relaxed">"I save so much on electricity and hardware now. AFK Live is the best investment for my content."</p>
            </div>
          </div>
        </div>

        {/* Pricing */}
        <div id="pricing" className="w-full mt-32 mb-10">
          <div className="inline-flex items-center gap-2 mb-4 px-4 py-1.5 rounded-full bg-white dark:bg-zinc-900/5 border border-white/10 text-primary text-xs font-semibold tracking-wide uppercase mx-auto">
            Pay as you go
          </div>
          <h2 className="text-4xl font-bold text-center mb-4">Simple, Transparent Pricing</h2>
          <p className="text-gray-600 dark:text-gray-400 text-center mb-16 text-lg max-w-xl mx-auto">No subscriptions. Pay only for the hours you stream. Get <span className="text-white font-bold">₹50 free credit</span> on signup.</p>
          
          <div className="grid grid-cols-1 md:grid-cols-3 gap-8 max-w-5xl mx-auto items-center">
            {/* 720p */}
            <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 text-center flex flex-col h-full hover:border-white/10 transition">
              <h3 className="text-xl font-medium text-gray-300 mb-2">Standard</h3>
              <div className="text-4xl font-bold text-white mb-6">₹10<span className="text-lg text-gray-500 font-medium">/hr</span></div>
              <ul className="text-left text-gray-600 dark:text-gray-400 space-y-4 mb-8 flex-1">
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> 720p Resolution</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> 30 FPS Output</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> 1 Destination</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> Standard Support</li>
              </ul>
              <Link to="/login" className="block w-full py-3 rounded-xl border border-white/10 hover:bg-white dark:bg-zinc-900/10 transition font-semibold">Get Started</Link>
            </div>
            
            {/* 1080p */}
            <div className="bg-gradient-to-b from-primary/20 to-dark-800/80 backdrop-blur-md p-8 rounded-2xl border border-primary/30 text-center relative flex flex-col transform md:-translate-y-4 shadow-2xl shadow-primary/10">
              <div className="absolute -top-4 left-1/2 -translate-x-1/2 bg-gradient-to-r from-primary to-accent text-dark-900 text-xs font-bold px-4 py-1.5 rounded-full uppercase tracking-wider shadow-lg">Most Popular</div>
              <h3 className="text-xl font-medium text-white mb-2">Pro</h3>
              <div className="text-5xl font-bold text-white mb-6">₹15<span className="text-xl text-gray-600 dark:text-gray-400 font-medium">/hr</span></div>
              <ul className="text-left text-gray-200 space-y-4 mb-8 flex-1">
                <li className="flex items-center"><i className="fa-solid fa-check text-accent mr-3"></i> 1080p Resolution</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-accent mr-3"></i> 60 FPS Output</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-accent mr-3"></i> Up to 3 Destinations</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-accent mr-3"></i> Priority Support</li>
              </ul>
              <Link to="/login" className="block w-full py-4 rounded-xl bg-primary hover:bg-primary-dark text-white transition font-bold shadow-lg shadow-primary/25">Start Streaming</Link>
            </div>

            {/* 4K */}
            <div className="bg-gray-50/50 dark:bg-zinc-900/50 backdrop-blur-sm p-8 rounded-2xl border border-gray-200 dark:border-white/5 text-center flex flex-col h-full hover:border-white/10 transition">
              <h3 className="text-xl font-medium text-gray-300 mb-2">Ultra</h3>
              <div className="text-4xl font-bold text-white mb-6">₹20<span className="text-lg text-gray-500 font-medium">/hr</span></div>
              <ul className="text-left text-gray-600 dark:text-gray-400 space-y-4 mb-8 flex-1">
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> 4K Resolution</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> 60 FPS Output</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> Unlimited Destinations</li>
                <li className="flex items-center"><i className="fa-solid fa-check text-primary mr-3"></i> Dedicated Account Manager</li>
              </ul>
              <Link to="/login" className="block w-full py-3 rounded-xl border border-white/10 hover:bg-white dark:bg-zinc-900/10 transition font-semibold">Contact Sales</Link>
            </div>
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="w-full border-t border-gray-200 dark:border-white/5 py-8 text-center text-gray-500 text-sm bg-dark-900 mt-auto relative z-10">
        &copy; {new Date().getFullYear()} AFK Live. All rights reserved.
      </footer>
    </div>
  );
}
