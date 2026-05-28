// Pyodide-in-Worker shim for kash's web REPL.
//
// Lives in :tools:kash:python3-pyodide as a wasmJs resource; kash-app-web's
// bundle task copies it into dist/. Started from the main thread via:
//
//   const w = new Worker('./pyodide-worker.js');
//   w.postMessage({type:'init', indexURL:'./pyodide/', stdinSab, interruptSab, capacity});
//
// Protocol — main → worker:
//   {type:'init', indexURL, stdinSab, interruptSab, capacity}
//   {type:'fs-init', controlSab, dataSab, dataCapacity, mount, symlinks}
//   {type:'chdir', path}
//   {type:'run', source}
//   {type:'shutdown'}
//
// Protocol — worker → main:
//   {type:'ready'}                                // Pyodide loaded
//   {type:'fs-ready'}                             // kash FS plugin mounted
//   {type:'fs-rpc'}                               // wake-up: FS request waiting
//   {type:'out', bytes:Uint8Array}                // stdout chunk
//   {type:'err', bytes:Uint8Array}                // stderr chunk
//   {type:'run-result', exitCode, errorMessage, resultRepr} // run finished
//
// File-system architecture:
//   The kash FileSystem is exposed live via an Emscripten FS plugin
//   (KashFS, defined below) mounted under `/kash`. The plugin's
//   synchronous node/stream ops marshal each request into the shared
//   control SAB + data SAB and block in Atomics.wait until the main-thread
//   `SabFsServer` (kash-side coroutine) writes the response and notifies.
//
//   Curated MEMFS-root symlinks (`/tmp -> /kash/tmp`, `/home/user ->
//   /kash/home/user`, …) translate the absolute paths Python uses into
//   the plugin mount, so `open('/tmp/foo.py')` works without rewriting.
//
// Stdin flows through a separate SAB ring (SabStdin.kt) — orthogonal to
// the FS bridge, same Atomics.wait discipline.

'use strict';

importScripts('./pyodide/pyodide.js');

// ----- stdin SAB constants (matches SabStdin.kt / StdinRingMath.kt) -----

const WRAP = 1 << 30;
const STDIN_SLOT_HEAD = 0;
const STDIN_SLOT_TAIL = 1;
const STDIN_SLOT_EOF = 2;

// ----- FS-RPC SAB constants (matches SabFsProtocol.kt) -----

const FS_SLOT_REQ_SEQ = 0;
const FS_SLOT_RESP_SEQ = 1;
const FS_SLOT_OP = 2;
const FS_SLOT_STATUS = 3;
const FS_SLOT_PAYLOAD_LEN = 4;
const FS_SLOT_ARG0 = 5;
const FS_SLOT_ARG1 = 6;
const FS_SLOT_ARG2 = 7;
const FS_SLOT_ARG3 = 8;

const OP_NOP = 0;
const OP_STAT = 1;
const OP_LIST = 2;
const OP_OPEN = 3;
const OP_READ = 4;
const OP_WRITE = 5;
const OP_CLOSE = 6;
const OP_MKDIR = 7;
const OP_RMDIR = 8;
const OP_UNLINK = 9;
const OP_RENAME = 10;

// WASI errno numbers — Pyodide's Emscripten + musl use this table, NOT
// POSIX. `new FS.ErrnoError(2)` is EACCES here; ENOENT is 44; EEXIST 20;
// EISDIR 31; ENOTDIR 54; EINVAL 28; ENOSPC 51; ENOSYS 52. Keep in sync
// with SabFsProtocol.Status on the Kotlin side. Returning POSIX 2 for a
// missing file gives the user "PermissionError: [Errno 2] Permission
// denied" — happened once, will not happen again.
const STATUS_OK = 0;
const STATUS_EACCES = -2;
const STATUS_EBADF = -8;
const STATUS_EEXIST = -20;
const STATUS_EINVAL = -28;
const STATUS_EIO = -29;
const STATUS_EISDIR = -31;
const STATUS_ENOENT = -44;
const STATUS_ENOSPC = -51;
const STATUS_ENOSYS = -52;
const STATUS_ENOTDIR = -54;

const STAT_OFF_SIZE = 0;
const STAT_OFF_MTIME = 8;
const STAT_OFF_MODE = 16;
const STAT_OFF_TYPE = 20;
const STAT_SIZE = 32;

