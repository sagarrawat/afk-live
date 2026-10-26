import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PushPrompt } from './PushPrompt';

const push = vi.hoisted(() => ({
  supported: true,
  info: { available: true, publicKey: 'BKey', devices: 0 } as { available: boolean; publicKey: string | null; devices: number },
  sub: null as unknown,
  grant: true,
  enabled: [] as string[],
}));

vi.mock('@/lib/push', () => ({
  pushSupported: () => push.supported,
  pushInfo: async () => push.info,
  currentSubscription: async () => push.sub,
  enablePush: async (key: string) => {
    push.enabled.push(key);
    return push.grant;
  },
}));

function permission(p: NotificationPermission) {
  vi.stubGlobal('Notification', { permission: p });
}

describe('PushPrompt (NT-1)', () => {
  beforeEach(() => {
    push.supported = true;
    push.info = { available: true, publicKey: 'BKey', devices: 0 };
    push.sub = null;
    push.grant = true;
    push.enabled = [];
    permission('default');
    localStorage.clear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('asks only when tapped, then says alerts are on', async () => {
    render(<PushPrompt />);
    await userEvent.click(await screen.findByRole('button', { name: 'Turn on' }));
    expect(push.enabled).toEqual(['BKey']);
    expect(await screen.findByText('Alerts are on for this device.')).toBeInTheDocument();
  });

  it('explains when the browser refuses', async () => {
    push.grant = false;
    render(<PushPrompt />);
    await userEvent.click(await screen.findByRole('button', { name: 'Turn on' }));
    expect(await screen.findByText(/didn't allow it/)).toBeInTheDocument();
  });

  it('"Not now" hides it on this device for good', async () => {
    const { unmount } = render(<PushPrompt />);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(screen.queryByText('Get alerts on this device')).not.toBeInTheDocument();
    unmount();
    render(<PushPrompt />);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('Get alerts on this device')).not.toBeInTheDocument();
  });

  it.each([
    ['push is not set up on the server', () => (push.info = { available: false, publicKey: null, devices: 0 })],
    ['alerts are already on here', () => (push.sub = { endpoint: 'x' })],
    ['the browser blocked notifications', () => permission('denied')],
    ['the browser cannot do push', () => (push.supported = false)],
  ])('stays hidden when %s', async (_why, setup) => {
    setup();
    render(<PushPrompt />);
    await new Promise((r) => setTimeout(r, 20));
    await waitFor(() => expect(screen.queryByText('Get alerts on this device')).not.toBeInTheDocument());
  });
});
