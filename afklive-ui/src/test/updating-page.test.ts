// afklive-scripts/caddy/errors/index.html: the page Caddy shows while the web app restarts. Its script, run here
// as a browser would: it asks /healthz until the app is back, then loads the page the visitor wanted.
import html from '../../../afklive-scripts/caddy/errors/index.html?raw';

function load(answers: number[]) {
  const asked: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      asked.push(String(input));
      return new Response(null, { status: answers.shift() ?? 200 });
    }),
  );
  const doc = new DOMParser().parseFromString(html, 'text/html');
  document.body.innerHTML = doc.body.innerHTML;
  new Function(doc.querySelector('script')!.textContent!)();
  return asked;
}

const state = () => document.getElementById('state')!.textContent;

describe('the "updating" page', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('checks every few seconds, slower over time, and reloads once the app answers', async () => {
    const asked = load([503, 503]);
    expect(state()).toBe('Checking again in 5 s');
    await vi.advanceTimersByTimeAsync(5_000);
    expect(asked).toEqual(['/healthz']);
    expect(state()).toBe('Checking again in 8 s');
    await vi.advanceTimersByTimeAsync(7_500);
    expect(asked).toHaveLength(2);
    expect(state()).toBe('Checking again in 11 s');
    await vi.advanceTimersByTimeAsync(11_250);
    expect(asked).toHaveLength(3);
    expect(state()).toContain('Back. Loading');
  });

  it('"Try again" checks right away', async () => {
    const asked = load([503]);
    document.getElementById('retry')!.click();
    await vi.advanceTimersByTimeAsync(0);
    expect(asked).toEqual(['/healthz']);
    expect(state()).toBe('Checking again in 5 s');
  });

  it('never waits more than 30 s between checks', async () => {
    const asked = load(Array(20).fill(503));
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(asked.length).toBeGreaterThanOrEqual(12);
    expect(state()).toBe('Taking longer than usual. Checking again in 30 s');
  });
});
