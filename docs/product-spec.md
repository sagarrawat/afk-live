# AFK Live — Product Spec & User Stories

Oct 22, 2026 · @sagar

## Vision and positioning

**AFK Live keeps a creator's channel live 24/7 from the cloud: upload once, build a playlist, and stay on air on every platform without OBS or a PC left running.** The product we're measured against is upstream.so, which already sells cloud 24/7 streaming, playlists, schedules, multistreaming and overlays. Copying its feature list is not enough to win; we need a sharper wedge.

**The promise:** "Your channel never goes offline." Every screen, alert and default should serve that promise: the stream is up, it is healthy, and if something breaks we fix it before the creator notices.

**Where we can win:**

- **Live in 5 minutes.** A guided first run (connect YouTube, upload, press Go Live) with no stream keys, encoders or settings to understand. Most 24/7 tools assume the user already knows RTMP.
- **India-first pricing, global-ready.** INR prices, UPI and Indian cards at checkout, GST invoices, and a lower entry price than USD-only competitors. USD pricing and card checkout for everyone else.
- **AI that runs the channel while you sleep.** Titles, descriptions and tracklists written for each broadcast; chat auto-replies and moderation within rules the creator sets; a weekly report on what worked.
- **Reliability you can see.** A health panel, an uptime record and instant alerts, so creators trust us with a channel that earns them money.

**What we are not building (for now):** a browser studio with live guests, a full video editor, or a general social-media scheduler. Each pulls focus away from 24/7 playout.

**Product shape:** a workspace holds **Streams**. Each stream is one always-on broadcast with its own playlist, music, overlays, schedule and destinations. Creators run one stream; agencies run dozens.

## Personas

Five personas cover the market. Meera, Harjeet and Rohit (the mobile streamer, described in the mobile section) are the launch targets: they need the product most and need the least else.

| Persona | Who they are | Job to be done | What makes them pay | What makes them leave |
| --- | --- | --- | --- | --- |
| **Meera, the music channel owner** | Runs a lofi, ambient or devotional-music channel; 1–3 channels | Keep a music stream on air around the clock with a visual loop, rotating tracks and a now-playing overlay | Uptime, playlists with crossfade, low price per stream | A stream that silently dies overnight; repetitive audio |
| **Harjeet, the temple media volunteer** | Non-technical; manages a gurdwara or temple's YouTube channel | Rebroadcast daily prayers and kirtan at fixed times, every day, without a PC | Recurring schedules, set-and-forget, simple Hindi/Punjabi-friendly UI | Jargon (RTMP, bitrate); anything that needs daily attention |
| **Arjun, the educator and podcaster** | Has a back catalogue of episodes or lectures | Rerun content as scheduled "premieres" on YouTube and Facebook to grow reach | Multistream, scheduled broadcasts with good titles, chat replies | Poor video quality; titles and thumbnails he must write by hand |
| **Nisha, the agency manager** | Runs 10–50 channels for clients | Operate many always-on streams with a small team | Team roles, bulk control, client reports, API | No visibility across streams; per-seat pricing that doesn't scale |

## Design direction

**Rebuild the app around one idea: the first thing on every screen answers "am I live, and is it healthy?"** Today's UI mixes three style systems, emoji logos, fixed-width modals that break on phones, and settings ahead of status. The redesign replaces all of it with one design system.

**Principles**

1. **Status before settings.** Live state, uptime and health sit at the top of every stream; configuration is one tab away.
2. **One primary action per screen.** Go Live, Add to playlist, Connect destination; everything else is secondary.
3. **Plain language.** No "RTMP", "bitrate" or "stream key" in primary flows; technical detail lives in the health panel and help text.
4. **Defaults that just work.** Every setting has a good default; advanced options are collapsed.
5. **Calm, broadcast-grade.** It should feel like a control room, not a social-media toolkit.

*(Wireframe, see the design canvas: header with LIVE badge, uptime, Restart and Stop; tabs Overview · Playlist · Audio · Overlays · Destinations · Schedule · Settings; live output preview on the left with now playing and up next; Health and Destinations cards on the right.)*

The stream overview is the screen creators see most: the live badge and Stop button in the header, the real output on the left, health and destinations on the right, and every setting behind a tab.

**Visual language**

- **One design system:** Tailwind compiled at build time (not the CDN script), design tokens for colour, spacing and type, and a shared component library.
- **Theme:** the app is dark-first like a broadcast control room, with a light option; the marketing site is light, showing real dark product screenshots.
- **Colour:** neutral greys, one brand accent, red reserved only for the LIVE state, and green, amber and red used only for health.
- **Type:** Inter for UI; tabular figures for timecodes and metrics so numbers don't jitter.
- **Icons:** one set (for example Lucide) replacing Font Awesome and emoji.
- **Imagery:** real product screens and short loops on the marketing site; no placeholders.

**Information architecture**

- **Home:** all streams with status, uptime and now playing
- **Streams → one stream:** Overview · Playlist · Audio · Overlays · Destinations · Schedule · Settings
- **Library:** all media, imports and storage
- **Schedule:** calendar across all streams
- **Destinations:** connected accounts and saved custom servers
- **Analytics:** hours, viewers and growth
- **Settings:** Profile · Team · Billing · Notifications · API

**Key screens**

| Screen | Job | Must show |
| --- | --- | --- |
| Setup wizard | First stream live in 5 minutes | 3 steps with progress; skip; success state |
| Home | Is everything up? | Stream cards: status, uptime, now playing, quick stop/start |
| Stream overview | Run one broadcast | Live state, preview, health, destinations, now playing |
| Playlist editor | Shape what plays | Drag reorder, shuffle/loop, now and next, live skip |
| Overlay designer | Brand the stream | Canvas preview, layers list, safe areas |
| Schedule | Plan the week | Week/month calendar, recurring runs, clash warnings |
| Library | Manage media | Grid/list, processing status, storage meter, search |
| Billing | Understand cost | Plan, usage, next charge, invoices |

**Frontend stack.** The current Alpine + 1,300-line `app.js` won't carry drag-and-drop playlists, live status, a calendar and an overlay canvas. Rebuild the app as a single-page app (React, Vite, TypeScript, Tailwind and an accessible component kit such as shadcn/ui) served by Spring, and the marketing site as static pages built for SEO (for example Astro).

## Mobile streamers: a cloud studio for your phone

**For small YouTubers who stream from a phone, AFK Live becomes the production studio they can't run on the phone itself: overlays, scenes, alerts and a stream that survives bad signal, all added in the cloud and sent to YouTube with just a stream key.** No YouTube API and no Google sign-in are needed, which keeps setup simple and avoids API quota and review entirely.

**Why this wedge is real.** YouTube's own app needs at least 50 subscribers before you can go live from a phone ([YouTube Help](https://support.google.com/youtube/answer/9228390?hl=en&co=GENIE.Platform%3DAndroid)), while streaming through an encoder lists no subscriber minimum, only a verified channel with no recent live restrictions ([YouTube Help](https://support.google.com/youtube/answer/2474026?hl=en&co=GENIE.Platform%3DDesktop)). A free encoder app on the phone sending to AFK Live counts as encoder streaming, so a brand-new channel can go live with overlays on day one.

