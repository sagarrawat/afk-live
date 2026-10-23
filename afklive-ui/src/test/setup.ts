import '@testing-library/jest-dom/vitest';

// jsdom doesn't implement scrolling.
window.scrollTo = () => {};
