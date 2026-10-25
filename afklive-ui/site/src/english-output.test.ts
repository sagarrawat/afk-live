import { features, planCards, type PlanView } from './plans';
import { formula, verdict, DEFAULTS } from './calc';
import { incidentNote, proofLine } from './proof';
import { NICHES } from './niches';
import type { PublicStatus } from './status';

// Regression guard for the per-language dictionary (site/src/i18n.ts): the English homepage's
// generated text must stay exactly what it was before the strings moved into the dictionary.
// The snapshot was recorded from the hard-coded English; don't update it to make a change pass.

const plan = (over: Partial<PlanView>): PlanView => ({
  id: 'ESSENTIALS',
  name: 'Creator',
  concurrentStreams: 1,
  maxHeight: 1080,
  maxFps: 30,
  storageBytes: 25 * 1024 ** 3,
  monthlyHours: 0,
  watermark: false,
  destinationsPerStream: 2,
  currency: 'INR',
  monthly: 49900,
  yearly: 499000,
  monthlyLabel: '₹499',
  yearlyLabel: '₹4,990',
  ...over,
});

const PLANS = [
  plan({ id: 'FREE', name: 'Free', monthly: 0, yearly: 0, monthlyLabel: 'Free', yearlyLabel: 'Free', monthlyHours: 10, watermark: true, maxHeight: 720, destinationsPerStream: 1, storageBytes: 2 * 1024 ** 3 }),
  plan({}),
  plan({ id: 'PRO', name: 'Pro', concurrentStreams: 3, maxFps: 60, destinationsPerStream: 5, storageBytes: 100 * 1024 ** 3, monthlyLabel: '₹1,499', yearlyLabel: '₹14,990' }),
  plan({ id: 'AGENCY', name: 'Agency', concurrentStreams: 10, maxHeight: 2160, maxFps: 60, destinationsPerStream: 5, storageBytes: 500 * 1024 ** 3, monthlyLabel: '₹3,999', yearlyLabel: '₹39,990' }),
];

const status = (n: number): PublicStatus => ({
  overall: 'operational',
  checkedAt: '2026-10-24T08:00:00Z',
  components: [],
  incidents: Array.from({ length: n }, (_, i) => ({ id: i, title: 't', status: 'resolved', startedAt: '', updates: [] })),
  proof: { liveNow: null, hoursStreamed: null },
});

describe('English homepage text is unchanged', () => {
  it('plan cards, monthly and yearly', () => {
    expect(PLANS.map((p) => features(p))).toMatchSnapshot();
    expect(planCards(PLANS, 'MONTHLY')).toMatchSnapshot();
    expect(planCards(PLANS, 'YEARLY')).toMatchSnapshot();
  });

  it('calculator formula and verdicts', () => {
    expect(formula(DEFAULTS)).toMatchSnapshot();
    expect(formula({ watts: 1250, hoursPerDay: 8, rupeesPerUnit: 7.5 })).toMatchSnapshot();
    expect([verdict(864, 499), verdict(499, 499), verdict(200, 499)]).toMatchSnapshot();
  });

  it('proof line and incident note', () => {
    expect([
      proofLine({ liveNow: 37, hoursStreamed: null }),
      proofLine({ liveNow: 2, hoursStreamed: 123400 }),
      proofLine({ liveNow: 12, hoursStreamed: 1200 }),
    ]).toMatchSnapshot();
    expect([incidentNote(status(0)), incidentNote(status(1)), incidentNote(status(3))]).toMatchSnapshot();
  });

  it('example stream titles, descriptions and template names', () => {
    expect(Object.values(NICHES).map(({ id, title, alt, template }) => ({ id, title, alt, template }))).toMatchSnapshot();
  });
});
