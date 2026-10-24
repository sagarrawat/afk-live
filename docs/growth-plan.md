# Growth plan: helping creators gain views

**AFK Live should sell watch time, not streaming.** YouTube gives looped lives no ranking boost. A
live gains views by keeping its broadcast unbroken (so its URL, likes and viewers pile up), by being
found through the new doors YouTube has opened for lives (the Shorts feed for vertical lives, Shorts
clips, reminders, redirects), and by feeling alive enough that people stay. None of the 24/7 tools we
compete with sells that. They sell playout. This plan turns the research in
`docs/research/youtube-live-growth.md` into stories (epic 24 in `docs/product-spec.md`), in the order
the evidence supports.

Written 24 October 2026. Stories with an ID already in the spec are reused rather than duplicated.

## North star and guardrails

| | Metric | Why |
| --- | --- | --- |
| **North star** | Weekly live watch hours on creators' channels, from YouTube Analytics, per connected stream | Watch time is the only optimisation target YouTube states (Test & Compare judges on it) and what 24/7 creators are paid on |
| Leading | Broadcast continuity: share of planned live time on one unbroken broadcast | An encoder gap over ~1 minute ends the broadcast and its URL (`enableAutoStop`) |
| Leading | Entry points used: % of streams with vertical output, Shorts clips posted, a scheduled successor | The documented ways new viewers arrive |
| Leading | Chat rate on streams with engagement features | A metric YouTube reports for lives |
| Business | Activation (first live confirmed, RE-8), conversion to the growth plan, churn | Growth features are why creators pay instead of using a $6 looper |
| Guardrail | Monetization or policy strikes on AFK Live channels: zero | One wave of "inauthentic content" demonetisations would end the product |
| Guardrail | YouTube API quota and compliance audit status | 10,000 units a day is shared by every customer until audited |

## What the research says, in five lines

1. **Vertical lives reach the Shorts feed.** It's the only discovery door YouTube has built for lives. YouTube says portrait-streamed lives are shown to eligible viewers browsing Shorts, and dual-format (horizontal plus vertical, one chat) was in testing from September 2025. *Unknown: whether a 9:16 RTMP stream from a cloud encoder qualifies.*
2. **Continuity is a growth feature.** A gap of more than about a minute ends the broadcast. Streams over 12 hours may not be archived, so creators trade one long-lived URL against archives they can reuse.
3. **Policy rewards variety.** No rule bans loops, but the July 2025 "inauthentic content" rule names templated slideshows and scrolling text as ineligible for monetization, and it covers lives. Fake engagement, repetitive chat and rewards for subscribing are banned.
4. **The APIs allow a lot, with consent.** Scheduling broadcasts, rotating titles and thumbnails, uploading Shorts, reading chat through `streamList` and posting polls are all possible. Community posts and YouTube's own A/B tests are not. Every automated action needs the creator's explicit consent, data must be refreshed within 30 days, and our own metrics must be labelled as ours.
5. **India is TV-first, devotional, regional and has no INR-priced tool.** 75M+ adults watch YouTube on TV, 56% of digital content is regional, devotional has proven spend (Sri Mandir), Gifts launched in July 2026, and phone lives below 1,000 subscribers may have their audience capped.

## Rules every growth feature follows

1. **Consent per stream, logged.** Nothing posts, uploads, replies or changes metadata on a channel unless the creator turned that feature on for that stream. Every action is listed in the stream's activity.
2. **Real engagement only.** No viewer boosting, no generated chat, no rewards for likes or subscriptions, no repeated identical chat messages. Timed messages vary and have a minimum interval.
3. **Variety by default.** New 24/7 streams start with shuffle, a now-playing layer and a context layer. A single file looped over a still image shows a monetization warning.
4. **Music rights are the creator's problem, made visible.** Licensed music can still interrupt a live through Content ID. We warn, point to royalty-free sources and never promise "copyright safe".
5. **Our numbers are labelled as ours.** Uptime, continuity and experiment results say "AFK Live". YouTube's numbers are cached for at most 30 days and refreshed while the channel stays connected.
6. **Honest titles.** We never write "LIVE now" over a replay without the creator's choice. A "24/7 replay" tag is offered as the default for pre-recorded loops.