**Persona: Rohit, the mobile gamer.** Streams BGMI or Free Fire, or IRL walks, from an Android phone on 4G/5G; under 1,000 subscribers; earns from tips more than ads. He wants to look like a "real" streamer without a PC, and his biggest fear is the stream dying when the signal drops.

*(Flow: phone app (Larix, PRISM, Streamlabs), remote control and viewer page → AFK Live cloud studio (never-drop switch, scenes and overlays, alerts and goals, encode and fan out) → YouTube by stream key, Instagram/Facebook by key, recording and Shorts clips.)*

The phone only has to send one clean feed; everything that costs battery, CPU or a PC (overlays, scene switching, alerts, multistreaming, recording) happens in the cloud.

**Features, best first**

| Feature | What it does for a small mobile streamer | Priority |
| --- | --- | --- |
| Personal ingest + QR setup | A private RTMP/SRT address and key per stream; scan a QR code into Larix, PRISM or Streamlabs mobile and you're configured | P0 |
| Never-drop protection | When the phone's signal dips or cuts out, viewers see a BRB scene with music instead of the stream ending; switches back the moment the phone reconnects | P0 |
| Cloud overlays | Logo, socials, text ticker, clock, webcam or game frame, rendered in the cloud so the phone's battery and CPU don't pay for them | P0 |
| Scenes | Starting soon, Live, BRB, Ending; each with its own background, music and overlays | P0 |
| Remote control | A phone-friendly control page on a second device (or a floating PWA) to switch scenes and show or hide overlays while streaming | P0 |
| Stream key only | Paste the YouTube stream key once; no Google sign-in, no API | P0 |
| Cloud recording | A full-quality copy of every stream, even if YouTube's VOD fails | P1 |
| UPI tips with on-stream alerts | A tip page (link and QR on screen); each tip shows an alert with the viewer's name and message, optional text-to-speech, and a tip goal bar | P1 |
| Viewer page | Polls, questions and song requests through a link or QR on the stream, shown as overlays; works without reading YouTube chat | P1 |
| Vertical and landscape at once | A vertical phone feed is placed on a 16:9 canvas with a blurred background for YouTube, while a 9:16 copy goes to Shorts-style and Instagram live | P1 |
| Data saver | Phone sends efficient HEVC over SRT at a lower bitrate; the cloud re-encodes to what YouTube expects; the remote page warns when upload is weak | P1 |
| Multistream by key | YouTube plus Instagram and Facebook from one phone upload | P1 |
| Clip to Shorts | Tap "mark" on the remote (or auto-mark big tips); the cloud cuts 30–60 second vertical clips ready to post as Shorts | P1 |
| Offline autopilot | When you're not live, your 24/7 playlist of past streams keeps the channel on air; going live from the phone takes over automatically | P2 |
| Game overlay packs | Ready-made overlay templates for popular mobile games and IRL, in English and Hindi | P2 |
| Browser broadcaster | Go live with the phone camera from a web page, no app install (screen capture still needs an app) | P2 |

**What we give up without the YouTube API, and how we cover it**

| Without the API we can't | Instead |
| --- | --- |
| Create the broadcast, title and thumbnail | The streamer sets them in the YouTube app or Studio; we link straight there |
| Read YouTube chat for an on-stream chat box | Our own viewer page for questions and polls; an optional API connection later for creators who want chat |
| Show the live subscriber count | A goal bar the streamer updates by hand, or a tip goal |
| Pull YouTube analytics | Our own stats: stream health, uptime, tips, viewer-page activity |

**One story for the whole product.** "Your channel never goes offline" now covers both halves: the phone stream survives bad signal, and when the creator is asleep the 24/7 playlist keeps the channel live. The mobile P0 stories (MS-1 to MS-6) are part of the MVP; the rest land in v1.

## User stories

**There are 163 stories across 24 epics: 65 P0, 61 P1 and 37 P2. P0 is the launch bar, P1 makes us competitive with upstream.so, P2 is where we pull ahead.** Each story is written as "As a \[persona\], I want … so that …" with testable acceptance criteria. "Creator" means any of the personas; "admin" means the AFK Live operator.

### 1. Onboarding and account

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| ON-1 | As a new creator, I want to sign up with Google in one click so that I can start without a password | Google sign-in creates an account and lands on the setup wizard; email/password sign-up also works with email verification | P0 |
| ON-2 | As a new creator, I want a guided first run (connect YouTube, upload a video, go live) so that I'm live in under 5 minutes | 3-step wizard with progress; each step skippable; ends on a live stream or a saved draft stream; median time to first live tracked | P0 |
| ON-3 | As a creator, I want my timezone and currency detected so that schedules and prices make sense | Timezone from browser, editable in settings; INR for India, USD elsewhere, switchable | P0 |
| ON-4 | As a returning creator, I want to land on my streams overview so that I can see at a glance that everything is up | Home shows every stream's status, uptime and now playing | P0 |
| ON-5 | As a creator, I want a sample video and music track available on day one so that I can test without uploading | Demo media in every new library, clearly labelled, deletable | P1 |
| ON-6 | As a creator, I want to secure my account with two-factor authentication so that my channels can't be hijacked | TOTP 2FA; recovery codes; enforced for agency owners if they choose | P1 |
| ON-7 | As a creator, I want to delete my account and export my data so that I stay in control | Self-serve export (zip of metadata + media links) and deletion with 7-day grace | P1 |
| ON-8 | As a new creator, I want setup to end with proof that YouTube is receiving my stream so that I know I am done | Setup tests the destination automatically; after Go live it waits for RE-8 "On YouTube" and shows success with a link to YouTube Studio, or the fix; "first live confirmed" is recorded and is the activation metric | P0 |

### 2. Media library

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| ML-1 | As a creator, I want to upload large videos reliably so that a 10 GB file doesn't fail at 90% | Resumable chunked upload; survives a page refresh or network drop; progress and time remaining shown | P0 |
| ML-2 | As a creator, I want every upload checked and prepared automatically so that it streams without problems | On upload: probe, reject unsupported files with a plain-language reason, normalize to H.264/AAC at a fixed frame rate in the background; status Ready / Processing / Failed | P0 |
| ML-3 | As a creator, I want to see thumbnail, duration, resolution and size for each file so that I can pick the right one | Grid and list views with those fields; preview player on click | P0 |
| ML-4 | As a creator, I want to search, sort and filter my library so that I find files in a big catalogue | Search by name; sort by date, name, duration; filter by type (video, audio, image) and status; paging or infinite scroll | P0 |
| ML-5 | As a creator, I want to see my storage usage against my plan so that I'm never surprised | Meter in the library header; warning at 80%; upload blocked at 100% with an upgrade link | P0 |
| ML-6 | As a creator, I want to delete one or many files so that I free up space | Bulk select and delete with confirm; files in use by a live stream can't be deleted (clear message) | P0 |
| ML-7 | As a creator, I want to import from YouTube, Google Drive, Dropbox and OneDrive so that I don't download and re-upload | Paste a link or connect the account; import runs in the background with status; only content the user has rights to (ToS checkbox) | P1 |
| ML-8 | As a creator, I want folders or tags so that I organise media by show or mood | Create, rename, delete folders/tags; filter by them | P1 |
| ML-9 | As a creator, I want to trim a video's start and end so that I cut intros without an editor | In/out points stored per file or per playlist item; no re-upload | P2 |

