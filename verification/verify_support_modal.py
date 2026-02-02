from playwright.sync_api import sync_playwright, expect

def run(playwright):
    browser = playwright.chromium.launch()
    page = browser.new_page()
    page.goto("http://localhost:8080/app.html")

    # Click Support link in the sidebar
    page.get_by_role("link", name="Support").click()

    # Wait for modal to be visible
    modal = page.locator("#supportModal")
    expect(modal).to_be_visible()

    # Check for file input label
    expect(page.get_by_text("Screenshot or Recording (Optional)")).to_be_visible()

    # Check for file input
    expect(page.locator("#supportAttachment")).to_be_visible()

    # Take screenshot of the modal
    page.screenshot(path="verification/support_modal.png")

    browser.close()

with sync_playwright() as playwright:
    run(playwright)
