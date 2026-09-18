// Four HTML entries, because each page's context is its subject: the replay has already loaded the checker
// when it asserts, the cold page must never have loaded it, and the prepared page must have loaded AND warmed
// it before anything is timed — and the after-edit page must additionally HOLD one analysis across the whole
// campaign, which is the thing it measures.
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
        prepared: resolve(import.meta.dirname, 'prepared.html'),
        'after-edit': resolve(import.meta.dirname, 'after-edit.html'),
      },
    },
  },
});
