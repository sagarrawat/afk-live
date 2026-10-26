import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App, makeQueryClient } from '@/App';
import { attachmentProblem } from './ContactSupport';

beforeAll(() => {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.open = false;
  };
});

const me = {
  id: 1, email: 'rohit@example.com', name: 'Rohit', admin: false, onboarded: true, emailAlerts: true, timeZone: 'Asia/Kolkata', currency: 'INR',
  plan: { id: 'FREE', name: 'Free', streams: 1, maxHeight: 720, maxFps: 30, storageBytes: 1, monthlyHours: 10, destinations: 1, watermark: true },
};

let tickets: FormData[] = [];
beforeEach(() => {
  tickets = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input).split('?')[0];
      if (path === '/api/support/ticket') {
        tickets.push(init?.body as FormData);
        return new Response(JSON.stringify({ success: true, id: 4 }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      const routes: Record<string, unknown> = { '/api/me': me, '/api/notifications': { unread: 0, emailAlerts: true, items: [] } };
      const body = routes[path];
      return new Response(JSON.stringify(body ?? {}), { status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  window.history.pushState({}, '', '/studio/help');
});
afterEach(() => vi.unstubAllGlobals());

describe('Contact support files a ticket in the app (UX audit #23)', () => {
  it('sends the topic, message and a screenshot from Help', async () => {
    render(<App client={makeQueryClient()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Contact support' }));
    const dialog = screen.getByRole('dialog', { name: 'Contact support' });
    expect(within(dialog).getByRole('button', { name: 'Send' })).toBeDisabled();
    await userEvent.selectOptions(within(dialog).getByLabelText("What's it about?"), 'Destinations');
    await userEvent.type(within(dialog).getByLabelText('What happened?'), 'YouTube says offline');
    const shot = new File([new Uint8Array([1, 2, 3])], 'screen.png', { type: 'image/png' });
    await userEvent.upload(within(dialog).getByLabelText('Attach a screenshot'), shot);
    expect(within(dialog).getByText('screen.png')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(tickets).toHaveLength(1));
    expect(tickets[0].get('category')).toBe('Destinations');
    expect(tickets[0].get('message')).toBe('YouTube says offline');
    expect((tickets[0].get('file') as File).name).toBe('screen.png');
    expect(await screen.findByRole('dialog', { name: 'Message sent' })).toHaveTextContent('usually reply within a day');
    expect(document.querySelector('a[href^="mailto:"]')).toBeNull();
  });

  it('opens from the account menu too, instead of the mail app', async () => {
    window.history.pushState({}, '', '/studio/settings');
    render(<App client={makeQueryClient()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    fireEvent.click(screen.getByRole('button', { name: 'Contact support' }));
    expect(screen.getByRole('dialog', { name: 'Contact support' })).toBeInTheDocument();
  });

  it('refuses files the server would refuse, before uploading', () => {
    expect(attachmentProblem(new File(['x'], 'run.sh'))).toMatch(/PNG or JPG/);
    expect(attachmentProblem(new File(['x'], 'shot.JPG'))).toBeNull();
    const big = new File(['x'], 'clip.mp4');
    Object.defineProperty(big, 'size', { value: 26 * 1024 * 1024 });
    expect(attachmentProblem(big)).toMatch(/under 25 MB/);
  });
});

describe('the notifications list links to alert settings (UX audit #19)', () => {
  it('has an Alert settings link at the bottom', async () => {
    render(<App client={makeQueryClient()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Notifications' }));
    expect(screen.getByRole('link', { name: 'Alert settings' })).toHaveAttribute('href', '/studio/settings/notifications');
  });
});