### 3. Streams

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| ST-1 | As a creator, I want to create a stream with a name, format (landscape or vertical) and quality so that each broadcast has its own setup | Stream created in draft; quality options capped by plan (720p/1080p/4K, 30/60 fps) with the cap explained | P0 |
| ST-2 | As a creator, I want a stream overview page so that I control everything about one broadcast from one place | Tabs: Overview, Playlist, Audio, Overlays, Destinations, Schedule, Settings | P0 |
| ST-3 | As an agency manager, I want to run many streams at once so that I serve many clients | Concurrent stream count limited only by plan; overview lists all with status | P0 |
| ST-4 | As a creator, I want to duplicate a stream so that I set up a similar channel quickly | Copies playlist, audio, overlays, settings; not destinations' keys | P1 |
| ST-5 | As a creator, I want to archive a stream so that old setups don't clutter my home | Archive and restore; archived streams don't count against plan limits | P2 |
| ST-6 | As a creator, I want the stream's shape suggested from my videos so that a vertical phone clip fills the screen | Mostly 9:16 uploads preselect Vertical in setup and on an empty playlist; a landscape stream with a vertical video shows a one-tap switch (off air); mixed shapes show the fit used in plain words | P1 |

### 4. Playlist and playback

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| PL-1 | As Meera, I want to add many videos to a stream's playlist so that the broadcast isn't one clip on repeat | Add from library (multi-select); playlist shows order, durations and total runtime | P0 |
| PL-2 | As a creator, I want to drag to reorder the playlist so that I control the running order | Drag-and-drop reorder; keyboard alternative; saved instantly | P0 |
| PL-3 | As a creator, I want to choose in-order or shuffle playback and loop forever or N times so that the stream fits my format | Mode switch; shuffle avoids repeating an item until all have played | P0 |
| PL-4 | As a creator, I want to see what's playing now and what's next so that I know what viewers see | Now playing with elapsed/remaining; next 3 items listed | P0 |
| PL-5 | As a creator, I want to add, remove, reorder or skip items while live so that I fix the stream without stopping it | Changes take effect at the next item boundary (skip: immediately) with no disconnect on any destination | P1 |
| PL-6 | As a creator, I want to choose how videos fit the frame (fit with bars, fill and crop) so that mixed sizes look right | Per stream default and per item override; preview shows the result | P1 |
| PL-7 | As a creator, I want a fallback slate if a file fails so that the stream never goes black | Failed item is skipped automatically; a chosen image or clip plays if the playlist is empty; creator is alerted | P1 |
| PL-8 | As Arjun, I want stings, jingles or sponsor clips inserted between items so that the stream feels produced | Insert every N items or every N minutes; separate sting list | P2 |

### 5. Audio

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| AU-1 | As Meera, I want a music playlist separate from the video playlist so that a short visual loop plays under hours of music | Independent audio playlist with its own order, shuffle and loop; video loops underneath | P0 |
| AU-2 | As a creator, I want to mute, keep or mix the videos' original audio with music so that I control what viewers hear | Three modes; music volume slider; original-audio volume slider | P0 |
| AU-3 | As Meera, I want crossfade between tracks so that the music never cuts abruptly | Crossfade 0–20 seconds, set per stream | P1 |
| AU-4 | As a creator, I want a royalty-free music library so that I don't risk copyright strikes | Curated tracks with clear licence terms per track; searchable by mood | P2 |
| AU-5 | As a creator, I want loudness normalized across tracks so that viewers don't reach for the volume | Every track normalized to a common loudness target on upload | P1 |

### 6. Go live and controls

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| GL-1 | As a creator, I want a pre-flight check before going live so that I don't start a broken stream | Checks: playlist has ready media, at least one destination enabled and reachable, plan allows the quality; each failure has a fix-it link | P0 |
| GL-2 | As a creator, I want one Go Live button with clear states so that I always know what's happening | States: Draft → Starting → Live → Stopping → Ended (or Error); button and badge change with state; duration counter while live | P0 |
| GL-3 | As a creator, I want to stop a stream now, at a set time, or after the playlist finishes so that it ends when I choose | Three stop options; scheduled stop shown on the stream and cancellable | P0 |
| GL-4 | As a creator, I want a live preview of exactly what viewers see so that I can check overlays and audio | Low-latency preview of the encoded output, with audio toggle | P1 |
| GL-5 | As a creator, I want to restart a stream with one click so that I recover from a platform glitch myself | Restart keeps settings and playlist position; destinations reconnect | P1 |
| GL-6 | As a new creator, I want to know what YouTube will show when I go live so that I don't think the stream is broken | On the account's first go-live, and in the first 2 minutes of any run, the stream's Overview explains: Preparing is normal for up to ~30 s; for 24/7 turn YouTube's Auto-start on and Auto-stop off; if stuck on Preparing, end that live in YouTube Studio and start a fresh one with the same key; links to YouTube Studio and the help article. Dismissible, remembered for the account (not the browser) | P1 |

### 7. Reliability and health

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| RE-1 | As a creator, I want streams to restart automatically after a crash so that my channel stays live overnight | Supervisor restarts the encoder and each destination with backoff; restart logged on the stream timeline | P0 |
| RE-2 | As a creator, I want streams to keep running when AFK Live deploys an update so that releases don't take me offline | Streams run on workers separate from the web app; deploys don't touch running streams | P0 |
| RE-3 | As a creator, I want a health panel (bitrate, fps, dropped frames, speed, per-destination status) so that I can see the stream is healthy | Updated every few seconds; green / amber / red per metric with a plain-language explanation | P0 |
| RE-4 | As a creator, I want an uptime record for each stream so that I can trust (and show clients) that it stays up | Uptime % for 24 h, 7 days, 30 days; timeline of starts, stops, restarts and errors | P1 |
| RE-5 | As a creator, I want long YouTube broadcasts rolled over before 12 hours so that every part is saved as a video on my channel | Optional; new broadcast created and switched with a gap of a few seconds at most; titles numbered | P1 |
| RE-6 | As Nisha, I want a backup stream feeding YouTube's backup ingest so that a server failure doesn't drop a client's stream | Runs on a second worker; counts as a stream against the plan | P2 |
| RE-7 | As a creator, I want a public status page so that I can check whether a problem is mine or AFK Live's | Status page with incident history; linked from the app footer and alerts | P1 |
| RE-8 | As a creator, I want "Live" to mean the platform is actually receiving my stream so that I never find out from a viewer | A destination is "On <platform>" only after it has been sending steadily for 10 s; otherwise "Not reaching <platform>" with the reason in plain words; stream badge Live / Live with problems / Not reaching anyone; all destinations down for 60 s opens an incident and alerts | P0 |
| RE-9 | As a creator, I want each destination reconnect and failure on my stream's timeline so that I can see what happened and that it recovered | Reconnects, give-ups and recoveries are timeline events with the reason; a destination down for 5 minutes alerts on its own (deduplicated) | P1 |

