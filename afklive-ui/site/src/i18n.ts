/**
 * Words the site's scripts write into the page, per language. The pages themselves are plain HTML
 * (index.html in English, hi.html in Hindi); only text that scripts generate lives here: plan cards,
 * the calculator, the proof line and the example streams. A page's language is its <html lang>.
 *
 * Hindi is everyday spoken Hindi in Devanagari, keeping the English words creators use (YouTube, live,
 * stream, playlist, UPI). Every Hindi string needs a native speaker's review: docs/translations.md.
 */
export type Lang = 'en' | 'hi';
export const LANGS: Lang[] = ['en', 'hi'];

export type NicheId = 'lofi' | 'devotional' | 'gaming' | 'podcasts' | 'study';

export interface Dict {
  /** Number formatting: Latin digits with Indian grouping in both languages. */
  locale: string;
  plans: {
    streamsAtOnce(n: number): string;
    upTo(quality: string, fps: number): string;
    hoursAMonth(h: number): string;
    unlimitedHours: string;
    destinations(n: number): string;
    storage(gb: string): string;
    watermark: string;
    noWatermark: string;
    pitch: Record<'FREE' | 'ESSENTIALS' | 'PRO' | 'AGENCY', string>;
    mostPicked: string;
    perMonth: string;
    perYear: string;
    startWith(name: string): string;
    startFree: string;
    /** The free plan's price, or null to show the catalogue's own label. */
    freePrice: string | null;
    /** After "/ month" on rupee prices (BI-9: every price includes GST). */
    inclGst: string;
    /** Read out before a struck-through list price (screen readers). */
    listPrice: string;
    /** BI-9 launch offer lines: real list price, real offer price, how long, and who it's for. */
    offerMonthly(name: string, percent: number, months: number, price: string, list: string): string;
    offerYearly(name: string, percent: number, price: string, list: string): string;
    offerWho(day: string): string;
  };
  calc: {
    hours: string;
    days: string;
    units: string;
    more(diff: string): string;
    same: string;
    less: string;
  };
  proof: {
    liveAndHours(live: string, hours: string): string;
    live(live: string): string;
    hours(hours: string): string;
    noIncidents: string;
    incidents(n: number): string;
  };
  creators: { liveWith(niche: string): string };
  niches: Record<NicheId, { title: string; alt: string }>;
}

const en: Dict = {
  locale: 'en-IN',
  plans: {
    streamsAtOnce: (n) => `${n} stream${n === 1 ? '' : 's'} at once`,
    upTo: (q, fps) => `Up to ${q} at ${fps} fps`,
    hoursAMonth: (h) => `${h} hours a month`,
    unlimitedHours: 'Unlimited hours, 24/7',
    destinations: (n) => `${n} destination${n === 1 ? '' : 's'} per stream`,
    storage: (gb) => `${gb} of storage`,
    watermark: 'Small AFK Live mark on stream',
    noWatermark: 'No watermark',
    pitch: {
      FREE: 'Try it with a real stream.',
      ESSENTIALS: 'One channel, live around the clock.',
      PRO: 'Several channels, or 60 fps gaming.',
      AGENCY: 'Run streams for clients.',
    },
    mostPicked: 'Most picked',
    perMonth: 'month',
    perYear: 'year',
    startWith: (name) => `Start with ${name}`,
    startFree: 'Start free',
    freePrice: null,
    inclGst: 'incl. GST',
    listPrice: 'List price',
    offerMonthly: (name, pct, months, price, list) =>
      `${name}: ${pct}% off your first ${months === 1 ? 'month' : `${months} months`} — ${price}/month, then ${list}.`,
    offerYearly: (name, pct, price, list) => `${name}: ${pct}% off your first year — ${price}, then ${list}/year.`,
    offerWho: (day) => `For new subscribers, until ${day}.`,
  },
  calc: {
    hours: 'h',
    days: 'days',
    units: 'units',
    more: (d) => `That’s ${d} a month more than Creator, before the PC itself wears out.`,
    same: 'About the same as Creator. The difference is what happens when the power or internet goes.',
    less: 'Electricity alone costs less than Creator. What you pay us for is the stream staying on through power cuts, updates and internet drops, with your PC off.',
  },
  proof: {
    liveAndHours: (l, h) => `${l} streams live on AFK Live right now. ${h}+ hours streamed so far.`,
    live: (l) => `${l} streams live on AFK Live right now.`,
    hours: (h) => `${h}+ hours streamed on AFK Live so far.`,
    noIncidents: 'No platform incidents in the last 90 days.',
    incidents: (n) => `${n} platform incident${n === 1 ? '' : 's'} in the last 90 days, each written up on the status page.`,
  },
  creators: { liveWith: (niche) => `${niche} · Live with AFK Live` },
  niches: {
    lofi: {
      title: 'Rainy night lofi, live 24/7',
      alt: 'A lofi stream: a desk lamp and a rainy window at night, with "Now playing: Rain on the window, lofi mix" at the bottom and the time, 02:14, in the corner.',
    },
    devotional: {
      title: 'संध्या आरती, live every evening',
      alt: 'A temple stream at dusk: a lit aarti thali and marigold garlands, with the now-playing and up-next lines in Hindi and the time, 18:42.',
    },
    gaming: {
      title: 'Night drive, streamed from my phone',
      alt: 'A racing game streamed from a phone, with the time, 21:37, added in the corner by AFK Live.',
    },
    podcasts: {
      title: 'Past episodes, live between new ones',
      alt: 'A podcast stream: a microphone at a desk, with "Now playing: Episode 41" and "Up next: Episode 42" at the bottom and the time, 23:05.',
    },
    study: {
      title: 'Study with me, 6 am to 11 pm',
      alt: 'A study stream before dawn: a lamp over an open notebook, a big clock at the top showing 05:48 and the music playing at the bottom.',
    },
  },
};

