from playwright.sync_api import sync_playwright, expect

def run(playwright):
    browser = playwright.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()

    try:
        # 1. Register
        print("Navigating to register...")
        page.goto("http://localhost:8080/register")
        page.fill("input[name='name']", "Test User")
        page.fill("input[name='email']", "test@test.com")
        page.fill("input[name='password']", "password123")
        page.click("button[type='submit']")

        # Wait for navigation
        page.wait_for_load_state('networkidle')
        print(f"URL after register: {page.url}")

        # If redirected to login (or on login page)
        if "/login" in page.url:
            print("Logging in...")
            page.fill("input[name='username']", "test@test.com")
            page.fill("input[name='password']", "password123")
            page.click("button[type='submit']")
            page.wait_for_load_state('networkidle')

        print(f"URL after login: {page.url}")

        # Ensure we are on studio or redirect to it
        if "/studio" not in page.url and "/app.html" not in page.url:
             print("Forcing navigation to studio...")
             page.goto("http://localhost:8080/studio")
             page.wait_for_load_state('networkidle')

        # 2. Go to Live Stream view
        print("Navigating to Live Stream...")
        # Sidebar click
        page.click("a[data-target='view-stream']")

        # Wait for visibility
        expect(page.locator("#view-stream")).to_be_visible()

        # 3. Click "My Library" button
        print("Clicking My Library...")
        my_lib_btn = page.locator("#tabStreamAudioMyLib")
        expect(my_lib_btn).to_be_visible()
        my_lib_btn.click()

        # 4. Verify Content
        print("Verifying My Library section...")
        section = page.locator("#streamAudioMyLibSection")
        expect(section).to_be_visible()
        expect(section).not_to_have_class("hidden")

        # Take screenshot
        print("Taking screenshot...")
        page.screenshot(path="verification/verification_music.png")

    except Exception as e:
        print(f"Error: {e}")
        page.screenshot(path="verification/error.png")
    finally:
        browser.close()

with sync_playwright() as playwright:
    run(playwright)
