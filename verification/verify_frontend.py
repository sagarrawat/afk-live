from playwright.sync_api import sync_playwright
import os

def test_frontend():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        # Verify app.html loads
        cwd = os.getcwd()
        app_path = f"file://{cwd}/afklive-web/src/main/resources/static/app.html"
        print(f"Loading {app_path}")
        page.goto(app_path)

        # Take screenshot of app.html
        page.screenshot(path="verification/app_html.png")
        print("Screenshot of app.html saved.")

        browser.close()

    # Verify register.html source
    with open("afklive-web/src/main/resources/templates/register.html", "r") as f:
        content = f.read()
        if 'th:action="@{/register}"' in content:
            print("SUCCESS: register.html contains th:action")
        else:
            print("FAILURE: register.html missing th:action")
            exit(1)

if __name__ == "__main__":
    test_frontend()