const TYPE_REGULAR = 0;
const TYPE_DIRECTORY = 1;

// musl/Emscripten open(2) flag values — keep in sync with SabFsServer.Companion.
const O_RDONLY = 0;
const O_WRONLY = 1;
const O_RDWR = 2;
const O_CREAT = 0x40;
const O_TRUNC = 0x200;
const O_APPEND = 0x400;

// ----- module state -----

let pyodide = null;
let stdinCtrl = null;
let stdinData = null;
let stdinCapacity = 0;
let interruptInt32 = null;

let fsCtrl = null;       // Int32Array over the FS control SAB
let fsData = null;       // Uint8Array over the FS data SAB
let fsDataCapacity = 0;
let fsMount = '/kash';
let fsReqSeq = 0;        // monotonic per request
let fsRespSeqLast = 0;   // last response seqno observed
let fsPluginMounted = false;

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

// ----- message dispatch -----

self.onmessage = async (e) => {
  const msg = e.data;
  try {
    switch (msg.type) {
      case 'init':     await onInit(msg); break;
      case 'fs-init':  onFsInit(msg); break;
      case 'chdir':    onChdir(msg); break;
      case 'run':      await onRun(msg); break;
      case 'shutdown': self.close(); break;
      default:
        // Unknown messages are ignored — forwards-compat with newer clients.
        break;
    }
  } catch (err) {
    const text = 'pyodide-worker: ' + ((err && err.stack) || String(err)) + '\n';
    self.postMessage({type: 'err', bytes: textToBytes(text)});
    self.postMessage({type: 'run-result', exitCode: 1, errorMessage: String(err), resultRepr: ''});
  }
};

async function onInit(msg) {
  pyodide = await loadPyodide({indexURL: msg.indexURL});

  stdinCapacity = msg.capacity;
  stdinCtrl = new Int32Array(msg.stdinSab, 0, 3);
  stdinData = new Uint8Array(msg.stdinSab, 12, stdinCapacity);
  interruptInt32 = new Int32Array(msg.interruptSab, 0, 1);

  pyodide.setInterruptBuffer(interruptInt32);
  pyodide.setStdin({read: readStdin, isatty: true});
  pyodide.setStdout({
    write: (buf) => { self.postMessage({type: 'out', bytes: copyOf(buf)}); return buf.length; },
    isatty: true,
  });
  pyodide.setStderr({
    write: (buf) => { self.postMessage({type: 'err', bytes: copyOf(buf)}); return buf.length; },
    isatty: true,
  });

  self.postMessage({type: 'ready'});
}

// ----- FS plugin init -----
//
// Mount KashFS at `msg.mount` and install MEMFS symlinks in the order:
// rmdir the conflicting empty MEMFS dir (if any) → create the symlink.
// The plugin itself just dispatches to rpc(...) — see KashFS below.
function onFsInit(msg) {
  fsCtrl = new Int32Array(msg.controlSab, 0, 16);
  fsData = new Uint8Array(msg.dataSab, 0, msg.dataCapacity);
  fsDataCapacity = msg.dataCapacity;
  fsMount = msg.mount || '/kash';
  fsReqSeq = 0;
  fsRespSeqLast = 0;
  // Reset response slot so the worker's first wait sees a clean seqno.
  Atomics.store(fsCtrl, FS_SLOT_RESP_SEQ, 0);
  Atomics.store(fsCtrl, FS_SLOT_REQ_SEQ, 0);

  if (!fsPluginMounted) {
    const FS = pyodide.FS;
    FS.mkdirTree(fsMount);
    FS.mount(KashFS, {}, fsMount);
    fsPluginMounted = true;
  }

  installSymlinks(msg.symlinks || []);

  self.postMessage({type: 'fs-ready'});
}

