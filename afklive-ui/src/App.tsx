import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Shell, useTheme } from './components/Shell';
import { Empty, ToastProvider } from './components/ui';
import { ApiError, patch } from './lib/api';
import { browserTimeZone, guessCurrency } from './lib/format';
import { keys, useMe } from './lib/queries';
import { UploadsProvider } from './lib/uploads-context';
import { AdminConsole } from './pages/admin/AdminConsole';
import { Destinations } from './pages/Destinations';
import { Help } from './pages/Help';
import { Home } from './pages/Home';
import { Library } from './pages/Library';
import { Schedule } from './pages/Schedule';
import { Settings } from './pages/Settings';
import { Setup } from './pages/Setup';
import { StreamPage } from './pages/StreamPage';

export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (n, e) => !(e instanceof ApiError && e.status < 500) && n < 2,
        refetchOnWindowFocus: true,
        staleTime: 2_000,
      },
    },
  });
}

/** ON-3: the first visit records the browser's time zone and a currency guess (editable later). */
function useDetectPreferences(qc: QueryClient) {
  const { data: me } = useMe();
  useEffect(() => {
    if (!me || (me.timeZone && me.currency)) return;
    patch('/api/me', { timeZone: me.timeZone ?? browserTimeZone(), currency: me.currency ?? guessCurrency() })
      .then(() => qc.invalidateQueries({ queryKey: keys.me }))
      .catch(() => {});
  }, [me, qc]);
}

/** The payment page sends people back to /studio?payment_order=…; billing confirms it. */
function LegacyRedirects() {
  const { search } = useLocation();
  const p = new URLSearchParams(search);
  if (p.get('payment_order')) return <Navigate to={`/studio/settings/billing?payment_order=${encodeURIComponent(p.get('payment_order')!)}`} replace />;
  return <Home />;
}

function Routed({ qc }: { qc: QueryClient }) {
  useTheme();
  useDetectPreferences(qc);
  return (
    <Routes>
      {/* The admin console has its own layout: none of the creator app's nav or plan card. */}
      <Route path="/studio/admin/*" element={<AdminConsole />} />
      <Route path="*" element={<CreatorApp />} />
    </Routes>
  );
}

function CreatorApp() {
  return (
    <Shell>
      <Routes>
        <Route path="/studio" element={<LegacyRedirects />} />
        <Route path="/studio/setup" element={<Setup />} />
        <Route path="/studio/streams/:id" element={<StreamPage />} />
        <Route path="/studio/streams/:id/:tab" element={<StreamPage />} />
        <Route path="/studio/schedule" element={<Schedule />} />
        <Route path="/studio/library" element={<Library />} />
        <Route path="/studio/destinations" element={<Destinations />} />
        <Route path="/studio/settings" element={<Settings />} />
        <Route path="/studio/settings/:section" element={<Settings />} />
        <Route path="/studio/help" element={<Help />} />
        <Route
          path="*"
          element={
            <Empty title="That page doesn't exist" action={<Link to="/studio" className="text-accent hover:underline">Go to your streams</Link>}>
              The link may be old or mistyped.
            </Empty>
          }
        />
      </Routes>
    </Shell>
  );
}

export function App({ client }: { client?: QueryClient }) {
  const qc = client ?? makeQueryClient();
  return (
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <UploadsProvider>
          <BrowserRouter>
            <Routed qc={qc} />
          </BrowserRouter>
        </UploadsProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
