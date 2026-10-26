import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { suggestedShape } from '@/lib/shape';
import type { DestinationHealth, LiveState, MediaRef } from '@/lib/types';
import { FirstLiveProof, firstLiveStep, ShapeChoice } from './Setup';

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

describe('setup ends with proof that YouTube receives the stream (ON-8)', () => {
  const youtube = (reach: DestinationHealth['reach'], message?: string): DestinationHealth => ({ name: 'YouTube', status: 'LIVE', level: 'green', restarts: 0, platform: 'YouTube', reach, message });
  const live = (state: LiveState['state'], destinations: DestinationHealth[] = [], endReason?: string): LiveState => ({
    state,
    uptimeSeconds: 20,
    endReason,
    health: { overall: 'green', metrics: [], destinations },
  });

  it('waits after Go live until YouTube has been receiving it', () => {
    expect(firstLiveStep(live('DRAFT')).step).toBe('off');
    expect(firstLiveStep(live('STARTING')).step).toBe('waiting');
    expect(firstLiveStep(live('LIVE', [youtube('CONNECTING')])).step).toBe('waiting');
    expect(firstLiveStep(live('LIVE', [youtube('ON', 'On YouTube')])).step).toBe('on');
    render(<FirstLiveProof live={live('LIVE', [youtube('CONNECTING')])} streamId={7} onFinish={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('Checking that YouTube is receiving your stream');
    expect(screen.queryByText("You're live on YouTube")).not.toBeInTheDocument();
  });

  it('says so in plain words once YouTube receives it, with YouTube Studio and the way on', async () => {
    const went: string[] = [];
    render(<FirstLiveProof live={live('LIVE', [youtube('ON', 'On YouTube')])} streamId={7} onFinish={(to) => went.push(to)} />);
    expect(screen.getByText("You're live on YouTube")).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See it in YouTube Studio' })).toHaveAttribute('href', expect.stringContaining('studio.youtube.com'));
    expect(screen.getByRole('status').textContent).not.toMatch(/RTMP|bitrate|stream key/i);
    await userEvent.click(screen.getByRole('button', { name: 'Open my stream' }));
    expect(went).toEqual(['/studio/streams/7']);
  });

  it("shows the reason and the fix while YouTube isn't receiving it", async () => {
    const went: string[] = [];
    const msg = 'Not reaching YouTube. YouTube refused the stream key.';
    render(<FirstLiveProof live={live('LIVE', [youtube('NOT_REACHING', msg)])} streamId={7} onFinish={(to) => went.push(to)} />);
    expect(screen.getByRole('alert')).toHaveTextContent("YouTube isn't receiving it yet");
    expect(screen.getByText(msg)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Check the stream key' }));
    expect(went).toEqual(['/studio/streams/7/destinations']);
    expect(firstLiveStep(live('ERROR', [], 'The stream key was refused.'))).toEqual({ step: 'stopped', message: 'The stream key was refused.' });
  });

  it('opens the edit of the key saved in setup (DS-8)', async () => {
    const went: string[] = [];
    render(<FirstLiveProof live={live('LIVE', [youtube('NOT_REACHING', 'Not reaching YouTube.')])} streamId={7} destinationId={12} onFinish={(to) => went.push(to)} />);
    await userEvent.click(screen.getByRole('button', { name: 'Check the stream key' }));
    expect(went).toEqual(['/studio/destinations?edit=12']);
  });
});
