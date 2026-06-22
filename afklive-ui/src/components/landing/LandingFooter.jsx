import React from 'react';
import { Link } from 'react-router-dom';

export default function LandingFooter() {
  return (
    <footer className="bg-[#0b1121] text-white pt-16 pb-8 mt-auto">
        <div className="max-w-7xl mx-auto px-6">
            <div className="flex flex-col md:flex-row justify-between items-center md:items-start gap-8 mb-12 border-b border-gray-800 pb-12">
                <div className="text-center md:text-left">
                    <div className="afk-logo mx-auto md:mx-0 mb-4" style={{ filter: 'brightness(0) invert(1)', transform: 'scale(0.85)', transformOrigin: 'left center' }}>
                        <div className="afk-icon"><div className="afk-play"></div></div>
                        <div className="afk-text">AFK<span className="afk-live">Live</span></div>
                    </div>
                    <p className="text-gray-400 text-sm max-w-xs">The easiest way to run 24/7 continuous live streams in the cloud. Pay as you go, cancel anytime.</p>
                </div>
                
                <div className="flex gap-16 text-center md:text-left">

                    <div>
                        <h4 className="font-bold mb-4 text-gray-200">Legal</h4>
                        <ul className="space-y-2 text-sm text-gray-400">
                            <li><a href="/terms" className="hover:text-white transition">Terms of Use</a></li>
                            <li><a href="/privacy" className="hover:text-white transition">Privacy Policy</a></li>
                            <li><a href="/refund-policy" className="hover:text-white transition">Refund Policy</a></li>
                            <li><a href="/disclaimer" className="hover:text-white transition">Disclaimer</a></li>
                        </ul>
                    </div>
                </div>
            </div>
            
            <div className="flex flex-col md:flex-row justify-between items-center gap-4 text-xs text-gray-500">
                <p>&copy; 2026 AFK Live. All rights reserved. Transparent, no-BS pricing.</p>
            </div>
        </div>
    </footer>
  );
}
