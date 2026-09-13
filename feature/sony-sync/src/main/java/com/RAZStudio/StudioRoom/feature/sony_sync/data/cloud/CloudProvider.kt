/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud

/**
 * Cloud-storage abstraction for Sony Sync's live-upload feature. One
 * implementation per provider (Google Drive first; OneDrive / Dropbox /
 * MediaFire added later behind this same surface). All calls are blocking
 * network I/O — invoke from an IO dispatcher.
 */
interface CloudProvider {
    /** Human name for the UI ("Google Drive"). */
    val displayName: String

    /** True once a usable credential (token) is set. */
    fun isConnected(): Boolean

    /** Provide/replace the access token (pasted by the user for now). */
    fun setAccessToken(token: String)

    /** Verify the token by making a cheap authenticated call. */
    fun verify(): CloudResult<String>          // returns the account email/name

    /** Create a folder (at Drive root) and return it. */
    fun createFolder(name: String): CloudResult<CloudFolder>

    /** Make [folderId] world-readable ("anyone with the link") and return the public link. */
    fun makeFolderPublic(folderId: String): CloudResult<String>

    /** Upload a JPEG into [folderId]. Returns the created file id. */
    fun uploadJpeg(folderId: String, fileName: String, bytes: ByteArray): CloudResult<String>

    /** Extract a folder id from a user-pasted share link/URL (null if it isn't one). */
    fun parseFolderId(link: String): String?

    /** Inspect a folder: is it really a folder, and can this account write to it? */
    fun checkFolder(folderId: String): CloudResult<FolderInfo>
}

/** Result of [CloudProvider.checkFolder] — folder correctness + write access. */
data class FolderInfo(
    val name: String,
    val isFolder: Boolean,
    val canWrite: Boolean,
)

/** A created/selected cloud folder + its public link once shared. */
data class CloudFolder(
    val id: String,
    val name: String,
    val shareLink: String? = null,
)

/** Tiny result type so callers get an error message instead of exceptions. */
sealed interface CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>
    data class Err(val message: String) : CloudResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    val errorOrNull: String? get() = (this as? Err)?.message
}
