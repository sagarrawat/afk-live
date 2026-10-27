# Translations

The public site in Hindi: the homepage at `/hi` (MK-1) and four help articles under `/hi/help`
(MK-4), written for the Hindi and Hinglish searches in `docs/growth-plan.md`. The app, the rest of
the help centre, the use-case pages, pricing, legal pages and emails are English only.

## Where the words live

| What | File | Notes |
| --- | --- | --- |
| Hindi homepage copy | `afklive-ui/site/hi.html` | Same sections, ids and script hooks as `index.html` (a test compares them) |
| Hindi header, footer | `afklive-ui/site/partials/header-hi.html`, `footer-hi.html` | |
| Hindi static plan cards | `afklive-ui/site/partials/pricing-hi.html` | Generated: must equal `planCards(STATIC_PLANS, 'MONTHLY', 'hi')` (tested). Regenerate if you change the dictionary's plan strings |
| Hindi help articles and their index | `afklive-ui/site/help.py` (`HI_ARTICLES`, `hi_index`, `CTA_HI`) | Run `python3 site/help.py` after editing; it writes `site/hi-help*.html`. A question in an article's `faq` is shown on the page and becomes FAQPage markup at build time, so keep each question unique across the site |
| Words scripts write into the page | `afklive-ui/site/src/i18n.ts` (`DICT.en`, `DICT.hi`) | Plan cards, calculator formula and verdict, proof line, incident note, example-stream titles and image descriptions |

Scripts pick the language from `<html lang>`. The English strings in `DICT.en` must not change by
accident: `site/src/english-output.test.ts` holds a snapshot recorded before the dictionary existed.

## Style for Hindi

- Everyday spoken Hindi in Devanagari, the way Indian creators talk about their channels. Not
  formal or Sanskritised: "चैनल", "फ़्री", "पेमेंट", "कीमत", not "प्रणाली", "निःशुल्क", "भुगतान", "मूल्य".
- Keep the English words creators already use, in Latin script: YouTube, live, stream, playlist,
  Go live, UPI, Shorts, upload, update, server, template, schedule, watermark, Sign in.
- Address the reader as "आप". Buttons say what happens ("फ़्री में शुरू करें").
- Template names, plan names (Free, Creator, Pro, Agency) and app screen names stay in English:
  they're what the creator will see in the app.
- Links to pages that are only in English say so: "(अंग्रेज़ी में)".
- Numbers stay in Latin digits with Indian grouping (₹1,499; 1,23,400).

## Needs a native speaker's review

Everything Hindi in this change was written without a native speaker. Before `/hi` is promoted
(linked from ads, shared in groups), someone who speaks Hindi daily should read the page on a phone.
These are the strings I'm least sure of:

| Where | String | Doubt |
| --- | --- | --- |
| Hero headline | आप सो जाइए, आपका चैनल live रहेगा। स्क्रीन पर क्या दिखे, ये आप चुनिए। | Natural? Too long for a headline? |
| Closing band | सो जाइए। Live रहिए। | Does the "Go to sleep. Stay live." punch survive? |
| Niche tabs | भक्ति, फ़ोन game, पढ़ाई और नींद | Would creators say "Devotional" instead of भक्ति? |
| Calculator sentence | मेरा PC [150] watt लेता है, दिन में [24] घंटे चलता है, और बिजली [₹8] प्रति यूनिट है। | Word order around the inputs; "watt लेता है" vs "watt खाता है" |
| Calculator verdict | …और PC की घिसाई अलग से। | "घिसाई" for wear and tear: understood? |
| Reliability heading | उन घंटों के लिए बना, जब कोई नहीं देख रहा | |
| Plan feature | हर stream 2 प्लेटफ़ॉर्म पर | English says "destinations"; is "प्लेटफ़ॉर्म" right for YouTube + Facebook? |
| Plan pitch | असली stream चलाकर देखिए। | |
| Plan badge | सबसे ज़्यादा चुना गया | Long for a badge; "सबसे पॉपुलर"? |
| Pricing heading | सीधी-सादी कीमत | |
| Billing toggle | हर महीने / सालाना, 2 महीने फ़्री | |
| Study panel | …हल्की घड़ी जो अंधेरे कमरे में चुभे नहीं। | "चुभे" for a too-bright clock: natural? |
| FAQ, YouTube rules | …content बार-बार एक जैसा या थोक में बना हुआ न हो… | YouTube's "repetitive or mass-produced" policy wording; must stay accurate |
| FAQ, music | …जब तक उसके owner ने आपका चैनल clear न किया हो। | "clear" as a verb |
| Incidents note | …हर एक का पूरा ब्योरा status पेज पर है। | |
| Image descriptions (alt) | `DICT.hi.niches.*.alt`, the BRB image alt | Read by screen readers; check they make sense spoken |
| Meta description and Open Graph | `hi.html` `<head>` | What shows in Google and in WhatsApp link previews |
| `/hi` help links | `hi.html` (under the FAQ heading), header and footer "मदद" | "मदद, हिन्दी में", "सारी मदद" |

### Hindi help (`/hi/help`, `site/help.py` `HI_ARTICLES`)

