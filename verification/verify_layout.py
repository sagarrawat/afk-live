from playwright.sync_api import sync_playwright, expect
import os

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        # Point to the local static HTML file
        # In this env, it's at /app/src/main/resources/static/app.html
        # We need absolute path for file://
        cwd = os.getcwd()
        file_path = f"file://{cwd}/src/main/resources/static/app.html"

        print(f"Navigating to {file_path}")
        page.goto(file_path)

        # Wait for load
        page.wait_for_load_state("domcontentloaded")

        # 1. Click "Live Studio" in sidebar
        print("Clicking Live Studio...")
        page.click("text=Live Studio")

        # Verify new layout elements visible
        print("Verifying Stream Studio Layout...")
        expect(page.locator(".stream-studio-grid")).to_be_visible()
        expect(page.locator(".studio-sidebar-left")).to_be_visible()
        expect(page.locator(".studio-center")).to_be_visible()
        expect(page.locator(".studio-sidebar-right")).to_be_visible()

        # 2. Click "Upload New" to check if Optimize modal trigger is conceptually reachable
        # (Though we mock behavior here, we check UI existence)

        # 3. Take screenshot of the new layout
        output_path = f"{cwd}/verification/stream_studio_layout.png"
        page.screenshot(path=output_path)
        print(f"Screenshot saved to {output_path}")

        browser.close()

if __name__ == "__main__":
    run()
