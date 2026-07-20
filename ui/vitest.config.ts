/// <reference types="vitest/config" />
/**
 * vitest.config.ts — test configuration for the CardDemo walking-skeleton UI.
 *
 * Standalone (does NOT extend vite.config.ts) so the production build config —
 * the dev-server proxy and the React plugin — stays untouched and the unit tests
 * run in a lightweight Node environment. The current UI test surface is the pure
 * display helpers (e.g. features/card-detail/formatExpiry.ts, finding P4-m02),
 * which need no DOM, so `environment: 'node'` is sufficient and fast.
 *
 * This file is intentionally excluded from tsconfig.json's `include`, so the
 * production `tsc --noEmit` step never type-checks it; vitest loads it directly.
 */
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: [
      'app/**/*.test.ts',
      'components/**/*.test.ts',
      'features/**/*.test.ts',
    ],
  },
});
