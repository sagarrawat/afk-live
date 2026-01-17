from playwright.sync_api import sync_playwright
import time
import random

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        # Grant permissions or modify context if needed, but default should work for audio
        context = browser.new_context()
        page = context.new_page()

        # Generate random user
        rnd = random.randint(1000, 9999)
        email = f"stock{rnd}@example.com"
        password = "Password123!"

        # 1. Register
        print(f"Registering user {email}...")
        page.goto("http://localhost:8080/register")
        page.fill("input[name='name']", "Stock User")
        page.fill("input[name='email']", email)
        page.fill("input[name='password']", password)
        page.click("button[type='submit']")

        time.sleep(2)

        # 2. Login
        print("Logging in...")
        page.goto("http://localhost:8080/login")
        page.fill("input[name='username']", email)
        page.fill("input[name='password']", password)
        page.click("button[type='submit']")

        # Wait for dashboard
        page.wait_for_selector("#view-publish", timeout=10000)
        print("Login successful.")

        # 3. Go to Stream View
        print("Switching to Stream View...")
        page.click("a[data-target='view-stream']")
        time.sleep(1) # Animation

        # 4. Check Stock Music UI
        print("Checking Stock Music UI...")
        assert page.is_visible("#musicUploadSection"), "Upload section should be visible by default"
        assert not page.is_visible("#musicStockSection"), "Stock section should be hidden by default"

        # Click Stock Library Button
        print("Clicking 'Stock Library'...")
        page.click("#btnMusicStock")

        # Verify toggle
        assert not page.is_visible("#musicUploadSection"), "Upload section should be hidden"
        assert page.is_visible("#musicStockSection"), "Stock section should be visible"

        # 5. Check Dropdown Content
        print("Waiting for stock music options...")
        # Since fetch works in curl, if this times out it means fetch in browser failed or JS error
        try:
            page.wait_for_function("document.getElementById('stockMusicSelect').options.length > 1", timeout=5000)
        except Exception as e:
            # Debug: print console logs
            print("Timeout waiting for options. Checking logs...")
            # We can't easily get logs retrospectively unless we listened earlier.
            # Let's check if the element exists and how many options
            count = page.evaluate("document.getElementById('stockMusicSelect').options.length")
            print(f"Option count: {count}")
            raise e

        options = page.locator("#stockMusicSelect option").all_text_contents()
        print(f"Found options: {options}")
        assert "Lofi Chill" in [o.strip() for o in options], "Expected 'Lofi Chill' in options"

        # 6. Select Music and Verify Preview
        print("Selecting 'Lofi Chill'...")
        page.select_option("#stockMusicSelect", label="Lofi Chill")

        # Check audio source
        audio_src = page.get_attribute("#audioPreview", "src")
        print(f"Audio Preview Src: {audio_src}")
        assert "api/stock-music/preview" in audio_src, "Audio src should point to preview API"
        assert "key=stock" in audio_src, "Audio src should contain key parameter"

        # 7. Verify Preview Endpoint works (via request)
        full_url = "http://localhost:8080" + audio_src if audio_src.startswith("/") else audio_src
        print(f"Verifying preview endpoint: {full_url}")

        response = page.request.get(full_url)
        print(f"Preview Response: {response.status}")
        assert response.status == 200, "Preview audio should return 200 OK"
        assert "audio/mpeg" in response.headers['content-type'], "Content type should be audio/mpeg"

        print("Stock Music Verification Passed!")
        browser.close()

if __name__ == "__main__":
    run()
