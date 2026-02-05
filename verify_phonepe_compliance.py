
from playwright.sync_api import sync_playwright

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()

        # Terms of Use - Refund Policy & Address
        page.goto("http://localhost:8000/terms.html")
        page.set_viewport_size({"width": 1280, "height": 800})

        refund_policy = page.locator("h2:has-text('Refund and Cancellation Policy')")
        refund_policy.scroll_into_view_if_needed()
        page.screenshot(path="verification_terms_refund.png", full_page=False)

        contact_section = page.locator("h2:has-text('Contact Us')")
        contact_section.scroll_into_view_if_needed()
        page.screenshot(path="verification_terms_address.png", full_page=False)

        # Privacy Policy - Address
        page.goto("http://localhost:8000/privacy.html")
        contact_privacy = page.locator("h2:has-text('Contact Us')")
        contact_privacy.scroll_into_view_if_needed()
        page.screenshot(path="verification_privacy_address.png", full_page=False)

        browser.close()

if __name__ == "__main__":
    run()
