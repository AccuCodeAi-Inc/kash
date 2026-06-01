@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    ExperimentalWasmJsInterop::class,
)

package com.accucodeai.kash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.fs.sanitizeDropName
import com.accucodeai.kash.fs.uniqueDropPath
import com.accucodeai.kash.snapshot.Kind
import com.accucodeai.kash.snapshot.SnapshotPayload
import com.accucodeai.kash.webres.JetBrainsMono_Bold
import com.accucodeai.kash.webres.JetBrainsMono_Regular
import com.accucodeai.kash.webres.NotoColorEmoji
import com.accucodeai.kash.webres.NotoSansMonoCJKsc_Regular
import com.accucodeai.kash.webres.Res
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font
import kotlin.time.Duration.Companion.milliseconds

/**
 * The kash-on-web workspace: a windowed, multi-pane layout that wraps
 * one or more [KashSessionRunner]s.
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────┐
 *   │ Top bar: File menu, workspace title                 │
 *   ├──────────────────────────────────────┬──────────────┤
 *   │                                      │  Shells:     │
 *   │  Active terminal (KashTerminalApp)   │  ▶ shell-1   │
 *   │                                      │    shell-2 ✕ │
 *   │                                      │  [+ New]     │
 *   └──────────────────────────────────────┴──────────────┘
 *
 *  - File menu (top): New Shell / Save Snapshot… / Load Snapshot… /
 *    Manage Snapshots…
 *  - Right pane: list of shells (1 by default; 0 allowed). Each entry
 *    can be selected (becomes the active terminal) or closed (cancels
 *    that runner's scope, killing its kash process).
 *  - Center: the active shell's terminal canvas, or a hint when no
 *    shell is open.
 *
 * Snapshots persist to `localStorage` via [BrowserSnapshotStore]. By
 * default a "Save" captures the full machine state (interpreter
 * functions / aliases / vars / cwd / history + user FS). The dialog
 * has a checkbox to fall back to FS-only — useful when the user just
 * wants to ship the file tree without the shell's mutable state.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
public fun KashWorkspace(registryFactory: (NetworkPolicy) -> CommandRegistry) {
    // ONE virtual machine for the whole web app — one fs, one process
    // table, one /proc, one init. Tabs are sibling kash children of
    // that init, so files / processes are shared across them.
    //
    // Stored as state so "New Machine" can wipe everything: stop all
    // running shells, throw away the old VM, install a fresh one. A
    // fresh VM means fresh InMemoryFs, fresh /proc, fresh process
    // table — equivalent to rebooting the box.
    // When kash is embedded (iframe / popup), it must not read or write the
    // persistent snapshot store — otherwise the host's instance could load
    // (and then export) the user's first-party autosave. Suppressing
    // autosave here isolates embedded instances regardless of whether the
    // browser partitions third-party storage. Embedded mode also starts
    // from the locked-down policy default (no network, SAFE sandbox).
    val embedded = remember { isEmbedded() }
    var policy by remember {
        mutableStateOf(if (embedded) WorkspacePolicy.Embedded else WorkspacePolicy.Standalone)
    }
    var workspace by remember { mutableStateOf(KashWorkspaceVm(registryFactory, policy)) }
    val focusManager = LocalFocusManager.current
    // Coroutine scope for the document-level drop host's async file
    // reads. Tied to this composable so it dies with the workspace.
    val workspaceScope = rememberCoroutineScope()
    val dropHost = remember(workspaceScope) { BrowserDropHost(workspaceScope) }
    InstallBrowserDropHost(dropHost)
    // Surface dropper status into the workspace's existing flash toast.
    val dropMessage by dropHost.pendingMessage

    // Warm both terminal faces into the Skiko font resolver before the
    // terminal first paints. Compose Web has NO system fonts, so the glyph-
    // fallback chain is exactly what we preload here — without this, CJK
    // would render as tofu because Skiko would have nothing to fall back to
    // when JetBrains Mono lacks a glyph.
    //  - JetBrains Mono (Regular + Bold): the primary face, used directly by
    //    `TerminalCanvas`'s TextStyle. Latin / Greek / Cyrillic / box-drawing.
    //  - Noto Sans Mono CJK SC (Regular): preloaded as a fallback face. It's
    //    not referenced by any FontFamily — once in the resolver's collection
    //    Skiko pulls CJK/kana/hangul glyphs from it automatically. ~16 MB,
    //    served as a separate composeResources asset next to the .wasm on
    //    GitHub Pages (caches across deploys, doesn't bloat the binary).
    //  - Noto Color Emoji (CBDT bitmap): also preloaded as a fallback face,
    //    so emoji scalars (which the TerminalGrid now keeps intact as one
    //    cell) shape in color. ~10 MB; same fallback mechanism as the CJK
    //    face. Needs a cross-origin-isolated page; it's an asset, not in wasm.
    // `preload(...)` kicks the fetch as the workspace mounts; otherwise the
    // first text-layout pass would block on the network.
    // See kash-app-web/THIRD_PARTY_LICENSES/ for the OFL texts (JetBrains
    // Mono + Noto Sans Mono CJK + Noto Color Emoji).
    val fontResolver = LocalFontFamilyResolver.current
    val terminalFont =
        FontFamily(
            Font(Res.font.JetBrainsMono_Regular, weight = FontWeight.Normal, style = FontStyle.Normal),
            Font(Res.font.JetBrainsMono_Bold, weight = FontWeight.Bold, style = FontStyle.Normal),
        )
    val cjkFallbackFont =
        FontFamily(
            Font(Res.font.NotoSansMonoCJKsc_Regular, weight = FontWeight.Normal, style = FontStyle.Normal),
        )
    val emojiFallbackFont =
        FontFamily(
            Font(Res.font.NotoColorEmoji, weight = FontWeight.Normal, style = FontStyle.Normal),
        )
    LaunchedEffect(terminalFont, cjkFallbackFont, emojiFallbackFont) {
        runCatching { fontResolver.preload(terminalFont) }
        runCatching { fontResolver.preload(cjkFallbackFont) }
        runCatching { fontResolver.preload(emojiFallbackFont) }
    }

    // After any workspace-chrome click we drop focus so subsequent
    // keystrokes (especially Enter) flow to the terminal rather than
    // re-activating the just-clicked Compose Button. Without this, the
    // Material3 Button keeps Compose's focus after click and Enter
    // re-fires `+ New Shell`, opening a new tab on every prompt submit.
    fun chrome(action: () -> Unit) {
        action()
        focusManager.clearFocus(force = true)
    }

    // Mutable list of open shells. ShellTab carries its own runner +
    // a display label; we use a stable counter so re-opening a closed
    // tab doesn't collide with a still-open one.
    val tabs = remember { mutableStateListOf<ShellTab>() }
    var nextShellNumber by remember { mutableStateOf(1) }
    var activeIndex by remember { mutableStateOf(-1) }

    // Removes a tab by id. Used by both the manual ✕ close button and
    // the auto-exit path (user typed `exit`/`quit`/Ctrl-D). Safe to
    // call from any thread — Compose's SnapshotStateList tolerates
    // off-main-thread mutation.
    val closeTab: (Long) -> Unit = { tabId ->
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            val removed = tabs.removeAt(idx)
            removed.runner.stop()
            activeIndex =
                when {
                    tabs.isEmpty() -> -1
                    idx < activeIndex -> activeIndex - 1
                    idx == activeIndex -> idx.coerceAtMost(tabs.lastIndex)
                    else -> activeIndex
                }
        }
    }

    // Boot one shell on first composition (per spec: "opens 1 by default").
    // First check for a previous-session autosave — restoring it before the
    // first tab spawns means the new shell's interpreter restore-from-slot
    // picks up the saved cwd / aliases / functions / vars / history.
    // Quiescence: a full snapshot needs the shell to have published its
    // slot (the shell does this every time the prompt is shown). If the
    // last `writeAutosave` could only manage FS-only (mid-exec at the
    // time), files come back but shell state starts fresh.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            // Embedded instances start clean — never resurrect the
            // first-party autosave.
            val autosave = if (embedded) null else BrowserSnapshotStore.loadAutosave()
            if (autosave != null) {
                when (autosave) {
                    is SnapshotPayload.Full -> workspace.restoreFull(autosave.snapshot)
                    is SnapshotPayload.FsOnly -> workspace.restoreFsOnly(autosave.snapshot)
                }
            }
            val tab = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
            nextShellNumber++
            tabs.add(tab)
            activeIndex = 0
        }
    }

    // Periodic autosave as a safety net against browser crashes / tab
    // kill. `beforeunload` is the primary save trigger (below) but it
    // isn't guaranteed to fire (force-kill, mobile suspend, OOM).
    LaunchedEffect(workspace) {
        if (embedded) return@LaunchedEffect
        while (true) {
            delay(30_000.milliseconds)
            workspace.writeAutosave()
        }
    }

    // `beforeunload` handler — synchronous save right before the page goes
    // away. localStorage writes are synchronous so the snapshot is durable
    // before the page unloads. We never preventDefault / return a string;
    // showing an "are you sure" dialog would be hostile.
    DisposableEffect(workspace) {
        if (embedded) return@DisposableEffect onDispose { }
        val handler: (org.w3c.dom.events.Event) -> Unit = { workspace.writeAutosave() }
        window.addEventListener("beforeunload", handler)
        onDispose { window.removeEventListener("beforeunload", handler) }
    }

    // Dialog state.
    var saveDialog by remember { mutableStateOf(false) }
    var manageDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    var aiSetupDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // KashFrame (cross-origin embedding). Disabled unless the page URL
    // carries an embedder allowlist. `frameRequest` holds a host-initiated
    // op awaiting (or skipping, for trusted origins) user confirmation.
    val frameAllowed = remember { KashFrameConfig.allowedEmbedders() }
    val frameTrusted = remember { KashFrameConfig.trustedEmbedders() }
    val frameOrigin = remember { mutableStateOf<String?>(null) }
    var frameRequest by remember { mutableStateOf<KashFrameRequest?>(null) }
    // A host-supplied policy awaiting application (applied by rebuilding the
    // VM in the composition, not from the JS callback stack).
    var pendingConfigure by remember { mutableStateOf<PendingConfigure?>(null) }

    // FS explorer drawer state. Persists across renders but not across
    // page reloads — that's a future v2 with localStorage.
    var explorerExpanded by remember { mutableStateOf(false) }

    // Auto-dismiss status flash after a few seconds.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            kotlinx.coroutines.delay(3000)
            statusMessage = null
        }
    }

    // Forward drop-host messages into the same flash channel so the user
    // sees "Imported 2 files into /home/user" the same way they see
    // "Saved my-snap".
    LaunchedEffect(dropMessage) {
        val m = dropMessage ?: return@LaunchedEffect
        statusMessage = m
        dropHost.pendingMessage.value = null
    }

    val activeTab by remember {
        derivedStateOf { tabs.getOrNull(activeIndex) }
    }

    // Apply a restored payload (from a saved snapshot OR an uploaded file)
    // to the live VM. FS-only is non-destructive; a full restore touches
    // interpreter slots, so we tear down every live shell first and spawn
    // a fresh one whose restore-from-slot picks up the saved state. Shared
    // by the Load-snapshot dialog and the Upload-snapshot file picker.
    val applyPayload: (String, SnapshotPayload) -> Unit = { label, payload ->
        when (payload) {
            is SnapshotPayload.FsOnly -> {
                statusMessage =
                    if (workspace.restoreFsOnly(payload.snapshot)) {
                        "Loaded FS from “$label”."
                    } else {
                        "Couldn't restore “$label” — snapshot is corrupt or incompatible."
                    }
            }

            is SnapshotPayload.Full -> {
                for (t in tabs) t.runner.stop()
                tabs.clear()
                activeIndex = -1
                val ok = workspace.restoreFull(payload.snapshot)
                // Spawn a fresh shell either way: on failure the VM may be
                // partially restored, but an empty terminal beats a blank
                // screen, and the user gets a clear status message.
                val tab = newTab(workspace, label = label, onExit = closeTab)
                tabs.add(tab)
                activeIndex = 0
                nextShellNumber++
                statusMessage =
                    if (ok) "Restored “$label”." else "Restored “$label” with errors — snapshot may be incompatible."
            }
        }
    }

    // Carry out a (confirmed or trusted) KashFrame op against the live VM
    // and reply to the host over the port. Recreated each recomposition so
    // it always reads the current `workspace`.
    val executeFrameRequest: (KashFrameRequest) -> Unit = { req ->
        when (req) {
            is KashFrameRequest.Restore -> {
                applyPayload(req.name, req.payload)
                kashFramePost("kashframe:ack", req.replyId, "")
            }

            is KashFrameRequest.Export -> {
                val payload: SnapshotPayload? =
                    if (req.fsOnly) {
                        workspace.takeFsSnapshot()?.let { SnapshotPayload.FsOnly(it) }
                    } else {
                        workspace.takeFullSnapshot()?.let { SnapshotPayload.Full(it) }
                    }
                if (payload == null) {
                    kashFramePost("kashframe:error", req.replyId, "capture-failed")
                    statusMessage = "Couldn't capture machine state for ${req.origin}."
                } else {
                    val text = BrowserSnapshotStore.encodeToFile("snapshot", payload)
                    kashFramePost("kashframe:snapshot", req.replyId, text)
                    statusMessage = "Sent snapshot to ${req.origin}."
                }
            }
        }
    }

    // Install the KashFrame listener once, only when an allowlist exists.
    // Callbacks fire on the main thread, so they set Compose state directly;
    // restore/export are gated through `frameRequest` so they run in the
    // composition (with a fresh `workspace`) rather than from the JS stack.
    DisposableEffect(Unit) {
        if (frameAllowed.isNotEmpty()) {
            installKashFrame(
                allowedCsv = frameAllowed.joinToString(","),
                onConnect = { origin ->
                    frameOrigin.value = origin
                    kashFramePost("kashframe:ready", "", BuildConfig.VERSION)
                    statusMessage = "Connected to $origin"
                },
                onMessage = { type, replyId, payload ->
                    val origin = frameOrigin.value
                    if (origin != null) {
                        when (type) {
                            "kashframe:configure" -> {
                                val base = if (embedded) WorkspacePolicy.Embedded else WorkspacePolicy.Standalone
                                val newPolicy = parseFramePolicy(payload, base)
                                if (newPolicy == null) {
                                    kashFramePost("kashframe:error", replyId, "invalid-config")
                                } else {
                                    pendingConfigure = PendingConfigure(newPolicy, replyId)
                                }
                            }

                            "kashframe:load-snapshot" -> {
                                val imported = BrowserSnapshotStore.decodeFromFile(payload)
                                if (imported == null) {
                                    kashFramePost("kashframe:error", replyId, "invalid-snapshot")
                                } else {
                                    frameRequest =
                                        KashFrameRequest.Restore(origin, replyId, imported.name, imported.payload)
                                }
                            }

                            "kashframe:request-snapshot" -> {
                                frameRequest = KashFrameRequest.Export(origin, replyId, fsOnly = payload == "fs")
                            }
                            // Unknown verbs are ignored — forward-compatible.
                        }
                    }
                },
            )
        }
        onDispose { }
    }

    MaterialTheme(colorScheme = WorkspaceColors) {
        Column(modifier = Modifier.fillMaxSize().background(WindowBackground)) {
            WorkspaceTopBar(
                activeLabel = activeTab?.label,
                onNewShell = {
                    chrome {
                        val tab = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
                        nextShellNumber++
                        tabs.add(tab)
                        activeIndex = tabs.lastIndex
                    }
                },
                onNewMachine = {
                    chrome {
                        // Stop every shell so its kash interpreter
                        // exits cleanly before we drop its machine.
                        for (t in tabs) t.runner.stop()
                        tabs.clear()
                        activeIndex = -1
                        nextShellNumber = 1
                        // Wipe the autosave too — otherwise the next
                        // page reload would resurrect the old machine
                        // the user just asked to throw away.
                        BrowserSnapshotStore.clearAutosave()
                        workspace = KashWorkspaceVm(registryFactory, policy)
                        val tab = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
                        nextShellNumber++
                        tabs.add(tab)
                        activeIndex = 0
                        statusMessage = "Booted a fresh machine."
                    }
                },
                onSave = { chrome { saveDialog = true } },
                onManage = { chrome { manageDialog = true } },
                onAbout = { chrome { aboutDialog = true } },
                onAiSetup = { chrome { aiSetupDialog = true } },
            )
            // Tab strip directly under the top bar (Ghostty / iTerm
            // style). Horizontal real estate is cheap; the sidebar's
            // 200dp on the right was eating terminal width.
            ShellTabStrip(
                tabs = tabs,
                activeIndex = activeIndex,
                onSelect = { chrome { activeIndex = it } },
                onClose = { idx ->
                    chrome {
                        tabs.getOrNull(idx)?.let { closeTab(it.id) }
                    }
                },
                onNew = {
                    chrome {
                        val tab = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
                        nextShellNumber++
                        tabs.add(tab)
                        activeIndex = tabs.lastIndex
                    }
                },
            )
            Row(modifier = Modifier.fillMaxSize()) {
                // Left sidebar: collapsible FS explorer. The narrow rail
                // shows just the toggle button when collapsed.
                FsExplorerSidebar(
                    fs = workspace.fs,
                    expanded = explorerExpanded,
                    onToggle = { chrome { explorerExpanded = !explorerExpanded } },
                    dropHost = dropHost,
                )
                // Center pane: active terminal.
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(TerminalBackground)) {
                    val tab = activeTab
                    if (tab != null) {
                        // Keying on the tab's id forces a fresh subtree
                        // (Compose state, DOM listeners, snapshot collectors)
                        // when we switch tabs — without this, the keystroke
                        // listener and selection state from the old tab leak
                        // into the new one.
                        key(tab.id) {
                            KashTerminalApp(
                                runner = tab.runner,
                                modifier = Modifier.fillMaxSize(),
                                dropHost = dropHost,
                                // Stand down while a modal is open so the
                                // dialog gets clicks, text selection, and keys.
                                inputEnabled =
                                    !(
                                        saveDialog || manageDialog || aboutDialog || aiSetupDialog ||
                                            frameRequest != null
                                    ),
                            )
                        }
                    } else {
                        EmptyState(onNewShell = {
                            chrome {
                                val nt = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
                                nextShellNumber++
                                tabs.add(nt)
                                activeIndex = tabs.lastIndex
                            }
                        })
                    }
                }
            }
        }

        // Status flash overlay.
        statusMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(color = Color(0xFF2A2A2A), contentColor = Color(0xFFEAEAEA)) {
                    Text(msg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }

        if (saveDialog) {
            SaveSnapshotDialog(
                defaultName = "snapshot",
                canTakeFull = true,
                onDismiss = { saveDialog = false },
                onConfirm = { name, fsOnly ->
                    saveDialog = false
                    val saved =
                        if (fsOnly) {
                            val fsSnap = workspace.takeFsSnapshot()
                            if (fsSnap == null) {
                                statusMessage = "Could not capture the filesystem."
                                return@SaveSnapshotDialog
                            }
                            BrowserSnapshotStore.save(
                                name,
                                SnapshotPayload.FsOnly(fsSnap),
                            )
                        } else {
                            val machineSnap = workspace.takeFullSnapshot()
                            if (machineSnap == null) {
                                statusMessage = "Could not capture machine state."
                                return@SaveSnapshotDialog
                            }
                            BrowserSnapshotStore.save(
                                name,
                                SnapshotPayload.Full(machineSnap),
                            )
                        }
                    statusMessage = "Saved “${saved.name}” (${formatBytes(saved.sizeBytes)})."
                },
            )
        }

        if (manageDialog) {
            ManageSnapshotsDialog(
                onDismiss = { manageDialog = false },
                onLoad = { meta ->
                    manageDialog = false
                    val payload = BrowserSnapshotStore.load(meta.name)
                    if (payload == null) {
                        statusMessage = "Failed to load “${meta.name}”."
                    } else {
                        applyPayload(meta.name, payload)
                    }
                },
                onDownload = { meta ->
                    val payload = BrowserSnapshotStore.load(meta.name)
                    if (payload == null) {
                        statusMessage = "Failed to read “${meta.name}”."
                    } else {
                        val text = BrowserSnapshotStore.encodeToFile(meta.name, payload)
                        downloadBytes("${meta.name}.kash.json", text.encodeToByteArray())
                        statusMessage = "Downloaded “${meta.name}.kash.json” (${formatBytes(text.length)})."
                    }
                },
                onCopy = { meta ->
                    val payload = BrowserSnapshotStore.load(meta.name)
                    if (payload == null) {
                        statusMessage = "Failed to read “${meta.name}”."
                    } else {
                        val text = BrowserSnapshotStore.encodeToFile(meta.name, payload)
                        writeToClipboard(text)
                        statusMessage = "Copied “${meta.name}” to clipboard (${formatBytes(text.length)})."
                    }
                },
                onDelete = { meta ->
                    BrowserSnapshotStore.delete(meta.name)
                    statusMessage = "Deleted “${meta.name}”."
                },
                onUpload = {
                    manageDialog = false
                    // Native file picker → read text → decode → restore. The
                    // FileReader callback runs back on the main thread, so
                    // touching Compose state from it is safe.
                    pickTextFile { text ->
                        val imported = BrowserSnapshotStore.decodeFromFile(text)
                        if (imported == null) {
                            statusMessage = "Couldn't read that snapshot file."
                        } else {
                            applyPayload(imported.name, imported.payload)
                        }
                    }
                },
                onPaste = {
                    manageDialog = false
                    readClipboardText { text ->
                        val imported = BrowserSnapshotStore.decodeFromFile(text)
                        if (imported == null) {
                            statusMessage = "No snapshot found on the clipboard."
                        } else {
                            applyPayload(imported.name, imported.payload)
                        }
                    }
                },
            )
        }

        if (aboutDialog) {
            AboutDialog(onDismiss = { aboutDialog = false })
        }

        if (aiSetupDialog) {
            LmStudioSetupDialog(onDismiss = { aiSetupDialog = false })
        }

        // Host policy change — rebuild the VM with the new posture. Runs in
        // a LaunchedEffect so it executes in the composition (fresh state),
        // not from the JS port callback. Applied without a prompt: it's the
        // host setting up its embed on a fresh, empty machine.
        pendingConfigure?.let { pc ->
            LaunchedEffect(pc) {
                for (t in tabs) t.runner.stop()
                tabs.clear()
                activeIndex = -1
                nextShellNumber = 1
                policy = pc.policy
                workspace = KashWorkspaceVm(registryFactory, pc.policy)
                val tab = newTab(workspace, label = "shell-$nextShellNumber", onExit = closeTab)
                nextShellNumber++
                tabs.add(tab)
                activeIndex = 0
                kashFramePost("kashframe:ack", pc.replyId, "")
                statusMessage = "Applied sandbox policy from ${frameOrigin.value ?: "host"}."
                pendingConfigure = null
            }
        }

        // KashFrame host request. Trusted origins act without a prompt
        // (run in a LaunchedEffect so it executes in the composition);
        // everyone else is confirm-gated.
        frameRequest?.let { req ->
            if (req.origin in frameTrusted) {
                LaunchedEffect(req) {
                    executeFrameRequest(req)
                    frameRequest = null
                }
            } else {
                KashFrameConfirmDialog(
                    request = req,
                    onConfirm = {
                        executeFrameRequest(req)
                        frameRequest = null
                    },
                    onDismiss = {
                        kashFramePost("kashframe:error", req.replyId, "declined")
                        frameRequest = null
                    },
                )
            }
        }
    }
}

/**
 * Modal "About kash" panel — meta info about what kash is and why it
 * exists. Surfaced from the About menu in [WorkspaceTopBar].
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About kash") },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "A Unix shell that runs entirely in your browser.",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEAEAEA),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "kash is a POSIX/bash-compatible shell, a virtual filesystem, and a " +
                            "suite of coreutils written in Kotlin Multiplatform and compiled to " +
                            "WebAssembly. Everything executes locally in the browser sandbox — " +
                            "there is no backend, and nothing leaves your machine.",
                        color = Color(0xFFBDBDBD),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Save and restore the whole machine — filesystem plus shell state — as " +
                            "snapshots, drag files in from your desktop, and run an optional AI " +
                            "agent against a local LLM (see “Set up AI · LM Studio”).",
                        color = Color(0xFFBDBDBD),
                    )
                    Spacer(Modifier.size(14.dp))
                    Text(
                        "Version ${BuildConfig.VERSION}",
                        color = Color(0xFF8A8A8A),
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Apache-2.0 licensed",
                        color = Color(0xFF8A8A8A),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Modal walkthrough for pointing kash's AI agent at a local LM Studio
 * server. The load-bearing step is enabling CORS — without it the browser
 * blocks the cross-origin request to localhost and the agent can't connect.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun LmStudioSetupDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up AI — LM Studio") },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "kash's AI agent talks to a local LLM over an OpenAI-compatible API, and " +
                            "LM Studio is the easiest way to run one. Because kash runs in the " +
                            "browser, LM Studio must allow cross-origin (CORS) requests — otherwise " +
                            "the browser blocks the connection.",
                        color = Color(0xFFBDBDBD),
                    )
                    Spacer(Modifier.size(12.dp))
                    SetupStep("1.", "Install LM Studio (lmstudio.ai) and download a model.")
                    SetupStep(
                        "2.",
                        "Open the Developer tab in LM Studio's left sidebar, load your model, and " +
                            "start the server. It listens on http://localhost:1234 by default.",
                    )
                    SetupStep(
                        "3.",
                        "In Server Settings, turn ON “Enable CORS”. This is what lets this browser " +
                            "page reach the API. (CLI equivalent: lms server start --cors)",
                    )
                    SetupStep(
                        "4.",
                        "Optional: to reach LM Studio from another device, also enable “Serve on " +
                            "Local Network”.",
                    )
                    SetupStep("5.", "Back in a kash shell, run:  agent")
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "The agent defaults to http://localhost:1234 and lists the models it finds. " +
                            "Point it elsewhere with:  agent http://host:port",
                        color = Color(0xFFBDBDBD),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Security: enabling CORS lets any web page your browser visits call your " +
                            "local server. Only enable it on machines you trust.",
                        color = Color(0xFFC9A227),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SetupStep(
    num: String,
    body: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            num,
            color = Color(0xFF8A8A8A),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(22.dp),
        )
        Text(body, color = Color(0xFFD8D8D8))
    }
}

private data class ShellTab(
    val id: Long,
    val label: String,
    val runner: KashSessionRunner,
)

/** A host-supplied [WorkspacePolicy] awaiting application, with its reply id. */
private data class PendingConfigure(
    val policy: WorkspacePolicy,
    val replyId: String,
)

