package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.core.models.Device

/**
 * API for controlling the local Media Server (if enabled).
 * Spec Reference: 5.6
 */
interface MediaServerApi {
    fun isRunning(): Boolean
    fun getServerDevice(): Device?
    
    /**
     * Forces the server to clear caches or re-index content 
     * if the app's content has changed.
     */
    fun refreshContent()
}