### 8. Scheduling

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| SC-1 | As a creator, I want to schedule a one-time start and end so that a stream runs while I'm away | Date, time, timezone; stream starts and stops within 1 minute of the set time | P0 |
| SC-2 | As Harjeet, I want daily and weekly recurring schedules so that prayers air at the same time every day without me | Recurrence by day of week with start and end times; exceptions (skip a date) | P0 |
| SC-3 | As a creator, I want a calendar of all scheduled runs so that I see the week at a glance and spot clashes | Week and month views across streams; drag to move a run; clash warning when a run would exceed the plan's concurrent limit | P1 |
| SC-4 | As a creator, I want a different playlist for each scheduled slot so that mornings and evenings have different content | Slot can override the stream's playlist | P1 |
| SC-5 | As a creator, I want to be told when a scheduled run fails to start so that I can react | Alert on failure with the reason and a one-click retry | P0 |

### 9. Destinations and multistream

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| DS-1 | As a creator, I want to connect YouTube with sign-in instead of copying stream keys so that setup is safe and simple | OAuth connect; one or many channels; stream key created and managed for me; keys never rotated out from under a saved destination | P0 |
| DS-2 | As a creator, I want to add Twitch, Kick, Facebook, Instagram, TikTok and Rumble from presets so that I only paste a key | Preset fills the server URL; key field with show/hide; help link for where to find the key | P1 |
| DS-3 | As a creator, I want a custom RTMP, RTMPS or SRT destination so that I can stream anywhere | Server URL + key fields; URL validated; private and local addresses blocked | P0 |
| DS-4 | As a creator, I want to send one stream to several destinations so that I reach every platform at once | One encode fanned out; limit per plan; adding a destination doesn't increase CPU per stream materially | P1 |
| DS-5 | As a creator, I want per-destination status and on/off while live so that one failing platform doesn't affect the others | Each destination shows Connected / Reconnecting / Failed; toggling one doesn't interrupt the rest | P1 |
| DS-6 | As a creator, I want platform time limits handled for me so that Instagram or Facebook don't cut me off mid-show | Stops or reconnects before known platform limits, with a note on the timeline | P2 |
| DS-7 | As a creator, I want to test a destination before going live so that a wrong key is caught early | "Test" sends a short test stream and reports success or the error | P1 |
| DS-8 | As a creator, I want the app to stop me saving a server address as my stream key so that I don't stream into nothing | Key field refuses addresses and explains where the key is; server+key pasted together keeps the key; the server refuses nested or keyless addresses; every new or changed destination is tested automatically on save | P0 |

### 10. YouTube integration

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| YT-1 | As a creator, I want AFK Live to create the YouTube broadcast with title, description, thumbnail, privacy and category so that I never open YouTube Studio | All fields editable in the stream; applied on start; "made for kids" flag required | P0 |
| YT-2 | As a creator, I want title templates with the date or episode number so that each broadcast has a fresh title | Variables such as {date}, {weekday}, {n}; preview of the result | P1 |
| YT-3 | As a creator, I want the description to list the tracks that played so that viewers find the music | Tracklist with timestamps appended to the VOD description after the broadcast | P2 |
| YT-4 | As a creator, I want my YouTube API quota used sparingly so that my channel isn't blocked | No background jobs for removed features; quota usage visible in admin | P0 |

### 11. Overlays and branding

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| OV-1 | As a creator, I want to add my logo as a watermark with position, size and opacity so that the stream is branded | Drag to place on a preview canvas; corners snap; safe-area guides | P0 |
| OV-2 | As Meera, I want a now-playing overlay so that viewers see the track name | Shows current track title/artist from file metadata or an editable name; style options | P1 |
| OV-3 | As a creator, I want text, clock and countdown layers so that I add context without editing video | Font, colour, size, background box; clock in a chosen timezone | P1 |
| OV-4 | As a creator, I want a subscriber count or goal bar so that viewers are nudged to subscribe | Live count from YouTube, refreshed every few minutes; goal value editable | P1 |
| OV-5 | As a creator, I want ready-made overlay templates so that it looks professional without design skill | 10+ templates by use case (lofi, prayer, podcast); apply and edit | P2 |
| OV-6 | As Meera, I want an audio visualizer layer so that a static image feels alive | Bars or waveform reacting to the audio; colour and position options | P2 |
| OV-7 | As a creator, I want to show and hide layers while live so that I can run promotions | Toggle per layer; change appears within a few seconds without a restart | P2 |

### 12. AI assistant

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| AI-1 | As Arjun, I want titles, descriptions and tags written from my video's name and a short brief so that every broadcast is search-friendly | Generate 3 options; edit before use; language selectable (English, Hindi first) | P1 |
| AI-2 | As a creator, I want thumbnail ideas generated from a frame of my video so that I don't need a designer | Pick a frame; add generated text; export at YouTube's size | P2 |
| AI-3 | As a creator, I want opt-in chat auto-replies within rules I set so that viewers get answers while I sleep | Off by default; tone, topics and frequency limits; every reply logged and reviewable | P1 |
| AI-4 | As a creator, I want spam and abuse in chat hidden by rules I approve so that the chat stays clean | Off by default; hides (never deletes) unless the creator enables deletion; review queue | P2 |
| AI-5 | As a creator, I want a weekly AI summary of my streams so that I know what to improve | Email and in-app: hours live, peak viewers, top items, suggestions | P2 |

### 13. Chat and engagement

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| CH-1 | As a creator, I want to read and reply to live chat from AFK Live so that I engage without opening each platform | YouTube chat in the stream page; reply box; Super Chats highlighted | P1 |
| CH-2 | As a creator, I want chat from all platforms in one feed so that I see every viewer | YouTube, Twitch, Kick merged with platform icons | P2 |
| CH-3 | As a creator, I want to pin a message or post a timed message so that viewers see links and calls to action | Pinned message; repeating message every N minutes | P2 |

### 14. Analytics

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| AN-1 | As a creator, I want hours streamed, concurrent viewers and peak viewers per stream so that I see whether it's working | Charts for 7 and 30 days; per destination where the platform allows | P1 |
| AN-2 | As a creator, I want subscribers gained and watch time from my 24/7 streams so that I can justify paying | Pulled from YouTube Analytics; shown per stream | P2 |
| AN-3 | As Nisha, I want a client report I can export so that I show value to my clients | PDF/CSV per stream and date range; agency logo | P2 |

### 15. Notifications

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| NT-1 | As a creator, I want an alert when a stream goes down and when it recovers so that I'm never the last to know | Email and push (PWA) within 2 minutes of the failure; recovery message after auto-restart | P0 |
| NT-2 | As a creator, I want alerts for storage almost full, payment failed and plan limits reached so that nothing breaks unexpectedly | One alert per event, not repeated more than daily | P0 |
| NT-3 | As a creator, I want to choose which alerts I get and where so that I'm not spammed | Per-event settings for email, push and (later) WhatsApp or Telegram | P1 |

