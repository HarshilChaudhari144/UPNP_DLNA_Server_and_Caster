package com.example.mysecondapp.dlna_lib.core.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A shared CoroutineScope for all core internal tasks.
 * Using SupervisorJob ensures that one failing network request doesn't
 * crash the entire DLNA engine.
 */
internal val dlnaScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)