import React from 'react';
import { Link } from 'react-router-dom';
import LandingNavbar from '../landing/LandingNavbar';
import LandingFooter from '../landing/LandingFooter';

export default function ForgotPassword() {
  return (
    <div className="min-h-screen bg-gray-50 dark:bg-zinc-950 flex flex-col font-sans">
      <LandingNavbar />
      
      <main className="flex-grow flex items-center justify-center p-6 mt-16">
        <div className="bg-white dark:bg-zinc-900 shadow-xl rounded-2xl p-8 max-w-md w-full border border-gray-100 dark:border-zinc-800">
          <div className="text-center mb-8">
            <div className="w-16 h-16 bg-blue-100 dark:bg-blue-900/30 text-brand-blue rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fa-solid fa-key text-2xl"></i>
            </div>
            <h1 className="text-3xl font-bold text-gray-900 dark:text-white mb-2">Reset Password</h1>
            <p className="text-gray-500 dark:text-gray-400">Enter your email address and we'll send you a link to reset your password.</p>
          </div>
          
          <form className="space-y-6" onSubmit={(e) => { e.preventDefault(); alert('Password reset link sent!'); }}>
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">Email Address</label>
              <input 
                type="email" 
                required 
                className="w-full px-4 py-3 bg-gray-50 dark:bg-zinc-950 border border-gray-300 dark:border-zinc-700 rounded-xl focus:ring-2 focus:ring-brand-blue focus:border-brand-blue outline-none transition text-gray-900 dark:text-white"
                placeholder="you@example.com"
              />
            </div>
            
            <button 
              type="submit" 
              className="w-full bg-brand-blue hover:bg-blue-700 text-white font-bold py-3 px-4 rounded-xl shadow-lg shadow-blue-500/30 transition transform active:scale-95"
            >
              Send Reset Link
            </button>
          </form>
          
          <div className="mt-8 text-center">
            <Link to="/login" className="text-sm font-medium text-gray-500 hover:text-brand-blue transition">
              <i className="fa-solid fa-arrow-left mr-2"></i>Back to log in
            </Link>
          </div>
        </div>
      </main>
      
      <LandingFooter />
    </div>
  );
}