### 16. Plans and billing

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| BI-1 | As a new creator, I want a free tier with limited monthly hours so that I can try the product properly | Free hours per month, 720p, 1 stream, a small watermark; usage meter visible | P0 |
| BI-2 | As a creator, I want prices per concurrent stream so that cost grows with my use | Plans priced by number of simultaneous streams; quality and storage by tier; monthly and yearly (yearly discounted) | P0 |
| BI-3 | As an Indian creator, I want to pay in INR with UPI or cards and get a GST invoice so that it fits how I pay | INR checkout; UPI autopay for subscriptions; GST invoice emailed and downloadable | P0 |
| BI-4 | As an international creator, I want to pay in USD by card so that I can buy from anywhere | USD checkout via an international processor; tax-compliant receipts | P1 |
| BI-5 | As a creator, I want upgrades to apply instantly and downgrades or cancellations at period end so that billing feels fair | Proration on upgrade; downgrade and cancel take effect at renewal; access kept until then | P0 |
| BI-6 | As a creator, I want to see my plan, usage, next charge and invoices in one place so that billing has no surprises | Billing page with all four; download every invoice | P0 |
| BI-7 | As a creator, I want a grace period and reminders when a payment fails so that my streams aren't cut off by a card problem | Retries over several days; reminders; streams stop only after grace ends | P1 |
| BI-8 | As a creator, I want plan limits enforced clearly so that I understand what I'm paying for | Every blocked action names the limit and the plan that removes it; no limit can be bypassed (e.g. quality via copy mode) | P0 |

### 17. Teams and agencies

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| TM-1 | As Nisha, I want to invite teammates with roles (viewer, moderator, manager, owner) so that people only do what they should | Invite by email; role changes and removal; owner can't be removed | P1 |
| TM-2 | As Nisha, I want separate workspaces per client so that client media and channels never mix | Switch workspace from the top bar; billing per workspace or consolidated | P2 |
| TM-3 | As an owner, I want an activity log so that I know who started, stopped or changed what | Log of stream and settings actions with user and time; 90 days kept | P2 |

### 18. API and integrations

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| AP-1 | As a developer, I want a REST API with API keys so that I can start, stop and edit streams from my own tools | Keys created and revoked in settings; documented endpoints for streams, playlists, media, destinations | P2 |
| AP-2 | As a developer, I want webhooks for stream events so that my systems react to starts, stops and failures | Signed webhooks with retries; event log | P2 |
| AP-3 | As a creator, I want to control my streams from an AI assistant so that I can say "skip this track" or "go live at 8" | MCP server exposing the API's actions with the user's permission | P2 |

### 19. Admin and operations (internal)

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| AD-1 | As an admin, I want to see every live stream, its worker and its health so that I can act before users complain | Live list with filters; stop or move a stream to another worker | P0 |
| AD-2 | As an admin, I want worker capacity and cost per stream-hour so that I price plans and scale servers correctly | CPU, memory and stream count per worker; cost per stream-hour report | P1 |
| AD-3 | As an admin, I want to look up a user, their plan, payments and streams so that I resolve support tickets fast | User page with plan change, refund and credit actions, all audited | P0 |
| AD-4 | As an admin, I want feature flags so that I release features to a few users first | Flags per user, plan or percentage | P1 |
| AD-5 | As an admin, I want no default accounts, secrets or open dashboards in production so that the platform is safe | Seed data only in dev; admin dashboards behind admin login; secrets only from the environment | P0 |
| AD-6 | As an admin, I want a support view of a stream so that I fix tickets without a server console | Source media, output profile, each destination with host, masked key and a key-format check, last platform error, restarts, RE-8 status; last 200 lines of FFmpeg output per process with keys redacted; "Send a test" on the user's destination, audited with a reason | P0 |
| OPS-1 | As a user, I want email (verification, password reset, alerts) to work, or the app to say plainly that it doesn't, so that I'm never locked out | Startup warning and admin banner when email is off; password sign-ups verified automatically while it's off; "Forgot password" never claims a link was sent when none was; every send recorded and visible per user; admin "send test email" | P0 |
| OPS-2 | As a user, I want an expired form to say "this page expired, try again" so that a deploy doesn't look like an outage | Expired session or form re-renders with that message; the app retries once on an expired token; "something went wrong on our side" only for real server errors, with a request id admins can search | P0 |
| OPS-3 | As a creator, I want my stream to keep going when AFK Live updates its stream servers so that releases never take me offline | Worker deploys hand each stream to the new version one at a time; YouTube destinations switch via the backup ingest with no gap; other platforms reconnect within about 2 s; a failed handover leaves the old run live; no extra servers | P0 |
| OPS-4 | As a user, I want the website and app to stay up during updates so that I never see errors because of a release | Blue/green on the same server behind Caddy with a readiness check and graceful shutdown; migrations are expand-then-contract; zero 502s during a deploy | P0 |
| OPS-5 | As an admin, I want a staging copy of the app so that I try a release before creators get it | Staging runs on the production droplet on its own port, database and worker slot with a stream limit of 1; deploys there first; costs no new server | P1 |
| OPS-6 | As an admin, I want a nightly soak test so that leaks and drift show up before release | A 6-hour run each night (72 hours before major releases) checking memory, timestamps, A/V drift under 40 ms and reconnects; results posted to the PR checks | P1 |

### 20. Marketing site and help

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| MK-1 | As a visitor, I want the home page to show what AFK Live does in 5 seconds so that I know if it's for me | Headline, a real product video/screenshot, 3 steps, social proof, pricing teaser; loads fast on mobile | P0 |
| MK-2 | As a visitor, I want honest, clear pricing with a currency toggle so that I can decide without contacting anyone | Plan cards match the real limits exactly; INR/USD toggle works; FAQ matches billing behaviour | P0 |
| MK-3 | As a visitor searching for a use case, I want a page for my use case so that I see it works for me | Pages for 24/7 music, prayer broadcasts, podcast reruns, agencies | P1 |
| MK-4 | As a creator, I want a help centre with guides so that I solve problems myself | Getting started, destinations, scheduling, billing, troubleshooting; searchable | P1 |
| MK-5 | As a visitor, I want to compare AFK Live with alternatives so that I can choose with confidence | Fair comparison pages based on public information | P2 |
| MK-6 | As a creator, I want a changelog so that I see the product improving | Public changelog; in-app "what's new" | P2 |

### 21. Mobile and accessibility

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| MB-1 | As a creator on my phone, I want to check status and start or stop streams so that I manage my channel from anywhere | Every core screen usable at 375 px wide; no horizontal scroll; modals fit the screen | P0 |
| MB-2 | As a creator, I want to install AFK Live as an app with push alerts so that alerts reach me instantly | PWA install; push for the alerts in NT-1 | P1 |
| MB-3 | As a keyboard or screen-reader user, I want every control labelled and reachable so that I can use the product | WCAG 2.1 AA on core flows: labels, focus states, contrast, keyboard drag alternatives | P1 |
| MB-4 | As Harjeet, I want the app in Hindi so that I'm comfortable using it | Hindi UI for core flows; language switch in settings | P2 |

