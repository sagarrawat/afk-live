import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ToastProvider } from '@/components/ui';
import { SecuritySettings, type SecurityStatus } from './SecuritySettings';

beforeAll(() => {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.open = false;
  };
});

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const me = { id: 1, email: 'meera@example.com', name: 'Meera', admin: false, plan: { id: 'FREE', name: 'Free' }, onboarded: true, emailAlerts: true };

let calls: { method: string; path: string; body: unknown }[] = [];
function serve(status: Partial<SecurityStatus>, answers: Record<string, () => Response> = {}) {
  calls = [];
  let current: SecurityStatus = { twoFactorEnabled: false, recoveryCodesLeft: 0, twoFactorSince: null, hasPassword: true, emailChangeAvailable: true, pendingEmail: null, ...status };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : null });
      const answer = answers[`${method} ${path}`];
      if (answer) return answer();
      if (path === '/api/me') return json(me);
      if (path === '/api/account/security') return json(current);
      if (method === 'POST' && path === '/api/account/email') {
        current = { ...current, pendingEmail: 'new@example.com' };
        return json({ pendingEmail: 'new@example.com' });
      }
      if (method === 'DELETE' && path === '/api/account/email') {
        current = { ...current, pendingEmail: null };
        return new Response(null, { status: 204 });
      }
      return json({ otherSessions: 2 });
    }),
  );
}

const show = () =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ToastProvider>
        <SecuritySettings />
      </ToastProvider>
    </QueryClientProvider>,
  );

afterEach(() => vi.unstubAllGlobals());

describe('change password (UX audit #14)', () => {
  it('needs the current password and the new one twice', async () => {
    serve({});
    show();
    const change = await screen.findByRole('button', { name: 'Change password' });
    await userEvent.type(screen.getByLabelText('Current password'), 'Old-pass1!');
    await userEvent.type(screen.getByLabelText('New password'), 'New-pass2!');
    await userEvent.type(screen.getByLabelText('New password again'), 'New-pass2?');
    expect(screen.getByText("The two new passwords don't match.")).toBeInTheDocument();
    expect(change).toBeDisabled();
    await userEvent.clear(screen.getByLabelText('New password again'));
    await userEvent.type(screen.getByLabelText('New password again'), 'New-pass2!');
    await userEvent.click(change);
    await waitFor(() => expect(calls).toEqual([{ method: 'POST', path: '/api/account/password', body: { currentPassword: 'Old-pass1!', newPassword: 'New-pass2!' } }]));
    expect(await screen.findByText(/Password changed\. Other devices are signed out/)).toBeInTheDocument();
    expect(screen.getByLabelText('Current password')).toHaveValue('');
  });

  it("shows the server's reason when the current password is wrong", async () => {
    serve({}, { 'POST /api/account/password': () => json({ message: "That isn't your current password." }, 400) });
    show();
    await userEvent.type(await screen.findByLabelText('Current password'), 'nope');
    await userEvent.type(screen.getByLabelText('New password'), 'New-pass2!');
    await userEvent.type(screen.getByLabelText('New password again'), 'New-pass2!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByText("That isn't your current password.")).toBeInTheDocument();
  });
});

describe('Google sign-in accounts (UX audit #14, #15)', () => {
  it("has no password form, and two-step copy that doesn't assume a password", async () => {
    serve({ hasPassword: false });
    show();
    expect(await screen.findByText(/You sign in with Google, so there's no AFK Live password to change/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument();
    expect(screen.getByText(/after you sign in with Google, we also ask for a code/)).toBeInTheDocument();
    expect(screen.queryByText(/after your password/)).not.toBeInTheDocument();
    expect(screen.getByText(/tied to this Google address/)).toBeInTheDocument();
  });
});

describe('change email (UX audit #14)', () => {
  it('says plainly when it is not available yet (email is off)', async () => {
    serve({ emailChangeAvailable: false });
    show();
    expect(await screen.findByText(/Changing your email isn't available yet/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change email' })).not.toBeInTheDocument();
  });

  it('sends a link to the new address with the password, and can be cancelled', async () => {
    serve({});
    show();
    expect(await screen.findByText('meera@example.com')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Change email' }));
    const dialog = screen.getByRole('dialog', { name: 'Change email' });
    await userEvent.type(within(dialog).getByLabelText('New email'), 'new@example.com');
    await userEvent.type(within(dialog).getByLabelText('Your password'), 'Old-pass1!');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send the link' }));
    await waitFor(() => expect(calls[0]).toEqual({ method: 'POST', path: '/api/account/email', body: { newEmail: 'new@example.com', password: 'Old-pass1!' } }));
    expect(await screen.findByText(/Open it within 24 hours/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel the change' }));
    await waitFor(() => expect(calls[1]).toEqual({ method: 'DELETE', path: '/api/account/email', body: null }));
    expect(await screen.findByRole('button', { name: 'Change email' })).toBeInTheDocument();
  });
});

describe('sign out other devices (UX audit #14)', () => {
  it('asks first, then says this device stays signed in', async () => {
    serve({});
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out other devices' }));
    const dialog = screen.getByRole('dialog', { name: 'Sign out other devices?' });
    expect(calls).toEqual([]);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Sign out other devices' }));
    await waitFor(() => expect(calls).toEqual([{ method: 'POST', path: '/api/account/sign-out-others', body: {} }]));
    expect(await screen.findByText('Signed out 2 other sign-ins. This device stays signed in.')).toBeInTheDocument();
  });
});
