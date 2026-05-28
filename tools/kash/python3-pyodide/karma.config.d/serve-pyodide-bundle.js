// Two responsibilities, both stacked so Pyodide can actually load in the
// Karma test page:
//
// 1) Map the URLs PyodideEngine uses ('/pyodide-worker.js',
//    '/pyodide/*') to where Kotlin's wasmJs test toolchain actually
//    drops them — under `/base/kotlin/...` (Karma serves the test
//    package's `kotlin/` subdir, which is where `wasmJsTestProcessResources`
//    + `copyPyodideWorkerForTest` land the files).
//
// 2) Force COOP `same-origin` + COEP `require-corp` on EVERY response,
//    including proxied ones. Karma's `customHeaders` config only
//    applies to its static-file handler, not to the proxy layer
//    (Karma uses node-http-proxy and lets target response headers pass
//    through unchanged). Without COEP on the worker script response,
//    Chrome refuses to spawn the worker in a cross-origin-isolated
//    context, `new SharedArrayBuffer(...)` throws, and the FS bridge
//    can't even start.
//
// The middleware approach also avoids the `customHeaders`-only-on-/base
// limitation: every request goes through our header injector before
// Karma's own routing decides what to do with it.

// Register the on-disk worker + pyodide bundle as Karma-served files.
// Karma only serves what's in `files:` — the webpack-output framework
// drops `pyodide-worker.js` and the `pyodide/` tree under `<basePath>/kotlin/`
// (basePath is set to the test webpack package dir), but Karma's static
// handler doesn't index that directory by itself. Without these entries
// every request to `/base/kotlin/pyodide-worker.js` (and the proxy target
// below) 404s.
//
// `served: true, included: false, watched: false, nocache: true` =
// "make these reachable via /base/... URLs, don't inject into the test
// page, don't re-run on change." See
// https://karma-runner.github.io/6.4/config/files.html
//
// The `**` glob covers everything under `kotlin/` so we don't have to
// enumerate the ~10 pyodide bundle files individually.
const path = require('path');
const kotlinDir = path.join(config.basePath, 'kotlin');
config.files = (config.files || []).concat([
  { pattern: path.join(kotlinDir, 'pyodide-worker.js'), watched: false, included: false, served: true, nocache: true },
  { pattern: path.join(kotlinDir, 'pyodide', '**'),     watched: false, included: false, served: true, nocache: true },
]);

// Map ONLY the URLs PyodideEngine asks for; a catch-all `'/'` → '/base/kotlin/'
// proxy infinitely recurses because the rewritten URL also starts with '/'
// and Karma re-proxies it ('failed to proxy /base/kotlin/base/kotlin/...').
config.proxies = Object.assign({}, config.proxies, {
  '/pyodide-worker.js': '/base/kotlin/pyodide-worker.js',
  '/pyodide/':          '/base/kotlin/pyodide/',
});

// Lift Mocha's 2s per-test budget — every Pyodide-backed test pays a
// ~3s cold-boot cost (worker spawn + pyodide.asm.wasm load + Python
// runtime init) before its actual body runs. 60s leaves plenty of head
// room for the first test in the suite without masking real hangs.
// Karma's default `browserNoActivityTimeout` (10s) kills the headless
// browser between Pyodide tests when one stalls — even briefly — and
// truncates the suite mid-run. Lift it so Karma waits long enough for
// the slowest test (stdin path) without masking a true hang.
config.browserNoActivityTimeout = 120000;

config.client = Object.assign({}, config.client, {
  mocha: Object.assign({}, (config.client && config.client.mocha) || {}, {
    timeout: 60000,
  }),
});

config.beforeMiddleware = (config.beforeMiddleware || []).concat(['kashCoopCoep']);
config.plugins = (config.plugins || []).concat([
  {
    'middleware:kashCoopCoep': [
      'factory',
      function () {
        return function (req, res, next) {
          // setHeader before downstream handlers (static, proxy) write
          // their own. Proxy responses preserve these unless the target
          // explicitly clears them.
          res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
          res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
          // CORP is sometimes needed too when the page is iframed by
          // the test runner.
          res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
          next();
        };
      },
    ],
  },
]);
