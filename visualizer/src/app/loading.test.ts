import { describe, expect, it } from 'vitest';
import { loadingState } from './loading';

describe('loading pill', () => {
  it('says what is still arriving, and nothing once everything is in', () => {
    expect(loadingState('connecting', false, 0, 0, 0)).toMatch(/Connecting/);
    expect(loadingState('open', false, 0, 0, 0)).toMatch(/world/);
    expect(loadingState('open', true, 100, 25, 0)).toBe('Loading terrain 25 % (25/100 tiles)');
    expect(loadingState('open', true, 100, 100, 3)).toBe('Building terrain (3 tiles left)');
    expect(loadingState('open', true, 100, 100, 0)).toBeNull();
  });
});
