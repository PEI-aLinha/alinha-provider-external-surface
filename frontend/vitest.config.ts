import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    // One copy of React and friends, shared with the SDK (same as the shell).
    dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query'],
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      // lcov paths relative to the plugin root, where its CI runs the SonarCloud analysis.
      reporter: ['text-summary', 'text', ['lcov', { projectRoot: '..' }]],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**'],
      // RNF19: coverage target ≥ 70%.
      thresholds: { lines: 70, statements: 70, functions: 70, branches: 70 },
    },
  },
})
