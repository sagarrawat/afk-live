import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { makeQueryClient } from '@/App';
import type { StreamView } from '@/lib/types';
import { SettingsTab } from './SettingsTab';

describe('fit (PL-6)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('saves how off-shape videos fill the frame', async () => {
    const puts: [string, unknown][] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'PUT') puts.push([url, JSON.parse(String(init.body))]);
      return new Response(url.includes('quality-options') ? '[]' : '{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    const s = { id: 4, name: 'Lofi', timeZone: 'UTC', orientation: 'LANDSCAPE', qualityHeight: 720, fps: 30, fit: 'FIT', live: { state: 'DRAFT', uptimeSeconds: 0 } } as unknown as StreamView;
    render(<QueryClientProvider client={makeQueryClient()}><MemoryRouter><SettingsTab stream={s} /></MemoryRouter></QueryClientProvider>);
    expect(screen.getByRole('radio', { name: /Bars/ })).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(screen.getByRole('radio', { name: /Blurred background/ }));
    expect(puts).toEqual([['/api/streams/4/presentation', { fit: 'BLUR' }]]);
  });
});