Read these on a phone too: they are long, and a reader will follow the steps with YouTube Studio open
in another tab. The facts must stay as they are (prices, YouTube's rules, what AFK Live does and
doesn't do); only the wording is for the reviewer. Strings I'm least sure of:

| Where | String | Doubt |
| --- | --- | --- |
| Every page, title and description | e.g. "YouTube पर 24 घंटे Live कैसे करें (बिना PC) \| AFK Live" | Titles mix Hindi with the English words people search; descriptions start with the Hinglish query in Latin script ("24 ghante live kaise kare: ..."). Natural, or spammy? |
| Stream key page title | YouTube stream key कहाँ मिलेगी | "key" treated as feminine (मिलेगी). Searchers type "kahan milega"; which does a creator say? |
| Pillar, rules | YouTube से कमाई के नियम ... उस content को बाहर रखते हैं जो थोक में बना, बार-बार एक जैसा या कम मेहनत वाला हो। | YouTube's "mass-produced, repetitive or low-effort": must stay accurate |
| Pillar, rules | पहले से बने वीडियो पर “🔴 LIVE” लिखना गुमराह करने वाला माना जा सकता है | "गुमराह करने वाला" for misleading |
| Pillar, ways | अपना VPS (किराए का server); server और traffic, टैक्स अलग | Is "VPS" understood, or should it be "cloud server"? |
| Pillar, ways | ... और कुछ टूटे तो ठीक करना आपके ज़िम्मे है। | |
| Pillar, ways | PC बंद रख सकते हैं, Linux नहीं सीखनी। | Colloquial "नहीं सीखनी" |
| Pillar, steps | App अभी अंग्रेज़ी में है, इसलिए बटनों के नाम यहाँ अंग्रेज़ी में लिखे हैं। | |
| Pillar, FAQ | क्या 24 घंटे live से views और subscribers बढ़ेंगे? / इसकी कोई गारंटी नहीं। | Must not read as a promise either way |
| CTA (every article) | अपना चैनल आज रात से live रखें | "Keep your channel live from tonight": natural? |
| Recorded video | पहले से record किया वीडियो YouTube पर दो तरह से दर्शकों के साथ चलाया जा सकता है | Word order |
| Recorded video | Premiere ... फिर वो आम वीडियो बन जाता है। | "आम वीडियो" for a regular upload |
| Stream key | Browser के menu (⋮) में Desktop site चालू करें। | Chrome on Android says "Desktop site"; other browsers may say it differently |
| Stream key | Key किसी को न दें / इसे WhatsApp group या screenshot में भी share न करें। | |
| Mobile | 50 से 999 subscribers वाले चैनलों के mobile live पर YouTube दर्शकों की गिनती सीमित कर सकता है, और live की recording default रूप से private रहती है। | Must match YouTube's help page (answer 9228390) exactly in meaning |
| Mobile | ये रास्ता AFK Live के बिना भी काम करता है | Honest on purpose; keep the meaning |
| Mobile | AFK Live YouTube के नियम नहीं बदलता | |
| Breadcrumbs | होम / मदद | "होम" vs "मुख्य पेज" |
| Related box | ये भी पढ़ें / किसके लिए | "किसके लिए" heads links to the English use-case pages |
| Index | YouTube live की मदद, हिन्दी में | |
| English pages | हिन्दी में पढ़ें / हिन्दी में: {Hindi title} | On the English stream-key, loops and phone articles |

## Reviewer checklist

Open https://afklive.in/hi and https://afklive.in/hi/help (each article) on a phone (and a laptop),
and the English pages next to them.

- [ ] Every sentence sounds like something a Hindi-speaking creator would say out loud. Mark any
      that sound translated, bookish or Sanskritised.
- [ ] English words are the ones creators really use in Hindi conversation; nothing translated that
      people say in English (and nothing left in English that people say in Hindi).
- [ ] Spelling and matras: nuqta (फ़्री, ज़्यादा), chandrabindu vs anusvara (महँगा, हैं), half letters.
- [ ] Same meaning as the English page, especially promises: what's free, prices, "we can't promise
      monetisation", YouTube's rules, music copyright. Nothing stronger than the English.
- [ ] Gender and number agreement (stream is treated as masculine: "stream चलता है").
- [ ] Buttons: "फ़्री में शुरू करें" leads to sign-up in English; is that surprising? Is "Sign in" fine
      in English?
- [ ] Switch tabs in "आपका चैनल किस बारे में है?" and the hero; the generated text (titles,
      calculator after changing a number, pricing after switching to yearly) reads naturally.
- [ ] Link previews: paste the link into WhatsApp; the title and description read well.
- [ ] Nothing is cut off or overlapping at 375 px; Devanagari marks above and below the line aren't
      clipped.
- [ ] Fix strings directly in the files above, run `cd afklive-ui && npm test`, and if you changed a
      plan string in `i18n.ts`, regenerate `partials/pricing-hi.html` (the test failure shows the
      expected markup).

## Adding a language

Add it to `Lang`/`LANGS` and `DICT` in `i18n.ts` (TypeScript and `hindi-homepage.test.ts` check
every key exists), copy `hi.html` and the `-hi` partials, add the page to `vite.site.config.ts`,
`HomeController`, `SecurityConfig` and `sitemap.xml`, add hreflang links to every version, add it to
the language switch in each header, and load a web font for its script if Inter doesn't cover it.
