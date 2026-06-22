import VideoCard from './VideoCard';

export default function VideoGrid({ videos, onRefresh, onGoLive }) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6 pb-20">
      {videos.map(video => (
        <VideoCard key={video.id} video={video} onRefresh={onRefresh} onGoLive={onGoLive} />
      ))}
    </div>
  );
}
