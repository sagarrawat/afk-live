"""MK-4: the help centre, written from real support tickets. Run after editing the text below:

    python3 site/help.py

Writes site/help.html (the searchable index, served at /help) and site/help-<slug>.html (served at
/help/<slug>); vite.site.config.ts builds every help*.html. A new article also needs its slug in
PublicPages.HELP_ARTICLES (which also puts it in /sitemap.xml) and an entry in src/lib/help.ts
(site/src/help-pages.test.ts checks all of them). "for" names the use-case pages (site/use-cases.py)
the article helps: it links to them, and they link back.

Hindi articles (HI_ARTICLES, below the English ones) are written to site/hi-help.html (/hi/help) and
site/hi-help-<slug>.html (/hi/help/<slug>); their slugs go in PublicPages.HINDI_HELP_ARTICLES
(site/src/hindi-help.test.ts checks them). An English article's "hi" names the Hindi article on the same
question: it links there ("हिन्दी में पढ़ें"), and when that Hindi article names it back as its "en" twin,
both pages carry hreflang links to each other.

Article text may use a little inline HTML (links, <strong>); it is ours, not user input.
"""
import html
import importlib.util
import json
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
UPDATED = "27 October 2026"
UPDATED_ISO = "2026-10-27"
STUDIO = "https://studio.youtube.com/channel/UC/livestreaming"