### 22. Mobile streaming studio

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| MS-1 | As Rohit, I want a private ingest address and a QR code to set up my phone app so that I'm ready in a minute without typing URLs | RTMP and SRT address + key per stream; QR scans into Larix and PRISM; step-by-step guides for each app; key regenerable | P0 |
| MS-2 | As Rohit, I want my stream to survive a signal drop so that a tunnel or weak 4G doesn't end my broadcast | Input loss or very low bitrate switches to the BRB scene within a few seconds; output to YouTube never stops; switches back on reconnect; grace time configurable (default 2 minutes, then optional end) | P0 |
| MS-3 | As Rohit, I want overlays added in the cloud so that my phone's battery and CPU aren't spent on them | Logo, socials, text ticker, clock and frame layers placed on a live preview; vertical and landscape layouts | P0 |
| MS-4 | As Rohit, I want Starting soon, Live, BRB and Ending scenes so that my stream looks produced | Each scene has its own background, music and overlays; Starting soon can count down | P0 |
| MS-5 | As Rohit, I want to control scenes and overlays from a second device or a floating control so that I don't stop streaming to change them | Phone-friendly control page with big buttons (≥44 px); changes on air within 2 seconds; works on a slow connection | P0 |
| MS-6 | As Rohit, I want to connect YouTube by pasting my stream key so that I don't need to sign in with Google | Key stored encrypted; "test" button confirms YouTube receives the stream; link to where to find the key | P0 |
| MS-7 | As Rohit, I want every stream recorded in the cloud so that I have a full copy even if YouTube's VOD fails | Recording on by default within plan storage; download link after the stream | P1 |
| MS-8 | As Rohit, I want viewers to tip me by UPI with an on-stream alert so that I earn from small audiences | Tip page with link and QR overlay; alert with name, amount and message; optional text-to-speech with a word filter; tip goal bar; payouts and fees shown clearly | P1 |
| MS-9 | As Rohit, I want polls and questions from a viewer page so that viewers interact without me reading chat | Viewer page by link or QR; results and picked questions shown as overlays; streamer moderates from the remote | P1 |
| MS-10 | As Rohit, I want my vertical phone stream shown well on YouTube and also sent vertical elsewhere so that one stream fits every platform | Landscape output with blurred background and side panels; a vertical copy to platforms that want 9:16 | P1 |
| MS-11 | As Rohit, I want to stream on less mobile data and be warned when my upload is weak so that I can move or lower quality before it breaks | HEVC over SRT accepted; recommended settings per network; warning on the remote when incoming bitrate drops | P1 |
| MS-12 | As Rohit, I want to mark moments and get vertical clips so that each stream becomes Shorts | Mark button on the remote; big tips auto-marked; 30–60 second 9:16 clips ready after the stream | P1 |
| MS-13 | As Rohit, I want my past streams to play 24/7 when I'm offline so that my channel is always live | Offline playlist starts when the phone stops; the phone takes over when it reconnects | P2 |
| MS-14 | As Rohit, I want overlay packs for my game so that I look professional instantly | Templates for popular mobile games and IRL, in English and Hindi | P2 |
| MS-15 | As Rohit, I want to go live with my camera from a web page so that I don't need to install an app | Browser camera streaming on Android Chrome and iOS Safari; screen capture still needs an app (said plainly) | P2 |

Stories that fix today's code (the free upgrade endpoint, seeded admin, broken scheduling, the mock "stop at") are covered by BI-8, AD-5, SC-1 and GL-3; they block launch regardless of priority labels elsewhere.

### 23. Cost and efficiency (FinOps)

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| FO-1 | As the operator, I want each library video prepared once at the size and frame rate a stream uses so that live streams don't shrink a 1080p60 file every second | A copy is requested when a stream plays a bigger or faster video and made in the background at the lowest priority; playout switches at the next item without a restart; copies are deleted with their file and don't count toward the creator's storage; decoding drops frames before scaling | P0 |
| FO-2 | As the operator, I want playlists without live overlays to stream by copy, not re-encode, so that a 24/7 stream costs almost no CPU | Prepared files at the stream profile are concatenated with stream copy when no live overlay, scene or phone input is in use; switching to a feature that needs encoding moves the stream to the studio at the next item without a disconnect | P1 |
| FO-3 | As an admin, I want cost per stream-hour including bandwidth so that prices follow real cost | Admin shows server cost and traffic cost per stream-hour per plan and resolution, from measured CPU and bytes sent; monthly projection | P1 |
| FO-4 | As the operator, I want each profile to send no more bits than YouTube needs so that traffic costs stay low without hurting quality | Bitrate per profile set from YouTube's recommended range and a measured quality check; a change shows its traffic saving in FO-3 | P1 |
| FO-5 | As an admin, I want a traffic budget with alerts so that a bandwidth overage never surprises us | Outbound traffic per worker and per account tracked monthly against the included transfer; alert at 70% and 90% | P1 |
| FO-6 | As the operator, I want playout copies nobody plays deleted so that storage doesn't grow forever | Copies not played for 30 days are deleted and made again when needed; storage per account and for copies shown in admin | P2 |
| FO-7 | As the operator, I want workers added and removed with load so that we pay for capacity only when streams need it | A scheduler adds a worker above 75% of fleet capacity and drains one (OPS-3) below 40%; never below one; every change logged with its cost | P2 |
| FO-8 | As the operator, I want 24/7 playout on servers whose traffic is included so that bandwidth stops being the main cost | A second worker pool in a region with included traffic; 24/7 playlist streams placed there, phone streams stay near India; placement shown in admin | P2 |

### 24. Growth: helping creators gain views

Why and in what order: `docs/growth-plan.md` (research in `docs/research/youtube-live-growth.md`).

