from playwright.sync_api import sync_playwright
import os
import json
import subprocess
import time
import signal

def run(playwright):
    # Start server
    server_process = subprocess.Popen(
        ["python3", "-m", "http.server", "8000", "--directory", "afklive-web/src/main/resources/static"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    time.sleep(2) # Wait for server to start

    try:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 800})

        # Mock API responses
        page.route("**/api/user-info*", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body=json.dumps({
                "username": "testuser",
                "email": "test@example.com",
                "name": "Test User",
                "picture": "https://via.placeholder.com/150",
                "plan": {"name": "Free", "storageUsed": 100, "storageLimit": 1000},
                "unpaidBalance": 10.50,
                "creditLimit": 50.0,
                "enabled": True
            })
        ))

        page.route("**/api/channels", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body='[]'
        ))

        page.route("**/api/pricing*", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body='{"plans": []}'
        ))

        page.route("**/api/stream/history*", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body=json.dumps({
                "data": {
                    "content": [
                        {
                            "id": 1,
                            "title": "Test Stream Game",
                            "destinationName": "YouTube Gaming",
                            "startTime": "2023-10-27T10:00:00Z",
                            "endTime": "2023-10-27T12:00:00Z",
                            "accumulatedCost": 2.50
                        },
                        {
                            "id": 2,
                            "title": "Another Stream",
                            "destinationName": "Twitch",
                            "startTime": "2023-10-26T15:00:00Z",
                            "endTime": "2023-10-26T15:30:00Z",
                            "accumulatedCost": 0.65
                        }
                    ]
                }
            })
        ))

        # Load the page
        print(f"Loading http://localhost:8000/app.html")
        page.goto("http://localhost:8000/app.html")

        # Click Settings in sidebar
        page.locator("a.menu-item:has-text('Settings')").click()

        # Wait for settings to appear
        page.wait_for_selector("#view-settings", state="visible")

        # Click Billing tab (Usage)
        page.locator("#view-settings").get_by_text("Billing", exact=True).click()

        # Wait for table to populate
        try:
            page.wait_for_selector("text=Test Stream Game", timeout=5000)
            print("Table populated successfully.")
        except:
            print("Timeout waiting for stream data. Taking screenshot anyway.")

        # Screenshot
        page.screenshot(path="verification/usage_history.png")
        print("Screenshot saved to verification/usage_history.png")

        browser.close()

    finally:
        server_process.terminate()
        server_process.wait()

with sync_playwright() as playwright:
    run(playwright)