ARTICLES = [
    {
        "slug": "youtube-stream-key",
        "seo_title": "Where to find your YouTube stream key | AFK Live",
        "group": "YouTube setup",
        "for": ["lofi", "podcasts"],
        "title": "Where to find your YouTube stream key",
        "summary": "Copy the key from YouTube Studio's live dashboard in a minute, and avoid the two mix-ups we see most.",
        "keywords": "stream key copy paste youtube studio go live live control room default key server url rtmp encoder",
        "sections": [
            ("", [
                ("p", "AFK Live sends your stream to YouTube with a <strong>stream key</strong>: a code like "
                      "<code>abcd-1234-efgh-5678-ijkl</code> that tells YouTube which channel the video belongs to. "
                      "You copy it from YouTube once and paste it into AFK Live. No Google sign-in needed."),
            ]),
            ("Copy the key on a computer", [
                ("ol", [
                    f'Open <a href="{STUDIO}" target="_blank" rel="noopener">YouTube Studio\'s live dashboard</a> and sign in to the channel you want to stream to. (It\'s also <strong>Create</strong> → <strong>Go live</strong> in YouTube Studio.)',
                    "If YouTube asks you to turn on live streaming, do it. The very first time, YouTube can take up to 24 hours before your channel can go live.",
                    "In the menu on the left, choose <strong>Stream</strong> (not Webcam or Manage).",
                    "Under <strong>Stream settings</strong>, find <strong>Stream key</strong>. Keep <strong>Default stream key</strong> selected, then press <strong>Copy</strong>.",
                    "In AFK Live, paste it into <strong>YouTube stream key</strong> (in setup, or Destinations → Add a destination → YouTube) and save. We check the key straight away by sending a short test picture.",
                ]),
            ]),
            ("On a phone", [
                ("p", "The YouTube Studio app doesn't show stream keys. Open <strong>studio.youtube.com</strong> in your phone's browser, "
                      "switch it to the desktop site (in the browser's menu), and follow the same steps."),
            ]),
            ("Mix-ups we see most", [
                ("ul", [
                    "<strong>Pasting the Stream URL instead of the key.</strong> YouTube shows a Stream URL "
                    "(<code>rtmp://a.rtmp.youtube.com/live2</code>) just above the key. AFK Live already knows it; paste only the key.",
                    "<strong>A key from a scheduled live.</strong> A live you scheduled in the Manage tab can have its own key. "
                    "Use the default key on the Stream tab unless you mean to stream into that scheduled live.",
                    "<strong>The wrong channel.</strong> If you run more than one channel, check the picture in YouTube Studio's top right "
                    "before you copy.",
                ]),
            ]),
            ("Keep it private", [
                ("p", "Anyone with your key can stream to your channel. AFK Live stores it encrypted and only ever shows its last four "
                      "characters. If someone else has seen it, make a new key in YouTube Studio (the Stream key menu → "
                      "<strong>Create new stream key</strong>) and paste the new one into AFK Live."),
            ]),
        ],
        "related": ["live-here-not-on-youtube", "youtube-preparing"],
        "hi": "youtube-stream-key",
    },
    {
        "slug": "youtube-preparing",
        "seo_title": "YouTube live stuck on Preparing? Fix it | AFK Live",
        "group": "YouTube setup",
        "for": ["devotional", "podcasts"],
        "title": "YouTube says “Preparing”: Auto-start and Auto-stop",
        "summary": "Why YouTube shows Preparing for a few seconds, the two settings a 24/7 stream needs, and what to do if it's stuck.",
        "keywords": "preparing waiting for stream stuck auto-start auto-stop autostart autostop end stream restart 24/7 broadcast offline",
        "sections": [
            ("Preparing is normal at first", [
                ("p", "When you go live, AFK Live starts sending within seconds, but YouTube takes a little while to start showing it. "
                      "<strong>Preparing</strong> or <strong>Waiting for stream</strong> for up to about 30 seconds is normal. "
                      "Your stream's page in AFK Live shows Live as soon as we're sending."),
            ]),
            ("For 24/7 streams: Auto-start on, Auto-stop off", [
                ("p", "YouTube has two settings that decide what happens when a stream connects and disconnects. Set them once:"),
                ("ol", [
                    f'Open <a href="{STUDIO}" target="_blank" rel="noopener">YouTube Studio\'s live dashboard</a> and choose <strong>Stream</strong>.',
                    "In <strong>Stream settings</strong>, open <strong>Additional settings</strong>.",
                    "Turn <strong>Enable Auto-start</strong> on. YouTube then goes live by itself as soon as our stream arrives, without you pressing Go live in YouTube Studio.",
                    "Turn <strong>Enable Auto-stop</strong> off. With it on, YouTube ends your live the moment the stream pauses, even for a second when we restart it, and the next connection can sit on Preparing.",
                ]),
            ]),
            ("Stuck on Preparing?", [
                ("p", "This usually happens after a restart or after you change the stream's shape (landscape or vertical) or quality: "
                      "YouTube holds on to the old live and won't show the new picture in it."),
                ("ol", [
                    "Leave your stream running in AFK Live.",
                    f'In <a href="{STUDIO}" target="_blank" rel="noopener">YouTube Studio\'s live dashboard</a>, press <strong>End stream</strong> on the live that\'s stuck.',
                    "Start a fresh live with the same stream key (the Stream tab again). With Auto-start on, it picks up our stream within about 30 seconds.",
                ]),
                ("p", "You don't need a new key, and you don't need to stop anything in AFK Live."),
            ]),
        ],
        "related": ["live-here-not-on-youtube", "youtube-stream-key"],
    },
    {
        "slug": "live-here-not-on-youtube",
        "seo_title": "Live stream not showing on YouTube? 5 checks | AFK Live",
        "group": "YouTube setup",
        "for": ["devotional"],
        "title": "Live here but not on YouTube",
        "summary": "AFK Live says Live but your channel shows nothing? Check these in order: the key, a scheduled live, Auto-start, and the channel.",
        "keywords": "not showing nothing on youtube offline wrong key scheduled live auto-start no video black waiting test destination refused",
        "sections": [
            ("", [
                ("p", "If your stream's page in AFK Live says <strong>Live</strong> and every destination is green, we're sending the picture. "
                      "When YouTube still shows nothing after a minute, it's almost always one of these."),
            ]),
            ("1. The key is wrong or old", [
                ("p", "Open the stream's <strong>Destinations</strong> tab and press <strong>Send a test</strong> on YouTube "
                      "(do it while the stream is off air). If YouTube refuses the key, copy it again: "
                      '<a href="/help/youtube-stream-key">where to find your YouTube stream key</a>. '
                      "A key stops working if you made a new one in YouTube Studio."),
            ]),
            ("2. The key belongs to a scheduled live", [
                ("p", "A live you scheduled in YouTube Studio's <strong>Manage</strong> tab can have its own key. Our stream then goes into "
                      "that scheduled live, not the one on the Stream tab. Open it from Manage to see it, or paste the "
                      "<strong>Default stream key</strong> from the Stream tab instead."),
            ]),
            ("3. Auto-start is off", [
                ("p", "With Auto-start off, YouTube receives the stream and waits for you to press <strong>Go live</strong> in YouTube Studio. "
                      'Press it, or turn Auto-start on so it never waits again: <a href="/help/youtube-preparing">Preparing, Auto-start and Auto-stop</a>.'),
            ]),
            ("4. YouTube is stuck on an old live", [
                ("p", "After a restart or a change of shape, YouTube can stay on Preparing. End that live in YouTube Studio and start a fresh "
                      "one with the same key; your stream in AFK Live keeps running."),
            ]),
            ("5. The channel can't go live yet", [
                ("p", "A channel's first live needs YouTube's live streaming turned on, which can take up to 24 hours, and YouTube stops "
                      "live streaming on channels with some kinds of restriction. YouTube Studio's live dashboard says so at the top."),
            ]),
            ("Still nothing?", [
                ("p", 'Check <a href="/status">AFK Live\'s status page</a>, then email <a href="mailto:support@afklive.in">support@afklive.in</a> '
                      "with your stream's name. We reply within a day."),
            ]),
        ],
        "related": ["youtube-preparing", "youtube-stream-key"],
    },
    {
        "slug": "vertical-or-landscape",
        "seo_title": "Vertical or landscape YouTube live? | AFK Live",
        "group": "Phone streaming and picture",
        "for": ["mobile-gaming"],
        "title": "Vertical or landscape?",
        "summary": "Which shape to pick, what YouTube does with vertical lives, and how videos of the other shape are shown.",
        "keywords": "vertical landscape 9:16 16:9 portrait shorts phone shape orientation blurred background bars crop fit 60 fps frame rate",
        "sections": [
            ("Pick the shape of most of your videos", [
                ("ul", [
                    "<strong>Landscape 16:9</strong> fills computers and TVs, and phones turned sideways. Most 24/7 channels use it.",
                    "<strong>Vertical 9:16</strong> fills a phone held upright. Pick it if most of your videos were filmed on a phone held upright.",
                ]),
                ("p", "When most of the videos you add are vertical, AFK Live suggests Vertical for you."),
            ]),
            ("What YouTube does with a vertical live", [
                ("p", "YouTube shows a vertical live full screen to people watching on phones, and can show it in the Shorts feed too. "
                      "On a computer it appears with bars at the sides."),
            ]),
            ("Where to change it", [
                ("p", "Open the stream, then <strong>Settings</strong> → <strong>Picture</strong> → <strong>Shape</strong>. "
                      "The shape can only change while the stream is off air; it applies the next time you go live. "
                      "If YouTube then stays on Preparing, end the old live in YouTube Studio and start a fresh one "
                      '(<a href="/help/youtube-preparing">how</a>).'),
            ]),
            ("Videos of the other shape", [
                ("p", "A playlist can mix shapes. Videos that aren't the stream's shape are shown one of three ways, set in "
                      "<strong>Settings</strong> → <strong>Videos that aren't the stream's shape</strong>, or per video in the playlist:"),
                ("ul", [
                    "<strong>Blurred background</strong>: the whole picture over a soft, blurred copy of itself. Best for phone videos in a landscape stream.",
                    "<strong>Bars</strong>: the whole picture, with black bars at the sides or top and bottom.",
                    "<strong>Fill and crop</strong>: fills the frame; the edges of the picture are cut off.",
                ]),
            ]),
            ("Videos filmed at 60 frames per second", [
                ("p", "Plans that stream at 30 frames per second play 60 frames per second videos smoothly at 30. "
                      "You don't need to convert them first. Plans with 60 frames per second can change it in Settings → Quality."),
            ]),
        ],
        "related": ["youtube-preparing", "phone-streaming"],
    },
    {
        "slug": "phone-streaming",
        "seo_title": "Stream to YouTube from your phone: Larix, PRISM | AFK Live",
        "group": "Phone streaming and picture",
        "for": ["mobile-gaming"],
        "title": "Go live from your phone (Larix or PRISM)",
        "summary": "Send your phone's camera or screen to AFK Live with a free app; we add your overlays and keep you on air when the signal drops.",
        "keywords": "phone mobile larix broadcaster prism live studio streamlabs srt rtmp qr code camera screen gaming ingest brb signal",
        "sections": [
            ("", [
                ("p", "Your phone sends one stream to AFK Live; we add your logo, scenes and overlays and send it on to YouTube and your other "
                      "destinations. If your signal drops, viewers see your Be right back scene with music until your phone reconnects."),
            ]),
            ("Before you start", [
                ("ol", [
                    'Create a stream and choose <strong>Live from my phone</strong>, or open an existing phone stream.',
                    'Add YouTube on the <strong>Destinations</strong> tab (<a href="/help/youtube-stream-key">where to find the key</a>).',
                    "Open the <strong>Phone setup</strong> tab. It shows a QR code, a server address and a stream key made just for this stream.",
                ]),
            ]),
            ("Larix Broadcaster (Android and iPhone, free)", [
                ("ol", [
                    "Install Larix Broadcaster from your app store.",
                    "On the Phone setup tab, choose <strong>Larix</strong> and scan the QR code with your phone's camera. Reading this on your phone? Tap <strong>Open in Larix</strong> instead.",
                    "Larix adds a connection named after your stream. Turn your phone the way your stream is shaped.",
                    "In AFK Live, press <strong>Go live</strong>. Then tap the record button in Larix.",
                ]),
            ]),
            ("PRISM Live Studio (Android and iPhone, free)", [
                ("ol", [
                    "Install PRISM Live Studio and open it.",
                    "Choose to stream to <strong>Custom RTMP</strong> (not YouTube: AFK Live sends it to YouTube for you).",
                    "On the Phone setup tab, choose <strong>PRISM</strong>, then copy the <strong>Server</strong> into PRISM's URL and the <strong>Stream key</strong> into its key, or scan the QR code.",
                    "In AFK Live, press <strong>Go live</strong>. Then start the stream in PRISM.",
                ]),
            ]),
            ("Weak signal?", [
                ("p", "Apps that support SRT (Larix does) ride out a poor connection better. Choose <strong>Other (SRT)</strong> on the Phone setup tab "
                      "and use that address. More buffer in <strong>SRT buffer</strong> survives worse signal but adds a little delay; 1000 ms suits most 4G."),
            ]),
            ("Good to know", [
                ("ul", [
                    "Press Go live in AFK Live first. Until your phone connects, viewers see your Starting soon scene.",
                    "Switch scenes from another phone or tablet with the remote control link on the stream's Overview.",
                    "Keep the stream key private. If someone else has seen it, press <strong>New stream key</strong> and set up the app again.",
                    "YouTube's own app needs 50 subscribers to go live from a phone. Streaming through AFK Live doesn't.",
                ]),
            ]),
        ],
        "related": ["youtube-preparing", "vertical-or-landscape"],
        "hi": "mobile-se-live",
    },
    {
        "slug": "loops-and-youtube-rules",
        "seo_title": "Are 24/7 loop streams allowed on YouTube? | AFK Live",
        "group": "Growing your channel",
        "for": ["lofi", "podcasts"],
        "title": "24/7 loops and YouTube's rules",
        "summary": "What YouTube's inauthentic content and spam rules mean for a looped live, and the three things the stream check looks for.",
        "keywords": "monetisation monetization demonetised inauthentic repetitive reused content loop still image static slideshow spam repeated message ypp ads policy",
        "sections": [
            ("", [
                ("p", "A 24/7 live that plays videos you made is allowed on YouTube. What YouTube's rules for earning (the YouTube Partner "
                      "Program) exclude is content that is <strong>mass-produced, repetitive or low-effort</strong>: since July 2025 this is "
                      "called the <em>inauthentic content</em> policy. Its examples include image slideshows and scrolling text with little or "
                      "nothing else. Separately, the spam policy covers repeated identical chat messages and misleading titles."),
                ("p", "Nobody outside YouTube can say how a particular channel will be reviewed. AFK Live's stream check only points out the "
                      "patterns the rules name, when we can see them for certain in what you set up."),
            ]),
            ("1. One video playing over and over", [
                ("p", "If the whole stream is one video (and at most one song) on repeat, viewers who stay see the same thing again and again. "
                      "Add more videos, or put a music playlist under a looping visual: a looping scene under many tracks is the usual, "
                      "accepted way 24/7 music channels work."),
            ]),
            ("2. One still picture with nothing on screen", [
                ("p", "We look at frames from the start to the end of each video. If every video is one unchanging picture and nothing is "
                      "drawn on top, the stream is a still image. Add something that changes: the check's one-tap fix adds a clock and the "
                      "name of the song that's playing. Better still, use a short looping animation."),
            ]),
            ("3. The same reply posted again and again", [
                ("p", "Posting the same words three or more times in a week is the repeated identical message the spam policy names. "
                      "AFK Live no longer replies in chat or to comments for you: anything posted on your channel is something you "
                      "chose to send. If replies AFK Live posted in the past show up in the check, you can delete them in YouTube Studio."),
            ]),
            ("Keeping a flag", [
                ("p", "Every flag can be kept as it is (for example, a stream that really is one long video). Kept flags stay listed "
                      "under the check, and the weekly check doesn't remind you about them. The check never stops you going live."),
            ]),
        ],
        "related": ["titles-and-thumbnails", "music-on-your-live"],
        "hi": "24-ghante-live",
    },
    {
        "slug": "titles-and-thumbnails",
        "seo_title": "Titles and thumbnails for YouTube lives | AFK Live",
        "group": "Growing your channel",
        "for": ["mobile-gaming"],
        "title": "Titles and thumbnails that get found",
        "summary": "Start the title with what people search for, don't say LIVE over a replay, and make the thumbnail readable at phone size.",
        "keywords": "title thumbnail seo search keywords live now replay misleading 1280x720 16:9 phone small text contrast clickbait",
        "sections": [
            ("", [
                ("p", "People find a 24/7 live mostly through search and suggestions, so the title and thumbnail matter. Type the title you use "
                      "in YouTube Studio into the stream check (on the stream's Overview) and choose your thumbnail from your library: we check "
                      "them there. We only read them in AFK Live; nothing changes on YouTube."),
            ]),
            ("Titles", [
                ("ul", [
                    "<strong>Start with the words people type.</strong> “Lofi beats to study and relax”, “Hanuman Chalisa 24/7”, "
                    "“Rain sounds for sleep”. Words like live, stream, 24/7 or new don't say what's on.",
                    "<strong>Don't say LIVE over a replay.</strong> A 🔴 or “LIVE now” on videos you uploaded can read as misleading, and "
                    "YouTube's spam policy covers misleading titles. “24/7 replay” is honest. If you are really there live, keep your title.",
                    "<strong>Up to 100 characters,</strong> but the first 40 or so are what shows on phones.",
                ]),
            ]),
            ("Thumbnails", [
                ("ul", [
                    "<strong>1280x720 (16:9).</strong> YouTube needs at least 640 pixels wide; other shapes get cut off or get bars.",
                    "<strong>Readable at phone size.</strong> In search and up next, a thumbnail is about the size of a stamp. We shrink "
                    "yours to that size and measure how much stands out from the background. If it's nearly one flat colour, text and faces "
                    "on it won't be seen. Use a few big words and strong contrast.",
                    "We can't read the words on your thumbnail, so we don't judge what it says.",
                ]),
            ]),
            ("Change them on YouTube", [
                ("p", f'Set the title and thumbnail in <a href="{STUDIO}" target="_blank" rel="noopener">YouTube Studio\'s live dashboard</a> '
                      "(the pencil next to the title). A stream key alone can't change them from AFK Live."),
            ]),
        ],
        "related": ["loops-and-youtube-rules", "youtube-preparing"],
    },
    {
        "slug": "music-on-your-live",
        "seo_title": "Music on a 24/7 YouTube live: copyright claims | AFK Live",
        "group": "Growing your channel",
        "for": ["lofi", "devotional"],
        "title": "Music on your live",
        "summary": "Why no tool can tell you whether a song will be claimed, what a claim can do to a live, and where to get music you can use.",
        "keywords": "music copyright content id claim muted blocked royalty free audio library license bhajan song cover strike",
        "sections": [
            ("", [
                ("p", "YouTube checks live streams for music owned by others (Content ID). Whether a song is claimed depends on who owns it, "
                      "what they told YouTube to do, and the exact recording. <strong>AFK Live can't tell you whether a song will be claimed, "
                      "and no other tool can either.</strong> We'll never call a track safe or risky."),
            ]),
            ("What a claim can do to a live", [
                ("ul", [
                    "Your live can be muted, shown with a placeholder, or stopped.",
                    "The recording can lose its ads, or the owner can earn from it instead of you.",
                    "Repeated problems can lead to restrictions on live streaming for the channel.",
                ]),
            ]),
            ("Music you can use", [
                ("ul", [
                    "Music you made yourself, or that you have a written licence for (keep it).",
                    "YouTube's own <strong>Audio Library</strong> in YouTube Studio: free tracks that are cleared for YouTube.",
                    "Royalty-free libraries whose licence covers live streams on YouTube. Read the licence: some cover videos but not lives.",
                    "Devotional songs: the words may be traditional, but a particular recording usually belongs to a label. Record your own or use a licensed one.",
                ]),
            ]),
        ],
        "related": ["loops-and-youtube-rules", "titles-and-thumbnails"],
    },
]

