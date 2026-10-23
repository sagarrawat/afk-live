import '@testing-library/jest-dom/vitest';

// jsdom doesn't implement scrolling.
window.scrollTo = () => {};

// jsdom has no PointerEvent: a MouseEvent with a pointer id is enough for drag tests.
if (typeof window.PointerEvent === 'undefined') {
  class PointerEventPolyfill extends MouseEvent {
    readonly pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
    }
  }
  window.PointerEvent = PointerEventPolyfill as unknown as typeof PointerEvent;
}
