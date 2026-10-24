import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, makeQueryClient } from '@/App';
import type { Me } from '@/lib/types';
import { describeMedia, type RunDetail } from './Support';

const admin: Me = {
  id: 1,
  email: 'ops@example.com',
  name: 'Ops',
  admin: true,
  plan: { id: 'PRO', name: 'Pro', streams: 3, maxHeight: 1080, maxFps: 60, storageBytes: 1, monthlyHours: 0, destinations: 3, watermark: false },
  timeZone: 'Asia/Kolkata',
  currency: 'INR',
  onboarded: true,
  emailAlerts: true,
};
const base = { '/api/me': admin, '/api/notifications': { unread: 0, emailAlerts: true, items: [] } };

function mockApi(routes: Record<string, unknown>) {
  const fn = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const path = String(input).replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    const body = routes[path];
    return new Response(body === undefined ? '{}' : JSON.stringify(body), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

const now = new Date().toISOString();
const emailOff = { configured: false, sent24h: 0, failed24h: 4, lastFailure: { at: now, kind: 'VERIFY', ok: false, error: "Email isn't set up" } };

const run: RunDetail = {
  jobId: 77,
  streamId: 9,
  streamName: 'Lofi 24/7',
  userId: 42,
  userEmail: 'aarti@example.com',
  plan: 'PRO',
  live: true,
  status: 'LIVE',
  desiredState: 'RUN',
  health: 'amber',
  worker: 'worker-mumbai-1',
  heartbeatAgeSeconds: 2,
  startTime: now,
  restarts: 1,
  bitrateKbps: 6012,
  fps: 30,
  speed: 1,
  source: { kind: 'PLAYLIST', count: 2, musicCount: 0, media: [{ id: 3, title: 'rain.mp4', width: 1080, height: 1920, fps: 30, shape: 'Vertical', codec: 'h264', hasAudio: false, status: 'READY', problem: 'No sound track' }] },
  output: { width: 1920, height: 1080, fps: 30, shape: 'Landscape', videoKbps: 6000, audio: 'AAC 128 kbps, 44.1 kHz stereo', keyframes: 'Every 2 s' },
  destinations: [
    { position: 0, name: 'YouTube', off: false, server: 'rtmp://a.rtmp.youtube.com/live2', keyMasked: '••••qrst', keyLength: 24, checkLevel: 'OK', check: 'Looks right', status: 'RECONNECTING', restarts: 3, lastError: 'Broken pipe' },
    { position: 1, name: 'Old channel', off: true, server: 'rtmp://a.rtmp.youtube.com/live2', keyMasked: '', keyLength: 0, checkLevel: 'BAD', check: 'Key looks like a server address', restarts: 0 },
  ],
  log: '12:00:01 dest 1: [flv] Broken pipe\n12:00:02 worker: dest 1 dropped; reconnecting (restart 3)',
  logUpdatedAt: now,
  otherRuns: [{ jobId: 70, startTime: now, endTime: now, status: 'ENDED', endReason: 'Stopped by user' }],
  history: [],
};

describe('Admin support tools', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows email off on the Servers page and sends an audited test email', async () => {
    window.history.pushState({}, '', '/studio/admin/servers');
    const fetch = mockApi({ ...base, '/api/admin/workers': [], '/api/admin/servers/history': [], '/api/admin/email': emailOff, '/api/admin/email/test': { ok: false, error: "Email isn't set up (ZEPTOMAIL_URL / ZEPTOMAIL_TOKEN are empty)" } });
    render(<App client={makeQueryClient()} />);
    const panel = (await screen.findByText('Off: not set up')).closest('section')!;
    await userEvent.click(within(panel).getByRole('button', { name: 'Send a test email' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Send' })).toBeDisabled(); // needs a reason
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Checking after setup');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/email/test')!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ reason: 'Checking after setup' });
    expect(await screen.findByText(/Not sent: Email isn't set up/)).toBeInTheDocument();
  });

  it('no longer puts an email banner on top of every tab', async () => {
    window.history.pushState({}, '', '/studio/admin/live');
    mockApi({ ...base, '/api/admin/live': [], '/api/admin/email': emailOff });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findAllByText('Nothing is live right now.')).not.toHaveLength(0);
    expect(screen.queryByText(/Email is off/)).toBeNull();
  });

  it('shows a run: source, output, masked destinations with checks, and the log', async () => {
    window.history.pushState({}, '', '/studio/admin/runs/77');
    mockApi({ ...base, '/api/admin/email': { configured: true, sent24h: 0, failed24h: 0 }, '/api/admin/jobs/77': run });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Lofi 24/7')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'aarti@example.com' })).toHaveAttribute('href', '/studio/admin/users/42');
    expect(screen.getByText('1080×1920, 30 fps, vertical, h264, no sound')).toBeInTheDocument();
    expect(screen.getByText('No sound track')).toBeInTheDocument();
    expect(screen.getByText('1920×1080, landscape, 30 fps')).toBeInTheDocument();
    expect(screen.getByText('••••qrst')).toBeInTheDocument();
    expect(screen.getByText('Looks right')).toBeInTheDocument();
    expect(screen.getByText('Key looks like a server address')).toBeInTheDocument();
    expect(screen.getByText('Platform said: Broken pipe')).toBeInTheDocument();
    expect(screen.getByText(/worker: dest 1 dropped/)).toBeInTheDocument();
    // A live destination can't be tested; a turned-off one can.
    const [live, off] = screen.getAllByRole('button', { name: 'Send a test' });
    expect(live).toBeDisabled();
    expect(off).toBeEnabled();
    expect(screen.getByRole('link', { name: /Stopped by user/ })).toHaveAttribute('href', '/studio/admin/runs/70');
  });

  it('shows whether the platform is receiving when the stream server reports it (RE-8)', async () => {
    window.history.pushState({}, '', '/studio/admin/runs/77');
    const reaching = {
      ...run,
      destinations: [{ ...run.destinations[0], reach: 'NOT_REACHING' as const, reachReason: 'The platform refused the stream key.' }, run.destinations[1]],
    };
    mockApi({ ...base, '/api/admin/email': { configured: true, sent24h: 0, failed24h: 0 }, '/api/admin/jobs/77': reaching });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Not reaching the platform')).toBeInTheDocument();
    expect(screen.getByText('Why: The platform refused the stream key.')).toBeInTheDocument();
    // Without a reach (older stream servers) the relay status shows instead.
    expect(screen.queryByText('reconnecting')).toBeNull();
  });

  it('tests a destination with a reason and shows the result', async () => {
    window.history.pushState({}, '', '/studio/admin/runs/77');
    const ended = { ...run, live: false, status: 'ENDED', endTime: now, endReason: 'Stopped by user' };
    const fetch = mockApi({
      ...base,
      '/api/admin/email': { configured: true, sent24h: 0, failed24h: 0 },
      '/api/admin/jobs/77': ended,
      '/api/admin/jobs/77/destinations/0/test': { id: 5, status: 'PENDING' },
      '/api/admin/destination-tests/5': { id: 5, status: 'FAILED', message: 'The platform refused the stream key.' },
    });
    render(<App client={makeQueryClient()} />);
    await userEvent.click((await screen.findAllByRole('button', { name: 'Send a test' }))[0]);
    await userEvent.type(screen.getByLabelText('Reason'), 'Ticket 12');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send test' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/jobs/77/destinations/0/test')!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ reason: 'Ticket 12' });
    expect(await screen.findByText('Test failed: The platform refused the stream key.')).toBeInTheDocument();
  });

  it('lists the emails sent to a user, links streams to their last run, and makes a reset link with a reason', async () => {
    window.history.pushState({}, '', '/studio/admin/users/42');
    const fetch = mockApi({
      ...base,
      '/api/admin/email': emailOff,
      '/api/admin/users/42': {
        id: 42,
        email: 'aarti@example.com',
        plan: 'FREE',
        enabled: false,
        role: 'ROLE_USER',
        storageBytes: 0,
        storageLimit: 1,
        hoursThisMonth: 0,
        hoursIncluded: 10,
        streams: [{ id: 9, name: 'Lofi 24/7', live: false }],
        payments: [],
        invoices: [],
        history: [],
      },
      '/api/admin/users/42/support': {
        awaitingVerification: true,
        hasPassword: true,
        streams: [{ streamId: 9, name: 'Lofi 24/7', live: false, lastJobId: 77 }],
        emails: [{ at: now, kind: 'VERIFY', ok: false, error: "Email isn't set up" }],
      },
      '/api/admin/users/42/reset-link': { link: 'https://afklive.in/reset-password?token=t0k3n' },
    });
    render(<App client={makeQueryClient()} />);
    expect(await screen.findByText('Waiting for email verification.')).toBeInTheDocument();
    const email = screen.getByText('Verify email').closest('li')!;
    expect(within(email).getByText('Not sent')).toBeInTheDocument();
    expect(within(email).getByText("Email isn't set up")).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Lofi 24\/7/ })).toHaveAttribute('href', '/studio/admin/runs/77');

    await userEvent.click(screen.getByRole('button', { name: 'Password reset link' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Make link' })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Ticket 40');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Make link' }));
    const call = fetch.mock.calls.find(([u]) => String(u) === '/api/admin/users/42/reset-link')!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ reason: 'Ticket 40' });
    expect(await screen.findByLabelText('Password reset link')).toHaveValue('https://afklive.in/reset-password?token=t0k3n');
  });

  it('describes a source file in one line', () => {
    expect(describeMedia({ title: 'a', width: 1920, height: 1080, fps: 29.97, shape: 'Landscape', codec: 'h264', hasAudio: true })).toBe('1920×1080, 29.97 fps, landscape, h264, sound');
    expect(describeMedia({ title: 'a' })).toBe('Not probed yet');
  });
});
