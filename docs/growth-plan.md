# Growth plan: helping creators gain views

**Prove that AFK Live grows channels before building growth features, then build only the few that
work for streams with almost no viewers.** YouTube gives looped lives no ranking boost. A live gains
views by keeping one broadcast unbroken, by being found (Shorts, reminders, search), and by looking
alive. But most of our creators run streams that nobody is watching yet, on channels we can't see
into unless they connect them. Many ideas that look strong on paper (chat overlays, A/B tests,
growth dashboards, dual output) do nothing, or harm, at zero viewers. This plan is the version that
survived a critique of the first draft (section "What changed and why"). Research:
`docs/research/youtube-live-growth.md`. Stories: epic 24 in `docs/product-spec.md`.

Written 24 October 2026; revised the same day after review.

## What changed and why

The first draft listed 17 stories in five phases. Reviewed against our real situation (a handful of
creators, a two-person team, a shared YouTube API quota, no proof yet that 24/7 lives grow
channels), these are its problems and the fixes.

| First draft said | Problem | Now |
| --- | --- | --- |
| North star: weekly live watch hours on creators' channels | Only visible for channels that connect via Google sign-in; YouTube Analytics lags 2–3 days; seasonality and content swamp our effect; "+25% in 8 weeks" was invented | Two measures: **continuity** (ours, every stream) and **watch hours for connected beta channels against their own baseline**, read honestly as signal, not proof |
| Build 17 stories after 6 experiments | Too much for the team before the Trust release, email and a real user base exist. Every feature also costs support and quota | **Growth v1 is five stories.** The rest are parked with an explicit trigger to revisit |
| Experiments decide feature order | Nothing tested the premise: **does running a 24/7 live grow a small channel at all?** The only evidence is vendor marketing ("2x views") | New **EX-0**: baseline study with beta creators. If 24/7 lives don't move their channels, we pivot the pitch to reliability and phone lives |
| GR-4: vertical plus landscape (dual output) for every stream | Two broadcasts split viewers, chat and likes. With few viewers, splitting hurts the signals that rank a live. It doubles encode and traffic cost. Shorts-feed eligibility for encoder streams is unknown | **Vertical-native**: phones and vertical content stream vertical only (already supported), with a one-tap fix. Dual output is parked until YouTube opens dual format to encoders *and* EX-1 passes |
| GR-5: Shorts cut automatically from 24/7 loops | Clipping files the creator already uploaded is the "reused, mass-produced" pattern the inauthentic content policy names. "Loudest moment" means nothing for music loops. Upload quota is 100 calls a day for all customers until the audit | **Shorts from phone lives only**, creator-marked moments, creator approves every upload. Loops get a "make a Short" checklist, not automation |
| GR-7: chat on screen, commands and polls on every stream | An empty chat box on screen is *negative* social proof. Auto-replies on a stream with no one watching look like bots. Reading chat 24/7 costs quota on every stream, used or not | Chat on screen **appears only while chat is active** (for example 3 messages in 10 minutes) and hides otherwise. Commands and polls are part of the phone studio, where a person is present |
| GR-11: rotate thumbnails and titles and compare | At under ~50 concurrent viewers the differences are noise. Time of day and day of week swamp the effect. YouTube's own test works only on archives. We'd be showing false winners, which breaks the honesty rule | **Dropped.** Replaced by a one-time "title and thumbnail check" (keyword first, readable at phone size, honest wording) inside GR-9 |
| GR-12: weekly growth report from YouTube Analytics | Duplicates YouTube Studio, which creators already have. A blended score needs Google's audit for derived metrics (III.L). Most creators won't have connected | **Next-step nudge** from what only we know: continuity, schedule consistency, variety warnings, plus a link into the matching Studio report |
| GR-2: scheduled broadcasts and a 12-hour rollover for everyone | A rollover resets the URL, likes and viewer count. 12-hour archives of a loop get few views. Most 24/7 creators reuse YouTube's default stream key and never schedule | **Default: one unbroken broadcast.** Scheduling and reminders are for *scheduled* lives (daily aarti, a phone show). Rollover is an advanced option only for creators who want recordings |
| GR-3: ask creators to connect their channel early | Full channel access is a big ask, and stolen tokens are a high-value target. Google verification takes weeks. Many creators will say no | Connect **only when a feature needs it**, with the scope shown in plain words. Tokens encrypted (SecretBox) and revocable. Every core promise still works with a stream key alone |
| GR-13: aarti times from sunrise and sunset; festival calendar | Over-engineered. Temples publish fixed times, many devotional channels play bhajans all day, and a calendar is content we'd have to maintain every year | **Schedule presets** ("morning and evening aarti", "all-day bhajans with a midday pause") the creator edits. No sun maths, no calendar |
| GR-14: diaspora scheduling | Meaningless for 24/7 (always on). For scheduled lives it is one time-zone picker | Folded into GR-2 as a second time zone shown next to the creator's |
| GR-9: warns about "likely music claims" | We can't predict Content ID. A false "safe" breeds false confidence, and a false "risky" scares people off | Flags only what we can see for certain: one file on loop, a still image with no overlay, a repeated identical message. Music gets a general warning and royalty-free sources, never a verdict |
| Growth plan sold as an INR add-on (GR-17) | No willingness-to-pay data. A separate growth fee competes with $6 loopers on price | Pricing waits for EX-5. Cheap features go in every plan. Only costly ones (extra encodes) are paid add-ons |
| Missing | Nothing about how AFK Live *itself* gets found | New **GR-18**: the creators' success becomes our acquisition. An opt-in "Streamed with AFK Live" line in the description, templates people can share, and a referral credit in INR |

