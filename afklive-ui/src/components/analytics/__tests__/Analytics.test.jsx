import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import Analytics from '../Analytics';
import { vi } from 'vitest';
import { useAuth } from '../../../hooks/useAuth';

vi.mock('../../../hooks/useAuth', () => ({
  useAuth: vi.fn()
}));

// Mock Recharts to avoid issues in jsdom
vi.mock('recharts', () => {
  return {
    ResponsiveContainer: ({ children }) => <div>{children}</div>,
    LineChart: ({ children }) => <div data-testid="line-chart">{children}</div>,
    AreaChart: ({ children }) => <div data-testid="area-chart">{children}</div>,
    CartesianGrid: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Area: () => <div />,
    Line: () => <div />,
  };
});

describe('Analytics Component', () => {
    beforeEach(() => {
        global.fetch = vi.fn();
        useAuth.mockReturnValue({
            user: { currency: 'USD' }
        });
    });

    afterEach(() => {
        vi.resetAllMocks();
    });

    test('renders loading state initially', () => {
        // Mock a pending fetch
        global.fetch.mockImplementation(() => new Promise(() => {}));
        
        const { container } = render(<Analytics />);
        expect(container.querySelector('.afk-loader-play')).toBeInTheDocument();
    });

    test('renders analytics data successfully', async () => {
        const mockData = {
            data: {
                content: [
                    {
                        id: 1,
                        startTime: new Date().toISOString(),
                        accumulatedCost: 45.50,
                        droppedFrames: 12,
                        bytesStreamed: 1073741824 * 5 // 5 GB
                    }
                ]
            }
        };

        global.fetch.mockResolvedValueOnce({
            ok: true,
            json: async () => mockData
        });

        render(<Analytics />);

        await waitFor(() => {
            // Total Streams = 1
            expect(screen.getByText('1')).toBeInTheDocument();
            // Total Cost = $45.50
            expect(screen.getByText('$45.50')).toBeInTheDocument();
            // Dropped frames = 12
            expect(screen.getByText('12')).toBeInTheDocument();
            // Data transferred = 5.00 GB
            expect(screen.getByText('5.00 GB')).toBeInTheDocument();
        });
    });

    test('handles fetch error gracefully', async () => {
        global.fetch.mockRejectedValueOnce(new Error('Network error'));

        render(<Analytics />);

        // Should just render empty aggregates without throwing
        await waitFor(() => {
            expect(screen.getByText('$0.00')).toBeInTheDocument();
        });
    });
});
