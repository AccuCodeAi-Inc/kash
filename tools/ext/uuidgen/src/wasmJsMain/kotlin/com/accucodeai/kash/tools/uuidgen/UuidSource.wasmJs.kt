package com.accucodeai.kash.tools.uuidgen

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * wasmJs implementation. `kotlin.uuid.Uuid.random()` produces a v4 UUID via
 * a CSPRNG (the browser's `crypto.getRandomValues` under the hood).
 *
 * `newTimeUuid` falls back to v4 — per the expect contract,
 * "Implementations may fall back to v4 if unsupported". A real v1 generator
 * needs a stable node ID across sessions, which on a browser tab isn't
 * meaningful anyway (every tab would invent its own).
 */
@OptIn(ExperimentalUuidApi::class)
internal actual fun newRandomUuid(): String = Uuid.random().toString()

@OptIn(ExperimentalUuidApi::class)
internal actual fun newTimeUuid(): String = Uuid.random().toString()
