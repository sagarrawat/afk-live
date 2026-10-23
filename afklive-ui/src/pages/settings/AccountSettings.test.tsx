import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { ToastProvider } from '@/components/ui';
import { DeleteAccountDialog } from './DataSettings';
import { SecuritySettings } from './SecuritySettings';

function wrap(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  );
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('two-step sign-in (ON-6)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sets up with a QR code, confirms with a code and shows the recovery codes once', async () => {
    const posts: { path: string; body: unknown }[] = [];
    let enabled = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (init?.method === 'POST') posts.push({ path, body: init.body ? JSON.parse(String(init.body)) : null });
        if (path === '/api/account/security') return json({ twoFactorEnabled: enabled, recoveryCodesLeft: enabled ? 10 : 0, twoFactorSince: null });
        if (path === '/api/account/2fa/setup') return json({ secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', uri: 'otpauth://totp/AFK%20Live:a@b.c?secret=JBSW' });
        if (path === '/api/account/2fa/enable') {
          enabled = true;
          return json({ recoveryCodes: Array.from({ length: 10 }, (_, i) => `abcde-fgh${i}k`) });
        }
        return json({}, 404);
      }),
    );
    wrap(<SecuritySettings />);
    await userEvent.click(await screen.findByRole('button', { name: /turn on two-step sign-in/i }));

    expect(await screen.findByRole('img', { name: /qr code/i })).toHaveAttribute('src', '/api/account/2fa/qr.png');
    expect(screen.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: /confirm and turn on/i });
    expect(confirm).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/6-digit code/i), '123456');
    await userEvent.click(confirm);

    const list = await screen.findByRole('list', { name: /recovery codes/i });
    expect(list.querySelectorAll('li')).toHaveLength(10);
    expect(posts).toContainEqual({ path: '/api/account/2fa/enable', body: { code: '123456' } });

    await userEvent.click(screen.getByRole('button', { name: /i've saved them/i }));
    expect(await screen.findByText('On')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: /recovery codes/i })).not.toBeInTheDocument();
  });
});

describe('delete account (ON-7)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('only deletes once the email is typed', async () => {
    const posts: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        posts.push(JSON.parse(String(init?.body)));
        return json({ scheduledFor: '2026-10-30T10:00:00Z' });
      }),
    );
    const deleted = vi.fn();
    wrap(<DeleteAccountDialog email="Meera@Example.com" open onClose={() => {}} onDeleted={deleted} />);
    const button = screen.getByRole('button', { name: /delete my account/i });
    expect(button).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/to confirm/i), 'meera@example');
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/to confirm/i), '.com');
    expect(button).toBeEnabled();

    await userEvent.click(button);
    await vi.waitFor(() => expect(deleted).toHaveBeenCalled());
    expect(posts).toEqual([{ email: 'meera@example.com' }]);
  });
});
