import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeQueryClient } from '@/App';
import { AddDestinationDialog, destinationUrl, needsServer, readKey } from './AddDestination';

HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };

describe('the stream key box', () => {
  it("refuses the server address pasted as the key (it once saved YouTube's server twice)", () => {
    for (const pasted of ['rtmp://a.rtmp.youtube.com/live2', ' rtmp://a.rtmp.youtube.com/live2/ ', 'a.rtmp.youtube.com/live2', 'a.rtmp.youtube.com', 'live2', 'rtmps://a.rtmps.youtube.com:443/live2/rtmp://a.rtmp.youtube.com/live2']) {
      expect(readKey('YOUTUBE', pasted).problem).toBe('This looks like the server address. Copy the stream key from YouTube Studio (Go live → Stream → Stream key) instead.');
    }
    expect(readKey('FACEBOOK', 'rtmps://live-api-s.facebook.com:443/rtmp/').problem).toMatch(/server address.*Facebook Live Producer/);
    expect(readKey('TWITCH', 'rtmp://live.twitch.tv/app').problem).toMatch(/server address.*Twitch dashboard/);
  });

  it('takes the key out of a full address for the same platform', () => {
    expect(readKey('YOUTUBE', ' rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl-mnop-qrst ')).toEqual({ key: 'abcd-efgh-ijkl-mnop-qrst', problem: null });
    expect(readKey('YOUTUBE', 'rtmps://a.rtmps.youtube.com:443/live2/abcd-efgh').key).toBe('abcd-efgh');
    expect(destinationUrl('YOUTUBE', 'rtmp://a.rtmp.youtube.com/live2/abcd-efgh')).toBe('rtmp://a.rtmp.youtube.com/live2/abcd-efgh');
    expect(destinationUrl('FACEBOOK', 'rtmps://live-api-s.facebook.com:443/rtmp/FB-1-0-Ab?s_bl=1&s_sc=2')).toBe('rtmps://live-api-s.facebook.com:443/rtmp/FB-1-0-Ab?s_bl=1&s_sc=2');
    expect(destinationUrl('TWITCH', 'rtmp://live-cdg.twitch.tv/app/live_123_abc')).toBe('rtmp://live.twitch.tv/app/live_123_abc');
  });

  it('accepts ordinary keys, and catches doubled addresses elsewhere too', () => {
    expect(readKey('YOUTUBE', 'abcd-efgh-ijkl-mnop-qrst').problem).toBeNull();
    expect(readKey('FACEBOOK', 'FB-1234-0-AbCd?s_bl=1&s_ps=1&a=Ab').problem).toBeNull();
    expect(readKey('TWITCH', 'live_123456_AbCdEf').problem).toBeNull();
    expect(readKey('KICK', 'sk_us-west_123').problem).toBeNull();
    expect(readKey('KICK', 'rtmps://x.live-video.net/app/sk_1', 'rtmps://x.live-video.net/app/')).toEqual({ key: 'sk_1', problem: null });
    expect(readKey('KICK', 'rtmps://x.live-video.net/app').problem).toMatch(/Paste it in Stream URL/);
    expect(readKey('CUSTOM', 'rtmp://live.example.com/app/key').problem).toBeNull();
    expect(readKey('CUSTOM', 'rtmp://live.example.com/app/rtmp://live.example.com/app').problem).toMatch(/another address inside it/);
  });

  it("explains the mistake and won't save it", async () => {
    const posts: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') posts.push(JSON.parse(String(init.body)));
      return new Response(JSON.stringify({ id: 3, name: 'YouTube', platform: 'YOUTUBE' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    const onAdded = vi.fn();
    render(<QueryClientProvider client={makeQueryClient()}><AddDestinationDialog open onClose={() => {}} onAdded={onAdded} /></QueryClientProvider>);
    const add = screen.getByRole('button', { name: 'Add destination' });
    const box = screen.getByLabelText('Stream key');
    await userEvent.type(box, 'rtmp://a.rtmp.youtube.com/live2');
    expect(screen.getByText(/This looks like the server address\. Copy the stream key from YouTube Studio/)).toBeInTheDocument();
    expect(add).toBeDisabled();

    await userEvent.type(box, '/abcd-efgh-ijkl');
    expect(screen.queryByText(/This looks like the server address/)).not.toBeInTheDocument();
    await userEvent.click(add);
    expect(posts).toEqual([{ name: 'YouTube', key: 'rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl', type: 'RTMP', selected: true }]);
    expect(onAdded).toHaveBeenCalledWith(expect.objectContaining({ id: 3 }));
    vi.unstubAllGlobals();
  });
});

describe('adding a destination (DS-2)', () => {
  it('builds the address from a fixed server, or the one the creator pastes', () => {
    expect(destinationUrl('YOUTUBE', ' abcd-efgh ')).toBe('rtmp://a.rtmp.youtube.com/live2/abcd-efgh');
    expect(destinationUrl('KICK', 'sk_us-west_123', 'rtmps://fa723fc1b171.global-contribute.live-video.net/app/')).toBe(
      'rtmps://fa723fc1b171.global-contribute.live-video.net/app/sk_us-west_123',
    );
    expect(destinationUrl('CUSTOM', 'srt://x.example:9000?streamid=abc')).toBe('srt://x.example:9000?streamid=abc');
    expect(needsServer('TIKTOK')).toBe(true);
    expect(needsServer('TWITCH')).toBe(false);
  });

  it('asks for the stream URL too on platforms where it differs per account', async () => {
    render(<QueryClientProvider client={makeQueryClient()}><AddDestinationDialog open onClose={() => {}} /></QueryClientProvider>);
    expect(screen.queryByLabelText('Stream URL')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /Rumble/ }));
    const add = screen.getByRole('button', { name: 'Add destination' });
    await userEvent.type(screen.getByLabelText('Stream key'), 'key123');
    expect(add).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Stream URL'), 'rtmp://live.rumble.com/live');
    expect(add).toBeEnabled();
  });
});
