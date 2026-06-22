import React from 'react';
import { Link } from 'react-router-dom';
import LandingNavbar from '../landing/LandingNavbar';
import LandingFooter from '../landing/LandingFooter';

export default function BlogIndex() {
  const posts = [
    {
      title: "How to Set Up a 24/7 YouTube Live Stream in 2026",
      excerpt: "Set up a continuous 24/7 YouTube live stream using AFK Live's cloud infrastructure. No dedicated PC, VPS, or complex OBS setups required. We break down the exact settings to use for maximum audience retention.",
      category: "Guide",
      date: "Jun 22, 2026",
      readTime: "5 min read",
      slug: "/blog/how-to-set-up-24-7-live-stream-on-youtube",
      image: "/images/blog/youtube.jpg"
    }
  ];

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950 text-gray-900 dark:text-gray-100 font-sans relative overflow-x-hidden transition-colors duration-200 flex flex-col">
      <LandingNavbar />

      <main className="max-w-7xl mx-auto px-6 pt-40 pb-20 relative z-10 flex-1 w-full">
        
        {/* Header section */}
        <div className="text-center mb-16">
          <h1 className="text-4xl md:text-5xl font-extrabold tracking-tight mb-4 text-gray-900 dark:text-white">
            Blog & Guides
          </h1>
          <p className="text-xl text-gray-500 dark:text-gray-400 max-w-2xl mx-auto">
            Everything you need to know about continuous live streaming and cloud playout.
          </p>
        </div>

        {/* Simple Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
          {posts.map((post, idx) => (
            <Link key={idx} to={post.slug} className="group flex flex-col bg-white dark:bg-zinc-900 border border-gray-200 dark:border-white/10 rounded-2xl overflow-hidden shadow-sm hover:shadow-md transition-shadow">
              <div className="h-48 overflow-hidden relative">
                <img src={post.image} alt={post.title} className="w-full h-full object-cover transform group-hover:scale-105 transition-transform duration-500" />
                <div className="absolute top-4 left-4 bg-white dark:bg-zinc-800 px-3 py-1 rounded-md text-xs font-bold text-gray-900 dark:text-white shadow-sm">
                  {post.category}
                </div>
              </div>
              <div className="p-6 flex flex-col flex-1">
                <div className="flex items-center gap-4 text-xs text-gray-500 dark:text-gray-400 font-medium mb-3">
                  <span><i className="fa-regular fa-calendar mr-1.5"></i>{post.date}</span>
                  <span><i className="fa-regular fa-clock mr-1.5"></i>{post.readTime}</span>
                </div>
                <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-3 group-hover:text-primary transition-colors">
                  {post.title}
                </h3>
                <p className="text-gray-600 dark:text-gray-400 text-sm mb-6 flex-1">
                  {post.excerpt}
                </p>
                <div className="text-primary font-semibold text-sm flex items-center gap-2">
                  Read Article <i className="fa-solid fa-arrow-right group-hover:translate-x-1 transition-transform"></i>
                </div>
              </div>
            </Link>
          ))}
        </div>

      </main>

      <LandingFooter />
    </div>
  );
}
