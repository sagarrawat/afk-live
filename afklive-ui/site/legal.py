"""Builds site/privacy.html, site/terms.html, site/refund-policy.html and site/contact.html from the text
below (plain-language legal pages in the site's style). Run: python3 site/legal.py. Have a lawyer review
changes before publishing.

The contact page's business details and grievance officer come from the server's settings, not from
here: each <div data-fill="..."> holds what's shown when a setting is empty, and the web app replaces
it when /contact is served (ContactPage.java). Keep those divs flat (no nested div)."""
import html, pathlib, re
HERE = pathlib.Path(__file__).resolve().parent
UPDATED = "27 October 2026"
SUPPORT = '<a href="mailto:support@afklive.in">support@afklive.in</a>'
GRIEVANCE = '<a href="/contact#grievance-redressal">grievance officer</a>'

PRIVACY = [
 ("", ["This policy explains what AFK Live collects, why, who helps us run the service, and the choices you have. AFK Live is run from Bengaluru, India, and follows India's Digital Personal Data Protection Act, 2023."]),
 ("What we collect", [
  "<strong>Your account:</strong> your name, email address and a protected (hashed) password, or your Google account's name and email if you sign in with Google. Your time zone, currency and alert choices.",
  "<strong>What you stream:</strong> the videos, music and images you upload, your streams' settings, overlays and schedules, and the destinations you add. Stream keys are stored encrypted and are never shown in full again.",
  "<strong>Billing:</strong> your plan, payments and invoices, including the billing name, address and GSTIN you give us. Card and UPI details are handled by our payment provider, PhonePe; we never see or store them.",
  "<strong>Running the service:</strong> technical records such as IP addresses, browser type, sign-in times, stream health and error logs, used to keep streams up and accounts secure.",
  "<strong>Devices for alerts:</strong> if you turn on alerts on a device, the address your browser gives us for sending them.",
 ]),
 ("How we use it", [
  "To run your streams and send them where you choose, to bill you and issue GST invoices, to send the alerts and emails you've asked for, to keep accounts secure (for example two-step sign-in and rate limits), and to fix problems. We don't sell your data and we don't show ads.",
 ]),
 ("Who helps us", [
  "<strong>DigitalOcean</strong> hosts our servers and your files (in India). <strong>Cloudflare</strong> runs our domain's DNS. <strong>PhonePe</strong> processes payments. <strong>Zoho ZeptoMail</strong> sends our emails. <strong>Google</strong> handles Google sign-in if you use it. The platforms you stream to (YouTube, Facebook and others) receive your stream under their own terms and privacy policies.",
 ]),
 ("Cookies", [
  "We use only the cookies needed to sign you in, remember you if you ask us to, and protect forms from forgery. No advertising or tracking cookies.",
 ]),
 ("How long we keep it", [
  "As long as your account is open. When you delete your account, it's switched off straight away and deleted with its files after 7 days (you can change your mind until then). Invoices are kept as Indian tax law requires; personal details are removed from them where the law allows.",
 ]),
 ("Your choices", [
  "You can download a copy of your data and delete your account in Settings. You can turn email and device alerts off, per kind of alert. For anything else, including correcting your data or a complaint, write to <a href=\"mailto:support@afklive.in\">support@afklive.in</a>; we reply within 30 days. If you're not happy with our answer, write to our " + GRIEVANCE + ".",
 ]),
 ("Children", ["AFK Live isn't meant for anyone under 18 without a parent or guardian managing the account."]),
 ("Changes", ["If we change this policy in a way that matters, we'll email account holders before it takes effect."]),
]