## Measures

| Measure | How | Why |
| --- | --- | --- |
| **Continuity** (every stream) | Share of planned live time on one unbroken broadcast; gaps over YouTube's auto-stop window, with cause | We control it fully; a broken broadcast loses its URL, likes and viewers |
| **Channel effect** (connected beta channels) | Weekly live watch hours and subscribers, against the same channel's 4-week baseline before AFK Live, read with notes on content changes | The honest test of the premise (EX-0); too noisy to be a target, good enough to decide direction |
| **Retention** (business) | Creators still streaming after 4 and 8 weeks; paid conversion | If growth features matter, retention moves |
| **Guardrails** | Policy strikes or demonetisations on AFK Live channels: zero. Quota use under 50% until the API audit | Either one would end the product |

## Rules every growth feature follows

1. **It must help at zero viewers, or switch itself off there.** No empty chat boxes, no "0 watching" counters, no tests that need traffic.
2. **Consent per stream, logged.** Nothing posts, uploads, replies or changes metadata unless the creator turned that feature on for that stream. Every action is listed in the stream's activity.
3. **Real engagement only.** No viewer boosting, no generated chat, no rewards for likes or subscriptions, no repeated identical chat messages.
4. **Variety by default.** New 24/7 streams start with shuffle, a now-playing layer and a context layer.
5. **We state only what we know.** No predictions about Content ID or ranking. Our numbers are labelled as ours. YouTube data is cached for at most 30 days.
6. **Honest titles.** No "LIVE now" over a replay unless the creator chooses it. "24/7 replay" is offered by default for loops.
7. **Every feature works without connecting a channel, or says plainly why it needs one.**

## Experiments first (2 weeks, before building Growth v1)

Each experiment has a pass mark set in advance and a decision tied to it.

| ID | Question | How | Pass mark | If it fails |
| --- | --- | --- | --- | --- |
| EX-0 | Does a 24/7 live grow a small channel? | 5–10 beta creators who connect; 4-week baseline, then 4 weeks live with good practice (title, thumbnail, variety, continuity) | Most channels gain live watch hours **and** subscribers above baseline | Pitch AFK Live as reliability and phone lives; Growth v1 shrinks to GR-1 and GR-9 |
| EX-1 | Does a 9:16 encoder stream reach the Shorts feed? | Vertical stream from the studio on a test channel; Studio traffic source "Shorts feed" over a week | Any "Shorts feed" traffic | Vertical stays a phone-format convenience, not a reach feature |
| EX-2 | Does an encoder live from a channel under 1,000 subscribers avoid the mobile audience cap? | 100-subscriber channel: our ingest vs the phone app, same content, same time | Encoder live shows no cap notice and reaches viewers the phone app didn't | Don't market it |
| EX-3 | How much quota does reading chat for 24 hours cost? | One 24-hour stream with `streamList`, quota dashboard hourly | Under 500 units a day per stream | Chat features only on active phone lives |
| EX-4 | How long can the encoder be gone before YouTube ends the broadcast? | Disconnects of 30 s, 60 s, 90 s and 5 min, auto-stop on and off | Known number | Sets reconnect deadlines (RE-8, OPS-3) |
| EX-5 | What will Indian creators pay? | 20 interviews and a pricing page with three INR prices | A price that covers cost per stream-hour (FO-3) | Rework plans, not features |
| EX-6 | How long do Google verification and the API audit take? | Apply now | — | Ship stream-key features first |

## Growth v1: five stories

