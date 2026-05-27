package com.accucodeai.kash.api

/**
 * The host UI's hook for handing files to a foreground command that has
 * attachment semantics. Set on [KashMachine.activeAttachmentSink] by the
 * command while it's running, cleared in `finally`.
 *
 * Today only the `agent` command registers a sink — it surfaces drops as
 * `[attachment 1]`, `[attachment 2]`, … inline in the prompt and expands
 * them into structured user-message parts on submit. Other commands could
 * adopt the same interface later (a hypothetical `mail` composer, an image
 * viewer, etc.).
 *
 * Single-slot, last-writer-wins. The current workspace runs at most one
 * attachment-aware foreground command at a time; multi-tab simultaneous
 * agents will race and the more recently registered one captures drops.
 * Good enough for v1.
 */
public interface AttachmentSink {
    /**
     * Register a freshly-dropped file. The bytes are already written to
     * the VM filesystem at [path] by the drop handler; the sink only needs
     * to remember the metadata and surface it to the user.
     *
     * Returns the new 1-based attachment index — the drop handler pastes
     * `[attachment N] ` into the prompt with this value.
     */
    public suspend fun add(
        path: String,
        mimeType: String,
        sizeBytes: Long,
        fileName: String,
    ): Int
}
