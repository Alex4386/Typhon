// MOCK — quick cost check of the synthetic world: `npx tsx mock/bench.ts`.
import { MockWorld } from './world';

const w = new MockWorld(1);
let t = performance.now();
w.advance(100);
console.log('10 quiet steps', (performance.now() - t).toFixed(0), 'ms');
w.forceEruption('mock-fuji');
w.forceEruption('mock-cone');
t = performance.now();
w.advance(600);
console.log('60 erupting steps', (performance.now() - t).toFixed(0), 'ms; events', w.drainEvents().length);
t = performance.now();
const s = w.section(1, [[-4000, 0], [4000, 0]], -5000, 2500, 400, 200);
console.log('section', (performance.now() - t).toFixed(0), 'ms; units', s.meta.units.length, 'overlays', s.meta.overlays.length);