## Phase G0: answer the unknowns first (1–2 weeks, before building)

Each experiment runs on an AFK Live test channel plus two or three friendly creators. Results are
written into this file, and anything that fails changes the plan below.

| ID | Question | How | Decides |
| --- | --- | --- | --- |
| EX-1 | Does a 9:16 RTMP stream from our encoder appear in the Shorts feed? | Vertical 720×1280 stream from the studio; check from several viewer accounts and devices over a week; compare Studio traffic sources ("Shorts feed") | Whether GR-4 is a P0 headline or a P2 option |
| EX-2 | Does an encoder (RTMP) live from a channel under 1,000 subscribers avoid the mobile audience cap? | Stream from a 100-subscriber channel via our ingest; compare reach with a phone-app live | The phone-studio pitch for small Indian creators |
| EX-3 | How is `liveChatMessages.streamList` billed against quota over 24 hours? | One 24-hour stream, quota dashboard every hour | Whether chat features fit the shared quota before the audit |
| EX-4 | How long can the encoder be gone before YouTube ends the broadcast, with auto-stop on and off? | Controlled disconnects of 30 s, 60 s, 90 s and 5 min | Reconnect deadlines in RE-8 and OPS-3 |
| EX-5 | What will Indian creators pay for growth? | 20 interviews plus a pricing page test with three INR price points | Packaging and price of the growth plan |
| EX-6 | How long does Google OAuth verification and the YouTube API audit take for our scopes? | Start the verification and audit applications now | When growth features can reach all users |

## Phase G1: keep the broadcast alive (P0)

The trust work already planned (RE-8, OPS-3) is the foundation. These stories make it a growth
promise the creator can see.

- **GR-1 Continuity score.** Every stream shows "one unbroken broadcast for N days". Gaps longer than YouTube's auto-stop window are shown with their cause, and alerts fire before that window closes.
- **GR-2 Broadcast lifecycle with a connected channel.** AFK Live creates and schedules broadcasts (YT-1, YT-2), so upcoming cards and "Notify me" reminders work. Creators choose between one unbroken URL (no archive) and a planned rollover before 12 hours. A rollover pre-schedules the next broadcast, sets Live Redirect when eligible, and carries title, thumbnail and playlist forward.
- **GR-3 Connect your channel for growth tools.** An optional, clearly scoped connection step after the first stream works: stream keys stay the default, and the connection unlocks GR-2 and everything below. It shows the permissions in plain words, and disconnecting deletes cached YouTube data.

## Phase G2: more ways in (P1, gated by EX-1)

- **GR-4 Vertical and dual output.** The studio can produce a second, 9:16 output from the same scene: its own layout, the safe area, and chat or now-playing placed for phones. It goes to the same channel as a separate broadcast, or as YouTube's dual format once that is open to encoders. It costs a second encode, so it's a paid add-on (FinOps), and copy mode can still serve the horizontal output.
- **GR-5 Clips to Shorts.** Moments are picked from the library (loudest, most-skipped-to, creator-marked) or from a phone live's recording. They're cut to 9:16 with captions and uploaded as Shorts that link to the live, with the creator approving each upload or a daily batch. Background CPU at the lowest priority (FinOps), within the upload quota bucket.
- **GR-6 Successor and redirect.** When a scheduled live ends, viewers are sent to the next one (Live Redirect at 1,000+ subscribers; otherwise an end slate with the next start time and a QR code). For daily lives, the next broadcast is created automatically so reminders start collecting.

## Phase G3: make it feel live (P1)

- **GR-7 Chat on screen, commands and polls.** Read chat through `streamList` (EX-3). Show recent messages or a "comment of the minute" on screen. Creator-defined commands such as `!song` or `!next` answer within rules (AI-3). Polls run on YouTube and show their result on screen. Builds on CH-1 and AI-4.
- **GR-8 Niche templates.** Ready layouts for lofi, devotional (aarti times, a deity image slot, bhajan name), study-with-me (Pomodoro timer), sleep and ambience, and phone gaming (webcam corner, chat and goal). Indic-script fonts, subscriber goal (OV-4). Builds on OV-5 and OV-6.
- **GR-9 Variety check.** Before go-live and weekly: flags a single file on loop, a still image with no overlay, identical repeated chat messages, or music likely to be claimed. Each flag links to the help article and a one-tap fix (add shuffle, add a layer). Protects monetization under the inauthentic content policy.