TERMS = [
 ("", ["These terms are the agreement between you and AFK Live for using afklive.in and its apps. By creating an account you accept them."]),
 ("Your account", [
  "Give accurate details and keep your password safe; turn on two-step sign-in if you can. You're responsible for what happens under your account.",
 ]),
 ("What you stream", [
  "You must own or have the rights to everything you upload and stream, including music. You're responsible for following the rules of the platforms you stream to, such as the <a href=\"https://www.youtube.com/t/terms\">YouTube Terms of Service</a>. Don't use AFK Live for anything illegal, for content that infringes others' rights, harasses people or endangers children, or to overload or attack any service. We may stop a stream or suspend an account that breaks these rules.",
 ]),
 ("Google sign-in", [
  "If you sign in with Google, you also agree to Google's terms, and Google's <a href=\"https://policies.google.com/privacy\">privacy policy</a> applies to that sign-in.",
 ]),
 ("Plans and payments", [
  "Paid plans are charged monthly or yearly in advance. Upgrades start straight away, with credit for the unused part of your current plan. Downgrades and cancellations take effect at the end of the period you've paid for. If a renewal isn't paid, you get 3 days of grace before the account moves to the Free plan; nothing is deleted. Indian prices include GST and every payment gets a GST invoice.",
  "Cancellations, plan changes and refunds work as our <a href=\"/refund-policy\">cancellation and refund policy</a> explains; it's part of these terms.",
 ]),
 ("The service", [
  "We work hard to keep streams up around the clock, with automatic recovery and alerts, but we can't promise the service will never be interrupted, and platforms like YouTube can end or limit a stream on their side. We may change features; if we remove something you pay for, we'll tell you first.",
 ]),
 ("Ending", [
  "You can delete your account at any time in Settings. We may suspend or close an account that breaks these terms, telling you why unless the law stops us.",
 ]),
 ("Liability", [
  "To the extent the law allows, AFK Live isn't liable for indirect or consequential losses, such as lost income or views, and our total liability is limited to what you paid us in the 3 months before the claim.",
 ]),
 ("Law", ["These terms are governed by the laws of India, and the courts of Bengaluru, Karnataka have jurisdiction."]),
 ("Contact and complaints", ["<a href=\"mailto:support@afklive.in\">support@afklive.in</a>. AFK Live, Bengaluru, Karnataka, India. Our full business details are on the <a href=\"/contact\">contact page</a>. If you have a complaint we haven't resolved, write to our " + GRIEVANCE + "."]),
]

REFUND = [
 ("", ["This policy explains how paying, cancelling, changing plans and refunds work on AFK Live. It's part of our <a href=\"/terms\">terms of use</a>. The Free plan costs nothing, so it has nothing to refund."]),
 ("How you pay", [
  "Paid plans are bought for a month or a year at a time, in advance, with a one-off UPI or card payment through our payment provider, PhonePe. Indian prices include 18% GST, and every payment gets a GST invoice in Settings &gt; Billing.",
  "<strong>We never charge you automatically.</strong> Nothing renews by itself: 3 days before your plan ends we remind you, and you renew by making a new payment (renewal opens 7 days before the end).",
 ]),
 ("Cancelling", [
  "You can cancel any time in Settings &gt; Billing. You keep your plan until the end of the period you've paid for, then your account moves to the Free plan. Your streams, files and settings are not deleted.",
  "You can change your mind before the period ends: undo the cancellation in Settings &gt; Billing and nothing changes.",
  "Cancelling doesn't refund the rest of the period you've already paid for.",
 ]),
 ("Changing plans", [
  "<strong>Upgrades</strong> (a bigger plan, or yearly instead of monthly) start straight away. The unused part of your current period is credited against the new price, worked out by the time left, and a new period starts on the day you pay. You always pay at least &#8377;1.",
  "<strong>Downgrades</strong> (a smaller plan, or monthly instead of yearly) start at the end of the period you've paid for. Nothing is charged until you renew on the new plan.",
 ]),
 ("If you don't renew", [
  "When a period ends without a renewal, you get 3 days of grace on your plan. If you still haven't paid after that, your account moves to the Free plan. Nothing is deleted, and you can upgrade again any time.",
 ]),
 ("When we refund", [
  "<strong>Charged twice or by mistake:</strong> we refund the extra payment in full.",
  "<strong>Paid but your plan didn't change:</strong> if money left your account but your plan wasn't applied, we either apply it or refund the payment in full, whichever you prefer.",
  "<strong>A problem on our side stopped your streams:</strong> if an outage or fault at AFK Live stopped you streaming, tell us within 30 days and we'll refund or credit the affected time (credit is added as extra days on your plan).",
  "<strong>Failed payments:</strong> if a payment fails, you aren't charged. If money was taken from your bank anyway, it's returned to you automatically by your bank or PhonePe, usually within 5 to 7 working days. If it hasn't come back after that, write to us and we'll chase it.",
 ]),
 ("When we don't refund", [
  "We don't refund a period you've started just because you didn't use it, changed your mind, or cancelled partway through. We don't refund when a platform you stream to, such as YouTube, ends or limits your stream on its side, or when we stop a stream or suspend an account for breaking our <a href=\"/terms\">terms</a>.",
 ]),
 ("How to ask for a refund", [
  "Write to " + SUPPORT + " from the email on your account, or use <strong>Contact support</strong> in the app (Help &gt; Contact support). Include your invoice number (it looks like AFK/2026-27/000042) or the payment's date and amount, and what went wrong. We reply within 2 working days.",
 ]),
 ("How refunds are paid", [
  "Refunds go back to the UPI account or card you paid with, through PhonePe. We start an approved refund within 7 days. After that it usually reaches you within 5 to 7 working days, depending on your bank. A refund can be for the full payment or part of it. If we refund a whole period, that plan ends and your account moves to the Free plan.",
  "Refunds include the GST you paid on the refunded amount, and we issue a credit note against your GST invoice.",
 ]),
 ("Complaints", [
  "If you're not happy with how we handled a payment or refund, write to our " + GRIEVANCE + ". They acknowledge complaints within 24 hours and resolve them within 15 days.",
 ]),
]

