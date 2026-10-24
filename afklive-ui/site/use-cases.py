"""MK-3: one page per kind of channel, from the content below. Run after editing it:

    python3 site/use-cases.py

Writes site/for-<slug>.html (served at /for/<slug>); vite.site.config.ts builds every for-*.html.
Pages share the site's head, header, footer and illustrations (site/partials).
"""
import html
import pathlib

HERE = pathlib.Path(__file__).resolve().parent

PAGES = [
    {
        "slug": "lofi",
        "template": ("Lofi and chill", [
            "A now-playing line with the track's name, bottom left",
            "A small clock in your time zone",
            "Starting soon, be right back and thanks-for-watching screens",
            "Videos and songs shuffled, with a 4-second blend between tracks",
        ], []),
        "illo": "illo-lofi",
        "title": "24/7 lofi and music channels",
        "h1": "A 24/7 music channel, without a computer left on",
        "lede": "Upload your visuals and your tracks once. AFK Live loops them on YouTube day and night from our servers, with no gaps between files and the music at one steady level.",
        "points": [
            ("Gapless, forever", "Videos and tracks play back to back with no black frames or silence between them, and loop for as long as you like."),
            ("Music under your visuals", "A short visual loop under hours of music: the music has its own playlist, order and shuffle."),
            ("Smooth between tracks", "Crossfade up to 20 seconds, and every track brought to the same loudness, so nobody reaches for the volume."),
            ("Show what's playing", "A now-playing line on screen with the track's name, updated as it changes."),
        ],
        "steps": [
            ("Upload", "Your loop video and your tracks. Big files upload in parts and resume if the connection drops."),
            ("Arrange", "Pick the music, shuffle it, add your logo and a now-playing line."),
            ("Go live", "Paste your YouTube stream key and press Go live. Close your laptop."),
        ],
        "faq": [
            ("Do I need the rights to the music?", "Yes. Use music you made or licensed for streaming; YouTube's Content ID still applies to live streams."),
            ("Can I change the playlist while it's live?", "Yes. Changes apply after the current track, so viewers never hear a cut."),
        ],
    },
    {
        "slug": "devotional",
        "template": ("Bhajans and aarti", [
            "Now playing and what's next, in Hindi or English",
            "The clock in your time zone",
            "Your order kept, for an aarti sequence",
            "Screens for before, during a break and after, in saffron",
        ], ["Morning and evening aarti (5:30 to 7 am, 6:30 to 8 pm)", "All-day bhajans with a midday pause (5 am to 1 pm, 4 to 10 pm)"]),
        "illo": "illo-devotional",
        "title": "Temples and devotional channels",
        "h1": "Aarti and bhajans live every day, right on time",
        "lede": "Set the schedule once: morning aarti at 5:30, evening bhajans at 7. AFK Live starts and stops the stream on its own, every day, and tells you if anything goes wrong.",
        "points": [
            ("Daily and weekly schedules", "Pick the days and times; skip a date for a festival or a special broadcast."),
            ("On time without anyone at the desk", "Streams start within a minute of the time you set, in your time zone."),
            ("Families everywhere can watch", "Send the same stream to YouTube and Facebook at once."),
            ("Told when something goes wrong", "An alert on your phone or by email if a stream doesn't start or goes down, and when it's back."),
        ],
        "steps": [
            ("Upload", "Recordings of aarti, bhajans or katha, as many as you like."),
            ("Schedule", "Choose the days and times, and skip any date you like."),
            ("Add destinations", "Paste the stream key from YouTube, and Facebook if you like. That's it."),
        ],
        "faq": [
            ("Can someone else manage it?", "Yes: anyone with the account can change the schedule from a phone."),
            ("What if the power or internet goes out at the temple?", "Nothing happens to the stream: it runs on our servers, not at the temple."),
        ],
    },
    {
        "slug": "mobile-gaming",
        "template": ("Phone gaming", [
            "Starting soon, be right back and thanks-for-watching screens",
            "A small clock while you play",
            "Words on screen in Hindi or English, or retype them in Tamil, Telugu, Bengali and more",
        ], ["Evening session (8 to 10 pm)"]),
        "illo": "illo-gaming",
        "title": "Mobile gaming streams",
        "h1": "Stream from your phone with overlays, like a PC setup",
        "lede": "Your phone sends one clean stream to AFK Live; we add your logo, scenes and ticker in the cloud and send it to YouTube. When your signal drops, viewers see your Be right back screen instead of an ended stream.",
        "points": [
            ("No 50-subscriber wait", "YouTube's own app needs 50 subscribers to go live from a phone. Streaming through AFK Live doesn't."),
            ("Never drops", "Lose signal in a lift or a tunnel and viewers see your BRB scene with music; you're back the moment your phone reconnects."),
            ("Real overlays from a phone", "Logo, text, ticker, clock and countdown, plus Starting soon, BRB and Ending scenes."),
            ("Vertical videos look right", "Play vertical clips on a landscape stream over a blurred background instead of black bars."),
        ],
        "steps": [
            ("Scan the QR code", "Into Larix, PRISM or Streamlabs on your phone. No typing server addresses."),
            ("Set your scenes", "Add your logo and overlays; choose the BRB screen and music."),
            ("Go live", "Start streaming from the app; switch scenes from a second phone with the remote."),
        ],
        "faq": [
            ("Does it use more of my data?", "No more than streaming straight to YouTube: your phone sends one stream, the overlays are added on our side."),
            ("Which games?", "Anything on your phone: BGMI, Free Fire, Clash of Clans, chess. If your phone can show it, you can stream it."),
        ],
    },
    {
        "slug": "podcasts",
        "illo": "illo-podcasts",
        "title": "Podcasts and reruns",
        "h1": "Keep your channel live between new episodes",
        "lede": "Turn your back catalogue into a channel that's always on. Past episodes play on a loop or on a schedule, so new viewers always find you live.",
        "points": [
            ("Your archive, always on", "Playlists of past episodes, in order or shuffled, looped as long as you like."),
            ("Premieres on a schedule", "Go live with a new episode at a set time, then fall back to reruns."),
            ("Everywhere at once", "YouTube, Facebook, Twitch, Kick and more from one upload."),
            ("Proof it stays up", "Uptime for 24 hours, 7 and 30 days for every stream."),
        ],
        "steps": [
            ("Upload episodes", "Video or audio with a still picture; up to 10 GB a file."),
            ("Build the playlist", "Drag to reorder; set how many times it loops."),
            ("Go live or schedule", "Start now or set a time; we handle the rest."),
        ],
        "faq": [
            ("Can I go live with a new episode and come back to reruns?", "Yes: schedule the premiere, and a second slot for the reruns after it."),
            ("Will it look right if my episodes are different sizes?", "Yes: choose bars, fill or a blurred background for anything that isn't the stream's shape."),
        ],
    },
]


