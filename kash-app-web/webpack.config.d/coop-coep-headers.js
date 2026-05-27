// Cross-origin isolation for the kash web REPL.
//
// SharedArrayBuffer (needed by the Pyodide-in-Worker stdin pipe — see
// :tools:kash:python3-pyodide/.../worker/) is only available when the page
// is "cross-origin isolated". That requires every response from the dev
// server to carry these headers:
//
//   Cross-Origin-Opener-Policy:   same-origin
//   Cross-Origin-Embedder-Policy: require-corp
//   Cross-Origin-Resource-Policy: same-origin
//
// Without them, `typeof SharedArrayBuffer` is undefined and the Pyodide
// engine falls back to its in-process always-EOF stdin (so `input()` raises
// EOFError). The fallback is functional for non-interactive scripts.
//
// PRODUCTION DEPLOYMENT: whatever serves dist/ must set the same three
// headers. nginx example:
//   add_header Cross-Origin-Opener-Policy   "same-origin";
//   add_header Cross-Origin-Embedder-Policy "require-corp";
//   add_header Cross-Origin-Resource-Policy "same-origin";

config.devServer = config.devServer || {};
config.devServer.headers = config.devServer.headers || {};
config.devServer.headers['Cross-Origin-Opener-Policy'] = 'same-origin';
config.devServer.headers['Cross-Origin-Embedder-Policy'] = 'require-corp';
config.devServer.headers['Cross-Origin-Resource-Policy'] = 'same-origin';
