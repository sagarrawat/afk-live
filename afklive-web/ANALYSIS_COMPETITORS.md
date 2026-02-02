# Competitive Analysis: Prerecorded Streaming Features

Based on a comparison with leading competitors (Gyre.pro, OneStream.live, Restream.io), AFK Live currently lacks the following key features for a robust "24/7 TV Channel" experience:

## 1. Playlist Streaming (Sequential Playback)
*   **Competitor Feature:** Gyre allows queuing multiple videos (A → B → C → Loop) to play sequentially as a continuous 24/7 stream without re-encoding them into a single massive file.
*   **Current State:** `StreamService.startStream` accepts only a single `videoKey`. `FFmpegCommandBuilder.buildMergeCommand` exists but re-encodes inputs into one file, which is inefficient for long playlists.
*   **Gap:** Need a way to stream a list of files seamlessly (using FFmpeg concat demuxer or playlist file) without merging first.

## 2. Cloud Storage Integration
*   **Competitor Feature:** OneStream allows direct import from Google Drive, Dropbox, OneDrive, etc.
*   **Current State:** Only supports local file upload (Multipart) or importing from YouTube URL.
*   **Gap:** OAuth integrations for major cloud storage providers to pull files directly.

## 3. Unified Live Chat
*   **Competitor Feature:** Aggregating live chat messages from all destinations (YouTube, Twitch) into a single dashboard in real-time.
*   **Current State:** `EngagementController` polls for comments, but it's not a real-time WebSocket chat aggregator for active live streams.
*   **Gap:** Real-time cross-platform chat integration.

## 4. Embeddable Web Player
*   **Competitor Feature:** Providing a hosted player (HLS/DASH) to embed the live stream on third-party websites.
*   **Current State:** AFK Live restreams to RTMP destinations (YouTube/Twitch) but does not generate its own HLS output for web playback.
*   **Gap:** HLS streaming output and hosted player page.

## 5. Fail-safe / Fallback Loop
*   **Competitor Feature:** Automatically switching to a "fallback" video loop if the main source fails or ends unexpectedly.
*   **Current State:** If a stream process fails or exits, the job is simply marked as `FAILED` or `NOT LIVE`.
*   **Gap:** Automatic fallback mechanism to keep the stream alive.

## 6. Captions (SRT/VTT)
*   **Competitor Feature:** Support for uploading sidecar subtitle files for prerecorded videos.
*   **Current State:** Only supports basic text overlays (like subscriber count) via `drawtext`.
*   **Gap:** Subtitle file support in FFmpeg command.