def esc(s):
    return html.escape(s, quote=True)


def page(p):
    points = "\n".join(
        f'          <div><dt class="text-lg font-semibold">{esc(t)}</dt><dd class="mt-1 text-muted">{esc(d)}</dd></div>'
        for t, d in p["points"])
    steps = "\n".join(
        f'      <li class="border-t-2 border-ink pt-5"><span class="display tnum text-[40px] font-extrabold leading-none">{i}</span>'
        f'<h3 class="mt-4 text-xl font-bold tracking-tight">{esc(t)}</h3><p class="mt-1 text-muted">{esc(d)}</p></li>'
        for i, (t, d) in enumerate(p["steps"], 1))
    template = ""
    if p.get("template"):
        name, layers, presets = p["template"]
        items = "\n".join(f'        <li>{esc(i)}</li>' for i in layers)
        sched = ""
        if presets:
            sched = ('\n      <p class="mt-6 font-semibold">Schedules you can start from</p>\n      <ul class="mt-2 list-disc space-y-1 pl-5 text-muted">\n'
                     + "\n".join(f'        <li>{esc(x)}</li>' for x in presets) + "\n      </ul>")
        template = f"""
  <section aria-labelledby="template" class="mx-auto max-w-6xl px-4 pb-20 sm:px-6">
    <div class="rounded-lg border border-line p-6 sm:p-8">
      <h2 id="template" class="text-2xl font-bold tracking-tight">Start from the {esc(name)} template</h2>
      <p class="mt-2 max-w-2xl text-muted">One tap in the studio sets up the look below. Your own logo and text stay, and you can change everything after.</p>
      <ul class="mt-5 list-disc space-y-1 pl-5">
{items}
      </ul>{sched}
    </div>
  </section>
"""
    faq = "\n".join(
        f'        <div class="border-b border-line py-5"><dt class="font-semibold">{esc(q)}</dt><dd class="mt-1.5 text-muted">{esc(a)}</dd></div>'
        for q, a in p["faq"])
    title = f'{p["title"]}: AFK Live'
    url = f'https://afklive.in/for/{p["slug"]}'
    others = "\n".join(
        f'        <li><a href="/for/{o["slug"]}" class="link">{esc(o["title"])}</a></li>'
        for o in PAGES if o["slug"] != p["slug"])
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{esc(title)}</title>
  <meta name="description" content="{esc(p["lede"])}" />
  <link rel="canonical" href="{url}" />
  <meta property="og:title" content="{esc(p["h1"])}" />
  <meta property="og:description" content="{esc(p["lede"])}" />
  <meta property="og:type" content="website" />
  <meta property="og:url" content="{url}" />
