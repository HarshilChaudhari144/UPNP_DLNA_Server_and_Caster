package com.example.mysecondapp.dlna_lib.core.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The single source of truth for the library's concurrency scope.
 *
 * Rules:
 * 1. Uses Dispatchers.Default for CPU-intensive work (XML parsing).
 * 2. Uses SupervisorJob so a failure in one child (e.g., parsing one device)
 *    does not cancel the scope for other devices.
 */
internal val dlnaScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)