1. **GR-1 Continuity score** (P0, S). "One unbroken broadcast for N days", with gaps and their cause. Alerts fire before YouTube's auto-stop window closes (EX-4). Built on RE-8. Works with a stream key.
2. **GR-9 Go-live check** (P0, S). Before go-live and weekly, it flags:
   - a single file on loop, or a still image with no overlay (the inauthentic content pattern);
   - a title without a searchable phrase, or with misleading "LIVE";
   - a thumbnail unreadable at phone size;
   - a repeated identical timed message.

   Each flag has a one-tap fix and a help link. Works with a stream key; title and thumbnail checks read what the creator typed into AFK Live.
3. **GR-8 Niche starter templates** (P1, M). Lofi, devotional, study, sleep/ambience and phone gaming. Each comes with layout, overlay layers (now playing, clock, next item), Indic-script fonts, and schedule presets (GR-13 folded in). Chat on screen only appears when chat is active. Works with a stream key; the subscriber goal needs a connection.
4. **GR-2 Scheduled lives** (P1, M; needs a connection). For daily or weekly scheduled lives (aarti, a phone show), AFK Live creates the broadcast ahead of time so the upcoming card and "Notify me" reminders collect. A second time zone is shown for diaspora audiences, and the next broadcast is created automatically. 24/7 streams default to one unbroken broadcast; rollover is an advanced option.
5. **GR-15 Phone lives that grow** (P1, M). Low-data phone presets, with data per hour in plain words. Vertical by default. Chat, polls and commands for a present host. "Mark a moment" during the live, then a Short from each marked moment after it ends, uploaded only on approval (GR-5, narrowed). The audience-cap message only if EX-2 passes.

## Parked, with what would bring each back

| Story | Bring back when |
| --- | --- |
| GR-4 Dual output | YouTube opens dual format to encoders **and** EX-1 passes **and** a creator pays for the second encode |
| GR-5 Shorts from 24/7 loops | Never automated. Revisit only if YouTube publishes guidance that clipping one's own uploads is fine |
| GR-6 Redirect to the next live | Creators with 1,000+ subscribers run scheduled lives on AFK Live |
| GR-7 Chat overlays and commands on 24/7 streams | Typical 24/7 streams on AFK Live have active chat (EX-0 data) |
| GR-10 Multilingual titles | GR-9 shows titles are the common weakness; AI-1 lands |
| GR-11 Thumbnail and title rotation | Dropped (noise at our viewer counts) |
| GR-12 Growth report | Replaced by GR-9's weekly nudge; revisit after the API audit, if creators ask for numbers outside Studio |
| GR-14 Diaspora scheduling | Folded into GR-2 |
| GR-16 Earnings checklist | A help article now (MK-4); in-app when creators reach YPP eligibility on AFK Live |
| GR-17 INR growth add-on | EX-5 |

## GR-18: our own acquisition loop (P1, S)

- **Credit line (opt-in, off by default).** A "Streamed 24/7 with AFK Live" line with a link in the broadcast description. It's never forced and never on screen, and never added when the creator connects their channel without asking.
- **Template gallery pages** on the public site: each niche template is a page that ranks for "24/7 bhajan live setup" or "lofi live stream 24/7", with a live example (with permission).
- **Referral credit in INR:** a free month for both sides, paid only after the referred creator's first confirmed live. This keeps it clear of the "rewards for engagement" rule, since it rewards using AFK Live, not YouTube actions.

## Order of work

| When | Work |
| --- | --- |
| Now | EX-6 applications (Google verification, API audit); EX-0 baseline recruitment; EX-1 to EX-4 test streams |
| Trust release | RE-8 and OPS-3 (already planned), then GR-1 |
| After 2 weeks of experiments | GR-9, then GR-8 |
| With the connection approved | GR-2 |
| Phone track | GR-15 |
| Anytime | GR-18 template pages and referral |

## FinOps notes

- **Growth v1 adds no always-on cost.** GR-1, GR-9 and GR-8 run on data we already have. GR-15's Shorts render at the lowest priority on spare CPU.
- **Quota is one pool for every customer until the API audit.** Chat is read only on active phone lives (until EX-3 says otherwise), broadcasts are created once per scheduled live, and nothing polls YouTube for status.
- **Dual output stays parked partly for cost:** it doubles encode and traffic for a reach gain that is unproven.

## Risks

| Risk | What we do |
| --- | --- |
| EX-0 shows 24/7 lives don't grow small channels | Say so. Market reliability, zero-downtime and phone lives; keep the growth features that cost little |
| Google verification or the audit is slow | Growth v1 runs mostly on stream keys; GR-2 waits |
| A policy change against loops | Variety defaults and GR-9; never position the product as "fake live" |
| Creators see the checks as nagging | One weekly summary, dismissible flags, no blocking except clear policy risks |
