import { useState, useEffect } from 'react';
import toast from 'react-hot-toast';
import { formatMoney } from '../../utils/formatters';

function formatDuration(seconds) {
  if (!seconds || seconds <= 0) return '0:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return h > 0 ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}` : `${m}:${String(s).padStart(2,'0')}`;
}

export default function Settings() {
  const [activeTab, setActiveTab] = useState('destinations');
  const [destinations, setDestinations]       = useState([]);
  const [history, setHistory]                 = useState([]);
  const [userInfo, setUserInfo]               = useState(null);
  const [showAddModal, setShowAddModal]       = useState(false);
  const [newDest, setNewDest]                 = useState({ name: '', streamKey: '', rtmpUrl: '' });
  const [destToDelete, setDestToDelete]       = useState(null);
  const [saving, setSaving]                   = useState(false);
  const [historyPage, setHistoryPage]         = useState(0);
  const [showCreditModal, setShowCreditModal] = useState(false);
  const [creditAmount, setCreditAmount]       = useState('500');
  const [processingPayment, setProcessingPayment] = useState(false);
  const [hourlyRate, setHourlyRate]           = useState(1.00);

  useEffect(() => {
    fetchAll();
  }, []);

  const fetchAll = () => {
    fetch('/api/destinations', { credentials: 'include' }).then(r => r.json()).then(setDestinations).catch(() => {});
    fetch('/api/stream/history?page=0&size=20', { credentials: 'include' }).then(r => r.json()).then(d => setHistory(d?.data?.content || [])).catch(() => {});
    fetch('/api/user-info', { credentials: 'include' }).then(r => r.json()).then(setUserInfo).catch(() => {});
    fetch('/api/pricing', { credentials: 'include' }).then(r => r.json()).then(d => { if(d?.hourlyRate) setHourlyRate(d.hourlyRate); }).catch(() => {});
  };

  const getCsrfToken = () => document.cookie.match(/(^|;)\s*XSRF-TOKEN\s*=\s*([^;]+)/)?.pop() || '';

  const addDestination = async () => {
    if (!newDest.name || !newDest.streamKey) return;
    setSaving(true);
    try {
      const res = await fetch('/api/destinations', {
        method: 'POST',
        credentials: 'include',
        headers: { 
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify(newDest),
      });
      if (!res.ok) throw new Error('Failed to save');
      setShowAddModal(false);
      setNewDest({ name: '', streamKey: '', rtmpUrl: '' });
      toast.success('Destination saved');
      fetchAll();
    } catch (e) {
      toast.error(e.message || 'Error saving destination');
    } finally { setSaving(false); }
  };

  const deleteDestination = async () => {
    if (!destToDelete) return;
    try {
      await fetch(`/api/destinations/${destToDelete.id}`, { 
        method: 'DELETE', 
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() }
      });
      toast.success('Destination deleted');
      fetchAll();
      setDestToDelete(null);
    } catch (e) {
      toast.error('Failed to delete destination');
    }
  };

  const handleBuyCredit = async () => {
    const amount = Number(creditAmount);
    if (!amount || isNaN(amount) || !Number.isInteger(amount)) {
      toast.error('Please enter a valid whole number');
      return;
    }

    const minAmount = userInfo?.currency === 'INR' ? 10 : 1;
    if (amount < minAmount) {
      toast.error(`Minimum top-up amount is ${userInfo?.currency === 'INR' ? '₹' : '$'}${minAmount}`);
      return;
    }
    
    setProcessingPayment(true);
    try {
      const res = await fetch('/api/stripe/create-checkout-session', { 
        method: 'POST', 
        credentials: 'include', 
        headers: { 
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        }, 
        body: JSON.stringify({ amount: amount }) 
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'Payment initiation failed');
      
      if (data?.redirectUrl) {
        window.location.href = data.redirectUrl;
      } else {
        throw new Error('No redirect URL found');
      }
    } catch (e) {
      toast.error(e.message || 'Error processing payment');
      setProcessingPayment(false);
    }
  };

  const tabs = [
    { id: 'destinations', label: 'Destinations', icon: 'fa-tower-broadcast' },
    { id: 'billing',      label: 'Billing',      icon: 'fa-receipt' },
  ];

  return (
    <div className="w-full min-h-full p-6 md:p-8">
      <div className="settings-container">

        {/* Sidebar */}
        <div className="settings-sidebar">
          <div className="px-6 mb-4">
            <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">Settings</p>
          </div>
          {tabs.map(tab => (
            <div
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`settings-nav-item flex items-center gap-3 ${activeTab === tab.id ? 'active' : ''}`}
            >
              <i className={`fa-solid ${tab.icon} w-4 text-center`}></i>
              {tab.label}
            </div>
          ))}
        </div>

        {/* Content */}
        <div className="settings-content">

          {/* ── Destinations ── */}
          {activeTab === 'destinations' && (
            <div className="settings-section">
              <div className="flex items-center justify-between mb-6">
                <h2 className="!mb-0 !border-0 !pb-0">Stream Destinations</h2>
                <button className="btn btn-primary btn-sm" onClick={() => setShowAddModal(true)}>
                  <i className="fa-solid fa-plus"></i> Add Destination
                </button>
              </div>

              {destinations.length === 0 ? (
                <div className="empty-state">
                  <i className="fa-solid fa-tower-broadcast"></i>
                  <p>No destinations yet. Add an RTMP stream key to get started.</p>
                </div>
              ) : (
                <div className="queue-list">
                  {destinations.map(d => (
                    <div key={d.id} className="queue-item">
                      <div className="queue-thumb bg-gray-100 dark:bg-zinc-800 text-gray-400">
                        <i className="fa-solid fa-signal text-sm"></i>
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="font-semibold text-gray-900 dark:text-white">{d.name}</p>
                        <p className="text-xs text-gray-400 truncate font-mono">{d.rtmpUrl || 'RTMP'} · ****{d.streamKey?.slice(-8)}</p>
                      </div>
                      <button onClick={() => setDestToDelete(d)} className="btn btn-text btn-sm text-red-500 hover:text-red-700">
                        <i className="fa-solid fa-trash"></i>
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* ── Billing ── */}
          {activeTab === 'billing' && (
            <div className="settings-section">
              <h2>Billing</h2>

              {/* Balance card */}
              {userInfo && (
                <div className={`card mb-6 ${userInfo.unpaidBalance > 0 ? 'border-orange-200 bg-orange-50' : ''}`}>
                  <div className="flex items-center justify-between mb-4">
                    <div>
                      <p className="text-sm font-medium text-gray-500 mb-1">{userInfo.unpaidBalance < 0 ? 'Prepaid Balance' : 'Unpaid Balance'}</p>
                      <p className={`text-4xl font-bold tracking-tight ${userInfo.unpaidBalance > 0 ? 'text-orange-600' : userInfo.unpaidBalance < 0 ? 'text-green-600' : 'text-gray-800 dark:text-gray-200'}`}>
                        {userInfo.unpaidBalance < 0 ? '+' : ''}{userInfo.currency === 'INR' ? '₹' : '$'}{formatMoney(Math.abs(userInfo.unpaidBalance))}
                      </p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        className="btn bg-blue-100 text-blue-700 hover:bg-blue-200 border-none shadow-sm"
                        onClick={() => setShowCreditModal(true)}
                      >
                        Buy Credit
                      </button>
                      
                      {userInfo.unpaidBalance > 0 && (
                        <button
                          className="btn btn-primary shadow-md"
                          onClick={() => {
                            fetch('/api/stripe/create-checkout-session', { 
                              method: 'POST',
                              credentials: 'include',
                              headers: {
                                'Content-Type': 'application/json',
                                'X-XSRF-TOKEN': getCsrfToken()
                              },
                              body: JSON.stringify({ amount: userInfo.unpaidBalance })
                            })
                            .then(async r => {
                              if (!r.ok) throw new Error((await r.json().catch(()=>({}))).message || 'Payment initiation failed');
                              return r.json();
                            })
                            .then(d => { 
                              if (d?.redirectUrl) window.location.href = d.redirectUrl; 
                              else toast.error(d?.message || 'Payment initiation failed');
                            })
                            .catch(e => toast.error('Error: ' + e.message));
                          }}
                        >
                          Clear Dues
                        </button>
                      )}
                    </div>
                  </div>
                  
                  {/* Credit Limit Progress */}
                  {userInfo.creditLimit > 0 && (
                    <div className="mt-2 pt-4 border-t border-gray-200 dark:border-zinc-700/60">
                      <div className="flex justify-between text-xs mb-1.5 font-medium">
                        <span className="text-gray-500">Usage vs Limit</span>
                        <span className="text-gray-700 dark:text-gray-300">{userInfo.currency === 'INR' ? '₹' : '$'}{formatMoney(userInfo.unpaidBalance)} / {userInfo.currency === 'INR' ? '₹' : '$'}{formatMoney(userInfo.creditLimit)}</span>
                      </div>
                      <div className="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
                        <div 
                          className={`h-2 rounded-full transition-all duration-500 ${
                            (userInfo.unpaidBalance / userInfo.creditLimit) > 0.9 ? 'bg-red-500' : 
                            (userInfo.unpaidBalance / userInfo.creditLimit) > 0.75 ? 'bg-orange-400' : 'bg-blue-500'
                          }`}
                          style={{ width: `${Math.min(100, ((userInfo.unpaidBalance || 0) / userInfo.creditLimit) * 100)}%` }}
                        ></div>
                      </div>
                      <p className="text-xs text-gray-400 mt-2">Streams will automatically stop if your unpaid balance exceeds your credit limit.</p>
                    </div>
                  )}
                </div>
              )}

              {/* History */}
              <h3 className="text-base font-semibold mb-3 text-gray-700 dark:text-gray-300">Session History</h3>
              {history.length === 0 ? (
                <div className="empty-state">
                  <i className="fa-solid fa-receipt"></i>
                  <p>No sessions yet. Go live to see your history here.</p>
                </div>
              ) : (
                <div className="queue-list space-y-3">
                  {history.map(session => {
                    const durationSec = session.startTime && session.endTime
                      ? Math.floor((new Date(session.endTime) - new Date(session.startTime)) / 1000)
                      : 0;
                    const isWatchdogKilled = session.status === 'FAILED' && session.failureReason && session.failureReason.includes('credit limit');
                    const displayStatus = isWatchdogKilled ? 'LIMIT REACHED' : session.status;
                    const symbol = userInfo?.currency === 'INR' ? '₹' : '$';
                    
                    return (
                      <div key={session.id} className="card !p-4 flex flex-col sm:flex-row gap-4 relative group hover:border-gray-300 transition-colors">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-1">
                            <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold tracking-wider ${
                              session.status === 'COMPLETED' ? 'bg-green-100 text-green-700' : 
                              isWatchdogKilled ? 'bg-orange-100 text-orange-700' :
                              session.status === 'FAILED' ? 'bg-red-100 text-red-700' : 
                              'bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-gray-400'
                            }`}>
                              {displayStatus}
                            </span>
                            <span className="text-sm font-medium text-gray-900 dark:text-white truncate">
                              {session.destinationName || 'Stream'}
                            </span>
                          </div>
                          <p className="text-xs text-gray-500">
                            {session.startTime ? new Date(session.startTime).toLocaleString() : '—'}
                            {durationSec > 0 && ` · Duration: ${formatDuration(durationSec)}`}
                            {(() => {
                              try {
                                const spec = JSON.parse(session.streamSpecJson || '{}');
                                const q = spec.stream_quality;
                                return ` · Quality: ${q == 2160 ? '4K' : (q == 1080 ? '1080p' : '720p')}`;
                              } catch { return ' · Quality: 720p'; }
                            })()}
                          </p>
                          {session.failureReason && session.status === 'FAILED' && (
                            <p className="text-xs text-red-500 mt-1"><i className="fa-solid fa-circle-exclamation mr-1"></i> {session.failureReason}</p>
                          )}
                        </div>
                        
                        <div className="sm:text-right flex flex-col justify-center bg-gray-50 dark:bg-zinc-950/50 sm:bg-transparent p-3 sm:p-0 rounded-lg">
                          <p className="font-bold text-gray-900 dark:text-white text-lg mb-0.5">{symbol}{formatMoney(session.accumulatedCost)}</p>
                          <div className="text-[11px] text-gray-400 font-mono" title="Math breakdown">
                            ({(durationSec / 60).toFixed(1)}m / 60) × {symbol}{formatMoney(session.lockedHourlyRate)}/hr
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Add Destination Modal */}
      {showAddModal && (
        <div className="modal-overlay">
          <div className="modal-box max-w-md w-full">
            <div className="modal-header px-0 pt-0">
              <h2>Add Stream Destination</h2>
              <button className="btn btn-text" onClick={() => setShowAddModal(false)}>
                <i className="fa-solid fa-xmark"></i>
              </button>
            </div>
            <div className="mt-4 space-y-4">
              <div className="form-group">
                <label>Name (e.g. "YouTube Main")</label>
                <input className="form-input" placeholder="My YouTube Channel" value={newDest.name} onChange={e => setNewDest(p => ({...p, name: e.target.value}))} />
              </div>
              <div className="form-group">
                <label>Stream Key</label>
                <input className="form-input" placeholder="live_xxxxxxxxxxxxxxxx" type="password" value={newDest.streamKey} onChange={e => setNewDest(p => ({...p, streamKey: e.target.value}))} />
              </div>
            </div>
            <div className="modal-footer px-0 pb-0 mt-4">
              <button className="btn btn-secondary mr-2" onClick={() => setShowAddModal(false)}>Cancel</button>
              <button className="btn btn-primary" onClick={addDestination} disabled={saving || !newDest.name || !newDest.streamKey}>
                {saving ? 'Saving...' : 'Save Destination'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {destToDelete && (
        <div className="modal-overlay z-50">
          <div className="modal-box max-w-sm w-full bg-white dark:bg-zinc-900 rounded-xl shadow-xl">
            <div className="modal-header px-0 pt-0 border-b border-gray-100 dark:border-zinc-800 pb-3 mb-4 flex justify-between items-center">
              <h2 className="text-xl font-bold text-gray-900 dark:text-white text-red-600">Delete Destination</h2>
              <button className="btn btn-text text-gray-400 hover:text-gray-600 dark:text-gray-400 p-1" onClick={() => setDestToDelete(null)}>
                <i className="fa-solid fa-xmark text-lg"></i>
              </button>
            </div>
            <div className="space-y-4">
              <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">
                Are you sure you want to delete <span className="font-bold">{destToDelete.name}</span>? This action cannot be undone.
              </p>
            </div>
            <div className="modal-footer px-0 pb-0 mt-6 pt-4 border-t border-gray-100 dark:border-zinc-800 flex gap-3">
              <button className="btn bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200 flex-1 py-2 rounded-lg font-medium" onClick={() => setDestToDelete(null)}>Cancel</button>
              <button 
                className="btn bg-red-600 hover:bg-red-700 text-white flex-1 shadow-md flex justify-center items-center gap-2 py-2 rounded-lg font-medium" 
                onClick={deleteDestination} 
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Buy Credit Modal */}
      {showCreditModal && (
        <div className="modal-overlay z-50">
          <div className="modal-box max-w-sm w-full bg-white dark:bg-zinc-900 rounded-xl shadow-xl">
            <div className="modal-header px-0 pt-0 border-b border-gray-100 dark:border-zinc-800 pb-3 mb-4 flex justify-between items-center">
              <h2 className="text-xl font-bold text-gray-900 dark:text-white">Buy Credit</h2>
              <button className="btn btn-text text-gray-400 hover:text-gray-600 dark:text-gray-400 p-1" onClick={() => setShowCreditModal(false)}>
                <i className="fa-solid fa-xmark text-lg"></i>
              </button>
            </div>
            <div className="space-y-4">
              <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">
                Top up your prepaid balance to ensure uninterrupted streaming. Your credits will be used automatically.
              </p>
              
              <div className="grid grid-cols-2 gap-3 mb-4">
                {[500, 1000, 2000, 5000].map(amt => (
                  <button 
                    key={amt}
                    onClick={() => setCreditAmount(amt.toString())}
                    className={`py-2 px-3 border rounded-lg text-sm font-medium transition-colors ${
                      creditAmount === amt.toString() 
                        ? 'bg-blue-50 border-blue-500 text-blue-700 ring-1 ring-blue-500' 
                        : 'border-gray-200 dark:border-zinc-700 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:bg-zinc-950'
                    }`}
                  >
                    {userInfo?.currency === 'INR' ? '₹' : '$'}{amt}
                  </button>
                ))}
              </div>

              <div className="form-group">
                <label className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-1 block">Custom Amount</label>
                <div className="relative mt-1 rounded-md shadow-sm">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <span className="text-gray-500 sm:text-sm font-medium">{userInfo?.currency === 'INR' ? '₹' : '$'}</span>
                  </div>
                  <input 
                    type="text"
                    inputMode="numeric"
                    className="form-input pl-8 w-full border-gray-300 rounded-lg focus:ring-blue-500 focus:border-blue-500 py-2 appearance-none" 
                    placeholder={`Min. ${userInfo?.currency === 'INR' ? '₹10' : '$1'}`}
                    value={creditAmount} 
                    onChange={e => setCreditAmount(e.target.value.replace(/[^0-9]/g, ''))}
                    min={userInfo?.currency === 'INR' ? "10" : "1"}
                  />
                </div>
              </div>

              {Number(creditAmount) > 0 && hourlyRate > 0 && (
                <div className="mt-4 p-3 bg-blue-50 rounded-lg border border-blue-100 text-xs text-blue-800">
                  <p className="font-semibold mb-1">Estimated Runtime ({userInfo?.currency === 'INR' ? '₹' : '$'}{formatMoney(hourlyRate)}/hr base per destination):</p>
                  <ul className="list-disc pl-4 space-y-1">
                    <li>720p Resolution (1x): ~{Math.floor(Number(creditAmount) / hourlyRate)} hours</li>
                    <li>1080p Resolution (2x): ~{Math.floor(Number(creditAmount) / (hourlyRate * 2.0))} hours</li>
                    <li>4K Resolution (4x): ~{Math.floor(Number(creditAmount) / (hourlyRate * 4.0))} hours</li>
                  </ul>
                </div>
              )}
            </div>
            <div className="modal-footer px-0 pb-0 mt-6 pt-4 border-t border-gray-100 dark:border-zinc-800 flex gap-3">
              <button className="btn bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200 flex-1 py-2 rounded-lg font-medium" onClick={() => setShowCreditModal(false)}>Cancel</button>
              <button 
                className="btn btn-primary flex-[2] shadow-md flex justify-center items-center gap-2 py-2 rounded-lg font-medium" 
                onClick={handleBuyCredit} 
                disabled={processingPayment || !creditAmount || Number(creditAmount) < (userInfo?.currency === 'INR' ? 10 : 1)}
              >
                {processingPayment ? (
                  <><i className="fa-solid fa-spinner fa-spin"></i> Processing...</>
                ) : (
                  <>Proceed to Pay {userInfo?.currency === 'INR' ? '₹' : '$'}{creditAmount || '0'}</>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
