// Pyodide-in-Worker shim for kash's web REPL.
//
// Lives in :tools:kash:python3-pyodide as a wasmJs resource; kash-app-web's
// bundle task copies it into dist/. Started from the main thread via:
//
//   const w = new Worker('./pyodide-worker.js');
//   w.postMessage({type:'init', indexURL:'./pyodide/', stdinSab, interruptSab, capacity, mountPoint});
//
// Protocol — main → worker:
//   {type:'init', indexURL, stdinSab, interruptSab, capacity, mountPoint}
//   {type:'fs-mkdir', path}                       // create dir in MEMFS
//   {type:'fs-file', path, bytes}                 // write file in MEMFS
//   {type:'chdir', path}
//   {type:'run', source}                          // execute one program
//   {type:'shutdown'}                             // graceful close
//
// Protocol — worker → main:
//   {type:'ready'}                                // Pyodide loaded, init done
//   {type:'out', bytes:Uint8Array}                // stdout chunk
//   {type:'err', bytes:Uint8Array}                // stderr chunk
//   {type:'run-result', exitCode, errorMessage}   // run finished
//
// Stdin flows entirely through the shared SAB ring (see SabStdin.kt /
// StdinRingMath.kt). The worker blocks in Atomics.wait inside the synchronous
// setStdin callback — that's the whole reason this file exists.

'use strict';

// Loader needs an absolute-ish path that resolves from the worker's own URL.
// `./pyodide/pyodide.js` works because the bundle copies pyodide-worker.js
// alongside the `pyodide/` directory under dist/.
importScripts('./pyodide/pyodide.js');

const WRAP = 1 << 30;
const SLOT_HEAD = 0;
const SLOT_TAIL = 1;
const SLOT_EOF = 2;

let pyodide = null;
let stdinCtrl = null;       // Int32Array(3) over the SAB control header
let stdinData = null;       // Uint8Array(capacity) over the SAB data region
let stdinCapacity = 0;
let interruptInt32 = null;  // Int32Array(1) Pyodide polls for SIGINT

self.onmessage = async (e) => {
  const msg = e.data;
  try {
    switch (msg.type) {
      case 'init':    await onInit(msg); break;
      case 'fs-mkdir': onFsMkdir(msg); break;
      case 'fs-file':  onFsFile(msg); break;
      case 'chdir':    onChdir(msg); break;
      case 'run':      await onRun(msg); break;
      case 'shutdown': self.close(); break;
      default:
        // Unknown messages are ignored — forwards-compat with newer clients.
        break;
    }
  } catch (err) {
    // Last-resort: any unexpected throw becomes a stderr line on the main
    // thread. Do NOT crash the worker — that would leave the main thread
    // hung on a promise that never resolves.
    const text = 'pyodide-worker: ' + ((err && err.stack) || String(err)) + '\n';
    self.postMessage({type: 'err', bytes: textToBytes(text)});
    self.postMessage({type: 'run-result', exitCode: 1, errorMessage: String(err)});
  }
};

async function onInit(msg) {
  pyodide = await loadPyodide({indexURL: msg.indexURL});

  stdinCapacity = msg.capacity;
  stdinCtrl = new Int32Array(msg.stdinSab, 0, 3);
  stdinData = new Uint8Array(msg.stdinSab, 12, stdinCapacity);
  interruptInt32 = new Int32Array(msg.interruptSab, 0, 1);

  pyodide.setInterruptBuffer(interruptInt32);

  pyodide.setStdin({
    read: readStdin,
    isatty: true,
  });
  pyodide.setStdout({
    write: (buf) => {
      // Copy off the SAB-backed view before transferring — buf is a view
      // owned by Pyodide and may be reused on the next write.
      self.postMessage({type: 'out', bytes: copyOf(buf)});
      return buf.length;
    },
    isatty: true,
  });
  pyodide.setStderr({
    write: (buf) => {
      self.postMessage({type: 'err', bytes: copyOf(buf)});
      return buf.length;
    },
    isatty: true,
  });

  pyodide.FS.mkdirTree(msg.mountPoint);
  self.postMessage({type: 'ready'});
}