function installSymlinks(pairs) {
  const FS = pyodide.FS;
  for (let i = 0; i < pairs.length; i++) {
    const from = pairs[i].from;
    const to = pairs[i].to;
    try {
      // If the target already exists as a symlink to the same place, skip.
      const st = FS.lstat(from);
      if (st && FS.isLink(st.mode)) {
        const cur = FS.readlink(from);
        if (cur === to) continue;
        FS.unlink(from);
      } else if (st && FS.isDir(st.mode)) {
        // Empty MEMFS dir Pyodide pre-created (e.g. /tmp) — replace it.
        try { FS.rmdir(from); } catch (_) { /* not empty; skip and hope */ }
      } else if (st) {
        FS.unlink(from);
      }
    } catch (_) {
      // lstat throws ENOENT — fall through to create.
    }
    try {
      // Ensure the symlink's parent dir exists in MEMFS first.
      const slash = from.lastIndexOf('/');
      if (slash > 0) {
        try { FS.mkdirTree(from.substring(0, slash)); } catch (_) {}
      }
      FS.symlink(to, from);
    } catch (err) {
      // Best-effort; some symlinks may collide with Pyodide internals. Don't
      // fail the whole init — just log.
      console.warn('kash-fs: symlink', from, '->', to, 'failed:', err);
    }
  }
}

function onChdir(msg) {
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
  // Stdin SAB reset is OWNED BY MAIN, not worker. If we reset here we race
  // the main-thread pump: when the pump completes before this handler runs
  // (small stdin, fast Buffer source), the pump has already written
  // head/tail/eof — and zeroing them here drops the entire stdin payload
  // on the floor. Python's input() then waits forever for bytes that were
  // already produced and discarded. See PyodideWorkerClient.runInSession
  // for the matching reset on the main side. We DO still clear the
  // interrupt slot — that's worker-local SIGINT state, not pump state.
  Atomics.store(interruptInt32, 0, 0);

  let exitCode = 0;
  let errorMessage = null;
  let resultRepr = '';
  try {
    const result = await pyodide.runPythonAsync(msg.source);
    // Stringify the Python eval result so the REPL can read PyodideConsole.push's
    // (syntax_check, exit_code) tuple back on the main side. Statements return
    // None → "None"; expressions/tuples carry the actual repr.
    if (result !== undefined && result !== null) {
      try { resultRepr = String(result); } catch (_) { resultRepr = ''; }
      // PyProxies hold a wasm-side reference; release it so Pyodide can GC
      // the underlying Python object.
      if (result && typeof result.destroy === 'function') {
        try { result.destroy(); } catch (_) {}
      }
    }
  } catch (err) {
    errorMessage = (err && err.message) || String(err);
    exitCode = extractSystemExit(errorMessage);
    if (exitCode === null) exitCode = 1;
  }
  self.postMessage({type: 'run-result', exitCode, errorMessage, resultRepr});
}

// ===== Stdin SAB reader =====

function readStdin(buf) {
  while (true) {
    const head = Atomics.load(stdinCtrl, STDIN_SLOT_HEAD);
    const tail = Atomics.load(stdinCtrl, STDIN_SLOT_TAIL);
    if (head !== tail) return drainStdinInto(buf, head, tail);
    if (Atomics.load(stdinCtrl, STDIN_SLOT_EOF) !== 0) return 0;
    if (Atomics.load(interruptInt32, 0) !== 0) return 0;
    Atomics.wait(stdinCtrl, STDIN_SLOT_HEAD, head, 250);
  }
}

function drainStdinInto(buf, head, tail) {
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
  Atomics.store(stdinCtrl, STDIN_SLOT_TAIL, newTail);
  Atomics.notify(stdinCtrl, STDIN_SLOT_TAIL, 1);
  return n;
}