BY_SLUG = {a["slug"]: a for a in ARTICLES}
GROUPS = ["YouTube setup", "Phone streaming and picture", "Growing your channel"]

# --- Hindi help (/hi/help, /hi/help/<slug>) ------------------------------------------------------------
#
# Written for the Hindi and Hinglish searches in docs/growth-plan.md ("24 ghante live kaise kare" and so
# on), in the style of docs/translations.md: everyday Hindi in Devanagari, with the English words creators
# use (YouTube, live, stream key, playlist, OBS) in Latin script. Every string here needs a native
# speaker's review (docs/translations.md lists them). "en" is the English twin, when there is one: the
# two pages then carry hreflang links to each other. A page with "faq" shows the questions and gets
# FAQPage markup from them at build time (site/src/seo.ts). Section ids are given, because Devanagari
# headings don't make readable ids. The costs are tested against the homepage calculator and the static
# plans (site/src/hindi-help.test.ts), so they can't drift apart.

HI_UPDATED = "27 अक्टूबर 2026"
YT_MOBILE = "https://support.google.com/youtube/answer/9228390"
YT_START = "https://support.google.com/youtube/answer/2474026"
YT_ENCODER = "https://support.google.com/youtube/answer/2907883"


def ext(href, text):
    return f'<a href="{href}" target="_blank" rel="noopener">{text}</a>'


def en_link(href, text):
    """A link to a page that is only in English, marked as such (docs/translations.md)."""
    return f'<a href="{href}" hreflang="en">{text} (अंग्रेज़ी में)</a>'