private var nextTabId: Long = 1

private fun newTab(
    workspace: KashWorkspaceVm,
    label: String,
    onExit: (Long) -> Unit = {},
): ShellTab {
    val id = nextTabId++
    val runner = KashSessionRunner(workspace, onExit = { onExit(id) })
    runner.start()
    return ShellTab(id = id, label = label, runner = runner)
}

/**
 * Slim custom top bar — Material3 `TopAppBar` is ~64dp and we want
 * something closer to a browser chrome strip (~36dp). It's a single
 * Row with our own height, so the layout never shifts based on tab
 * count or other dynamic content.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun WorkspaceTopBar(
    activeLabel: String?,
    onNewShell: () -> Unit,
    onNewMachine: () -> Unit,
    onSave: () -> Unit,
    onManage: () -> Unit,
    onAbout: () -> Unit,
    onAiSetup: () -> Unit,
) {
    var fileMenuOpen by remember { mutableStateOf(false) }
    var aboutMenuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("kash", fontWeight = FontWeight.SemiBold, color = Color(0xFFEAEAEA))
        if (activeLabel != null) {
            Text(
                " · $activeLabel",
                color = Color(0xFF9A9A9A),
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(
                onClick = {
                    fileMenuOpen = true
                    focusManager.clearFocus(force = true)
                },
                modifier = Modifier.focusProperties { canFocus = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("File", color = Color(0xFFEAEAEA))
            }
            DropdownMenu(
                expanded = fileMenuOpen,
                onDismissRequest = { fileMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("New Shell") },
                    onClick = {
                        fileMenuOpen = false
                        onNewShell()
                    },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("New Machine…") },
                    onClick = {
                        fileMenuOpen = false
                        onNewMachine()
                    },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Save Snapshot…") },
                    onClick = {
                        fileMenuOpen = false
                        onSave()
                    },
                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Snapshots…") },
                    onClick = {
                        fileMenuOpen = false
                        onManage()
                    },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                )
            }
        }
        Box {
            TextButton(
                onClick = {
                    aboutMenuOpen = true
                    focusManager.clearFocus(force = true)
                },
                modifier = Modifier.focusProperties { canFocus = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("About", color = Color(0xFFEAEAEA))
            }
            DropdownMenu(
                expanded = aboutMenuOpen,
                onDismissRequest = { aboutMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("About kash") },
                    onClick = {
                        aboutMenuOpen = false
                        onAbout()
                    },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Set up AI · LM Studio") },
                    onClick = {
                        aboutMenuOpen = false
                        onAiSetup()
                    },
                    leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                )
            }
        }
    }
}

/**
 * Horizontal tab strip across the top of the workspace, below the
 * TopAppBar. Browser-tab style: each entry is a clickable surface with
 * a close glyph; `+` button on the right adds a new shell.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun ShellTabStrip(
    tabs: List<ShellTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Fixed height so the layout doesn't shift when the tab
                // list goes empty / non-empty. The tabs themselves
                // fillMaxHeight inside this strip.
                .height(32.dp)
                .background(Color(0xFF111111))
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs.size) { idx ->
                val tab = tabs[idx]
                val active = idx == activeIndex
                Surface(
                    color = if (active) Color(0xFF2A2A40) else Color(0xFF1F1F1F),
                    contentColor = Color(0xFFEAEAEA),
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .focusProperties { canFocus = false },
                    onClick = { onSelect(idx) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            tab.label,
                            fontFamily = FontFamily.Monospace,
                            color = if (active) Color(0xFFEAEAEA) else Color(0xFFB0B0B0),
                        )
                        Spacer(Modifier.size(6.dp))
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .clickable { onClose(idx) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close tab",
                                tint = Color(0xFF999999),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clickable { onNew() }
                    .focusProperties { canFocus = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New shell",
                tint = Color(0xFFEAEAEA),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Left-side collapsible FS explorer. Collapsed: a 28dp rail with a
 * toggle glyph. Expanded: a 240dp panel with a tree-style listing
 * rooted at /home/user (the workspace's HOME). Click a directory row
 * to expand/collapse; refresh button re-reads the FS.
 *
 * No live updates — kash's FileSystem doesn't push change notifications.
 * The user pokes refresh after running commands that mutate files.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun FsExplorerSidebar(
    fs: com.accucodeai.kash.fs.FileSystem,
    expanded: Boolean,
    onToggle: () -> Unit,
    dropHost: BrowserDropHost,
) {
    if (!expanded) {
        Box(
            modifier =
                Modifier
                    .width(28.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF161616))
                    .clickable { onToggle() }
                    .focusProperties { canFocus = false },
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Expand file explorer",
                tint = Color(0xFF8A8A8A),
                modifier = Modifier.padding(top = 8.dp).size(18.dp),
            )
        }
        return
    }

    // expanded state of directories, keyed by absolute path
    val openDirs = remember { mutableStateListOf("/home/user") }
    // Bumping this re-keys the `remember` that builds tree rows, forcing
    // a re-read. Driven both by the manual `R` button (escape hatch) and
    // by `fs.watch("/")` below (auto-refresh on FS mutations).
    var refreshTick by remember { mutableStateOf(0) }

    // Subscribe to filesystem change events. .conflate() + a 100 ms
    // pace cap means a burst (e.g. `tar x`) collapses into a single
    // redraw instead of thrashing the tree builder.
    LaunchedEffect(fs) {
        fs
            .watch("/")
            .conflate()
            .collect {
                refreshTick++
                kotlinx.coroutines.delay(100)
            }
    }

    // Sidebar bounds in document-client-pixel space — captured via
    // onGloballyPositioned on the panel below, used by the drop host's
    // hit-test. Stored as a 4-tuple of Doubles so the registered
    // bounds() lambda can read live values without re-registering.
    val sidebarX = remember { mutableStateOf(0.0) }
    val sidebarY = remember { mutableStateOf(0.0) }
    val sidebarW = remember { mutableStateOf(0.0) }
    val sidebarH = remember { mutableStateOf(0.0) }

    DisposableEffect(dropHost, fs) {
        val id =
            dropHost.register(
                bounds = {
                    BrowserDropHost.DropRect(
                        sidebarX.value,
                        sidebarY.value,
                        sidebarW.value,
                        sidebarH.value,
                    )
                },
                onDrop = { files, _, _ ->
                    val target = "/home/user"
                    var written = 0
                    for (f in files) {
                        val safe = sanitizeDropName(f.name)
                        val finalPath = uniqueDropPath(fs, target, safe)
                        try {
                            if (!fs.exists(target)) fs.mkdirs(target)
                            fs.writeBytes(finalPath, f.bytes)
                            written++
                        } catch (_: Throwable) {
                            // Skip silently — surfaced via the diff in the count.
                        }
                    }
                    BrowserDropHost.DropOutcome(
                        message = "Imported $written file(s) into $target",
                    )
                },
            )
        onDispose { dropHost.unregister(id) }
    }

    Column(
        modifier =
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Color(0xFF161616))
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    sidebarX.value = pos.x.toDouble()
                    sidebarY.value = pos.y.toDouble()
                    sidebarW.value = coords.size.width.toDouble()
                    sidebarH.value = coords.size.height.toDouble()
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text("Files", color = Color(0xFF8A8A8A), modifier = Modifier.weight(1f))
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clickable { refreshTick++ }
                        .focusProperties { canFocus = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = Color(0xFF8A8A8A),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(6.dp))
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clickable { onToggle() }
                        .focusProperties { canFocus = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Collapse",
                    tint = Color(0xFF8A8A8A),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = Color(0xFF222222))
        // Read directory tree synchronously each time refreshTick or
        // openDirs changes. With our InMemoryFs this is fast — no I/O,
        // just map lookups. If we ever back with a slower FS we'd want
        // to push this to a Dispatchers.Default coroutine.
        val rows =
            remember(refreshTick, openDirs.toList()) {
                buildFsTreeRows(fs, root = "/", open = openDirs.toSet())
            }
        // Sidebar-level dialog state. Inline so the LazyColumn's per-row
        // pointerInput handlers can flip it without prop-drilling through
        // the row composable.
        var renameTarget by remember { mutableStateOf<String?>(null) }
        var moveTarget by remember { mutableStateOf<String?>(null) }
        var deleteTarget by remember { mutableStateOf<String?>(null) }
        val sidebarScope = rememberCoroutineScope()

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(rows.size) { i ->
                val row = rows[i]
                FsRowItem(
                    fs = fs,
                    row = row,
                    isOpen = row.path in openDirs,
                    onToggleDir = {
                        if (row.path in openDirs) openDirs.remove(row.path) else openDirs.add(row.path)
                    },
                    onRename = { renameTarget = row.path },
                    onMove = { moveTarget = row.path },
                    onDelete = { deleteTarget = row.path },
                )
            }
        }

        // Rename dialog — simple text field prefilled with the row's
        // basename. Same-directory rename only (Move… is the cross-dir
        // operation).
        renameTarget?.let { target ->
            FsRenameDialog(
                fs = fs,
                path = target,
                onDismiss = { renameTarget = null },
                onConfirm = { newName ->
                    val parent = target.substringBeforeLast('/', missingDelimiterValue = "")
                    val dest =
                        if (parent.isEmpty() || parent == "/") {
                            "/$newName"
                        } else {
                            "$parent/$newName"
                        }
                    sidebarScope.launch {
                        try {
                            fs.rename(target, dest)
                        } catch (_: Throwable) {
                            // Surface via the existing watch-driven redraw.
                        }
                    }
                    renameTarget = null
                },
            )
        }

        // Move dialog — full destination path, dialog suggests
        // `<parent>/<basename>` as a starting point.
        moveTarget?.let { target ->
            FsMoveDialog(
                fs = fs,
                path = target,
                onDismiss = { moveTarget = null },
                onConfirm = { dest ->
                    sidebarScope.launch {
                        try {
                            fs.rename(target, dest)
                        } catch (_: Throwable) {
                        }
                    }
                    moveTarget = null
                },
            )
        }

        // Delete confirmation.
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete?") },
                text = {
                    Text(
                        "Delete \"$target\"? This cannot be undone.",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            fs.remove(target)
                        } catch (_: Throwable) {
                        }
                        deleteTarget = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * Single row of the FS explorer tree. Left-click toggles directory open
 * state; right-click opens a context menu with Rename / Move / Delete /
 * Copy path / Download.
 *
 * The menu is anchored at the row's right edge (close-enough placement
 * for Compose's `DropdownMenu`, which doesn't accept arbitrary
 * positioning on web without pulling in a `Popup` from scratch).
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun FsRowItem(
    fs: com.accucodeai.kash.fs.FileSystem,
    row: FsRow,
    isOpen: Boolean,
    onToggleDir: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val rowScope = rememberCoroutineScope()
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { if (row.isDir) onToggleDir() }
                    // Secondary-button detection — Compose's `clickable`
                    // doesn't disambiguate primary vs secondary, so we
                    // intercept at the pointer level on a sibling
                    // modifier. Consuming the change keeps `clickable`
                    // from also firing on the same press.
                    .pointerInput(row.path) {
                        awaitEachGesture {
                            val event = awaitPointerEvent()
                            // Compose Multiplatform desktop/web sets
                            // PointerEvent.button to PointerButton.Secondary
                            // for right-clicks. On a touch event button is
                            // null. We open the menu only on Press to avoid
                            // re-firing on Release/Move events in the same
                            // gesture stream.
                            if (event.button == PointerButton.Secondary &&
                                event.type == PointerEventType.Press
                            ) {
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }.focusProperties { canFocus = false }
                    .padding(start = (8 + row.depth * 12).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (row.isDir) {
                Icon(
                    imageVector = if (isOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(14.dp),
                )
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFFAEC6F0),
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Spacer(Modifier.size(14.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                row.name,
                color = if (row.isDir) Color(0xFFAEC6F0) else Color(0xFFCFCFCF),
                fontFamily = FontFamily.Monospace,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 32.dp, y = 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Rename…") },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Move to…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text("Copy path") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    writeToClipboard(row.path)
                },
            )
            if (!row.isDir) {
                DropdownMenuItem(
                    text = { Text("Download…") },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        rowScope.launch {
                            try {
                                val bytes = fs.readBytes(row.path)
                                downloadBytes(row.name, bytes)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun FsRenameDialog(
    fs: com.accucodeai.kash.fs.FileSystem,
    path: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initialName = path.substringAfterLast('/')
    var name by remember(path) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                Text(path, color = Color(0xFF8A8A8A), fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != initialName && '/' !in name,
                onClick = { onConfirm(name.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    // Suppress unused — kept for future "name already taken?" pre-check.
    @Suppress("UNUSED_EXPRESSION")
    fs
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun FsMoveDialog(
    fs: com.accucodeai.kash.fs.FileSystem,
    path: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dest by remember(path) { mutableStateOf(path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move") },
        text = {
            Column {
                Text("Source:", color = Color(0xFF8A8A8A))
                Text(path, color = Color(0xFFCFCFCF), fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    label = { Text("Destination path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dest.isNotBlank() && dest != path && dest.startsWith("/"),
                onClick = { onConfirm(dest.trim()) },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    @Suppress("UNUSED_EXPRESSION")
    fs
}

private data class FsRow(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val depth: Int,
)

private fun buildFsTreeRows(
    fs: com.accucodeai.kash.fs.FileSystem,
    root: String,
    open: Set<String>,
): List<FsRow> {
    val out = mutableListOf<FsRow>()

    fun walk(
        path: String,
        depth: Int,
    ) {
        val names =
            try {
                fs.list(path).sorted()
            } catch (_: Throwable) {
                return
            }
        for (n in names) {
            val child = if (path == "/") "/$n" else "$path/$n"
            val isDir =
                try {
                    fs.isDirectory(child)
                } catch (_: Throwable) {
                    false
                }
            out += FsRow(name = n, path = child, isDir = isDir, depth = depth)
            if (isDir && child in open) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun EmptyState(onNewShell: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No shells open.", color = Color(0xFF8A8A8A))
            Spacer(Modifier.size(12.dp))
            Button(onClick = onNewShell) { Text("Open a shell") }
        }
    }
}

/**
 * Confirmation prompt for a KashFrame host request. An origin-validated
 * message is authorization to *ask*, not to act — restore replaces the
 * session, export hands the workspace to the host, so both need explicit
 * consent (unless the origin is trusted, in which case this dialog is
 * skipped entirely).
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun KashFrameConfirmDialog(
    request: KashFrameRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val body: String
    val confirmText: String
    when (request) {
        is KashFrameRequest.Restore -> {
            title = "Restore snapshot?"
            body =
                "“${request.origin}” wants to replace your current session with the snapshot " +
                "“${request.name}”. Your current shells and files will be discarded."
            confirmText = "Restore"
        }

        is KashFrameRequest.Export -> {
            title = "Share snapshot?"
            body =
                "“${request.origin}” is requesting a copy of your current workspace " +
                "(${if (request.fsOnly) "files only" else "full machine state"})."
            confirmText = "Share"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SaveSnapshotDialog(
    defaultName: String,
    canTakeFull: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, fsOnly: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var fsOnly by remember { mutableStateOf(!canTakeFull) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save snapshot") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = fsOnly,
                        onCheckedChange = { fsOnly = it },
                        enabled = canTakeFull,
                    )
                    Text(
                        "Filesystem only (skip shell state)",
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    if (fsOnly) {
                        "Files / dirs only. The new shell starts with a fresh interpreter."
                    } else {
                        "Captures interpreter state (functions, aliases, vars, cwd, history) plus the filesystem."
                    },
                    color = Color(0xFF8A8A8A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), fsOnly) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The snapshot hub: import (Upload file / Paste), and a list of saved
 * snapshots where clicking a row loads it and each row carries
 * Download / Copy / Delete actions. Save itself stays in the File menu.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun ManageSnapshotsDialog(
    onDismiss: () -> Unit,
    onLoad: (BrowserSnapshotStore.SavedSnapshotMeta) -> Unit,
    onDownload: (BrowserSnapshotStore.SavedSnapshotMeta) -> Unit,
    onCopy: (BrowserSnapshotStore.SavedSnapshotMeta) -> Unit,
    onDelete: (BrowserSnapshotStore.SavedSnapshotMeta) -> Unit,
    onUpload: () -> Unit,
    onPaste: () -> Unit,
) {
    // Local copy so a delete refreshes the list without re-opening.
    val entries = remember { mutableStateListOf<BrowserSnapshotStore.SavedSnapshotMeta>() }
    LaunchedEffect(Unit) {
        entries.clear()
        entries.addAll(BrowserSnapshotStore.list())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snapshots") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onUpload) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Upload file…")
                    }
                    Spacer(Modifier.size(4.dp))
                    TextButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Paste")
                    }
                }
                HorizontalDivider(color = Color(0xFF222222))
                Spacer(Modifier.size(6.dp))
                if (entries.isEmpty()) {
                    Text(
                        "No saved snapshots. Use File ▸ Save Snapshot, or import one above.",
                        color = Color(0xFF8A8A8A),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Text(
                        "Click a snapshot to load it.",
                        color = Color(0xFF8A8A8A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(entries.toList()) { meta ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // Clickable load area.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clickable { onLoad(meta) }
                                            .padding(vertical = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = Color(0xFF8A8A8A),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Column {
                                        Text(meta.name, fontFamily = FontFamily.Monospace)
                                        Text(
                                            "${kindLabel(meta.kind)} · ${formatBytes(meta.sizeBytes)}",
                                            color = Color(0xFF8A8A8A),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                SnapshotRowAction(
                                    Icons.Default.Download,
                                    "Download “${meta.name}”",
                                ) { onDownload(meta) }
                                SnapshotRowAction(Icons.Default.ContentCopy, "Copy “${meta.name}”") { onCopy(meta) }
                                SnapshotRowAction(Icons.Default.Delete, "Delete “${meta.name}”") {
                                    onDelete(meta)
                                    entries.remove(meta)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SnapshotRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clickable { onClick() }
                .focusProperties { canFocus = false },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color(0xFF9A9A9A),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun kindLabel(k: Kind): String =
    when (k) {
        Kind.FULL -> "Full machine"
        Kind.FS_ONLY -> "Filesystem only"
    }

private fun formatBytes(n: Int): String =
    when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${(n / 1024.0).round1()} KB"
        else -> "${(n / (1024.0 * 1024.0)).round1()} MB"
    }

private fun Double.round1(): String {
    val v = (this * 10).toLong() / 10.0
    return v.toString()
}

private val WindowBackground = Color(0xFF0B0B0B)
private val TerminalBackground = Color(0xFF0B0B0B)
private val WorkspaceColors =
    darkColorScheme(
        primary = Color(0xFF7AA2F7),
        background = Color(0xFF0B0B0B),
        surface = Color(0xFF1A1A1A),
    )