<!-- include:head -->
  <script type="module" src="./src/main.ts"></script>
</head>
<body>
<!-- generated by site/use-cases.py: edit the content there -->
<!-- include:header -->
<main>
  <section class="night">
    <div class="mx-auto grid max-w-6xl items-center gap-10 px-4 py-16 sm:px-6 lg:grid-cols-[1.2fr_1fr] lg:py-20">
      <div>
        <p class="text-sm font-medium text-sun">{esc(p["title"])}</p>
        <h1 class="mt-3 text-[40px] font-extrabold leading-[1.02] tracking-[-0.035em] sm:text-[56px]">{esc(p["h1"])}</h1>
        <p class="mt-6 max-w-xl text-lg text-muted">{esc(p["lede"])}</p>
        <div class="mt-8 flex flex-wrap gap-3">
          <a href="/register" class="btn-primary h-12 px-7 text-[17px]">Start free</a>
          <a href="/pricing" class="btn-secondary h-12 px-7 text-[17px]">See pricing</a>
        </div>
      </div>
      <div class="mx-auto w-full max-w-md">
<!-- include:{p["illo"]} -->
      </div>
    </div>
  </section>

  <section class="mx-auto max-w-6xl px-4 py-20 sm:px-6">
    <h2 class="h-section max-w-2xl">What you get</h2>
    <dl class="mt-10 grid gap-10 sm:grid-cols-2">
{points}
    </dl>
  </section>

{template}
  <section aria-labelledby="steps" class="border-y border-line bg-panel">
    <div class="mx-auto max-w-6xl px-4 py-20 sm:px-6">
      <h2 id="steps" class="h-section">Set it up once</h2>
      <ol class="mt-12 grid gap-10 md:grid-cols-3">
{steps}
      </ol>
    </div>
  </section>

  <section aria-labelledby="faq" class="mx-auto max-w-3xl px-4 py-20 sm:px-6">
    <h2 id="faq" class="text-2xl font-bold tracking-tight">Questions</h2>
    <dl class="mt-4">
{faq}
    </dl>
    <h2 class="mt-14 text-lg font-semibold">Also made for</h2>
    <ul class="mt-3 space-y-2">
{others}
    </ul>
  </section>

  <section class="night">
    <div class="mx-auto flex max-w-6xl flex-col items-start gap-8 px-4 py-20 sm:px-6 md:flex-row md:items-center md:justify-between">
      <div class="flex items-center gap-5">
        <img src="/site/brand/mark.svg" alt="" width="64" height="64" class="h-16 w-16" />
        <h2 class="text-[30px] font-extrabold leading-tight tracking-[-0.03em] sm:text-[40px]">Go to sleep. Stay live.</h2>
      </div>
      <a href="/register" class="btn-primary h-12 px-7 text-[17px]">Start free</a>
    </div>
  </section>
</main>
<!-- include:footer -->
</body>
</html>
"""


if __name__ == "__main__":
    for p in PAGES:
        (HERE / f'for-{p["slug"]}.html').write_text(page(p))
    print("wrote", len(PAGES), "pages")
