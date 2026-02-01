from playwright.sync_api import sync_playwright
import os

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        path = os.path.abspath("afklive-web/src/main/resources/static/app.html")
        page.goto(f"file://{path}")

        # 1. Verify PhonePe text (in payment modal)
        # It is hidden by default. <div id="paymentModal">
        # Remove hidden class
        # Note: There are TWO paymentModals in app.html?
        # One is <!-- PAYMENT CONFIRMATION MODAL (Alpine) --> near end of main content
        # One is <!-- Internal Payment Modal --> near end of file
        # The one I edited was the Alpine one: <div x-show="paymentModalOpen" ...>
        # It has: <i class="fa-solid fa-lock"></i> Secure payment with PhonePe
        # The other one: <div id="paymentModal" class="modal-overlay hidden"> ... Secure payment with Stripe (Wait, I only edited one!)

        # Let me check my edit.
        # I edited: <div class="flex items-center gap-2 text-xs text-gray-500"> ... PhonePe
        # That was in the Alpine modal (x-show="paymentModalOpen").
        # I should check if there is another one.
        # The other one in app.html:
        # <div id="paymentModal" class="modal-overlay hidden"> ...
        # <div style="font-size: 0.9rem; color: #666;"> <i class="fa-solid fa-credit-card"></i> **** **** **** 4242 </div>
        # It doesn't seem to have "Secure payment with Stripe" text in the second modal based on my previous reading?
        # Let's re-read app.html later if needed. For now I check the one I edited.

        # The Alpine modal is: <div x-show="paymentModalOpen" ...>
        # It doesn't have an ID?
        # It is inside #view-settings -> ...

        # I will target by text "Secure payment with PhonePe" to see if it exists and screenshot it.
        # I need to make it visible. It has x-show="paymentModalOpen".
        # It is: <div x-show="paymentModalOpen" class="fixed inset-0 ...">
        # I'll find it by content.

        phonepe_div = page.locator("div", has_text="Secure payment with PhonePe").first
        # Parent of that div is the modal content roughly.
        # I'll just screenshot the element itself or parent.

        # Force it visible
        page.evaluate("""
            const el = Array.from(document.querySelectorAll('div')).find(e => e.textContent.includes('Secure payment with PhonePe'));
            if(el) {
                let modal = el.closest('.fixed');
                if(modal) {
                    modal.style.display = 'flex';
                    modal.classList.remove('hidden');
                }
            }
        """)

        if phonepe_div.is_visible():
            phonepe_div.screenshot(path="verification/app_phonepe.png")

        # 2. Verify AI buttons in Live Studio (Broadcast Info)
        # Show view-stream
        page.evaluate("document.getElementById('view-stream').classList.remove('hidden')")
        # Ensure setup tab content is visible (it is inside x-show="activeTab === 'setup'")
        page.evaluate("""
            document.querySelectorAll('[x-show]').forEach(el => el.style.display = 'block');
        """)

        # Locate the title AI button
        btn = page.locator("#ai-btn-title")
        if btn.count() > 0:
            btn.screenshot(path="verification/app_ai_broadcast.png")

        # 3. Verify AI buttons in Schedule Modal
        # Show scheduleModal
        page.evaluate("document.getElementById('scheduleModal').classList.remove('hidden')")

        # Screenshot the modal body where title/tags are
        page.locator("#scheduleModal .modal-body").screenshot(path="verification/app_ai_schedule.png")

        browser.close()

if __name__ == "__main__":
    run()
