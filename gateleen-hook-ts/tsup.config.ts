import { defineConfig } from 'tsup';

export default defineConfig([
  {
    // Library build consumed by bundler-based applications (npm package).
    //
    // vertx3-eventbus-client (and its sockjs-client dependency) are inlined
    // (noExternal) rather than left as external imports. That package is
    // CommonJS-only with no ESM entry point, which trips up strict ESM
    // consumers (e.g. Angular's esbuild-based builder reports "is not ESM").
    // Bundling it here means consuming apps never import it directly from
    // node_modules, so the warning/error disappears and it no longer needs
    // to be installed as a runtime dependency by consumers.
    entry: ['src/index.ts'],
    format: ['esm', 'cjs'],
    noExternal: ['vertx3-eventbus-client', 'sockjs-client'],
    // Ensure esbuild honors dependencies' package.json "browser" field
    // remappings (see browser bundle config below for details).
    platform: 'browser',
    define: { global: 'globalThis' },
    dts: true,
    clean: true
  },
  {
    // Self-contained browser bundle (all dependencies inlined) for usage
    // via a plain <script> tag, e.g. in gateleen-playground. Exposes the
    // `GateleenHookTs` global with `HookService`, `EventBusService`, etc.
    entry: { 'gateleen-hook-ts.browser': 'src/index.ts' },
    format: ['iife'],
    globalName: 'GateleenHookTs',
    noExternal: [/.*/],
    // Ensure esbuild honors dependencies' package.json "browser" field
    // remappings (e.g. sockjs-client swaps Node's "crypto" module for a
    // window.crypto based shim only under the "browser" platform). Without
    // this, the bundle pulls in Node-only code paths that throw at runtime
    // in a real browser (e.g. "Dynamic require of 'crypto' is not supported").
    platform: 'browser',
    // sockjs-client's browser-safe shims (e.g. browser-crypto.js) assume a
    // webpack/browserify style polyfilled Node "global" object. esbuild does
    // not provide one, so alias it to globalThis.
    define: { global: 'globalThis' },
    outDir: 'dist',
    clean: false,
    minify: false,
    dts: false,
    sourcemap: true
  }
]);