const hi: Dict = {
  locale: 'en-IN',
  plans: {
    streamsAtOnce: (n) => `एक साथ ${n} stream`,
    upTo: (q, fps) => `${q} तक, ${fps} fps पर`,
    hoursAMonth: (h) => `हर महीने ${h} घंटे`,
    unlimitedHours: 'अनलिमिटेड घंटे, 24/7',
    destinations: (n) => `हर stream ${n} प्लेटफ़ॉर्म पर`,
    storage: (gb) => `${gb} स्टोरेज`,
    watermark: 'stream पर AFK Live का छोटा-सा निशान',
    noWatermark: 'कोई watermark नहीं',
    pitch: {
      FREE: 'असली stream चलाकर देखिए।',
      ESSENTIALS: 'एक चैनल, चौबीसों घंटे live।',
      PRO: 'कई चैनल, या 60 fps पर gaming।',
      AGENCY: 'क्लाइंट्स के लिए streams चलाइए।',
    },
    mostPicked: 'सबसे ज़्यादा चुना गया',
    perMonth: 'महीना',
    perYear: 'साल',
    startWith: (name) => `${name} से शुरू करें`,
    startFree: 'फ़्री में शुरू करें',
    freePrice: 'फ़्री',
    inclGst: 'GST सहित',
    listPrice: 'पूरी कीमत',
    offerMonthly: (name, pct, months, price, list) =>
      `${name}: पहले ${months === 1 ? 'महीने' : `${months} महीने`} ${pct}% छूट — ${price}/महीना, फिर ${list}।`,
    offerYearly: (name, pct, price, list) => `${name}: पहले साल ${pct}% छूट — ${price}, फिर ${list}/साल।`,
    offerWho: (day) => `सिर्फ़ नए सब्सक्राइबर के लिए, ${day} तक।`,
  },

  calc: {
    hours: 'घंटे',
    days: 'दिन',
    units: 'यूनिट',
    more: (d) => `यानी Creator से हर महीने ${d} ज़्यादा, और PC की घिसाई अलग से।`,
    same: 'लगभग Creator जितना ही। फ़र्क तब पता चलता है जब बिजली या इंटरनेट चला जाए।',
    less: 'सिर्फ़ बिजली का बिल Creator से कम है। हमें पैसे इसलिए मिलते हैं कि बिजली कटे, update आए या इंटरनेट जाए, आपका stream चलता रहे, और PC बंद रहे।',
  },
  proof: {
    liveAndHours: (l, h) => `अभी ${l} streams AFK Live पर live हैं। अब तक ${h}+ घंटे stream हो चुके हैं।`,
    live: (l) => `अभी ${l} streams AFK Live पर live हैं।`,
    hours: (h) => `AFK Live पर अब तक ${h}+ घंटे stream हो चुके हैं।`,
    noIncidents: 'पिछले 90 दिनों में प्लेटफ़ॉर्म पर कोई गड़बड़ी नहीं हुई।',
    incidents: (n) => `पिछले 90 दिनों में प्लेटफ़ॉर्म पर ${n} ${n === 1 ? 'गड़बड़ी हुई' : 'गड़बड़ियाँ हुईं'}, हर एक का पूरा ब्योरा status पेज पर है।`,
  },
  creators: { liveWith: (niche) => `${niche} · AFK Live के साथ live` },
  niches: {
    lofi: {
      title: 'बारिश वाली रात, lofi live 24/7',
      alt: 'एक lofi stream: रात में टेबल लैंप और बारिश वाली खिड़की, नीचे "Now playing: Rain on the window, lofi mix" और कोने में समय 02:14।',
    },
    devotional: {
      title: 'संध्या आरती, हर शाम live',
      alt: 'शाम के समय मंदिर का stream: जलती आरती की थाली और गेंदे की मालाएँ, नीचे हिन्दी में अभी चल रहा और आगे आने वाला, और समय 18:42।',
    },
    gaming: {
      title: 'रात की ड्राइव, फ़ोन से stream',
      alt: 'फ़ोन से stream हो रहा racing game, कोने में AFK Live का जोड़ा हुआ समय 21:37।',
    },
    podcasts: {
      title: 'पुराने एपिसोड, नए एपिसोड के बीच live',
      alt: 'एक podcast stream: टेबल पर माइक, नीचे "Now playing: Episode 41" और "Up next: Episode 42", और समय 23:05।',
    },
    study: {
      title: 'मेरे साथ पढ़ाई, सुबह 6 से रात 11',
      alt: 'सुबह होने से पहले पढ़ाई का stream: खुली कॉपी पर लैंप, ऊपर बड़ी घड़ी में 05:48 और नीचे चलता हुआ music।',
    },
  },
};

export const DICT: Record<Lang, Dict> = { en, hi };

/** The page's language from <html lang>; anything that isn't Hindi is English. */
export function pageLang(doc: Document | undefined = typeof document === 'undefined' ? undefined : document): Lang {
  return doc?.documentElement.lang.toLowerCase().startsWith('hi') ? 'hi' : 'en';
}

export function t(lang: Lang = 'en'): Dict {
  return DICT[lang] ?? en;
}
