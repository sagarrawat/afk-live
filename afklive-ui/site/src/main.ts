import './site.css';
import { mountPricing } from './plans';
import { mountHero } from './hero';
import { mountNichePicker } from './niche-picker';
import { mountCalc } from './calc';
import { mountProof } from './proof';
import { mountFunnel } from './funnel';
import { mountStatus } from './status';
import { mountHelpSearch } from './help';
import { mountCreators } from './creators';

// Mobile menu.
const toggle = document.querySelector<HTMLButtonElement>('[data-menu-toggle]');
const menu = document.querySelector<HTMLElement>('[data-menu]');
toggle?.addEventListener('click', () => {
  const open = toggle.getAttribute('aria-expanded') === 'true';
  toggle.setAttribute('aria-expanded', String(!open));
  menu?.classList.toggle('hidden', open);
});

document.querySelectorAll<HTMLElement>('[data-pricing]').forEach((e) => mountPricing(e));
document.querySelectorAll<HTMLElement>('[data-year]').forEach((e) => (e.textContent = String(new Date().getFullYear())));
document.querySelectorAll<HTMLElement>('[data-hero]').forEach((e) => mountHero(e));
document.querySelectorAll<HTMLElement>('[data-niche-picker]').forEach((e) => mountNichePicker(e));
document.querySelectorAll<HTMLElement>('[data-calc]').forEach((e) => mountCalc(e));
if (document.querySelector('[data-proof], [data-incidents]')) mountProof();
mountFunnel();
document.querySelectorAll<HTMLElement>('[data-status]').forEach((e) => mountStatus(e));
document.querySelectorAll<HTMLElement>('[data-help]').forEach((e) => mountHelpSearch(e));
document.querySelectorAll<HTMLElement>('[data-creators]').forEach((e) => mountCreators(e));