| ID | Story | Acceptance criteria | Priority |
| --- | --- | --- | --- |
| GR-1 | As a creator, I want to see how long my broadcast has run unbroken so that I trust it keeps its viewers and URL | "One unbroken broadcast for N days" per stream; gaps longer than YouTube's auto-stop window shown with their cause; alert before that window closes | P0 |
| GR-2 | As a creator with daily or weekly scheduled lives, I want AFK Live to create each broadcast ahead of time so that reminders collect and viewers know when I'm on | Needs a connected channel; broadcast created ahead with title and thumbnail (YT-1); next one created automatically; a second time zone shown for diaspora audiences; 24/7 streams default to one unbroken broadcast, with rollover before 12 h as an advanced option | P1 |
| GR-3 | As a creator, I want to connect my YouTube channel only when I want growth tools so that stream keys stay simple and I control what AFK Live can do | Asked only when a feature needs it; scope in plain words; tokens encrypted and revocable; disconnecting deletes cached YouTube data; every core promise still works with a stream key | P0 |
| GR-4 | As a creator, I want a vertical (9:16) output alongside my landscape stream so that my live can reach people scrolling Shorts | Parked until YouTube opens dual format to encoders and experiment EX-1 passes; meanwhile phones and vertical content stream vertical only (ST-6) | P2 |
| GR-5 | As a phone creator, I want Shorts made from moments I marked during my live so that new viewers find my next one | "Mark a moment" while live; after the live, a 9:16 Short per mark with captions and a link to the channel's lives; uploaded only after approval; never automated from 24/7 loops (reused-content risk) | P1 |
| GR-6 | As a creator, I want viewers sent to my next live when one ends so that I don't lose them between broadcasts | Live Redirect at 1,000+ subscribers; otherwise an end slate with the next start time and QR; the next broadcast created automatically for daily lives | P2 |
| GR-7 | As a creator, I want chat on screen, commands and polls when people are actually chatting so that my live feels alive without looking empty | Chat layer shows only while chat is active (e.g. 3 messages in 10 min) and hides otherwise; commands and polls for phone lives with a host; reads chat via the streaming method; no repeated identical messages | P2 |
| GR-8 | As a creator, I want templates made for my niche so that my stream looks right in minutes | Lofi, devotional, study, sleep/ambience and phone-gaming layouts with now playing, clock and next item; Indic-script fonts; schedule presets ("morning and evening aarti", "all-day bhajans"); subscriber goal when connected. Built: "Start from a template" on the Overlays tab (English or Hindi labels; replaces earlier template layers, keeps the creator's own; applies live between frames); Noto Sans for Bengali, Devanagari, Gujarati, Gurmukhi, Kannada, Malayalam, Odia, Tamil and Telugu bundled (OFL); presets become daily schedules. Not yet: chat-on-screen (GR-7, parked) and the subscriber goal (needs GR-3) | P1 |
| GR-9 | As a creator, I want a check before going live and each week so that my stream doesn't risk demonetisation or go unfound | Flags only what is certain: one file on loop, a still image with no overlay, a title with no searchable phrase or a misleading "LIVE", a thumbnail unreadable at phone size, a repeated identical message; one-tap fixes and help links; general music-rights warning, never a Content ID verdict | P0 |
| GR-10 | As a creator, I want titles and descriptions that people search for, in my languages, so that my live gets found | Search-style titles and descriptions in English, Hindi and a regional language; second-language title for diaspora audiences; timestamps and tracklist (AI-1, YT-3) | P2 |
| GR-12 | As a creator, I want one suggested next step each week so that I know what to improve | Built from what AFK Live knows (continuity, schedule consistency, check results) with a link into the matching YouTube Studio report; no blended score without Google's derived-metrics approval | P2 |
| GR-15 | As a small creator streaming from my phone, I want low data use, vertical by default and tools for a live host so that my daily lives grow | Low-bitrate presets with data per hour in plain words; vertical by default; chat, polls and commands for the host; "mark a moment" for Shorts (GR-5); audience-cap message only if experiment EX-2 confirms it | P1 |
| GR-16 | As a creator, I want to know how to earn from my lives so that I turn on the right features | Checklist for Super Chat, memberships and Gifts with eligibility and what each changes | P2 |
| GR-17 | As an Indian creator, I want growth features priced in rupees so that they're affordable | Priced after experiment EX-5; cheap features in every plan, only costly ones (extra encodes) as paid add-ons; UPI autopay; GST invoices | P1 |
| GR-18 | As AFK Live, I want creators' success to bring new creators so that growth doesn't depend on ads | Opt-in "Streamed 24/7 with AFK Live" description line (off by default, never on screen); a public page per niche template with a live example (with permission); referral credit in INR paid after the referred creator's first confirmed live. Built: invite link and referrals recorded at sign-up (email and Google), Settings → Invite creators, the opt-in line (account setting, appended only to descriptions AFK Live writes), a template section on /for/lofi, /for/devotional and /for/mobile-gaming. Follow-ups: the payout trigger waits for RE-8's confirmed live; live examples on the public pages need a per-stream permission flag first | P1 |

## Release plan

**Ship in four phases, and only move on when the gate before the next phase is met.** Phase 0 is not optional: today's code lets anyone upgrade for free and can lose every running stream on a deploy, so nothing new launches on top of it.

1. **Phase 0, Fix the foundation** (before any new feature): close the security holes; working dev build, PR checks; streams move to workers with a supervisor and auto-restart. *Gate: no critical security issues; running streams survive a deploy.*
2. **MVP, launch** (53 P0 stories): phone ingest with never-drop, scenes and overlays; 24/7 playlists; Go Live states, recurring schedules, YouTube and custom RTMP; down alerts, INR billing with UPI and GST invoices, redesigned site. *Gate: 99.5% uptime for 2 weeks with 20 beta creators.*
3. **v1, competitive** (47 P1 stories): multistream presets on one encode, live playlist edits, crossfade; overlays, AI titles, health and uptime views; schedule calendar, USD checkout, teams, installable app with push. *Gate: 5% of free accounts upgrade within 30 days.*
3a. **Trust** (after the first real users, October 2026): "Live" means live on the platform (RE-8, DS-8, GL-1, GL-6, ON-8), deploys never interrupt anyone (OPS-3, OPS-4), email and support work (OPS-1, OPS-2, AD-6), and a stream costs half the CPU (FO-1). Order, sizes and evidence: `docs/roadmap.md`. *Gate: zero interrupted streams across three worker deploys; activation measured as "first live confirmed".*
3b. **Growth** (after Trust): run experiments EX-1 to EX-6 first, then Growth v1: continuity score (GR-1), go-live check (GR-9), niche templates (GR-8), scheduled lives (GR-2) and phone lives that grow (GR-15), plus our own acquisition loop (GR-18); the rest is parked with triggers. Order and evidence: `docs/growth-plan.md`. *Gate: experiment EX-0 shows most beta channels gaining live watch hours and subscribers over their own baseline, with zero policy strikes.*
4. **v2, pull ahead** (28 P2 stories): overlay templates and visualizer, multichat, client reports; public API, webhooks, MCP server, backup streams; Hindi UI, music library, stings.

The MVP is the highlighted phase: everything a music channel or temple broadcaster needs to stay live unattended. Gate numbers are proposed and should be set against real data from the beta.

## Non-functional requirements: continuity, quality, latency, cost

**Three rules balance all four: encode only when something must change on screen, encode once and copy to every destination, and never close the connection to a platform.** Continuity comes first, because a stream that drops loses its viewers; cost comes from CPU (encoding) and bandwidth (sending), and both are controlled by those rules.

### Continuity (highest priority)

Each stream has one long-lived output process that is never restarted for a planned change. Its inputs (playlist, phone feed, BRB scene) switch behind it, a fallback image and silent audio are always ready, and the playlist position is saved every 10 seconds so a restart resumes where it stopped.

| Event | Required behaviour | Target |
| --- | --- | --- |
| Playlist edit, skip, overlay toggle, scene switch | Output stays connected; no black frames; audio never goes silent | 0 disconnects |
| Phone signal drops or bitrate collapses | Fallback scene goes on air; output keeps running | Fallback within 2 s; back to live within 2 s of a stable input |
| One destination fails | Only that destination reconnects, with backoff | First retry within 5 s; others unaffected |
| Encoder process crashes | Supervisor restarts it from the saved position | Back on air within 15 s |
| Worker server lost | Stream moves to another worker | Back on air within 60 s |
| AFK Live deploys the web app | Streams run on separate workers | 0 interruptions |
| Weeks of continuous running | Timestamps stay increasing, audio stays in sync, memory stays flat | 72-hour soak test passes before every release; A/V drift under 40 ms |
| YouTube's 12-hour archive limit | Optional rollover to a new broadcast | Gap under 5 s |

### Quality

YouTube asks for a keyframe every 2 seconds, AAC stereo at 44.1 kHz and 128 kbps, and at least 5 Mbps for 1080p30 (14 Mbps recommended) and 3 Mbps for 720p30 ([YouTube Help](https://support.google.com/youtube/answer/2853702?hl=en)). **Today's code sends 1080p at 4.5 Mbps, below YouTube's minimum; fix this first.**

| Profile | Video | Audio | Who gets it |
| --- | --- | --- | --- |
| 720p30 | H.264, 4 Mbps constant, keyframe 2 s, fixed 30 fps | AAC 128 kbps, 44.1 kHz, loudness normalized | Free plan |
| 1080p30 | H.264, 6 Mbps constant, keyframe 2 s | Same | Paid default |
| 1080p60 / 4K | Higher bitrates per YouTube's table | Same | Higher tiers, priced on measured cost |

Every upload is normalized once to the stream profile (codec, frame rate, keyframes, sample rate, loudness), so playout never meets a file it can't handle.

### Latency

For pre-recorded 24/7 streams latency doesn't matter. For mobile live it does, and the main lever is the SRT buffer: a bigger buffer survives worse networks but adds delay.

| Path | Target |
| --- | --- |
| Delay AFK Live adds between phone and platform | Under 2 s at default settings |
| SRT buffer | 1 s by default on 4G/5G; 2 s "unstable network" option |
| Scene switch or overlay toggle reaching the output | Under 2 s |
| Skip to the next video | Under 1 s |

The platform adds its own delay on top; for interactive streams we recommend YouTube's low-latency setting.

### Cost

**Copying a prepared file costs about 200 times less CPU than encoding it, so the cheapest stream is one that never re-encodes.** I measured this on a 2-vCPU Intel Xeon (2.8 GHz) with FFmpeg and x264, using 30 seconds of a synthetic 1080p30 test video; real footage will differ, so repeat the benchmark on the chosen servers.

| Mode | vCPU needed per stream (real time) | Use it for |
| --- | --- | --- |
| Stream copy (no re-encode) | 0.005 | 24/7 playlists of normalized files with no live overlays |
| x264 ultrafast, 1080p30 | 0.44 | Not recommended: worst quality per bit |
| x264 veryfast, 720p30 | 0.78 | Free plan live overlays |
| x264 veryfast, 1080p30 | 1.03 | Default for live overlays and mobile streams |
| x264 faster, 1080p30 | 1.54 | Only if quality complaints justify 50% more CPU |

**Bandwidth is the other half.** One 1080p30 stream at about 6.3 Mbps sends roughly 2.8 GB an hour, about 2 TB a month if it runs 24/7, and that is per destination. Incoming traffic from phones is usually free.

| Provider | Outbound traffic included | Beyond that | What one 24/7 destination costs in traffic |
| --- | --- | --- | --- |
| Hetzner Cloud, EU | 20 TB per server | about €1 per TB | Covered: one server's allowance fits about 9 such destinations |
| Hetzner Cloud, Singapore | 0.5 TB per server | about €1 per TB | About €1.50 a month |
| DigitalOcean | Per-plan allowance, pooled across the account | $0.01 per GiB | About $19 a month once past the allowance |
| AWS (hyperscaler) | 100 GB a month free | Billed per GB | Egress dominates the bill; avoid for the streaming servers |

Sources: [Hetzner traffic summary](https://egresscost.com/hetzner) (third-party, checked September 2026), [Hetzner Cloud](https://www.hetzner.com/cloud), [DigitalOcean bandwidth billing](https://docs.digitalocean.com/platform/billing/bandwidth/), [AWS EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/).

**Cost levers, with their trade-offs**

- **Normalize at upload, copy at playout.** One-time encode per file; 24/7 playlists then cost almost no CPU. Trade-off: upload processing time.
- **Burn static overlays in at upload.** A fixed logo baked into the normalized file keeps copy mode. Dynamic overlays (now playing, clock, alerts) force a live encode; price those plans on about 1 vCPU per 1080p stream.
- **Encode once, fan out by copy.** Each extra destination adds bandwidth, not CPU.
- **Split the fleet by job.** 24/7 playout has no live input, so run it where bandwidth is included (Hetzner EU); YouTube's ingest is global, so viewers aren't affected. Mobile live ingest runs close to streamers, in an India region such as DigitalOcean Bangalore, because a long network path hurts both latency and continuity.
- **HEVC to YouTube later.** YouTube accepts HEVC and AV1 over RTMP/RTMPS, which saves bandwidth at the same quality, but software HEVC encoding costs much more CPU. Benchmark it, ideally with hardware encoders, before adopting.
- **Track cost per stream-hour by tier** (story AD-2) and set prices from it, not the other way round.

### Other requirements

| Area | Requirement |
| --- | --- |
| Availability | Web app and API 99.9% a month; streams measured separately by stream uptime (99.5% target) |
| Scalability | Stateless workers placed by a scheduler on a vCPU and bandwidth budget; reach 1,000 concurrent streams without redesign |
| Security | Stream keys and ingest keys encrypted at rest; every destination URL validated; no secrets in the repo; admin tools behind admin login; per-user rate limits |
| Observability | Per-stream metrics every 5 s (bitrate, fps, dropped frames, speed, input health); per-stream logs; alerts to on-call |
| Data | Recordings kept 30 days by default; account deletion removes media and keys; backups of the database daily |

## Success metrics and open questions

**One number matters most: the share of stream-hours delivered without an unplanned outage.** The targets below are proposed starting points to revisit after the first month of real data.

| Metric | What it measures | Proposed target |
| --- | --- | --- |
| Stream uptime | Planned live time actually delivered, all streams | 99.5% |
| Recovery time | Crash to back on air via auto-restart | Under 60 seconds |
| Time to first live | Sign-up to first successful broadcast (median) | Under 10 minutes |
| Activation | Sign-ups who go live within 24 hours | 40% |
| Free to paid | Free accounts upgrading within 30 days | 5% |
| Monthly churn | Paying accounts cancelling per month | Under 6% |
| Cost per stream-hour | Server cost to deliver one stream for one hour | Known before pricing is set |

**Open questions**

- [ ] India-first or global from day one? This decides launch currency, payment provider and copy.
- [ ] Payment provider: stay on PhonePe, move to Razorpay (UPI autopay, cards, GST invoices), and add Stripe or Paddle for USD?
- [ ] Exact plan prices and free hours, once cost per stream-hour is measured on real workers.
- [ ] Playout engine: adopt Liquidsoap for seamless playlists and crossfade, or build on FFmpeg with short gaps on edits?
- [ ] Royalty-free music library: license a catalogue, partner, or skip?
- [ ] Keep the AFK Live name and domain, or rebrand before launch?
- [ ] Which platform after YouTube matters most to the launch personas: Facebook, Instagram or Twitch?
