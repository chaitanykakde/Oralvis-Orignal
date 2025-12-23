package com.oralvis.oralviscamera.core.identity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clinic identity persistence using SAF.
 *
 * The user must grant access once to `Documents/oralvis/` via ACTION_OPEN_DOCUMENT_TREE.
 * We persist the granted tree URI (takePersistableUriPermission) so the encrypted
 * `clinic.dat` survives app uninstall/reinstall. A factory reset or manual deletion
 * still clears it, requiring re-selection/registration.
 */
object ClinicIdentityManager {

    private const val TAG = "ClinicIdentityManager"
    private const val PREFS = "clinic_identity_prefs"
    private const val KEY_TREE_URI = "saf_tree_uri"
    private const val FILE_NAME = "clinic.dat"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_ID_SALT = "ORALVIS_CLINIC_V1"
    private const val HASH_LENGTH = 10 // 8–12 chars as required
    private const val IV_SIZE = 12
    private const val GCM_TAG_SIZE = 128

    // 16-byte AES key (128-bit). Keep this private to the app.
    private val SECRET_KEY: SecretKey =
        SecretKeySpec("OvKx9a3F0r4lVis!".toByteArray(Charsets.UTF_8), "AES")

    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedClinicId: String? = null

    fun needsFolderSelection(context: Context): Boolean {
        val uri = getPersistedTreeUri(context) ?: return true
        return !hasPersistedPermission(context, uri)
    }

    fun buildFolderSelectionIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

    fun persistFolderSelection(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
            Log.d(TAG, "Persisted SAF tree URI: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist SAF URI permission", e)
        }
    }

    /**
     * Main entry point. Attempts to load from SAF; if missing, derives from ANDROID_ID.
     * If a SAF folder is already granted, the derived ID is immediately persisted.
     */
    fun getClinicId(context: Context): String {
        cachedClinicId?.let { return it }
        val appCtx = context.applicationContext

        // 1) Try load from SAF
        val fromStorage = tryLoadClinicIdInternal(appCtx)
        if (!fromStorage.isNullOrBlank()) {
            cachedClinicId = fromStorage
            return fromStorage
        }

        // 2) Derive from ANDROID_ID and persist if we have a folder.
        val derived = deriveClinicId(appCtx)
        cachedClinicId = derived
        persistClinicId(appCtx, derived)
        return derived
    }

    /**
     * Try to read an existing clinicId from SAF only (no derivation).
     */
    fun tryLoadClinicId(context: Context): String? {
        return tryLoadClinicIdInternal(context.applicationContext)
    }

    /**
     * Explicitly set and persist the clinicId (e.g., after registration).
     * If the SAF folder is missing, this silently logs and returns.
     */
    fun setClinicId(context: Context, clinicId: String) {
        cachedClinicId = clinicId
        persistClinicId(context.applicationContext, clinicId)
    }

    // region Internal load/persist
    private fun tryLoadClinicIdInternal(appCtx: Context): String? {
        val file = resolveClinicFile(appCtx) ?: return null
        return try {
            appCtx.contentResolver.openInputStream(file.uri)?.use { input ->
                val encrypted = input.readBytes()
                if (encrypted.isEmpty()) {
                    Log.w(TAG, "clinic.dat is empty.")
                    return null
                }
                val decrypted = decrypt(encrypted)
                if (decrypted.isBlank()) {
                    Log.w(TAG, "Decrypted clinicId was blank.")
                    null
                } else {
                    Log.d(TAG, "Successfully decrypted clinicId from SAF.")
                    decrypted
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read/decrypt clinicId from SAF, will regenerate", e)
            null
        }
    }

    private fun persistClinicId(appCtx: Context, clinicId: String) {
        val file = ensureClinicFile(appCtx) ?: run {
            Log.w(TAG, "Cannot persist clinicId: SAF folder not selected or inaccessible.")
            return
        }
        try {
            val encrypted = encrypt(clinicId)
            appCtx.contentResolver.openOutputStream(file.uri, "rwt")?.use { out ->
                out.write(encrypted)
                out.flush()
            }
            Log.d(TAG, "Persisted clinicId to SAF file: ${file.uri}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist clinicId via SAF", e)
        }
    }

    private fun resolveClinicFile(appCtx: Context): DocumentFile? {
        val treeUri = getPersistedTreeUri(appCtx) ?: return null
        if (!hasPersistedPermission(appCtx, treeUri)) return null
        val tree = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return null
        val file = tree.findFile(FILE_NAME)
        if (file == null || !file.isFile || !file.canRead()) return null
        return file
    }

    private fun ensureClinicFile(appCtx: Context): DocumentFile? {
        val treeUri = getPersistedTreeUri(appCtx) ?: return null
        if (!hasPersistedPermission(appCtx, treeUri)) return null
        val tree = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return null
        val existing = tree.findFile(FILE_NAME)
        return existing ?: tree.createFile("application/octet-stream", FILE_NAME)
    }
    // endregion

    // region Encryption / derivation helpers
    private fun deriveClinicId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        val digest = MessageDigest.getInstance("SHA-256")
            .digest((androidId + ANDROID_ID_SALT).toByteArray(Charsets.UTF_8))

        val hashed = digest.toHexUpper().take(HASH_LENGTH)
        return "CLN_$hashed"
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, GCMParameterSpec(GCM_TAG_SIZE, iv))
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return iv + cipherText
    }

    private fun decrypt(encrypted: ByteArray): String {
        require(encrypted.size > IV_SIZE) { "Invalid encrypted payload" }
        val iv = encrypted.copyOfRange(0, IV_SIZE)
        val payload = encrypted.copyOfRange(IV_SIZE, encrypted.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, GCMParameterSpec(GCM_TAG_SIZE, iv))
        val plain = cipher.doFinal(payload)
        return String(plain, Charsets.UTF_8)
    }

    private fun ByteArray.toHexUpper(): String {
        val result = StringBuilder(size * 2)
        for (byte in this) {
            result.append(String.format("%02X", byte))
        }
        return result.toString()
    }
    // endregion

    // region SAF helpers
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getPersistedTreeUri(context: Context): Uri? {
        val uriString = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPersistedPermission(context: Context, uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == uri && perm.isReadPermission && perm.isWritePermission
        }
    }
    // endregion
}


