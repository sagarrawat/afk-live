import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AlertKinds } from './NotificationSettings';

const prefs = [
  { id: 'STREAM', label: 'A stream goes down or comes back', description: '', email: true, push: true },
  { id: 'PHONE', label: "Your phone's signal", description: '', email: true, push: true },
];

describe('alert preferences (NT-3)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('turns one kind of alert off by email', async () => {
    const puts: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'PUT') {
        puts.push(JSON.parse(String(init.body)));
        return new Response(JSON.stringify(prefs.map((p) => (p.id === 'PHONE' ? { ...p, email: false } : p))), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      const body = url.includes('/push') ? { available: true, publicKey: 'x', devices: 0 } : prefs;
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    render(<AlertKinds emailOn />);
    const box = await screen.findByRole('checkbox', { name: "Email: Your phone's signal" });
    expect(await screen.findByRole('checkbox', { name: "Devices: Your phone's signal" })).toBeChecked();
    await userEvent.click(box);
    expect(puts).toEqual([{ PHONE: { email: false, push: true } }]);
    expect(await screen.findByRole('checkbox', { name: "Email: Your phone's signal" })).not.toBeChecked();
  });
});