## Phase G4: help me improve (P2)

- **GR-10 Titles and descriptions that get found.** Search-style titles and descriptions in English, Hindi and the creator's regional language, with timestamps and tracklists (AI-1, YT-3). It also suggests a second-language title for diaspora audiences: over 40% of live watch time is international, and over 15% of Indian channels' watch time comes from outside India.
- **GR-11 Thumbnail and title experiments.** Rotate two or three thumbnails or titles on a schedule and compare concurrent viewers and watch time per period. These are labelled as AFK Live experiments, not YouTube's A/B tests (which only work on archives). One thumbnail change costs about 50 units of quota.
- **GR-12 Growth report.** A weekly report in the app and by email: watch hours, peak and average viewers, continuity, chat rate, which title or thumbnail did better, and the best hours for the creator's audience, with one suggested next step (AN-1, AN-2, AI-5). It follows the derived-metrics rule (III.L) and labels our numbers as ours.

## India pack (spread across phases)

- **GR-13 Devotional schedules.** Aarti and puja times computed from sunrise and sunset for the temple's city, a festival calendar with ready titles, and morning and evening scene switches. (G1, with GR-2.)
- **GR-14 Diaspora hours.** Scheduling and title suggestions for Gulf, UK and US evenings next to Indian prime time. (G2.)
- **GR-15 Phone-first studio for small channels.** Low-bitrate phone ingest presets (data budget per hour shown in plain words: Indian users average about 1.2 GB a day) and cloud encoding to YouTube quality. If EX-2 confirms it, add a "go live without the 1,000-subscriber audience cap" message. (G2.)
- **GR-16 Earnings readiness.** A checklist for Super Chat, memberships and Gifts (launched in India in July 2026), with what turning on Gifts changes (Super Stickers go away). (G3.)
- **GR-17 INR growth plan.** Growth features (GR-4 to GR-12) as a plan or add-on priced in rupees from EX-5, with UPI autopay and GST invoices. (Pricing, after EX-5.)

## Order of work

| When | Work | Depends on |
| --- | --- | --- |
| Now | EX-1 to EX-6 (no code beyond test streams) | Test channel |
| Trust release | RE-8, OPS-3 (already planned), GR-1 | — |
| Next | GR-3, GR-2, GR-13 | EX-6 for public launch of channel connection |
| Then | GR-9, GR-8, GR-7 | EX-3 |
| Then | GR-4, GR-15, GR-14 | EX-1, EX-2 |
| Then | GR-5, GR-6 | GR-2 |
| Later | GR-10, GR-11, GR-12, GR-16, GR-17 | GR-3; EX-5 for pricing |

## FinOps notes

- **GR-4 costs a second encode** (about 0.45 vCPU at 720p), so it is priced as an add-on. The horizontal output can still use copy mode.
- **GR-5 runs at the lowest priority on spare CPU,** like playout copies. The upload quota is 100 calls a day per project until the audit, so clips are batched per creator.
- **The quota is one pool for every customer until the API audit** (EX-6). Growth features read and write within it: chat through `streamList`, no polling of broadcast status (our worker already knows its health), and statistics cached for hours, never re-read per page view.
- **Every growth feature has a cost line in its PR,** per the roadmap's FinOps rules.

## Risks

| Risk | What we do |
| --- | --- |
| EX-1 fails: encoder verticals don't reach Shorts | GR-4 drops to P2; lean on GR-5 Shorts clips, which do reach the feed |
| Google OAuth verification or audit takes months | Ship G1 continuity and the India pack on stream keys; growth features beta to test users |
| Policy shift against 24/7 loops | GR-9 and variety defaults; never position the product as "fake live" |
| Creators won't pay for growth in INR | Fold the essentials (GR-8, GR-9, GR-13) into base plans to win on value; keep GR-4 and GR-5 as add-ons |
| Chat automation misread as spam | Strict limits, creator review, varied messages, everything logged |