function onFsMkdir(msg) {
  pyodide.FS.mkdirTree(msg.path);
}

function onFsFile(msg) {
  ensureParentDir(msg.path);
  pyodide.FS.writeFile(msg.path, msg.bytes);
}

function onChdir(msg) {
  // chdir is allowed to throw on missing paths; surface as stderr but don't
  // abort the worker.
  try {
    pyodide.FS.chdir(msg.path);
  } catch (err) {
    self.postMessage({
      type: 'err',
      bytes: textToBytes('pyodide-worker: chdir failed: ' + err + '\n'),
    });
  }
}

async function onRun(msg) {
  // Reset cursors + EOF + interrupt at the run boundary so stale state from
  // a previous invocation can't bleed in.
  Atomics.store(stdinCtrl, SLOT_HEAD, 0);
  Atomics.store(stdinCtrl, SLOT_TAIL, 0);
  Atomics.store(stdinCtrl, SLOT_EOF, 0);
  Atomics.store(interruptInt32, 0, 0);

  let exitCode = 0;
  let errorMessage = null;
  try {
    await pyodide.runPythonAsync(msg.source);
  } catch (err) {
    errorMessage = (err && err.message) || String(err);
    exitCode = extractSystemExit(errorMessage);
    if (exitCode === null) exitCode = 1;
  }
  self.postMessage({type: 'run-result', exitCode, errorMessage});
}

// ----- stdin SAB reader -----
//
// Pyodide's `read(buf)` callback is synchronous. We block here in
// Atomics.wait until either bytes arrive or EOF is signaled. Wait timeout
// is 250 ms so we periodically re-check the EOF + interrupt flags even if
// no notify fires (defensive against missed notifies).
function readStdin(buf) {
  while (true) {
    const head = Atomics.load(stdinCtrl, SLOT_HEAD);
    const tail = Atomics.load(stdinCtrl, SLOT_TAIL);
    if (head !== tail) {
      return drainInto(buf, head, tail);
    }
    if (Atomics.load(stdinCtrl, SLOT_EOF) !== 0) return 0;
    if (Atomics.load(interruptInt32, 0) !== 0) {
      // Pyodide will raise KeyboardInterrupt at the next bytecode boundary;
      // returning EOF here is the cleanest unblock.
      return 0;
    }
    Atomics.wait(stdinCtrl, SLOT_HEAD, head, 250);
  }
}

function drainInto(buf, head, tail) {
  let avail = head - tail;
  if (avail < 0) avail += WRAP;
  const n = Math.min(buf.length, avail);
  const tailIdx = tail & (stdinCapacity - 1);
  const tillEnd = stdinCapacity - tailIdx;
  const first = Math.min(n, tillEnd);
  for (let i = 0; i < first; i++) buf[i] = stdinData[tailIdx + i];
  if (n > first) {
    const second = n - first;
    for (let i = 0; i < second; i++) buf[first + i] = stdinData[i];
  }
  let newTail = tail + n;
  if (newTail >= WRAP) newTail -= WRAP;
  Atomics.store(stdinCtrl, SLOT_TAIL, newTail);
  Atomics.notify(stdinCtrl, SLOT_TAIL, 1);
  return n;
}

// ----- helpers -----

function ensureParentDir(path) {
  const i = path.lastIndexOf('/');
  if (i > 0) {
    try { pyodide.FS.mkdirTree(path.substring(0, i)); } catch (e) {}
  }
}

function copyOf(view) {
  // Subarray + slice gives a non-shared Uint8Array we can postMessage with
  // structured-clone semantics. Sliced into its own ArrayBuffer; transferable.
  return view.slice(0);
}

function textToBytes(s) {
  // TextEncoder is available in workers.
  return new TextEncoder().encode(s);
}

function extractSystemExit(msg) {
  if (!msg) return null;
  const marker = 'SystemExit: ';
  const i = msg.lastIndexOf(marker);
  if (i < 0) return null;
  const rest = msg.substring(i + marker.length).trim();
  const n = parseInt(rest, 10);
  return Number.isFinite(n) ? n : null;
}
