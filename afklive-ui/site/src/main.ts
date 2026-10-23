import './site.css';
import { mountPricing } from './plans';
import { mountDial } from './dial';

// Mobile menu.
const toggle = document.querySelector<HTMLButtonElement>('[data-menu-toggle]');
const menu = document.querySelector<HTMLElement>('[data-menu]');
toggle?.addEventListener('click', () => {
  const open = toggle.getAttribute('aria-expanded') === 'true';
  toggle.setAttribute('aria-expanded', String(!open));
  menu?.classList.toggle('hidden', open);
});

document.querySelectorAll<HTMLElement>('[data-pricing]').forEach(mountPricing);
document.querySelectorAll<HTMLElement>('[data-year]').forEach((e) => (e.textContent = String(new Date().getFullYear())));
document.querySelectorAll<HTMLElement>('[data-dial]').forEach((e) => mountDial(e));
