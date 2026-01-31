import os
from playwright.sync_api import sync_playwright

def handle_pricing_api(route):
    print("Serving pricing API mock")
    route.fulfill(json={
        "currency": "INR",
        "plans": [
            {
                "id": "FREE",
                "title": "Free",
                "price": "₹0",
                "period": "/mo",
                "features": ["1 Channel", "10 Scheduled Posts", "Basic Streaming"]
            },
            {
                "id": "ESSENTIALS",
                "title": "Essentials",
                "price": "₹499",
                "period": "/mo",
                "features": ["3 Channels", "Unlimited Scheduling", "Analytics", "HD Streaming"],
                "badge": "50% OFF"
            }
        ]
    })

def handle_payment_initiate(route):
    # Verify payload
    data = route.request.post_data_json
    print(f"Payment initiated with: {data}")
    if data.get('amount') == 49900: # 499 * 100
        print("Amount correct: 49900")
        route.fulfill(json={"redirectUrl": "https://sandbox.phonepe.com/pay"})
    else:
        print(f"Amount INCORRECT: {data.get('amount')}")
        route.fulfill(status=400, json={"message": "Invalid amount"})

def run(playwright):
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page()

    # Route static files
    base_path = "afklive-web/src/main/resources/static"

    def handle_static(route, path):
        try:
            with open(path, "rb") as f:
                content = f.read()
            # Determine content type
            if path.endswith(".css"):
                headers = {"Content-Type": "text/css"}
            elif path.endswith(".js"):
                headers = {"Content-Type": "application/javascript"}
            else:
                headers = {"Content-Type": "text/html"}

            route.fulfill(body=content, headers=headers)
        except Exception as e:
            print(f"Error serving {path}: {e}")
            route.continue_()

    page.route("**/pricing", lambda route: handle_static(route, f"{base_path}/pricing.html"))
    page.route("**/css/landing.css", lambda route: handle_static(route, f"{base_path}/css/landing.css"))
    page.route("**/css/theme.css", lambda route: handle_static(route, f"{base_path}/css/theme.css"))
    page.route("**/js/toast.js", lambda route: handle_static(route, f"{base_path}/js/toast.js"))

    # Route APIs
    page.route("**/api/pricing?country=IN", handle_pricing_api)
    page.route("**/api/payment/initiate", handle_payment_initiate)

    # Mock logged in user
    page.route("**/api/user-info", lambda route: route.fulfill(json={
        "email": "test@example.com",
        "plan": {"id": "FREE"}
    }))

    print("Navigating to pricing page...")
    # Go to page
    page.goto("http://localhost/pricing")

    # Wait for grid to load
    try:
        page.wait_for_selector(".price-card", timeout=5000)
    except:
        print("Timeout waiting for price cards. Screenshotting error state.")
        page.screenshot(path="verification/error.png")
        browser.close()
        return

    # Verify Badge
    badge = page.locator(".badge-discount")
    if badge.is_visible() and "50% OFF" in badge.inner_text():
        print("SUCCESS: Badge verification passed")
    else:
        print("FAILURE: Badge verification FAILED")

    page.screenshot(path="verification/verification_pricing_before.png")

    # Click Upgrade on Essentials (index 1)
    # The button is inside the second .price-card
    print("Clicking Upgrade button...")
    upgrade_btn = page.locator(".price-card").nth(1).get_by_role("button", name="Upgrade")
    upgrade_btn.click(force=True)

    # Wait for redirect
    page.wait_for_timeout(2000)
    print(f"Current URL: {page.url}")

    if "sandbox.phonepe.com" in page.url:
         print("SUCCESS: Redirected to PhonePe Sandbox")
    else:
         print("FAILURE: Did not redirect to PhonePe Sandbox")

    page.screenshot(path="verification/verification_pricing.png")
    browser.close()

if __name__ == "__main__":
    with sync_playwright() as playwright:
        run(playwright)
