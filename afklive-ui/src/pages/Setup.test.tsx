import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { suggestedShape } from '@/lib/shape';
import type { MediaRef } from '@/lib/types';
import { ShapeChoice } from './Setup';

const phone: MediaRef = { id: 1, title: 'Phone clip', width: 1080, height: 1920, contentType: 'VIDEO', status: 'READY', fps: 60 };
const wide: MediaRef = { id: 2, title: 'Wide', width: 1920, height: 1080, contentType: 'VIDEO', status: 'READY' };

describe('setup: the stream shape from the uploaded videos (ST-1)', () => {
  it('preselects Vertical when most videos are vertical, and says what YouTube does with it', async () => {
    const videos = [phone, { ...phone, id: 3 }, wide];
    const picked: string[] = [];
    render(<ShapeChoice videos={videos} value={suggestedShape(videos)!} suggested={suggestedShape(videos)} onChange={(v) => picked.push(v)} fit="BLUR" fps={30} planMaxFps={30} />);
    expect(screen.getByRole('radio', { name: 'Vertical 9:16' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText(/Most of your videos are vertical, so we picked Vertical 9:16/)).toBeInTheDocument();
    expect(screen.getByText(/full screen to people watching on phones/)).toBeInTheDocument();
    // The landscape one in a vertical stream, and the 60 fps clips, in plain words.
    expect(screen.getByText('1 landscape video plays over a blurred copy of the picture that fills the top and bottom.')).toBeInTheDocument();
    expect(screen.getByText(/filmed at 60 frames per second\. They play smoothly at 30 frames per second on your plan\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'Landscape 16:9' }));
    expect(picked).toEqual(['LANDSCAPE']);
  });

  it('keeps landscape for landscape videos and shows nothing before any video is measured', () => {
    const { unmount } = render(<ShapeChoice videos={[wide]} value="LANDSCAPE" suggested={suggestedShape([wide])} onChange={() => {}} fit="BLUR" fps={30} />);
    expect(screen.getByRole('radio', { name: 'Landscape 16:9' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByText(/so we picked Vertical/)).not.toBeInTheDocument();
    unmount();
    render(<ShapeChoice videos={[{ ...phone, width: null, height: null, status: 'PROCESSING' }]} value="LANDSCAPE" suggested={null} onChange={() => {}} fit="BLUR" fps={30} />);
    expect(screen.queryByText('Stream shape')).not.toBeInTheDocument();
  });
});