CONTACT = [
 ("", ["We're a small team in India. The quickest way to reach us is email; we reply within a day."]),
 ("Support", [
  "<strong>Email:</strong> " + SUPPORT + "",
  "<strong>In the app:</strong> sign in and use <a href=\"/studio/help?contact\">Help &gt; Contact support</a>. Your message reaches us with your account details, so we can help faster.",
  "Is a stream down for everyone? The <a href=\"/status\">status page</a> shows how AFK Live is running right now.",
  "Questions about payments, cancelling or refunds: see our <a href=\"/refund-policy\">cancellation and refund policy</a>.",
 ]),
 ("Business details", [
  '<div data-fill="business"><p><strong>AFK Live</strong><br />Bengaluru, Karnataka, India</p></div>',
 ]),
 ("Grievance redressal", [
  "If you have a complaint about AFK Live, such as a payment, a refund, your data or content on the service, and our support team hasn't resolved it, write to our grievance officer. Tell us your account's email, what happened and what you'd like us to do.",
  '<div data-fill="grievance"><p><strong>Grievance officer:</strong> write to <a href="mailto:support@afklive.in">support@afklive.in</a> with &ldquo;Grievance&rdquo; in the subject line.</p></div>',
  "We acknowledge every complaint within 24 hours and resolve it within 15 days of receiving it. This follows the Consumer Protection (E-Commerce) Rules, 2020 and the Information Technology (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021.",
 ]),
]

def anchor(heading):
    return re.sub(r"[^a-z0-9]+", "-", heading.lower()).strip("-")

def render(slug, title, desc, sections, numbered=True):
    body = []
    n = 0
    for h, paras in sections:
        if h:
            n += 1
            label = f"{n}. {html.escape(h)}" if numbered else html.escape(h)
            body.append(f'      <h2 id="{anchor(h)}">{label}</h2>')
        for p in paras:
            # A <div data-fill> block is filled in by the server (see the note at the top); others are paragraphs.
            body.append(f'      {p}' if p.startswith("<div") else f'      <p>{p}</p>')
    content = "\n".join(body)
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{title}: AFK Live</title>
  <meta name="description" content="{html.escape(desc)}" />
  <link rel="canonical" href="https://afklive.in/{slug}" />
  <meta property="og:title" content="{title}: AFK Live" />
  <meta property="og:description" content="{html.escape(desc)}" />
  <meta property="og:type" content="website" />
  <meta property="og:url" content="https://afklive.in/{slug}" />
<!-- include:head -->
  <script type="module" src="./src/main.ts"></script>
</head>
<body>
<!-- generated by site/legal.py: edit the text there -->
<!-- include:header -->
<main class="mx-auto max-w-3xl px-4 pb-24 pt-14 sm:px-6">
  <h1 class="text-[40px] font-extrabold leading-none tracking-[-0.035em] sm:text-[56px]">{title}</h1>
  <p class="mt-4 text-sm text-muted">Last updated {UPDATED}</p>
  <article class="legal mt-10">
{content}
  </article>
</main>
<!-- include:footer -->
</body>
</html>
"""

if __name__ == "__main__":
    (HERE / "privacy.html").write_text(render("privacy", "Privacy policy", "What AFK Live collects, why, who helps us run the service, how long we keep it, cookies, and your choices about your data.", PRIVACY))
    (HERE / "terms.html").write_text(render("terms", "Terms of use", "The agreement for using AFK Live: your account, what you stream, plans and payments, the service, ending it, liability and the law.", TERMS))
    (HERE / "refund-policy.html").write_text(render("refund-policy", "Cancellation and refund policy", "How cancelling, upgrades, downgrades and refunds work on AFK Live: when we refund, how to ask, and how long refunds take to reach you.", REFUND))
    (HERE / "contact.html").write_text(render("contact", "Contact us", "How to reach AFK Live: support email, help in the app, our business details and how to raise a complaint with our grievance officer.", CONTACT, numbered=False))
    print("ok")
