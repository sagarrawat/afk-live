# Translations

The public site has one translated page so far: the homepage in Hindi at `/hi` (MK-1). The app,
the help centre, the use-case pages, pricing, legal pages and emails are English only.

## Where the words live

| What | File | Notes |
| --- | --- | --- |
| Hindi homepage copy | `afklive-ui/site/hi.html` | Same sections, ids and script hooks as `index.html` (a test compares them) |
| Hindi header, footer | `afklive-ui/site/partials/header-hi.html`, `footer-hi.html` | |
| Hindi static plan cards | `afklive-ui/site/partials/pricing-hi.html` | Generated: must equal `planCards(STATIC_PLANS, 'MONTHLY', 'hi')` (tested). Regenerate if you change the dictionary's plan strings |
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

## Reviewer checklist

Open https://afklive.in/hi on a phone (and a laptop), and the English page next to it.

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