// ===== FS RPC =====
//
// One synchronous round-trip to the kash main thread. Encodes args into
// the control SAB + data SAB, postMessages a wake to main, and blocks in
// Atomics.wait on the response seqno. Returns a result descriptor that
// each op interprets.
function rpc(op, opts) {
  if (!fsCtrl) throw new Error('kash-fs: rpc before fs-init');
  // Encode inbound payload bytes into the data SAB.
  let payloadLen = 0;
  if (opts && opts.payload) {
    const p = opts.payload;
    if (p.length > fsDataCapacity) {
      throw new Error('kash-fs: payload exceeds data SAB capacity (' +
        p.length + ' > ' + fsDataCapacity + ')');
    }
    for (let i = 0; i < p.length; i++) fsData[i] = p[i];
    payloadLen = p.length;
  }
  // Write op + args.
  Atomics.store(fsCtrl, FS_SLOT_OP, op);
  Atomics.store(fsCtrl, FS_SLOT_PAYLOAD_LEN, payloadLen);
  Atomics.store(fsCtrl, FS_SLOT_ARG0, (opts && opts.arg0 != null) ? opts.arg0 : 0);
  Atomics.store(fsCtrl, FS_SLOT_ARG1, (opts && opts.arg1 != null) ? opts.arg1 : 0);
  Atomics.store(fsCtrl, FS_SLOT_ARG2, (opts && opts.arg2 != null) ? opts.arg2 : 0);
  Atomics.store(fsCtrl, FS_SLOT_ARG3, (opts && opts.arg3 != null) ? opts.arg3 : 0);

  // Bump request seqno and kick main. Main reads the seqno itself from the
  // SAB; the postMessage is just a wake-up.
  // Increment and mask to 31 bits so the counter stays non-negative.
  // `Atomics.wait` compares for equality and `Atomics.load` returns the
  // signed Int32 value; if we let it wrap to negative after 2^31 calls,
  // a stale RESP_SEQ from the previous cycle could match a new request's
  // expected seqno and the worker would falsely conclude the response had
  // arrived. Masking to 0..2^31-1 keeps the comparison monotonic across
  // the wrap point. (Two billion ops would never happen in one session,
  // but the mask is free and removes the foot-gun.)
  fsReqSeq = (fsReqSeq + 1) & 0x7FFFFFFF;
  Atomics.store(fsCtrl, FS_SLOT_REQ_SEQ, fsReqSeq);
  self.postMessage({type: 'fs-rpc'});

  // Block until response seqno catches up. Defensive 1s timeout that
  // re-checks — if main is genuinely hung we surface a useful error rather
  // than wedging Python forever.
  const expected = fsReqSeq;
  while (true) {
    const cur = Atomics.load(fsCtrl, FS_SLOT_RESP_SEQ);
    if (cur === expected) break;
    Atomics.wait(fsCtrl, FS_SLOT_RESP_SEQ, cur, 1000);
  }
  fsRespSeqLast = expected;

  return {
    status: Atomics.load(fsCtrl, FS_SLOT_STATUS),
    payloadLen: Atomics.load(fsCtrl, FS_SLOT_PAYLOAD_LEN),
    arg0: Atomics.load(fsCtrl, FS_SLOT_ARG0),
  };
}

function rpcStatPath(path) {
  return rpc(OP_STAT, {payload: textEncoder.encode(path)});
}

function readPayloadBytes(len) {
  // Copy out — the SAB-backed view is shared and we don't want callers
  // observing future overwrites.
  const out = new Uint8Array(len);
  for (let i = 0; i < len; i++) out[i] = fsData[i];
  return out;
}

function readI32LeFromData(off) {
  return (fsData[off] | (fsData[off+1] << 8) | (fsData[off+2] << 16) | (fsData[off+3] << 24));
}

function readU53LeFromData(off) {
  // 64-bit unsigned-ish, decoded as a JS number — safe up to 2^53.
  let v = 0;
  for (let i = 7; i >= 0; i--) v = v * 256 + fsData[off + i];
  return v;
}

function throwErrno(status) {
  // status is negative POSIX errno.
  throw new FS.ErrnoError(-status);
}

// ===== KashFS — the Emscripten FS plugin =====
//
// Defined inside an init function so we can reference `FS` (Pyodide's
// filesystem object) without re-declaring. The plugin object is passed to
// FS.mount and uses node_ops/stream_ops as Emscripten expects.

