/**
 * The hero's 24-hour dial: a day of one channel's schedule drawn round a clock, with a hand at the
 * visitor's local time and an on-air counter that keeps ticking. The ring never breaks: that's the
 * product.
 */

export interface Slot {
  from: number; // hour of day, 0–24
  to: number;
  label: string;
  tone: 'a' | 'b';
}

export const DAY: Slot[] = [
  { from: 23.5, to: 5.5, label: 'Lofi playlist on loop', tone: 'a' },
  { from: 5.5, to: 7, label: 'Morning aarti', tone: 'b' },
  { from: 7, to: 21, label: 'Lofi playlist on loop', tone: 'a' },
  { from: 21, to: 23.5, label: 'Live from your phone', tone: 'b' },
];

const R = 74; // programme ring radius in a 200-unit box centred on 0,0
const TAU = Math.PI * 2;
const n = (v: number) => Number(v.toFixed(2)) + 0; // + 0 turns -0 into 0

/** Clockwise angle for an hour, midnight at the top. */
export function angle(hour: number): number {
  return ((hour % 24) / 24) * TAU;
}

export function point(hour: number, r: number): [number, number] {
  const a = angle(hour);
  return [n(r * Math.sin(a)), n(-r * Math.cos(a))];
}

export function arc(from: number, to: number, r: number): string {
  const span = (((to - from) % 24) + 24) % 24 || 24;
  const [x1, y1] = point(from, r);
  const [x2, y2] = point(from + span, r);
  return `M${x1} ${y1}A${r} ${r} 0 ${span > 12 ? 1 : 0} 1 ${x2} ${y2}`;
}

export function slotAt(hour: number, day: Slot[] = DAY): Slot {
  return day.find((s) => (s.from < s.to ? hour >= s.from && hour < s.to : hour >= s.from || hour < s.to)) ?? day[0];
}

/** "3d 04:12:09": how long the demo channel has been on air. */
export function uptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const p = (v: number) => String(v).padStart(2, '0');
  return `${d}d ${p(h)}:${p(m)}:${p(s)}`;
}

function ticks(): string {
  let out = '';
  for (let h = 0; h < 24; h++) {
    const major = h % 6 === 0;
    const [x1, y1] = point(h, 90);
    const [x2, y2] = point(h, major ? 84 : 87);
    out += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" class="${major ? 'dial-tick-major' : 'dial-tick'}"/>`;
  }
  return out;
}

function hourLabels(): string {
  const names: [number, string][] = [
    [0, 'midnight'],
    [6, '6 am'],
    [12, 'noon'],
    [18, '6 pm'],
  ];
  return names
    .map(([h, t]) => {
      const [x, y] = point(h, 97);
      const anchor = h === 6 ? 'start' : h === 18 ? 'end' : 'middle';
      const dy = h === 0 ? -1 : h === 12 ? 7 : 2.5;
      return `<text x="${x}" y="${n(y + dy)}" text-anchor="${anchor}" class="dial-hour">${t}</text>`;
    })
    .join('');
}

export function dialSvg(day: Slot[] = DAY): string {
  const slots = day.map((s) => `<path d="${arc(s.from, s.to, R)}" class="dial-slot dial-slot-${s.tone}"/>`).join('');
  return `<svg viewBox="-112 -112 224 224" class="dial" aria-hidden="true">
  <circle r="62" class="dial-face"/>
  ${ticks()}${hourLabels()}
  <circle r="${R}" class="dial-track"/>
  ${slots}
  <g data-dial-hand><line x1="0" y1="${-R - 8}" x2="0" y2="${-R - 15}" class="dial-hand"/><circle cx="0" cy="${-R}" r="7" class="dial-onair"/><circle cx="0" cy="${-R}" r="7" class="dial-onair-halo"/></g>
</svg>`;
}

/** Fills [data-dial]: draws the dial, then moves the hand and the counter once a second. */
export function mountDial(root: HTMLElement, now: () => Date = () => new Date()) {
  const face = root.querySelector<HTMLElement>('[data-dial-face]');
  if (face) face.innerHTML = dialSvg();
  const hand = root.querySelector<SVGGElement>('[data-dial-hand]');
  const counter = root.querySelector<HTMLElement>('[data-dial-uptime]');
  const playing = root.querySelector<HTMLElement>('[data-dial-now]');
  const clock = root.querySelector<HTMLElement>('[data-dial-clock]');
  // The demo channel went live 3 days, 4 hours ago, give or take: a believable streak.
  const started = now().getTime() - (3 * 86400 + 4 * 3600 + 12 * 60) * 1000;
  const tick = () => {
    const t = now();
    const hour = t.getHours() + t.getMinutes() / 60 + t.getSeconds() / 3600;
    hand?.setAttribute('transform', `rotate(${n((hour / 24) * 360)})`);
    if (counter) counter.textContent = uptime((t.getTime() - started) / 1000);
    if (playing) playing.textContent = slotAt(hour).label;
    if (clock) clock.textContent = t.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  };
  tick();
  return window.setInterval(tick, 1000);
}
