import { componentList, incidentLists, mountStatus, stage, summary, type PublicStatus } from './status';

const base: PublicStatus = {
  overall: 'operational',
  checkedAt: '2026-10-23T08:00:00Z',
  components: [
    { id: 'website', name: 'Website and studio', status: 'operational', detail: 'Sign-in works.' },
    { id: 'phone', name: 'Phone streaming', status: 'no_data', detail: 'No phone has connected lately.' },
  ],
  incidents: [],
};

describe('status page', () => {
  it('says the overall state in words, not only colour', () => {
    expect(summary(base)).toContain('Everything is running');
    expect(summary({ ...base, overall: 'outage' })).toContain('Something is down');
  });

  it('labels every part, including ones with nothing measured', () => {
    const html = componentList(base);
    expect(html).toContain('Working');
    expect(html).toContain('No recent check');
  });

  it('escapes incident text and splits open from past incidents', () => {
    const s: PublicStatus = {
      ...base,
      incidents: [
        { id: 1, title: '<b>Slow starts</b>', status: 'identified', startedAt: '2026-10-23T07:00:00Z', updates: [{ status: 'identified', message: 'Fix <soon>', at: '2026-10-23T07:10:00Z' }] },
        { id: 2, title: 'Old one', status: 'resolved', startedAt: '2026-10-01T07:00:00Z', resolvedAt: '2026-10-01T08:00:00Z', updates: [] },
      ],
    };
    const { open, past } = incidentLists(s);
    expect(open).toContain('&lt;b&gt;Slow starts&lt;/b&gt;');
    expect(open).toContain('Fix &lt;soon&gt;');
    expect(open).toContain('Cause found');
    expect(open).not.toContain('Old one');
    expect(past).toContain('Old one');
    expect(stage('monitoring')).toBe('Fix in place, watching');
  });

  it('says so when there is no history', () => {
    expect(incidentLists(base).past).toContain('No incidents in the last 90 days');
  });

  it('fills the page, and says it cannot check when the API is down', async () => {
    document.body.innerHTML = `<main data-status aria-busy="true"><div data-status-summary></div><div data-status-open class="hidden"></div><ul data-status-components></ul><div data-status-past></div></main>`;
    const root = document.querySelector<HTMLElement>('[data-status]')!;
    mountStatus(root, async () => base);
    await vi.waitFor(() => expect(root.hasAttribute('aria-busy')).toBe(false));
    expect(root.textContent).toContain('Website and studio');

    document.body.innerHTML = `<main data-status><div data-status-summary></div><div data-status-open></div><ul data-status-components></ul><div data-status-past></div></main>`;
    const broken = document.querySelector<HTMLElement>('[data-status]')!;
    mountStatus(broken, async () => {
      throw new Error('503');
    });
    await vi.waitFor(() => expect(broken.textContent).toContain('can’t check right now'));
  });
});