const KashFS = {
  mount: function (mount) {
    return KashFS.createNode(null, '/', 0o40755 | 0, 0);
  },

  createNode: function (parent, name, mode, dev) {
    const node = FS.createNode(parent, name, mode, dev);
    node.node_ops = KashFS.node_ops;
    node.stream_ops = KashFS.stream_ops;
    return node;
  },

  // Compute the absolute kash-side path for a node by walking up parents
  // until we hit the mount root. `node.mount.mountpoint` is the MEMFS path
  // we mounted at (e.g. /kash); subtract that prefix so the kash FS sees
  // the natural absolute path (`/tmp/foo`, not `/kash/tmp/foo`).
  pathOf: function (node) {
    const segs = [];
    let n = node;
    while (n && n.parent !== n) {
      segs.unshift(n.name);
      n = n.parent;
    }
    const mp = node.mount.mountpoint; // e.g. "/kash"
    const inside = '/' + segs.join('/');
    // Strip the mount-point prefix; what remains is the kash-side path.
    // For paths inside /kash like "/kash/tmp/foo", inside is "/tmp/foo".
    // For the mount root itself, inside is "/".
    return inside === '' ? '/' : inside;
  },

  node_ops: {
    getattr: function (node) {
      const path = KashFS.pathOf(node);
      const r = rpcStatPath(path);
      if (r.status !== STATUS_OK) throwErrno(r.status);
      const size = readU53LeFromData(STAT_OFF_SIZE);
      const mtime = readU53LeFromData(STAT_OFF_MTIME);
      const mode = readI32LeFromData(STAT_OFF_MODE);
      const type = readI32LeFromData(STAT_OFF_TYPE);
      const isDir = (type === TYPE_DIRECTORY);
      // Force the high-bits Emscripten expects (S_IFDIR=0o40000, S_IFREG=0o100000).
      const fullMode = (isDir ? 0o40000 : 0o100000) | (mode & 0o7777);
      const d = new Date(mtime * 1000);
      return {
        dev: 1, ino: node.id, mode: fullMode, nlink: 1,
        uid: 0, gid: 0, rdev: 0, size: size,
        atime: d, mtime: d, ctime: d,
        blksize: 4096, blocks: Math.ceil(size / 4096),
      };
    },

    setattr: function (node, attr) {
      // mtime/chmod/etc are best-effort no-ops for v1. Truncate is honored
      // implicitly via O_TRUNC on the next open.
      if (attr.timestamp !== undefined) node.timestamp = attr.timestamp;
    },

    lookup: function (parent, name) {
      const path = joinPath(KashFS.pathOf(parent), name);
      const r = rpcStatPath(path);
      if (r.status === STATUS_ENOENT) throw new FS.ErrnoError(-STATUS_ENOENT);
      if (r.status !== STATUS_OK) throwErrno(r.status);
      const type = readI32LeFromData(STAT_OFF_TYPE);
      const mode = readI32LeFromData(STAT_OFF_MODE);
      const fullMode = (type === TYPE_DIRECTORY ? 0o40000 : 0o100000) | (mode & 0o7777);
      return KashFS.createNode(parent, name, fullMode, 0);
    },

    mknod: function (parent, name, mode, dev) {
      const path = joinPath(KashFS.pathOf(parent), name);
      if ((mode & 0o170000) === 0o40000) {
        // Directory — materialize immediately; an empty dir is meaningful
        // and there's no later stream_ops.open that would create it for us.
        const r = rpc(OP_MKDIR, {arg0: mode & 0o777, payload: textEncoder.encode(path)});
        if (r.status !== STATUS_OK && r.status !== STATUS_EEXIST) throwErrno(r.status);
      }
      // Regular file: do NOT materialize here. Emscripten will call
      // stream_ops.open next with the original flags (which include O_CREAT
      // for 'w'/'a' modes); the server's opOpen handles create-on-first-open
      // by allocating an empty buffer and flushing on close. Touching the
      // file here would either duplicate that work or fail noisily if the
      // parent dir isn't writable from kash's perspective.
      return KashFS.createNode(parent, name, mode, dev);
    },

    rename: function (oldNode, newDir, newName) {
      const from = KashFS.pathOf(oldNode);
      const to = joinPath(KashFS.pathOf(newDir), newName);
      // Wire format: ARG0=from byte-length; payload = from\0to.
      const fromBytes = textEncoder.encode(from);
      const toBytes = textEncoder.encode(to);
      const buf = new Uint8Array(fromBytes.length + 1 + toBytes.length);
      buf.set(fromBytes, 0);
      buf[fromBytes.length] = 0;
      buf.set(toBytes, fromBytes.length + 1);
      const r = rpc(OP_RENAME, {arg0: fromBytes.length, payload: buf});
      if (r.status !== STATUS_OK) throwErrno(r.status);
    },

    unlink: function (parent, name) {
      const path = joinPath(KashFS.pathOf(parent), name);
      const r = rpc(OP_UNLINK, {payload: textEncoder.encode(path)});
      if (r.status !== STATUS_OK) throwErrno(r.status);
    },

    rmdir: function (parent, name) {
      const path = joinPath(KashFS.pathOf(parent), name);
      const r = rpc(OP_RMDIR, {payload: textEncoder.encode(path)});
      if (r.status !== STATUS_OK) throwErrno(r.status);
    },

    readdir: function (node) {
      const path = KashFS.pathOf(node);
      const r = rpc(OP_LIST, {payload: textEncoder.encode(path)});
      if (r.status !== STATUS_OK) throwErrno(r.status);
      const blob = textDecoder.decode(readPayloadBytes(r.payloadLen));
      // Kotlin side encodes with NUL between entries (the only POSIX-safe
      // filename delimiter); split on '\0' to recover the original list.
      const names = blob.length === 0 ? [] : blob.split('\0');
      return ['.', '..'].concat(names);
    },

    symlink: function (parent, newName, oldPath) {
      throw new FS.ErrnoError(-STATUS_ENOSYS); // ENOSYS for v1
    },

    readlink: function (node) {
      throw new FS.ErrnoError(-STATUS_EINVAL); // EINVAL — not a symlink
    },
  },

  stream_ops: {
    open: function (stream) {
      const path = KashFS.pathOf(stream.node);
      const r = rpc(OP_OPEN, {arg0: stream.flags, arg1: 0o644, payload: textEncoder.encode(path)});
      if (r.status !== STATUS_OK) throwErrno(r.status);
      stream.kfsFd = r.arg0;
    },

    close: function (stream) {
      if (stream.kfsFd != null) {
        rpc(OP_CLOSE, {arg0: stream.kfsFd});
        stream.kfsFd = null;
      }
    },

    read: function (stream, buffer, offset, length, position) {
      // Position is 64-bit; we ship the low 32 bits for now (1 MiB data SAB
      // already caps single-request size, and 2 GiB files aren't a wasm
      // workload). If multi-GiB ever matters, claim ARG2 for the high half.
      const r = rpc(OP_READ, {arg0: stream.kfsFd, arg1: position | 0, arg3: length});
      if (r.status !== STATUS_OK) throwErrno(r.status);
      const n = r.payloadLen;
      for (let i = 0; i < n; i++) buffer[offset + i] = fsData[i];
      return n;
    },

    write: function (stream, buffer, offset, length, position) {
      // Chunk to the data-SAB capacity. A single Python write larger than
      // the data SAB (e.g. `os.write(fd, big_bytes)`, or a binary-mode
      // `f.write` past the io buffer) would otherwise trip rpc()'s capacity
      // guard and throw. The server writes at the absolute position we pass,
      // so we advance position+offset per chunk and sum what it accepts.
      // (Same documented low-32-bit position cap as read() above.)
      let written = 0;
      while (written < length) {
        const chunk = Math.min(length - written, fsDataCapacity);
        const slice = buffer.subarray(offset + written, offset + written + chunk);
        const r = rpc(OP_WRITE, {arg0: stream.kfsFd, arg1: (position + written) | 0, payload: slice});
        if (r.status !== STATUS_OK) throwErrno(r.status);
        const n = r.arg0;
        written += n;
        // Short write — server accepted fewer bytes than offered. Stop and
        // report what landed, matching POSIX write(2) semantics.
        if (n < chunk) break;
      }
      return written;
    },

    llseek: function (stream, offset, whence) {
      let position = offset;
      if (whence === 1) {
        position += stream.position;
      } else if (whence === 2) {
        // SEEK_END — need file size.
        const r = rpcStatPath(KashFS.pathOf(stream.node));
        if (r.status !== STATUS_OK) throwErrno(r.status);
        position += readU53LeFromData(STAT_OFF_SIZE);
      }
      if (position < 0) throw new FS.ErrnoError(-STATUS_EINVAL);
      return position;
    },
  },
};

// `FS` is set up after Pyodide loads; resolve it lazily inside the plugin
// callbacks via this getter so node_ops/stream_ops can reference it.
Object.defineProperty(self, 'FS', {get: function () { return pyodide.FS; }, configurable: true});

// ----- helpers -----

function joinPath(dir, name) {
  if (dir === '/' || dir === '') return '/' + name;
  return dir + '/' + name;
}

function copyOf(view) {
  return view.slice(0);
}

function textToBytes(s) {
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
