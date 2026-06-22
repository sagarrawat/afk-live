import React, { useState, useEffect } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area
} from 'recharts';
import { useAuth } from '../../hooks/useAuth';
import { formatMoney } from '../../utils/formatters';

import toast from 'react-hot-toast';

export default function Analytics() {
  const { user } = useAuth();
  const [history, setHistory] = useState([]);
  const [ledger, setLedger] = useState([]);
  const [loading, setLoading] = useState(true);
  
  const [historyPage, setHistoryPage] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(1);
  const [ledgerPage, setLedgerPage] = useState(0);
  const [ledgerTotal, setLedgerTotal] = useState(1);

  const fetchHistory = (page) => {
    return fetch(`/api/stream/history?page=${page}&size=10`, { credentials: 'include' })
      .then(res => { if(!res.ok) throw new Error(); return res.json(); });
  };

  const fetchLedger = (page) => {
    return fetch(`/api/ledger?page=${page}&size=10`, { credentials: 'include' })
      .then(res => { if(!res.ok) throw new Error(); return res.json(); });
  };

  useEffect(() => {
    setLoading(true);
    Promise.all([fetchHistory(historyPage), fetchLedger(ledgerPage)])
      .then(([historyData, ledgerData]) => {
        if (historyData.data?.content) {
          setHistory(historyData.data.content);
          setHistoryTotal(historyData.data.totalPages || 1);
        }
        if (ledgerData.content) {
          setLedger(ledgerData.content);
          setLedgerTotal(ledgerData.totalPages || 1);
        }
      })
      .catch(() => toast.error('Failed to load analytics data.'))
      .finally(() => setLoading(false));
  }, [historyPage, ledgerPage]);

  if (loading && history.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="afk-loader"><div className="afk-loader-play"></div></div>
      </div>
    );
  }

  // Calculate aggregates (only for the current page, or ideally this would come from a summary endpoint)
  const totalStreams = history.length;
  const totalCost = history.reduce((acc, job) => acc + (job.accumulatedCost || 0), 0);
  const totalDropped = history.reduce((acc, job) => acc + (job.droppedFrames || 0), 0);
  const totalBytes = history.reduce((acc, job) => acc + (job.bytesStreamed || 0), 0);

  // Formatting chart data
  const chartData = [...history].reverse().map(job => {
    const start = new Date(job.startTime);
    const durationMins = job.endTime ? (new Date(job.endTime) - start) / 60000 : 0;
    return {
      date: start.toLocaleDateString(),
      duration: Math.max(0, durationMins).toFixed(1),
      cost: job.accumulatedCost || 0,
      dropped: job.droppedFrames || 0,
    };
  });

  return (
    <div className="p-8 max-w-6xl w-full h-full overflow-y-auto">


      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800">
          <p className="text-sm font-medium text-gray-500">Streams (Page)</p>
          <p className="text-3xl font-bold text-gray-900 dark:text-white mt-2">{totalStreams}</p>
        </div>
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800">
          <p className="text-sm font-medium text-gray-500">Cost (Page)</p>
          <p className="text-3xl font-bold text-blue-600 mt-2">
            {user?.currency === 'INR' ? '₹' : '$'}{formatMoney(totalCost)}
          </p>
        </div>
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800">
          <p className="text-sm font-medium text-gray-500">Dropped Frames (Page)</p>
          <p className="text-3xl font-bold text-gray-900 dark:text-white mt-2">{totalDropped}</p>
        </div>
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800">
          <p className="text-sm font-medium text-gray-500">Data Transferred (Page)</p>
          <p className="text-3xl font-bold text-gray-900 dark:text-white mt-2">{(totalBytes / 1024 / 1024 / 1024).toFixed(2)} GB</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-8">
        {/* Stream Duration Chart */}
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800 h-80">
          <h3 className="font-semibold text-gray-700 dark:text-gray-300 mb-4">Stream Duration (mins)</h3>
          <ResponsiveContainer width="100%" height={260}>
            <AreaChart data={chartData}>
              <defs>
                <linearGradient id="colorDuration" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3}/>
                  <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{fill: '#9ca3af', fontSize: 12}} />
              <YAxis axisLine={false} tickLine={false} tick={{fill: '#9ca3af', fontSize: 12}} />
              <Tooltip cursor={{stroke: '#e5e7eb', strokeWidth: 2}} contentStyle={{borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)'}} />
              <Area type="monotone" dataKey="duration" stroke="#3b82f6" strokeWidth={3} fillOpacity={1} fill="url(#colorDuration)" />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        {/* Cost Chart */}
        <div className="card bg-white dark:bg-zinc-900 p-5 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800 h-80">
          <h3 className="font-semibold text-gray-700 dark:text-gray-300 mb-4">Cost Over Time ({user?.currency === 'INR' ? '₹' : '$'})</h3>
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{fill: '#9ca3af', fontSize: 12}} />
              <YAxis axisLine={false} tickLine={false} tick={{fill: '#9ca3af', fontSize: 12}} />
              <Tooltip cursor={{stroke: '#e5e7eb', strokeWidth: 2}} contentStyle={{borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)'}} />
              <Line type="monotone" dataKey="cost" stroke="#10b981" strokeWidth={3} dot={{r: 4, strokeWidth: 2}} activeDot={{r: 6}} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="card bg-white dark:bg-zinc-900 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800 overflow-hidden mb-8">
        <div className="p-5 border-b border-gray-100 dark:border-zinc-800 flex justify-between items-center">
          <h3 className="font-semibold text-gray-800 dark:text-gray-200">Transaction Ledger</h3>
          <div className="flex gap-2">
            <button className="px-3 py-1 bg-gray-100 dark:bg-zinc-800 hover:bg-gray-200 text-sm rounded-md disabled:opacity-50" disabled={ledgerPage === 0} onClick={() => setLedgerPage(p => p - 1)}>Prev</button>
            <span className="px-2 py-1 text-sm text-gray-500">Page {ledgerPage + 1} of {ledgerTotal}</span>
            <button className="px-3 py-1 bg-gray-100 dark:bg-zinc-800 hover:bg-gray-200 text-sm rounded-md disabled:opacity-50" disabled={ledgerPage >= ledgerTotal - 1} onClick={() => setLedgerPage(p => p + 1)}>Next</button>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left">
            <thead className="bg-gray-50 dark:bg-zinc-950 text-gray-500 font-medium">
              <tr>
                <th className="px-6 py-3">Date</th>
                <th className="px-6 py-3">Description</th>
                <th className="px-6 py-3">Type</th>
                <th className="px-6 py-3 text-right">Amount</th>
                <th className="px-6 py-3 text-right">Balance After</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {ledger.map((txn) => (
                <tr key={txn.id} className="hover:bg-gray-50 dark:bg-zinc-950/50">
                  <td className="px-6 py-4 whitespace-nowrap text-gray-600 dark:text-gray-400">
                    {new Date(txn.createdAt).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 font-medium text-gray-900 dark:text-white">
                    {txn.description || txn.transactionType}
                  </td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 rounded-full text-xs font-medium ${
                      txn.transactionType === 'DEPOSIT' ? 'bg-green-100 text-green-700' : 
                      txn.transactionType === 'STREAM_USAGE' ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-gray-300'
                    }`}>
                      {txn.transactionType.replace('_', ' ')}
                    </span>
                  </td>
                  <td className={`px-6 py-4 text-right font-medium ${txn.amount > 0 ? 'text-green-600' : 'text-gray-900 dark:text-white'}`}>
                    {txn.amount > 0 ? '+' : ''}{user?.currency === 'INR' ? '₹' : '$'}{formatMoney(Math.abs(txn.amount))}
                  </td>
                  <td className="px-6 py-4 text-right font-medium text-gray-900 dark:text-white">
                    {user?.currency === 'INR' ? '₹' : '$'}{formatMoney(txn.balanceAfter)}
                  </td>
                </tr>
              ))}
              {ledger.length === 0 && (
                <tr>
                  <td colSpan="5" className="px-6 py-8 text-center text-gray-500">
                    No transactions found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card bg-white dark:bg-zinc-900 rounded-xl shadow-sm border border-gray-100 dark:border-zinc-800 overflow-hidden">
        <div className="p-5 border-b border-gray-100 dark:border-zinc-800 flex justify-between items-center">
          <h3 className="font-semibold text-gray-800 dark:text-gray-200">Session History</h3>
          <div className="flex gap-2">
            <button className="px-3 py-1 bg-gray-100 dark:bg-zinc-800 hover:bg-gray-200 text-sm rounded-md disabled:opacity-50" disabled={historyPage === 0} onClick={() => setHistoryPage(p => p - 1)}>Prev</button>
            <span className="px-2 py-1 text-sm text-gray-500">Page {historyPage + 1} of {historyTotal}</span>
            <button className="px-3 py-1 bg-gray-100 dark:bg-zinc-800 hover:bg-gray-200 text-sm rounded-md disabled:opacity-50" disabled={historyPage >= historyTotal - 1} onClick={() => setHistoryPage(p => p + 1)}>Next</button>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left">
            <thead className="bg-gray-50 dark:bg-zinc-950 text-gray-500 font-medium">
              <tr>
                <th className="px-6 py-3">Date</th>
                <th className="px-6 py-3">Title</th>
                <th className="px-6 py-3">Destination</th>
                <th className="px-6 py-3">Quality</th>
                <th className="px-6 py-3 text-right">Cost</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {history.map((job) => (
                <tr key={job.id} className="hover:bg-gray-50 dark:bg-zinc-950/50">
                  <td className="px-6 py-4 whitespace-nowrap text-gray-600 dark:text-gray-400">
                    {new Date(job.startTime).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 font-medium text-gray-900 dark:text-white">
                    {job.title || 'Untitled Stream'}
                  </td>
                  <td className="px-6 py-4 text-gray-600 dark:text-gray-400">
                    {job.destinationName || 'Unknown'}
                  </td>
                  <td className="px-6 py-4 text-gray-600 dark:text-gray-400">
                    {(() => {
                      try {
                        const spec = JSON.parse(job.streamSpecJson || '{}');
                        const q = spec.stream_quality;
                        return q == 2160 ? '4K' : (q == 1080 ? '1080p' : '720p');
                      } catch { return '720p'; }
                    })()}
                  </td>
                  <td className="px-6 py-4 text-right font-medium text-gray-900 dark:text-white">
                    {user?.currency === 'INR' ? '₹' : '$'}{formatMoney(job.accumulatedCost)}
                  </td>
                </tr>
              ))}
              {history.length === 0 && (
                <tr>
                  <td colSpan="4" className="px-6 py-8 text-center text-gray-500">
                    No past billings found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
