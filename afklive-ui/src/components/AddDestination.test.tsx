import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeQueryClient } from '@/App';
import { AddDestinationDialog, destinationUrl, needsServer } from './AddDestination';

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
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };
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
