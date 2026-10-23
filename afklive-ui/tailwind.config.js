/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}', './site/**/*.{html,ts}', '../afklive-web/src/main/resources/templates/{login,register,forgot-password,reset-password}.html'],
  darkMode: ['class', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        // Tokens are CSS variables (src/index.css) so light and dark share one set of class names.
        bg: 'rgb(var(--bg) / <alpha-value>)',
        panel: 'rgb(var(--panel) / <alpha-value>)',
        raised: 'rgb(var(--raised) / <alpha-value>)',
        line: 'rgb(var(--line) / <alpha-value>)',
        ink: 'rgb(var(--ink) / <alpha-value>)',
        muted: 'rgb(var(--muted) / <alpha-value>)',
        accent: 'rgb(var(--accent) / <alpha-value>)',
        'accent-ink': 'rgb(var(--accent-ink) / <alpha-value>)',
        live: 'rgb(var(--live) / <alpha-value>)',
        ok: 'rgb(var(--ok) / <alpha-value>)',
        warn: 'rgb(var(--warn) / <alpha-value>)',
        bad: 'rgb(var(--bad) / <alpha-value>)',
      },
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        // A modular scale (1.2) from a 14px UI base.
        xs: ['12px', '16px'],
        sm: ['13px', '18px'],
        base: ['14px', '20px'],
        md: ['16px', '22px'],
        lg: ['19px', '26px'],
        xl: ['23px', '30px'],
        '2xl': ['28px', '34px'],
        '3xl': ['40px', '44px'],
      },
      borderRadius: { sm: '4px', DEFAULT: '6px', md: '8px', lg: '12px' },
    },
  },
  plugins: [],
};
