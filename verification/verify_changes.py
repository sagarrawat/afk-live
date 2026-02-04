import os
from playwright.sync_api import sync_playwright

def verify(page):
    # Absolute path to app.html
    cwd = os.getcwd()
    app_html = f"file://{cwd}/afklive-web/src/main/resources/static/app.html"

    print(f"Navigating to {app_html}")
    page.goto(app_html)

    # Wait for Alpine and content
    page.wait_for_timeout(2000)

    # 1. Verify Info Icons in Stream View
    print("Verifying Info Icons...")
    # Ensure we are in stream view (should be default or localStorage might affect it)
    page.evaluate("switchView('stream')")
    page.wait_for_timeout(500)

    # Check for info icon next to Quality
    quality_icon = page.locator("label", has_text="Quality").locator("i.fa-circle-info")
    if quality_icon.is_visible():
        print("Quality Info Icon found.")
    else:
        print("Quality Info Icon NOT found.")

    # 2. Verify No Channel Overlay in Analytics View
    print("Verifying No Channel Overlay...")
    # Set selectedChannelId to null explicitly
    page.evaluate("window.selectedChannelId = null;")
    page.evaluate("switchView('analytics')")
    page.wait_for_timeout(1000)

    overlay = page.locator("#noChannelOverlay")
    if overlay.is_visible():
        print("Overlay is visible.")
        print(f"Overlay text: {overlay.inner_text()}")
    else:
        print("Overlay is NOT visible.")

    page.screenshot(path="verification/verification.png")

if __name__ == "__main__":
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        verify(page)
        browser.close()
