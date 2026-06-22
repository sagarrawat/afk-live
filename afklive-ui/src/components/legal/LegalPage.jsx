import React from 'react';
import LandingNavbar from '../landing/LandingNavbar';
import LandingFooter from '../landing/LandingFooter';

const LEGAL_CONTENT = {
  terms: {
    title: 'Terms of Use',
    date: 'Last updated: 1 Feb 2026',
    content: (
      <>
        <p>Please read these Terms of Use ("Terms", "Terms of Use") carefully before using the afk.live website (the "Service") operated by AFK Live ("us", "we", or "our").</p>
        <p>Your access to and use of the Service is conditioned on your acceptance of and compliance with these Terms. These Terms apply to all visitors, users, and others who access or use the Service.</p>
        <h2>1. Accounts</h2>
        <p>When you create an account with us, you must provide information that is accurate, complete, and current at all times. Failure to do so constitutes a breach of the Terms.</p>
        <h2>2. Acceptable Use</h2>
        <p>You are solely responsible for the content you stream using our Service. You agree not to stream content that violates the terms of service of the destination platforms (e.g., YouTube, Twitch).</p>
      </>
    )
  },
  privacy: {
    title: 'Privacy Policy',
    date: 'Last updated: 1 Feb 2026',
    content: (
      <>
        <p>This page informs you of our policies regarding the collection, use, and disclosure of personal data when you use our Service and the choices you have associated with that data.</p>
        <h2>Information Collection and Use</h2>
        <p>We collect several different types of information for various purposes to provide and improve our Service to you.</p>
        <h2>Tracking & Cookies Data</h2>
        <p>We use cookies and similar tracking technologies to track the activity on our Service and hold certain information.</p>
      </>
    )
  },
  'refund-policy': {
    title: 'Refund Policy',
    date: 'Last updated: 1 Feb 2026',
    content: (
      <>
        <p>Thank you for using AFK Live.</p>
        <h2>Pay-As-You-Go Billing</h2>
        <p>Because our service operates on a strictly pay-as-you-go model where you are billed by the minute for actual server resources consumed, we generally do not offer refunds for streaming time that has already been utilized.</p>
        <h2>Account Balance</h2>
        <p>Unused credits in your wallet do not expire and can be used at any time. If you wish to close your account and request a refund of your unused wallet balance, please contact support.</p>
      </>
    )
  },
  disclaimer: {
    title: 'Disclaimer',
    date: 'Last updated: 1 Feb 2026',
    content: (
      <>
        <p>The information contained on the afk.live website (the "Service") is for general information purposes only.</p>
        <p>AFK Live assumes no responsibility for errors or omissions in the contents on the Service.</p>
        <h2>External Links Disclaimer</h2>
        <p>The Service may contain links to external websites that are not provided or maintained by or in any way affiliated with AFK Live. Please note that AFK Live does not guarantee the accuracy, relevance, timeliness, or completeness of any information on these external websites.</p>
      </>
    )
  }
};

export default function LegalPage({ type }) {
  const data = LEGAL_CONTENT[type] || LEGAL_CONTENT.terms;

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950 flex flex-col font-sans transition-colors duration-200">
      <LandingNavbar />
      
      <main className="flex-grow pt-32 pb-20 px-6">
        <div className="max-w-3xl mx-auto">
          <div className="mb-12 border-b border-gray-100 dark:border-zinc-800 pb-8">
            <h1 className="text-4xl md:text-5xl font-extrabold text-gray-900 dark:text-white mb-4 tracking-tight">{data.title}</h1>
            <p className="text-gray-500 dark:text-gray-400 font-medium">{data.date}</p>
          </div>
          
          <div className="prose prose-lg dark:prose-invert prose-blue max-w-none 
                          prose-headings:font-bold prose-headings:tracking-tight
                          prose-p:text-gray-600 dark:prose-p:text-gray-300 prose-p:leading-relaxed">
            {data.content}
          </div>
        </div>
      </main>
      
      <LandingFooter />
    </div>
  );
}