HI_ARTICLES = [
    {
        "slug": "24-ghante-live",
        "seo_title": "YouTube पर 24 घंटे Live कैसे करें (बिना PC) | AFK Live",
        "for": ["lofi", "devotional"],
        "title": "YouTube पर 24 घंटे live कैसे करें",
        "summary": "24 ghante live kaise kare: YouTube के नियम क्या कहते हैं, PC, VPS या AFK Live से 24/7 live का महीने का खर्च ₹ में, और setup के steps।",
        "sections": [
            ("", "", [
                ("p", "बहुत से lofi, भजन-आरती, पढ़ाई और podcast चैनल YouTube पर दिन-रात live रहते हैं। इस पेज पर तीन बातें हैं: "
                      "क्या ये YouTube पर allowed है, इसे करने के तीन तरीके और हर एक का महीने का खर्च, और AFK Live से setup के steps।"),
            ]),
            ("क्या YouTube पर 24 घंटे live allowed है?", "allowed", [
                ("p", "हाँ। अपने बनाए वीडियो 24/7 live में चलाना YouTube पर allowed है। YouTube से कमाई के नियम (YouTube Partner "
                      "Program) उस content को बाहर रखते हैं जो <strong>थोक में बना, बार-बार एक जैसा या कम मेहनत वाला</strong> हो। "
                      "जुलाई 2025 से इस नियम का नाम <em>inauthentic content</em> policy है। इसके उदाहरणों में फ़ोटो की slideshow और "
                      "scroll होता text है, जिनमें और कुछ खास न हो।"),
                ("p", "अलग से, YouTube की spam policy एक जैसे chat messages बार-बार भेजने और गुमराह करने वाले titles पर लागू होती है। "
                      "पहले से बने वीडियो पर “🔴 LIVE” लिखना गुमराह करने वाला माना जा सकता है; “24/7 replay” लिखना ईमानदार है।"),
                ("ul", [
                    "एक ही वीडियो बार-बार चलाने के बजाय कई वीडियो लगाएँ, चाहें तो shuffle में।",
                    "एक ही फ़ोटो के बजाय स्क्रीन पर कुछ बदलता रहे: छोटा loop animation, घड़ी, या अभी चल रहे गाने का नाम।",
                    "Music वही लगाएँ जो आपका हो या जिसका live के लिए licence हो: "
                    + en_link("/help/music-on-your-live", "आपके live पर music") + "।",
                ]),
                ("p", "<strong>Monetisation का वादा कोई नहीं कर सकता।</strong> आपका चैनल कैसे review होगा, ये YouTube तय करता है; "
                      "AFK Live या कोई और tool इसकी गारंटी नहीं दे सकता। पूरी बात: "
                      + en_link("/help/loops-and-youtube-rules", "24/7 loops और YouTube के नियम") + "।"),
            ]),
            ("24 घंटे live के 3 तरीके, और उनका खर्च", "teen-tarike", [
                ("p", "खर्च एक महीने (30 दिन) के, 1080p stream के हैं। ये अंदाज़े हैं: अपने बिजली के बिल और provider की आज की कीमत से "
                      "मिला लें। डॉलर और यूरो को अक्टूबर 2026 के रेट पर ₹ में बदला है।"),
                ("ways", [
                    ("PC और OBS", "₹864", "महीने की बिजली",
                     "घर का PC OBS चलाकर दिन-रात stream करता रहे। हिसाब: 150 W × 24 घंटे × 30 दिन ÷ 1000 = 108 यूनिट × ₹8 = ₹864। "
                     "इंटरनेट का upload भी दिन-रात चलता है: 1080p पर लगभग 5 Mbps, यानी महीने में करीब 1.6 TB। "
                     "बिजली गई, Windows update आया या इंटरनेट रुका, तो live भी रुक जाता है।"),
                    ("अपना VPS (किराए का server)", "₹600–1,100", "server और traffic, टैक्स अलग",
                     "किराए के Linux server पर FFmpeg से वीडियो loop करें। Hetzner का सबसे सस्ता server (CX23) जून 2026 से €5.49 महीना है "
                     "(Germany या Finland में, 20 TB traffic के साथ; IPv4 address अलग)। DigitalOcean का $6 वाला server 1 TB traffic देता है, "
                     "और 24/7 1080p में करीब 1.6 TB लगता है, तो ऊपर का traffic $0.01 प्रति GB जुड़कर कुल लगभग $12 होता है। "
                     "बिल डॉलर या यूरो में आता है, और server बनाना, FFmpeg चलाना और कुछ टूटे तो ठीक करना आपके ज़िम्मे है।"),
                    ("AFK Live", "₹499", "Creator plan, GST शामिल",
                     "वीडियो एक बार upload करें; हमारे servers उन्हें playlist की तरह 24 घंटे YouTube पर चलाते हैं, और stream रुके तो "
                     "अपने-आप दोबारा जोड़ते हैं। PC बंद रख सकते हैं, Linux नहीं सीखनी। Creator plan में 1 stream 1080p पर, अनलिमिटेड घंटे, "
                     "UPI से पेमेंट। आज़माने के लिए Free plan में हर महीने 10 घंटे (720p, छोटे watermark के साथ)।"),
                ]),
                ("p", "अगर आप Linux और FFmpeg जानते हैं, तो VPS सबसे सस्ता पड़ सकता है। PC वाला तरीका तब ठीक है जब PC वैसे भी दिन-रात चालू "
                      "रहता हो। AFK Live उनके लिए है जो server नहीं संभालना चाहते। सारे plans: "
                      '<a href="/hi#pricing">कीमतें</a>, या ' + en_link("/pricing", "पूरी pricing") + "।"),
            ]),
            ("AFK Live से 24 घंटे live: step-by-step", "steps", [
                ("ol", [
                    '<a href="/register" hreflang="en">फ़्री account बनाएँ</a>, कोई कार्ड नहीं चाहिए। App अभी अंग्रेज़ी में है, '
                    "इसलिए बटनों के नाम यहाँ अंग्रेज़ी में लिखे हैं।",
                    "<strong>Library</strong> में अपने वीडियो upload करें। Upload बीच में टूट जाए तो वहीं से आगे बढ़ता है।",
                    "<strong>New stream</strong> दबाएँ, वीडियो चुनें और playlist का क्रम तय करें, या <strong>Shuffle</strong> चालू करें।",
                    'YouTube Studio से अपनी stream key copy करके AFK Live में paste करें: <a href="/hi/help/youtube-stream-key">stream key '
                    "कहाँ मिलेगी</a>। हम उसी समय एक छोटी test picture भेजकर key जाँच लेते हैं।",
                    "YouTube Studio में <strong>Enable Auto-start</strong> चालू और <strong>Enable Auto-stop</strong> बंद करें। तब YouTube "
                    "stream आते ही खुद live हो जाता है, और restart पर live खत्म नहीं करता ("
                    + en_link("/help/youtube-preparing", "कैसे") + ")।",
                    "AFK Live में <strong>Go live</strong> दबाएँ। आम तौर पर 30 सेकंड में आपका चैनल live दिखता है। अब laptop बंद कर सकते हैं।",
                ]),
                ("p", "रोज़ या हफ़्ते के तय समय पर live करना हो (जैसे सुबह-शाम की आरती), तो <strong>Schedule</strong> में शुरू और "
                      "बंद होने का समय डालें।"),
            ]),
        ],
        "faq": [
            ("24 घंटे live के लिए कितने subscribers चाहिए?",
             "Stream key से live करने के लिए YouTube कोई subscriber गिनती नहीं माँगता; चैनल verified हो और पिछले 90 दिनों में live "
             "पर कोई रोक न लगी हो। सिर्फ़ YouTube app से फ़ोन पर live के लिए 50 subscribers चाहिए: "
             '<a href="/hi/help/mobile-se-live">मोबाइल से live</a>।'),
            ("क्या एक ही वीडियो को loop में चला सकते हैं?",
             "चला सकते हैं, पर दर्शक बार-बार वही देखेंगे, और ये YouTube के “बार-बार एक जैसा” वाले नियम के करीब है। कई वीडियो लगाएँ, "
             "या एक loop होता visual और उसके नीचे कई गानों की playlist: 24/7 music चैनल आम तौर पर ऐसे ही चलते हैं।"),
            ("बीच में live टूट जाए तो क्या होगा?",
             "AFK Live stream को अपने-आप दोबारा जोड़ता है। YouTube में Auto-stop बंद हो, तो दर्शकों के लिए वही live चलता रहता है। "
             "कोई गड़बड़ी एक मिनट से ज़्यादा चले, तो हम आपको बता देते हैं।"),
            ("क्या 24 घंटे live से views और subscribers बढ़ेंगे?",
             "इसकी कोई गारंटी नहीं। Live चलता रहे तो लोग उसे search और suggestions में पा सकते हैं, पर कितने लोग आएँगे ये आपके content, "
             "title और thumbnail पर है: " + en_link("/help/titles-and-thumbnails", "titles और thumbnails") + "।"),
        ],
        "related": ["recorded-video-live", "youtube-stream-key", "mobile-se-live"],
    },
    {
        "slug": "recorded-video-live",
        "seo_title": "YouTube पर Recorded वीडियो Live कैसे करें | AFK Live",
        "for": ["podcasts", "devotional"],
        "title": "YouTube पर recorded वीडियो live कैसे करें",
        "summary": "youtube par recorded video live kaise kare: Premiere और live का फ़र्क, OBS या AFK Live से पहले से बना वीडियो live चलाना, और title में क्या न लिखें।",
        "sections": [
            ("", "", [
                ("p", "पहले से record किया वीडियो YouTube पर दो तरह से दर्शकों के साथ चलाया जा सकता है: <strong>Premiere</strong> "
                      "या <strong>live stream</strong>। दोनों अलग चीज़ें हैं।"),
            ]),
            ("Premiere या live stream?", "premiere-ya-live", [
                ("ul", [
                    "<strong>Premiere</strong>: वीडियो upload करके समय तय करें। उस समय वो एक बार चलता है और दर्शक साथ में chat करते हैं; "
                    "फिर वो आम वीडियो बन जाता है। एक बार दिखाने के लिए ये सबसे आसान है, और YouTube Studio में ही हो जाता है।",
                    "<strong>Live stream</strong>: वीडियो किसी encoder (जैसे OBS, या AFK Live जैसी service) से stream key के ज़रिए भेजा "
                    "जाता है। ये जितनी देर चाहें चल सकता है: कई वीडियो एक के बाद एक, बार-बार, या रोज़ तय समय पर।",
                ]),
            ]),
            ("तरीका 1: OBS से (PC चालू रहेगा)", "obs", [
                ("ol", [
                    "PC पर OBS Studio install करें (फ़्री)।",
                    "<strong>Sources</strong> में <strong>Media Source</strong> जोड़ें और अपना वीडियो चुनें। बार-बार चलाना हो तो "
                    "<strong>Loop</strong> चालू करें।",
                    "<strong>Settings</strong> → <strong>Stream</strong> में Service: YouTube चुनें और अपनी stream key paste करें "
                    '(<a href="/hi/help/youtube-stream-key">key कहाँ मिलेगी</a>)।',
                    "<strong>Start Streaming</strong> दबाएँ।",
                ]),
                ("p", "जब तक live चले, PC और इंटरनेट चालू रहने चाहिए। दिन-रात चलाना हो तो बिजली का खर्च देखें: "
                      '<a href="/hi/help/24-ghante-live#teen-tarike">24 घंटे live के 3 तरीके</a>।'),
            ]),
            ("तरीका 2: AFK Live से (PC बंद)", "afk-live", [
                ("ol", [
                    '<a href="/register" hreflang="en">फ़्री account बनाएँ</a> और <strong>Library</strong> में वीडियो upload करें।',
                    "<strong>New stream</strong> दबाएँ और वीडियो चुनें: एक, या कई की playlist।",
                    'YouTube की stream key paste करें (<a href="/hi/help/youtube-stream-key">कहाँ मिलेगी</a>)।',
                    "अभी चलाना हो तो <strong>Go live</strong> दबाएँ। बाद में या रोज़ तय समय पर चलाना हो तो <strong>Schedule</strong> में "
                    "समय डालें।",
                ]),
                ("p", "वीडियो हमारे servers से चलता है, तो आपका PC या फ़ोन बंद रह सकता है।"),
            ]),
            ("Title में क्या न लिखें", "title", [
                ("p", "पहले से बने वीडियो पर “🔴 LIVE now” लिखना गुमराह करने वाला माना जा सकता है, और YouTube की spam policy गुमराह "
                      "करने वाले titles पर लागू होती है। “Replay” या “24/7 replay” जैसे ईमानदार शब्द लिखें। अगर आप सच में live मौजूद हैं, "
                      "तो LIVE लिखना ठीक है। और tips: " + en_link("/help/titles-and-thumbnails", "titles और thumbnails") + "।"),
            ]),
        ],
        "faq": [
            ("क्या recorded वीडियो को live चलाना YouTube पर ठीक है?",
             "हाँ, अगर वीडियो आपका है या आपके पास उसे चलाने का अधिकार है। किसी और का वीडियो या गाना चलाने पर copyright claim आ सकता है, "
             "और live रुक भी सकता है।"),
            ("क्या हर दिन तय समय पर वही वीडियो live चला सकते हैं?",
             "हाँ। AFK Live में Schedule से रोज़ या हफ़्ते के दिन चुनकर समय तय करें। ध्यान रहे, हर दिन बिल्कुल एक जैसा content "
             "“बार-बार एक जैसा” माना जा सकता है, इसलिए बीच-बीच में नए वीडियो जोड़ते रहें।"),
            ("Premiere और live में दर्शकों को क्या फ़र्क दिखता है?",
             "Premiere पर “Premiere” लिखा आता है और वो एक बार चलकर आम वीडियो बन जाता है। Live stream पर “Live” दिखता है और वो तब तक "
             "चलता है जब तक आप उसे बंद न करें।"),
        ],
        "related": ["24-ghante-live", "youtube-stream-key"],
    },
    {
        "slug": "youtube-stream-key",
        "en": "youtube-stream-key",
        "seo_title": "YouTube Stream Key कहाँ मिलेगी (मोबाइल और PC) | AFK Live",
        "for": ["lofi", "podcasts"],
        "title": "YouTube stream key कहाँ मिलेगी",
        "summary": "youtube stream key kahan milega: फ़ोन के browser और computer पर YouTube Studio से key copy करने के steps, और दो आम गलतियाँ।",
        "sections": [
            ("", "", [
                ("p", "AFK Live आपका stream YouTube पर एक <strong>stream key</strong> से भेजता है: <code>abcd-1234-efgh-5678-ijkl</code> "
                      "जैसा एक code, जिससे YouTube समझता है कि वीडियो किस चैनल का है। आप इसे YouTube से एक बार copy करके AFK Live में "
                      "paste करते हैं। Google से sign in की ज़रूरत नहीं।"),
            ]),
            ("फ़ोन पर key copy करें", "phone", [
                ("ol", [
                    "YouTube Studio app में stream key नहीं दिखती। फ़ोन के browser (जैसे Chrome) में <strong>studio.youtube.com</strong> "
                    "खोलें और उसी चैनल से sign in करें।",
                    "Browser के menu (⋮) में <strong>Desktop site</strong> चालू करें। पेज छोटा दिखेगा; zoom करके पढ़ें।",
                    "ऊपर <strong>Create</strong> → <strong>Go live</strong> दबाएँ। YouTube live streaming चालू करने को कहे तो चालू करें। "
                    "पहली बार इसमें 24 घंटे तक लग सकते हैं।",
                    "बाएँ menu में <strong>Stream</strong> चुनें (Webcam या Manage नहीं)।",
                    "<strong>Stream settings</strong> में <strong>Stream key</strong> के पास <strong>Copy</strong> दबाएँ।",
                    "AFK Live में key को <strong>YouTube stream key</strong> वाले खाने में paste करके save करें (setup में, या "
                    "Destinations → Add a destination → YouTube)। हम उसी समय एक छोटी test picture भेजकर key जाँच लेते हैं।",
                ]),
            ]),
            ("Computer पर", "computer", [
                ("ol", [
                    f'<a href="{STUDIO}" target="_blank" rel="noopener">YouTube Studio का live dashboard</a> खोलें और सही चैनल से sign in करें।',
                    "बाएँ menu में <strong>Stream</strong> चुनें।",
                    "<strong>Stream settings</strong> में <strong>Default stream key</strong> चुना रहने दें और <strong>Copy</strong> दबाएँ।",
                    "AFK Live में paste करके save करें।",
                ]),
            ]),
            ("Default key और scheduled live की key", "default-key", [
                ("p", "<strong>Default stream key</strong> हर live के लिए वही रहती है, इसलिए 24/7 stream के लिए यही रखें। YouTube Studio के "
                      "<strong>Manage</strong> tab में schedule किए गए live की अपनी अलग key हो सकती है; वो key डालेंगे तो stream उसी "
                      "scheduled live में जाएगा। जब तक आप यही न चाहें, Stream tab की default key ही copy करें।"),
            ]),
            ("आम गलतियाँ", "galtiyan", [
                ("ul", [
                    "<strong>Key की जगह Stream URL paste करना।</strong> Key के ठीक ऊपर Stream URL (<code>rtmp://a.rtmp.youtube.com/live2</code>) "
                    "दिखता है। AFK Live को वो पहले से पता है; सिर्फ़ key paste करें।",
                    "<strong>गलत चैनल।</strong> एक से ज़्यादा चैनल हैं, तो copy करने से पहले YouTube Studio में ऊपर दाईं ओर की फ़ोटो देख लें।",
                ]),
            ]),
            ("Key किसी को न दें", "private", [
                ("p", "जिसके पास आपकी key है, वो आपके चैनल पर stream कर सकता है। इसे WhatsApp group या screenshot में भी share न करें। "
                      "AFK Live key को encrypted रखता है और सिर्फ़ आख़िरी चार अक्षर दिखाता है। अगर किसी और ने देख ली हो, तो YouTube Studio "
                      "में नई key बनाएँ (Stream key menu → <strong>Create new stream key</strong>) और AFK Live में नई key paste करें।"),
            ]),
        ],
        "faq": [
            ("क्या stream key हर live के लिए बदलनी पड़ती है?",
             "नहीं। Default stream key हर live के लिए वही रहती है, इसलिए AFK Live में एक बार डाल दी तो बार-बार copy नहीं करनी पड़ती। "
             "Key तभी बदलें जब किसी और ने उसे देख लिया हो।"),
            ("Key डाल दी, फिर भी YouTube पर कुछ नहीं दिख रहा?",
             "YouTube Studio में Auto-start चालू करें, और देखें कि key scheduled live की तो नहीं। पूरी checklist: "
             + en_link("/help/live-here-not-on-youtube", "AFK Live पर live, YouTube पर नहीं") + "।"),
        ],
        "related": ["24-ghante-live", "mobile-se-live"],
    },
    {
        "slug": "mobile-se-live",
        "seo_title": "मोबाइल से YouTube Live कैसे करें (50 subscribers) | AFK Live",
        "for": ["mobile-gaming"],
        "title": "मोबाइल से YouTube live कैसे करें",
        "summary": "mobile se youtube live kaise kare: YouTube app के लिए 50 subscribers का नियम, कम subscribers पर stream key से live, और AFK Live क्या जोड़ता है।",
        "sections": [
            ("", "", [
                ("p", "फ़ोन से YouTube पर live जाने के दो रास्ते हैं: <strong>YouTube app</strong> का Go live बटन, या कोई "
                      "<strong>streaming app</strong> (जैसे Larix Broadcaster या PRISM Live Studio) जो stream key से भेजता है। "
                      "दोनों के नियम अलग हैं।"),
            ]),
            ("YouTube app से live: 50 subscribers का नियम", "50-subscribers", [
                ("ul", [
                    "YouTube app से mobile live के लिए चैनल पर <strong>कम से कम 50 subscribers</strong> चाहिए।",
                    "50 से 999 subscribers वाले चैनलों के mobile live पर YouTube <strong>दर्शकों की गिनती सीमित</strong> कर सकता है, "
                    "और live की recording default रूप से private रहती है।",
                    "हर तरह के live के लिए: चैनल verified हो, पिछले 90 दिनों में live streaming पर कोई रोक न लगी हो, और आपकी उम्र "
                    "कम से कम 16 साल हो।",
                ]),
                ("p", "स्रोत: YouTube Help, " + ext(YT_MOBILE, "Create a live stream on mobile") + " और "
                      + ext(YT_START, "Get started with live streaming") + " (27 अक्टूबर 2026 को देखा)। YouTube ये नियम बदल सकता है; "
                      "live से पहले वहाँ देख लें।"),
            ]),
            ("50 से कम subscribers? Stream key से live", "stream-key-se", [
                ("p", "YouTube की " + ext(YT_ENCODER, "encoder से live वाली help पेज") + " पर कोई subscriber गिनती नहीं लिखी, और उसी पेज "
                      "पर Larix Broadcaster और PRISM Live Studio फ़ोन के encoders के रूप में दिए हैं। ये apps YouTube app का Go live बटन "
                      "इस्तेमाल नहीं करते; ये आपकी stream key से भेजते हैं, जैसे PC पर OBS। दर्शकों की गिनती वाली सीमा YouTube ने app वाले "
                      "mobile live के लिए लिखी है, encoder वाली पेज पर ऐसी कोई सीमा नहीं लिखी।"),
                ("p", "ये रास्ता AFK Live के बिना भी काम करता है: Larix में सीधे YouTube की stream key डाल सकते हैं। ऊपर के बाकी नियम "
                      "(verified चैनल, 90 दिन, 16 साल) तब भी लागू हैं।"),
            ]),
            ("AFK Live क्या जोड़ता है", "afk-live", [
                ("ul", [
                    "आपका फ़ोन एक stream AFK Live को भेजता है; हम उस पर आपका logo, scenes और overlays जोड़कर YouTube पर भेजते हैं, "
                    "चाहें तो एक साथ दूसरे platforms पर भी।",
                    "Signal चला जाए तो live बंद नहीं होता: दर्शकों को music के साथ आपकी Be right back स्क्रीन दिखती है, और फ़ोन जुड़ते "
                    "ही आप वापस।",
                    "AFK Live में पहले Go live दबा दें; जब तक फ़ोन न जुड़े, दर्शकों को Starting soon स्क्रीन दिखती है।",
                    "Scenes किसी दूसरे फ़ोन या tablet से, remote control link से बदल सकते हैं।",
                ]),
                ("p", "AFK Live YouTube के नियम नहीं बदलता: आपका चैनल live कर सकता है या नहीं, ये YouTube तय करता है, और दर्शकों की "
                      "गिनती या monetisation पर हम कोई वादा नहीं करते।"),
            ]),
            ("Larix से setup (फ़्री, Android और iPhone)", "larix", [
                ("ol", [
                    '<a href="/register" hreflang="en">फ़्री account बनाएँ</a>, stream बनाते समय <strong>Live from my phone</strong> चुनें।',
                    '<strong>Destinations</strong> tab पर YouTube जोड़ें (<a href="/hi/help/youtube-stream-key">key कहाँ मिलेगी</a>)।',
                    "Larix Broadcaster install करें। <strong>Phone setup</strong> tab पर <strong>Larix</strong> चुनें और QR code फ़ोन के "
                    "camera से scan करें। यही पेज फ़ोन पर खुला है, तो <strong>Open in Larix</strong> दबाएँ।",
                    "AFK Live में <strong>Go live</strong> दबाएँ, फिर Larix में record बटन।",
                ]),
                ("p", "PRISM और दूसरे apps के steps, और कमज़ोर signal के लिए SRT: " + en_link("/help/phone-streaming", "फ़ोन से live") + "।"),
            ]),
        ],
        "faq": [
            ("क्या 50 से कम subscribers पर फ़ोन से live कर सकते हैं?",
             "YouTube app से नहीं: उसके लिए 50 चाहिए। Larix या PRISM जैसे app से stream key के ज़रिए (सीधे, या AFK Live से होकर) YouTube "
             "की encoder वाली help पेज कोई subscriber गिनती नहीं माँगती, बशर्ते चैनल verified हो और live पर कोई रोक न हो।"),
            ("फ़ोन से live में कितना data लगता है?",
             "1080p पर ज़्यादातर apps 4 से 6 Mbps भेजते हैं, यानी एक घंटे में करीब 1.8 से 2.7 GB। 720p पर इससे कम लगता है। लंबे live "
             "के लिए Wi-Fi या बड़ा data pack रखें।"),
            ("Network कमज़ोर हो तो क्या करें?",
             "Larix में SRT इस्तेमाल करें: Phone setup tab पर Other (SRT) चुनें। ये कमज़ोर 4G पर बेहतर टिकता है, और 1000 ms का SRT "
             "buffer ज़्यादातर 4G के लिए ठीक है। फिर भी signal जाए, तो दर्शकों को आपकी Be right back स्क्रीन दिखती है।"),
        ],
        "related": ["youtube-stream-key", "24-ghante-live"],
    },
]

