// Two HTML entries, because the cold page must be a context that has never loaded the checker.
//
// Vite builds `index.html` alone by default, so the cold page would simply not be emitted and its driver would
// 404 — which is why this file exists at all rather than the project relying on the default. The replay page and
// the cold page are separate documents on purpose: the replay has loaded the checker before it asserts anything,
// and the cold measurement needs the opposite.

import { resolve } from 'node:path';
import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    rollupOptions: {
      input: {
        index: resolve(import.meta.dirname, 'index.html'),
        cold: resolve(import.meta.dirname, 'cold.html'),
      },
    },
  },
});
