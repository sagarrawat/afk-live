"""
MK-1: checks the built public site in a real browser (Playwright + Chromium): the English and Hindi
homepages have no horizontal scroll from 375 px up, the language switch is visible at every width,
Devanagari renders with Noto Sans Devanagari, and screenshots are saved for review.

Build the site first (cd afklive-ui && npx vite build -c vite.site.config.ts), then:

    python3 verification/verify_hindi_homepage.py [screenshot-dir]

It serves afklive-web/target/classes/static the way Spring does for these pages (/ and /hi forward to
site/index.html and site/hi.html). API calls get a 404, so the static plan cards show.
"""
import http.server
import os
import sys
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent / "afklive-web/target/classes/static"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "hindi-homepage-screens")
ROUTES = {"/": "/site/index.html", "/hi": "/site/hi.html"}


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=str(ROOT), **k)

    def translate_path(self, path):
        clean = path.split("?")[0].split("#")[0]
        return super().translate_path(ROUTES.get(clean, clean))

    def log_message(self, *a):
        pass


def main():
    if not (ROOT / "site/hi.html").exists():
        sys.exit(f"Build the site first: {ROOT}/site/hi.html is missing")
    OUT.mkdir(parents=True, exist_ok=True)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    failures = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for name, path in (("en", "/"), ("hi", "/hi")):
            for width, height in ((375, 812), (390, 844), (768, 1024), (1024, 768), (1440, 900)):
                page = browser.new_page(viewport={"width": width, "height": height}, reduced_motion="reduce")
                page.goto(base + path, wait_until="networkidle")
                page.evaluate("document.fonts.ready")
                over = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
                if over > 0:
                    wide = page.evaluate("""() => [...document.querySelectorAll('body *')]
                        .filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
                        .slice(0, 5).map(e => e.tagName + '.' + e.className)""")
                    failures.append(f"{name} {width}px scrolls sideways by {over}px: {wide}")
                switch = page.locator("[data-lang-switch]")
                if not switch.is_visible():
                    failures.append(f"{name} {width}px: language switch not visible")
                else:
                    box = switch.bounding_box()
                    if box["x"] + box["width"] > width:
                        failures.append(f"{name} {width}px: language switch off screen")
                if name == "hi":
                    ok = page.evaluate("document.fonts.check('800 32px \"Noto Sans Devanagari Variable\"', 'आरती')")
                    loaded = page.evaluate("[...document.fonts].some(f => f.family.includes('Noto Sans Devanagari') && f.status === 'loaded')")
                    if not (ok and loaded):
                        failures.append(f"hi {width}px: Noto Sans Devanagari not loaded")
                if (width, height) in ((1440, 900), (390, 844)):
                    page.screenshot(path=str(OUT / f"{name}-{width}x{height}-fold.png"))
                    page.screenshot(path=str(OUT / f"{name}-{width}x{height}-full.png"), full_page=True)
                page.close()
        browser.close()
    server.shutdown()
    if failures:
        print("\n".join(failures))
        sys.exit(1)
    print(f"OK: no sideways scroll, switch visible, Devanagari font loaded. Screenshots in {OUT}")


if __name__ == "__main__":
    main()
