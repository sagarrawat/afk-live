from playwright.sync_api import sync_playwright

def verify_engagement_page():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        # Use existing context to persist login if needed, or start fresh
        # For verification, we assume we can reach the page if auth is bypassed or we mock it.
        # But this app seems to require login.
        # Since we cannot easily mock auth without extensive setup, we will check if the static assets are loaded
        # and if the HTML structure exists, assuming the app might redirect to login but we can check the file directly?
        # No, we must run the server.

        # The server failed to start because of DB connection (Connection refused).
        # We need to mock the DB or just verify the static HTML file directly?
        # Verification instructions say "Start the Application".
        # If application fails to start due to DB, I cannot verifying dynamic pages.

        # However, I can verify the static HTML file by loading it directly,
        # though JS API calls will fail. This is better than nothing for UI structure verification.

        page = browser.new_page()

        # Load the HTML file directly
        import os
        cwd = os.getcwd()
        page.goto(f"file://{cwd}/src/main/resources/static/app.html")

        # Unhide the engagement view for verification
        page.evaluate("document.getElementById('view-community').classList.remove('hidden')")

        # Check for new elements
        # Sidebar
        page.wait_for_selector(".comm-sidebar")
        page.wait_for_selector(".comm-nav-item:has-text('All Comments')")

        # List Panel
        page.wait_for_selector("#commListPanel")

        # Detail Panel
        page.wait_for_selector("#commDetailView")

        # Check if "Buffer-like" grid layout class exists
        page.wait_for_selector(".community-grid-layout")

        # Take screenshot
        page.screenshot(path="/home/jules/verification/engagement_ui.png")
        print("Screenshot taken at /home/jules/verification/engagement_ui.png")

        browser.close()

if __name__ == "__main__":
    verify_engagement_page()
