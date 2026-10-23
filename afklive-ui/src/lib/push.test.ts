import { keyBytes } from './push';

describe('push (MB-2)', () => {
  it('turns the server key into the bytes browsers subscribe with', () => {
    const bytes = keyBytes('BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8');
    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(4);
  });
});
