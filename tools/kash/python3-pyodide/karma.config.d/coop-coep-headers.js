// SabFsServer + Atomics + SharedArrayBuffer only work when the page is
// "cross-origin isolated" (`globalThis.crossOriginIsolated === true`).
// That requires the test server to send:
//
//   Cross-Origin-Opener-Policy:   same-origin
//   Cross-Origin-Embedder-Policy: require-corp
//
// Karma's built-in HTTP server doesn't set these by default, so test
// pages get a non-isolated context, `new SharedArrayBuffer(...)` throws
// `ReferenceError: SharedArrayBuffer is not defined`, and every test
// in SabFsServerTest fails at construction. The `customHeaders` array
// below tells Karma to attach the two headers to every response.
//
// Same set of headers the prod webpack-dev-server already injects
// (`kash-app-web/webpack.config.d/coop-coep-headers.js`); kept in sync
// so the test environment matches what the real browser sees.

config.customHeaders = (config.customHeaders || []).concat([
  { match: '.*', name: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
  { match: '.*', name: 'Cross-Origin-Embedder-Policy', value: 'require-corp' },
]);