HI_BY_SLUG = {a["slug"]: a for a in HI_ARTICLES}


def use_cases():
    """site/use-cases.py's pages by slug (loaded only when writing, so the two scripts don't import each other at load)."""
    spec = importlib.util.spec_from_file_location("site_use_cases", HERE / "use-cases.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return {p["slug"]: p for p in module.PAGES}


def ld(data):
    """JSON-LD for a <script> block; "<" escaped so no text can end the script early."""
    return json.dumps(data, ensure_ascii=False).replace("<", "\\u003c")


def crumbs(*items):
    return {
        "@context": "https://schema.org",
        "@type": "BreadcrumbList",
        "itemListElement": [{"@type": "ListItem", "position": i, "name": n, "item": u} for i, (n, u) in enumerate(items, 1)],
    }


def tech_article(title, summary, url, lang):
    return {
        "@context": "https://schema.org",
        "@type": "TechArticle",
        "headline": title,
        "description": summary,
        "url": url,
        "mainEntityOfPage": url,
        "inLanguage": lang,
        "dateModified": UPDATED_ISO,
        "author": {"@type": "Organization", "name": "AFK Live", "url": "https://afklive.in/"},
        "publisher": {"@type": "Organization", "name": "AFK Live", "url": "https://afklive.in/",
                      "logo": {"@type": "ImageObject", "url": "https://afklive.in/site/brand/apple-touch-icon.png"}},
        "image": "https://afklive.in/site/brand/og.png",
    }


def esc(s):
    return html.escape(s, quote=True)


def slugify(h):
    return "".join(c if c.isalnum() else "-" for c in h.lower()).strip("-").replace("--", "-")


def block(kind, content):
    if kind == "p":
        return f"    <p>{content}</p>"
    if kind == "ways":
        # The ways to stay live, each with what it costs a month: the cost first, because it's what people compare.
        rows = "\n".join(
            f'      <li class="help-way">\n'
            f'        <p class="help-way-cost"><span class="help-way-price">{esc(cost)}</span> <span class="help-way-unit">{esc(unit)}</span></p>\n'
            f'        <div>\n          <h3>{esc(name)}</h3>\n          <p>{desc}</p>\n        </div>\n'
            f'      </li>'
            for name, cost, unit, desc in content)
        return f'    <ol class="help-ways">\n{rows}\n    </ol>'
    items = "\n".join(f"      <li>{i}</li>" for i in content)
    return f"    <{kind}>\n{items}\n    </{kind}>"


def head(title, og_title, desc, path, structured, lang="en", alternates=None, faq=False):
    """alternates: (English path, Hindi path) when the page has a twin in the other language."""
    url = f"https://afklive.in{path}"
    scripts = "\n".join(f'  <script type="application/ld+json">{ld(d)}</script>' for d in structured)
    links = ""
    if alternates:
        en, hi = alternates
        links = (f'\n  <link rel="alternate" hreflang="en" href="https://afklive.in{en}" />'
                 f'\n  <link rel="alternate" hreflang="hi" href="https://afklive.in{hi}" />'
                 f'\n  <link rel="alternate" hreflang="x-default" href="https://afklive.in{en}" />')
    locale = ""
    if lang == "hi":
        locale = ('\n  <meta property="og:locale" content="hi_IN" />'
                  + ('\n  <meta property="og:locale:alternate" content="en_IN" />' if alternates else ""))
        note = "\n  <!-- Hindi help (site/help.py HI_ARTICLES). Every string needs a native speaker's review: docs/translations.md. -->"
        fonts = '\n  <!-- Devanagari only on Hindi pages: English pages don\'t download it. -->\n  <link rel="stylesheet" href="./src/devanagari.css" />'
    else:
        note = fonts = ""
    faq_ld = "\n  <!-- jsonld:faq -->" if faq else ""
    return f"""<!doctype html>
<html lang="{lang}">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />{note}
  <title>{esc(title)}</title>
  <meta name="description" content="{esc(desc)}" />
  <link rel="canonical" href="{url}" />{links}
  <meta property="og:title" content="{esc(og_title)}" />
  <meta property="og:description" content="{esc(desc)}" />
  <meta property="og:type" content="article" />
  <meta property="og:url" content="{url}" />{locale}
<!-- include:head -->{fonts}
  <script type="module" src="./src/main.ts"></script>
{scripts}{faq_ld}
</head>
<body>
<!-- generated by site/help.py: edit the text there -->
<!-- include:{"header-hi" if lang == "hi" else "header"} -->"""


def foot(lang="en"):
    return f"""<!-- include:{"footer-hi" if lang == "hi" else "footer"} -->
</body>
</html>
"""


FOOT = foot()


def hindi_link(a):
    """On an English article: a link to its Hindi twin, or to the Hindi article on the same question."""
    if not a.get("hi"):
        return ""
    h = HI_BY_SLUG[a["hi"]]
    text = "हिन्दी में पढ़ें" if h.get("en") == a["slug"] else f"हिन्दी में: {esc(h['title'])}"
    return f'\n  <p class="mt-4" lang="hi"><a href="/hi/help/{h["slug"]}" hreflang="hi" class="link">{text}</a></p>'


def article(a):
    body = []
    for h, blocks in a["sections"]:
        if h:
            body.append(f'    <h2 id="{slugify(h)}">{esc(h)}</h2>')
        body.extend(block(k, c) for k, c in blocks)
    related = "\n".join(
        f'      <li><a href="/help/{r}" class="link">{esc(BY_SLUG[r]["title"])}</a></li>' for r in a["related"])
    pages = use_cases()
    made_for = "\n".join(
        f'      <li><a href="/for/{f}" class="link">{esc(pages[f]["title"])}</a></li>' for f in a["for"])
    content = "\n".join(body)
    url = f'https://afklive.in/help/{a["slug"]}'
    structured = [
        crumbs(("Home", "https://afklive.in/"), ("Help", "https://afklive.in/help"), (a["title"], url)),
        tech_article(a["title"], a["summary"], url, "en"),
    ]
    twin = a.get("hi") if HI_BY_SLUG.get(a.get("hi"), {}).get("en") == a["slug"] else None
    alternates = ("/help/" + a["slug"], "/hi/help/" + twin) if twin else None
    return f"""{head(a["seo_title"], a["title"], a["summary"], "/help/" + a["slug"], structured, alternates=alternates)}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-10 sm:px-6">
  <nav aria-label="Breadcrumb" class="text-sm"><a href="/" class="link">Home</a> <span aria-hidden="true" class="text-muted">/</span> <a href="/help" class="link">Help</a> <span aria-hidden="true" class="text-muted">/</span> <span class="text-muted" aria-current="page">{esc(a["title"])}</span></nav>
  <h1 class="mt-4 text-[34px] font-extrabold leading-[1.05] tracking-[-0.03em] sm:text-[48px]">{esc(a["title"])}</h1>
  <p class="mt-4 text-lg text-muted">{esc(a["summary"])}</p>{hindi_link(a)}
  <article class="help-article mt-8">
{content}
  </article>
  <aside class="mt-14 border-t border-line pt-8">
    <h2 class="text-lg font-semibold">Related</h2>
    <ul class="mt-3 space-y-2">
{related}
    </ul>
    <h2 class="mt-8 text-lg font-semibold">Made for</h2>
    <ul class="mt-3 space-y-2">
{made_for}
    </ul>
    <p class="mt-8 text-sm text-muted">Still stuck? Email <a href="mailto:support@afklive.in" class="link">support@afklive.in</a>; we reply within a day. Updated {UPDATED}.</p>
  </aside>
</main>
{FOOT}"""


def item(a):
    return (f'      <li data-help-item data-search="{esc((a["title"] + " " + a["summary"] + " " + a["keywords"]).lower())}">\n'
            f'        <a href="/help/{a["slug"]}" class="block rounded-2xl border border-line bg-panel p-5 hover:border-muted/60">\n'
            f'          <span class="block text-lg font-semibold">{esc(a["title"])}</span>\n'
            f'          <span class="mt-1 block text-muted">{esc(a["summary"])}</span>\n'
            f'        </a>\n'
            f'      </li>')


def index():
    groups = []
    for g in GROUPS:
        items = "\n".join(item(a) for a in ARTICLES if a["group"] == g)
        groups.append(f'  <section data-help-group class="mt-10">\n    <h2 class="text-xl font-bold tracking-tight">{esc(g)}</h2>\n'
                      f'    <ul class="mt-4 space-y-3">\n{items}\n    </ul>\n  </section>')
    sections = "\n".join(groups)
    desc = "How to find your YouTube stream key, fix a live stuck on Preparing, stream from your phone, and keep a 24/7 live within YouTube's rules."
    structured = [crumbs(("Home", "https://afklive.in/"), ("Help", "https://afklive.in/help"))]
    return f"""{head("Help with 24/7 YouTube live streams | AFK Live", "AFK Live help", desc, "/help", structured, alternates=("/help", "/hi/help"))}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-14 sm:px-6" data-help>
  <h1 class="text-[40px] font-extrabold leading-none tracking-[-0.035em] sm:text-[56px]">Help with your YouTube live</h1>
  <p class="mt-4 text-lg text-muted">Short answers to what creators ask us most: setting up YouTube, streaming from your phone, and keeping a 24/7 live within YouTube's rules.</p>
  <p class="mt-4" lang="hi"><a href="/hi/help" hreflang="hi" class="link">हिन्दी में मदद</a></p>
  <form role="search" class="mt-8" action="/help" method="get" data-help-form>
    <label for="help-q" class="label">Search help</label>
    <input id="help-q" name="q" type="search" class="field" placeholder="Try &ldquo;stream key&rdquo; or &ldquo;Preparing&rdquo;" autocomplete="off" data-help-search />
  </form>
  <p class="mt-3 text-sm text-muted" aria-live="polite" data-help-count></p>
  <div data-help-list>
{sections}
  </div>
  <p class="mt-6 hidden text-muted" data-help-empty>Nothing matches that. Email <a href="mailto:support@afklive.in" class="link">support@afklive.in</a> and we'll help.</p>
  <p class="mt-14 text-sm text-muted">Is something down for everyone? See the <a href="/status" class="link">status page</a>.</p>
</main>
{FOOT}"""


# --- Hindi pages ---------------------------------------------------------------------------------------

HI_HOME = ("होम", "https://afklive.in/hi")
HI_HELP = ("मदद", "https://afklive.in/hi/help")

CTA_HI = """  <section class="night help-cta mt-14" aria-labelledby="cta-title">
    <h2 id="cta-title" class="text-2xl font-bold tracking-tight">अपना चैनल आज रात से live रखें</h2>
    <p class="mt-2 text-muted">10 घंटे फ़्री, कोई कार्ड नहीं। फिर Creator plan ₹499 महीना, UPI से।</p>
    <div class="mt-5 flex flex-wrap items-center gap-x-6 gap-y-3">
      <a href="/register" hreflang="en" data-funnel="start" class="btn-primary h-12 w-full px-8 text-[17px] sm:w-auto">फ़्री में शुरू करें</a>
      <a href="/hi#pricing" class="link">सारे plans और कीमतें</a>
    </div>
  </section>"""


def hi_breadcrumb(*items):
    """The visible breadcrumb: every item but the last is a link."""
    parts = [f'<a href="{u.replace("https://afklive.in", "")}" class="link">{esc(n)}</a>' for n, u in items[:-1]]
    parts.append(f'<span class="text-muted" aria-current="page">{esc(items[-1][0])}</span>')
    sep = ' <span aria-hidden="true" class="text-muted">/</span> '
    return f'<nav aria-label="Breadcrumb" class="text-sm">{sep.join(parts)}</nav>'


def hi_faq(entries):
    """Visible questions (<details>), marked data-faq: the build writes FAQPage markup from exactly these."""
    def styled(answer):  # outside .help-article, so links say they're links themselves
        return re.sub(r'<a ([^>]*?)>', lambda m: m.group(0) if 'class=' in m.group(1) else f'<a {m.group(1)} class="link">', answer)
    rows = "\n".join(f"      <details>\n        <summary>{esc(q)}</summary>\n        <p>{styled(a)}</p>\n      </details>" for q, a in entries)
    return (f'  <section class="mt-14" aria-labelledby="faq-title">\n'
            f'    <h2 id="faq-title" class="text-2xl font-bold tracking-tight">अक्सर पूछे जाने वाले सवाल</h2>\n'
            f'    <div class="faq mt-4" data-faq>\n{rows}\n    </div>\n  </section>')


def hi_article(a):
    body = []
    for h, hid, blocks in a["sections"]:
        if h:
            body.append(f'    <h2 id="{hid}">{esc(h)}</h2>')
        body.extend(block(k, c) for k, c in blocks)
    content = "\n".join(body)
    path = f'/hi/help/{a["slug"]}'
    url = f"https://afklive.in{path}"
    related = "\n".join(
        f'      <li><a href="/hi/help/{r}" class="link">{esc(HI_BY_SLUG[r]["title"])}</a></li>' for r in a["related"])
    pages = use_cases()
    made_for = "\n".join(
        f'      <li><a href="/for/{f}" hreflang="en" class="link">{esc(pages[f]["title"])}</a> <span class="text-muted">(अंग्रेज़ी में)</span></li>'
        for f in a["for"])
    english = ""
    if a.get("en"):
        english = f'\n  <p class="mt-4" lang="en"><a href="/help/{a["en"]}" hreflang="en" class="link">Read in English</a></p>'
    structured = [crumbs(HI_HOME, HI_HELP, (a["title"], url)), tech_article(a["title"], a["summary"], url, "hi")]
    alternates = ("/help/" + a["en"], path) if a.get("en") else None
    faq = hi_faq(a["faq"]) if a.get("faq") else ""
    return f"""{head(a["seo_title"], a["title"], a["summary"], path, structured, "hi", alternates, faq=bool(faq))}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-10 sm:px-6">
  {hi_breadcrumb(HI_HOME, HI_HELP, (a["title"], url))}
  <h1 class="mt-4 text-[32px] font-extrabold leading-[1.15] tracking-[-0.02em] sm:text-[44px]">{esc(a["title"])}</h1>
  <p class="mt-4 text-lg text-muted">{esc(a["summary"])}</p>{english}
  <article class="help-article mt-8">
{content}
  </article>
{faq}
{CTA_HI}
  <aside class="mt-14 border-t border-line pt-8">
    <h2 class="text-lg font-semibold">ये भी पढ़ें</h2>
    <ul class="mt-3 space-y-2">
{related}
      <li><a href="/hi/help" class="link">हिन्दी में सारी मदद</a></li>
    </ul>
    <h2 class="mt-8 text-lg font-semibold">किसके लिए</h2>
    <ul class="mt-3 space-y-2">
{made_for}
    </ul>
    <p class="mt-8 text-sm text-muted">कुछ अटका है? <a href="mailto:support@afklive.in" class="link">support@afklive.in</a> पर लिखें, हिन्दी या अंग्रेज़ी में; हम एक दिन में जवाब देते हैं। Updated {HI_UPDATED}।</p>
  </aside>
</main>
{foot("hi")}"""


def hi_index():
    items = "\n".join(
        f'      <li>\n'
        f'        <a href="/hi/help/{a["slug"]}" class="block rounded-2xl border border-line bg-panel p-5 hover:border-muted/60">\n'
        f'          <span class="block text-lg font-semibold">{esc(a["title"])}</span>\n'
        f'          <span class="mt-1 block text-muted">{esc(a["summary"])}</span>\n'
        f'        </a>\n'
        f'      </li>' for a in HI_ARTICLES)
    desc = "YouTube पर 24 घंटे live, recorded वीडियो live चलाना, stream key कहाँ मिलेगी और मोबाइल से live: आसान हिन्दी में, step-by-step।"
    structured = [crumbs(HI_HOME, HI_HELP)]
    return f"""{head("YouTube Live की मदद, हिन्दी में | AFK Live", "AFK Live: हिन्दी में मदद", desc, "/hi/help", structured, "hi", ("/help", "/hi/help"))}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-10 sm:px-6">
  {hi_breadcrumb(HI_HOME, HI_HELP)}
  <h1 class="mt-4 text-[36px] font-extrabold leading-[1.15] tracking-[-0.02em] sm:text-[52px]">YouTube live की मदद, हिन्दी में</h1>
  <p class="mt-4 text-lg text-muted">Creators हमसे जो सबसे ज़्यादा पूछते हैं, उनके सीधे जवाब: 24 घंटे live, पहले से बने वीडियो को live चलाना, stream key, और फ़ोन से live।</p>
  <ul class="mt-10 space-y-3">
{items}
  </ul>
  <p class="mt-10 text-muted">बाकी लेख अभी अंग्रेज़ी में हैं: <a href="/help" hreflang="en" class="link">सारी मदद (अंग्रेज़ी में)</a>। कुछ और पूछना है? <a href="mailto:support@afklive.in" class="link">support@afklive.in</a> पर हिन्दी में भी लिख सकते हैं।</p>
{CTA_HI}
</main>
{foot("hi")}"""


if __name__ == "__main__":
    (HERE / "help.html").write_text(index())
    for a in ARTICLES:
        (HERE / f'help-{a["slug"]}.html').write_text(article(a))
    (HERE / "hi-help.html").write_text(hi_index())
    for a in HI_ARTICLES:
        (HERE / f'hi-help-{a["slug"]}.html').write_text(hi_article(a))
    print("wrote", len(ARTICLES) + len(HI_ARTICLES) + 2, "pages")
