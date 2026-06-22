import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import Scheduling from '../Scheduling';
import { vi } from 'vitest';
import { useLibrary } from '../../../hooks/useLibrary';
import { useAuth } from '../../../hooks/useAuth';

// Mock the custom hooks
vi.mock('../../../hooks/useLibrary', () => ({
  useLibrary: vi.fn()
}));

vi.mock('../../../hooks/useAuth', () => ({
  useAuth: vi.fn()
}));

describe('Scheduling Logic', () => {
    beforeEach(() => {
        global.fetch = vi.fn();
        
        useLibrary.mockReturnValue({
            videos: [
                { id: 101, title: 'my-video.mp4', originalFilename: 'my-video.mp4', streamReadiness: 'STREAM_READY' },
                { id: 102, title: 'unready.mp4', originalFilename: 'unready.mp4', streamReadiness: 'PENDING_ANALYSIS' }
            ]
        });

        useAuth.mockReturnValue({
            user: { email: 'test@example.com' }
        });
    });

    afterEach(() => {
        vi.resetAllMocks();
    });

    test('submits scheduling request with correct payload', async () => {
        // Mock successful initial fetch for scheduled streams
        global.fetch.mockResolvedValueOnce({
            ok: true,
            json: async () => ({ data: [] })
        });

        render(<Scheduling />);

        // Wait for the loader to disappear
        await waitFor(() => {
            expect(screen.queryByText(/Loading/i)).not.toBeInTheDocument();
        });

        const selectElement = screen.getByRole('combobox');
        const dateTimeElement = screen.getByLabelText(/Date & Time/i);
        const submitButton = screen.getByRole('button', { name: /Schedule Stream/i });

        // Verify unready videos are filtered out (102 should NOT be in options, only 101)
        expect(screen.getByText('my-video.mp4')).toBeInTheDocument();
        expect(screen.queryByText('unready.mp4')).not.toBeInTheDocument();

        // Fill form
        fireEvent.change(selectElement, { target: { value: '101' } });
        fireEvent.change(dateTimeElement, { target: { value: '2027-01-01T12:00' } });

        // Mock the submission fetch
        global.fetch.mockResolvedValueOnce({
            ok: true,
            json: async () => ({ message: 'Stream scheduled successfully!' })
        });

        fireEvent.click(submitButton);

        await waitFor(() => {
            expect(global.fetch).toHaveBeenCalledWith('/api/stream/schedule', expect.objectContaining({
                method: 'POST',
                body: JSON.stringify({
                    mediaAsset: { id: 101 },
                    scheduledTime: new Date('2027-01-01T12:00').toISOString(),
                    streamMode: 'original',
                    muteVideoAudio: false,
                    loopCount: 1
                })
            }));
        });
    });
});
