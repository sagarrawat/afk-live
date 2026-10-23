# AFK Live brand

**Idea: the night shift.** AFK means away from keyboard: you're asleep, at work, off the grid, and the
channel stays on air. The brand is the night your stream runs through on its own.

## Mark and logo

A crescent moon holding an on-air dot: you're away, the channel is live. The wordmark is
Schibsted Grotesk ExtraBold, converted to outlines.

Everything comes from `afklive-ui/brand/build.py` (run `python3 brand/build.py` in `afklive-ui`).
Never redraw the mark by hand. Outputs:

| File | Use |
|---|---|
| `site/public/brand/logo-on-dark.svg`, `logo-on-light.svg` | Full-colour logo (served at `/site/brand/…`) |
| `logo-mono-light.svg`, `logo-mono-dark.svg` | One-colour logo, e.g. on photos or in print |
| `mark.svg`, `app-icon.svg`, `favicon.svg`, `apple-touch-icon.png`, `favicon-32.png` | Mark and icons |
| `og.png` | Link preview, 1200×630 |
| `public/icon-*.png`, `public/manifest.webmanifest` | App install (PWA) icons, served at `/app/…` |
| `afklive-web/…/static/favicon.ico` | `/favicon.ico` |
| `src/components/brand-paths.ts` | The same shapes for React (`Logo`, `Mark` in `components/Logo.tsx`) |

Give the mark clear space of half its height. Don't recolour the moon or the dot except in the
one-colour versions, rotate the mark, or set "AFK Live" in another typeface as a logo.

## Colour

| Name | Hex | Use |
|---|---|---|
| Night | `#10122A` | Dark surfaces, text on light |
| Dusk | `#1A1D3B` | Raised dark surfaces |
| Marigold | `#FFB627` | The brand; actions in dark UI |
| Mist | `#EEF0FA` | Text on dark; light backgrounds |
| On air | `#FF4438` | Only "live" (the mark's dot is the same signal) |

In the light theme, actions are night ink (`#21245C`), so marigold never carries small text on white.
Health keeps its own colours: green, amber (`#FF8A34` on dark), red, always with words.

## Type

- **Schibsted Grotesk** (variable, 400–900) for headings and the logo: sturdy, with newsroom roots.
- **Inter** for everything else, including numbers that tick (`tabular-nums`).

## Imagery

- Product screenshots are the real app rendered with sample data (dark theme, 2× scale), framed with
  a thin Dusk border. Regenerate them after UI changes rather than editing pixels.
- Spot illustrations: flat shapes on a Night tile, Marigold for the subject, Mist and `#3A3F72` for
  lines, at most one On-air red accent. No gradients, no people, no stock photos.
- The homepage's 24-hour dial (`site/src/dial.ts`) is the signature image: a day of one channel's
  schedule in an unbroken ring.

## Voice

Plain and calm. Say what happens ("We start and stop it"), not how it's built. No "RTMP" or
"bitrate" in primary flows (see `CLAUDE.md`).
