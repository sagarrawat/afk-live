import React, { useState, useEffect } from 'react';

export default function OnboardingModal() {
  const [isOpen, setIsOpen] = useState(false);
  const [step, setStep] = useState(1);

  useEffect(() => {
    const hasSeen = localStorage.getItem('afk_onboarded');
    if (!hasSeen) {
      setIsOpen(true);
    }
  }, []);

  const close = () => {
    localStorage.setItem('afk_onboarded', 'true');
    setIsOpen(false);
  };

  if (!isOpen) return null;

  return (
    <div className="modal-overlay z-50">
      <div className="modal-box max-w-lg relative">
        <button onClick={close} className="absolute top-4 right-4 text-gray-400 hover:text-gray-600 dark:text-gray-400">
          <i className="fa-solid fa-xmark"></i>
        </button>

        {step === 1 && (
          <div className="text-center py-6">
            <div className="w-16 h-16 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center mx-auto mb-4 text-3xl">
              <i className="fa-solid fa-satellite-dish"></i>
            </div>
            <h2 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">Welcome to AFK Live!</h2>
            <p className="text-gray-500 mb-6">Let's get you set up to stream your pre-recorded videos 24/7 without lifting a finger.</p>
            <button onClick={() => setStep(2)} className="btn btn-primary w-full py-3">Next Step <i className="fa-solid fa-arrow-right ml-2"></i></button>
          </div>
        )}

        {step === 2 && (
          <div className="py-2">
            <h2 className="text-xl font-bold text-gray-900 dark:text-white mb-4">How it works</h2>
            <div className="space-y-4 mb-6">
              <div className="flex gap-4 items-start">
                <div className="w-8 h-8 bg-blue-50 text-blue-600 rounded-full flex items-center justify-center shrink-0 font-bold">1</div>
                <div>
                  <h4 className="font-semibold text-gray-900 dark:text-white">Upload your videos</h4>
                  <p className="text-sm text-gray-500">Go to the Media Library to upload your pre-recorded content.</p>
                </div>
              </div>
              <div className="flex gap-4 items-start">
                <div className="w-8 h-8 bg-blue-50 text-blue-600 rounded-full flex items-center justify-center shrink-0 font-bold">2</div>
                <div>
                  <h4 className="font-semibold text-gray-900 dark:text-white">Add Destinations</h4>
                  <p className="text-sm text-gray-500">In Settings, add your YouTube, Twitch, or custom RTMP endpoints.</p>
                </div>
              </div>
              <div className="flex gap-4 items-start">
                <div className="w-8 h-8 bg-blue-50 text-blue-600 rounded-full flex items-center justify-center shrink-0 font-bold">3</div>
                <div>
                  <h4 className="font-semibold text-gray-900 dark:text-white">Go Live</h4>
                  <p className="text-sm text-gray-500">Use the Live Studio to start your broadcast instantly.</p>
                </div>
              </div>
            </div>
            <button onClick={close} className="btn btn-primary w-full py-3">Get Started <i className="fa-solid fa-rocket ml-2"></i></button>
          </div>
        )}
      </div>
    </div>
  );
}
