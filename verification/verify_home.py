from playwright.sync_api import sync_playwright
import os

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        path = os.path.abspath("afklive-web/src/main/resources/static/home.html")
        page.goto(f"file://{path}")

        # Take screenshot of footer
        footer = page.locator("footer")
        footer.screenshot(path="verification/home_footer.png")

        # Take screenshot of pricing
        pricing = page.locator("#pricing")
        pricing.screenshot(path="verification/home_pricing.png")

        browser.close()

if __name__ == "__main__":
    run()
