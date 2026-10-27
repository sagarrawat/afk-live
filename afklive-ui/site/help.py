"""MK-4: the help centre, written from real support tickets. Run after editing the text below:

    python3 site/help.py

Writes site/help.html (the searchable index, served at /help) and site/help-<slug>.html (served at
/help/<slug>); vite.site.config.ts builds every help*.html. A new article also needs its slug in
PublicPages.HELP_ARTICLES (which also puts it in /sitemap.xml) and an entry in src/lib/help.ts
(site/src/help-pages.test.ts checks all of them). "for" names the use-case pages (site/use-cases.py)
the article helps: it links to them, and they link back.

Article text may use a little inline HTML (links, <strong>); it is ours, not user input.
"""
import html
import importlib.util
import json
import pathlib

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


def esc(s):
    return html.escape(s, quote=True)


def slugify(h):
    return "".join(c if c.isalnum() else "-" for c in h.lower()).strip("-").replace("--", "-")


def block(kind, content):
    if kind == "p":
        return f"    <p>{content}</p>"
    items = "\n".join(f"      <li>{i}</li>" for i in content)
    return f"    <{kind}>\n{items}\n    </{kind}>"


def head(title, og_title, desc, path, structured):
    url = f"https://afklive.in{path}"
    scripts = "\n".join(f'  <script type="application/ld+json">{ld(d)}</script>' for d in structured)
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{esc(title)}</title>
  <meta name="description" content="{esc(desc)}" />
  <link rel="canonical" href="{url}" />
  <meta property="og:title" content="{esc(og_title)}" />
  <meta property="og:description" content="{esc(desc)}" />
  <meta property="og:type" content="article" />
  <meta property="og:url" content="{url}" />
<!-- include:head -->
  <script type="module" src="./src/main.ts"></script>
{scripts}
</head>
<body>
<!-- generated by site/help.py: edit the text there -->
<!-- include:header -->"""


FOOT = """<!-- include:footer -->
</body>
</html>
"""


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
        {
            "@context": "https://schema.org",
            "@type": "TechArticle",
            "headline": a["title"],
            "description": a["summary"],
            "url": url,
            "mainEntityOfPage": url,
            "inLanguage": "en",
            "dateModified": UPDATED_ISO,
            "author": {"@type": "Organization", "name": "AFK Live", "url": "https://afklive.in/"},
            "publisher": {"@type": "Organization", "name": "AFK Live", "url": "https://afklive.in/",
                          "logo": {"@type": "ImageObject", "url": "https://afklive.in/site/brand/apple-touch-icon.png"}},
            "image": "https://afklive.in/site/brand/og.png",
        },
    ]
    return f"""{head(a["seo_title"], a["title"], a["summary"], "/help/" + a["slug"], structured)}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-10 sm:px-6">
  <nav aria-label="Breadcrumb" class="text-sm"><a href="/" class="link">Home</a> <span aria-hidden="true" class="text-muted">/</span> <a href="/help" class="link">Help</a> <span aria-hidden="true" class="text-muted">/</span> <span class="text-muted" aria-current="page">{esc(a["title"])}</span></nav>
  <h1 class="mt-4 text-[34px] font-extrabold leading-[1.05] tracking-[-0.03em] sm:text-[48px]">{esc(a["title"])}</h1>
  <p class="mt-4 text-lg text-muted">{esc(a["summary"])}</p>
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
    return f"""{head("Help with 24/7 YouTube live streams | AFK Live", "AFK Live help", desc, "/help", structured)}
<main class="mx-auto max-w-3xl px-4 pb-24 pt-14 sm:px-6" data-help>
  <h1 class="text-[40px] font-extrabold leading-none tracking-[-0.035em] sm:text-[56px]">Help with your YouTube live</h1>
  <p class="mt-4 text-lg text-muted">Short answers to what creators ask us most: setting up YouTube, streaming from your phone, and keeping a 24/7 live within YouTube's rules.</p>
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


if __name__ == "__main__":
    (HERE / "help.html").write_text(index())
    for a in ARTICLES:
        (HERE / f'help-{a["slug"]}.html').write_text(article(a))
    print("wrote", len(ARTICLES) + 1, "pages")
