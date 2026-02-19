package com.oralvis.oralviscamera.feature.gallery.flow

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.oralvis.oralviscamera.FindPatientsActivity
import com.oralvis.oralviscamera.GlobalPatientManager
import com.oralvis.oralviscamera.SyncOrchestrator
import com.oralvis.oralviscamera.database.MediaRepository
import com.oralvis.oralviscamera.database.MediaState
import com.oralvis.oralviscamera.gallery.SequenceCard
import com.oralvis.oralviscamera.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GalleryFlowCoordinator(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val mediaRepository: MediaRepository,
    private val onUpdateTabContent: (List<com.oralvis.oralviscamera.database.MediaRecordV2>) -> Unit,
    private val onShowNoPatientState: () -> Unit,
    private val onShowSessionEmptyState: () -> Unit,
    private val onRedirectToPatientSelection: () -> Unit,
    private val onSyncProgress: (current: Int, total: Int) -> Unit,
    private val onSyncPhaseChange: () -> Unit,
    private val onSyncComplete: (success: Boolean, uploadCount: Int, downloadCount: Int, error: String?) -> Unit
) {
    
    var allMedia: List<com.oralvis.oralviscamera.database.MediaRecordV2> = emptyList()
        private set
    
    fun observeMediaForCurrentPatient() {
        val patientId = GlobalPatientManager.getCurrentPatientId()
        android.util.Log.d("GALLERY_DEBUG", "observeMediaForCurrentPatient called")
        android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.getCurrentPatientId() = $patientId")
        android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.hasPatientSelected() = ${GlobalPatientManager.hasPatientSelected()}")

        if (patientId == null) {
            android.util.Log.d("GalleryActivity", "No patient selected, redirecting to patient selection")
            onRedirectToPatientSelection()
            return
        }

        // Additional validation: check if patient exists in database
        lifecycleOwner.lifecycleScope.launch {
            try {
                val patient = GlobalPatientManager.getCurrentPatient()
                android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.getCurrentPatient() = $patient")
                if (patient != null) {
                    android.util.Log.d("GALLERY_DEBUG", "Patient details: id=${patient.id}, code=${patient.code}, name=${patient.displayName}")
                }

                if (patient == null) {
                    android.util.Log.e("GalleryActivity", "Invalid patientId $patientId, redirecting to patient selection")
                    onRedirectToPatientSelection()
                    return@launch
                }

                android.util.Log.d("GalleryActivity", "Starting session media observation for patient: ${patient.displayName} (id=$patientId)")
                observeMediaForCurrentSession()
            } catch (e: Exception) {
                android.util.Log.e("GalleryActivity", "Error validating patient: ${e.message}")
                onRedirectToPatientSelection()
            }
        }
    }

    fun observeMediaForCurrentSession() {
        val patientId = GlobalPatientManager.getCurrentPatientId()
        val sessionId = SessionManager(context).getCurrentSessionId()

        android.util.Log.d("GALLERY_DEBUG", "observeMediaForCurrentSession called with patientId: $patientId, sessionId: $sessionId")

        if (patientId == null || sessionId == null) {
            android.util.Log.d("GalleryActivity", "No active patient or session, showing empty gallery")
            onShowSessionEmptyState()
            return
        }

        // Use repeatOnLifecycle to properly observe the Flow
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    android.util.Log.d("GalleryActivity", "Collecting session media flow for patientId: $patientId, sessionId: $sessionId")
                    android.util.Log.d("SESSION_DEBUG", "Fetching session media for patientId=$patientId, sessionId=$sessionId")
                    mediaRepository.getMediaForCurrentSession(patientId, sessionId).collect { mediaList ->
                        android.util.Log.d("GALLERY_DEBUG", "Session flow collected - mediaList.size = ${mediaList.size}")
                        android.util.Log.d("GalleryActivity", "Received session media list update: ${mediaList.size} items")
                        android.util.Log.d("SESSION_MEDIA", "Gallery received ${mediaList.size} media records for sessionId=$sessionId")

                        // Detailed logging of each media item
                        mediaList.forEachIndexed { index, media ->
                            android.util.Log.d("GALLERY_DEBUG", "SessionMedia[$index]: mediaId=${media.mediaId}, sessionId=${media.sessionId}, patientId=${media.patientId}, state=${media.state}, fileName=${media.fileName}")
                        }

                        allMedia = mediaList

                        // Update tab content with the new media (this will handle grouping and adapter updates)
                        onUpdateTabContent(mediaList)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GalleryActivity", "Error observing session media: ${e.message}")
                    onShowSessionEmptyState()
                }
            }
        }
    }
    
    /**
     * Group media into sequence cards based on arch tab selection.
     */
    fun groupMediaIntoSequences(
        mediaList: List<com.oralvis.oralviscamera.database.MediaRecordV2>,
        currentArchTab: Int
    ): List<SequenceCard> {
        android.util.Log.d("GROUP_DEBUG", "groupMediaIntoSequences called with ${mediaList.size} media items")
        android.util.Log.d("GROUP_DEBUG", "Current arch tab: $currentArchTab")

        // DEBUG: Log all media with their properties
        mediaList.forEachIndexed { index, media ->
            android.util.Log.d("GROUP_DEBUG", "Media[$index]: mediaId=${media.mediaId}, fileName=${media.fileName}, arch=${media.dentalArch}, mode=${media.mode}, sessionId=${media.sessionId}, guidedId=${media.guidedSessionId}, seqNum=${media.sequenceNumber}, state=${media.state}")
        }

        // 2️⃣ Log BEFORE TAB FILTERING
        android.util.Log.d("MEDIA_DEBUG", "Before filtering: total media = ${mediaList.size}")
        val archGroups = mediaList.groupBy { it.dentalArch ?: "UNGUIDED" }
        archGroups.forEach { (arch, list) ->
            android.util.Log.d("MEDIA_DEBUG", "Arch=$arch count=${list.size}")
        }

        // DEBUG: Log current tab and media details
        android.util.Log.d("FILTER_DEBUG", "=== FILTERING DEBUG ===")
        android.util.Log.d("FILTER_DEBUG", "Current tab: $currentArchTab")
        android.util.Log.d("FILTER_DEBUG", "Total media before filtering: ${mediaList.size}")

        // Log arch distribution
        val archCounts = mediaList.groupBy { it.dentalArch ?: "NULL" }
        archCounts.forEach { (arch, list) ->
            android.util.Log.d("FILTER_DEBUG", "Arch '$arch': ${list.size} items")
        }

        // Filter by current arch tab
        // UPPER = guided UPPER only. LOWER = guided LOWER only. OTHER = unguided (remote/manual/record) and any non-UPPER/non-LOWER.
        val filteredMedia = when (currentArchTab) {
            0 -> {
                // UPPER tab: only guided UPPER media
                val filtered = mediaList.filter { it.dentalArch == "UPPER" }
                android.util.Log.d("FILTER_DEBUG", "UPPER tab result: ${filtered.size} items")
                filtered
            }
            1 -> {
                // LOWER tab: only guided LOWER media (no unguided)
                val filtered = mediaList.filter { it.dentalArch == "LOWER" }
                android.util.Log.d("FILTER_DEBUG", "LOWER tab: guided lower=${filtered.size} items")
                filtered
            }
            2 -> {
                // OTHER tab: unguided (dentalArch == null) + any other arch value (remote/manual/record captures go here)
                val filtered = mediaList.filter { it.dentalArch != "UPPER" && it.dentalArch != "LOWER" }
                android.util.Log.d("FILTER_DEBUG", "OTHER tab: unguided+other=${filtered.size} items")
                filtered
            }
            else -> {
                android.util.Log.d("FILTER_DEBUG", "Unknown tab $currentArchTab, returning empty")
                emptyList()
            }
        }

        android.util.Log.d("FILTER_DEBUG", "Final filtered count: ${filteredMedia.size}")
        android.util.Log.d("FILTER_DEBUG", "=== END FILTERING DEBUG ===")

        // 3️⃣ Log AFTER TAB FILTERING
        android.util.Log.d(
            "MEDIA_DEBUG",
            "After filtering for tab=$currentArchTab, count=${filteredMedia.size}"
        )
        filteredMedia.forEach { media ->
            android.util.Log.d(
                "MEDIA_DEBUG",
                "FILTERED mediaId=${media.mediaId}, arch=${media.dentalArch}, cloud=${media.cloudFileName}, fileName=${media.fileName}"
            )
        }

        if (filteredMedia.isEmpty()) {
            android.util.Log.d("GalleryActivity", "No media found for current arch tab")
            return emptyList()
        }

        // Other tab: no pairing or sequence — show a flat list of images (one card per image)
        if (currentArchTab == 2) {
            return filteredMedia.sortedBy { it.captureTime }.mapIndexed { index, media ->
                SequenceCard(
                    sequenceNumber = index + 1,
                    dentalArch = "OTHER",
                    guidedSessionId = null,
                    rgbImage = if (media.mode == "Normal") media else null,
                    fluorescenceImage = if (media.mode == "Fluorescence") media else null
                )
            }
        }
        
        // Upper/Lower tabs: group by guidedSessionId, dentalArch, and sequenceNumber (pairing)
        val sequenceMap = mutableMapOf<String, MutableMap<Int, SequenceCard>>()

        // First, handle guided media (local captured with dentalArch, sequenceNumber, and guidedSessionId)
        // Cloud media (DOWNLOADED state) is treated as unguided even if it has sequenceNumber
        val guidedMedia = filteredMedia.filter { it.dentalArch != null && it.sequenceNumber != null && it.guidedSessionId != null && it.state != MediaState.DOWNLOADED }
        val unguidedMedia = filteredMedia.filter { it.dentalArch == null || it.state == MediaState.DOWNLOADED }

        // Group guided media by guidedSessionId + arch and pair optimally within each group
        val guidedGroups = guidedMedia.groupBy { media ->
            val arch = media.dentalArch!!
            val sessionId = media.guidedSessionId ?: "legacy_${media.sessionId}"
            "$sessionId|$arch"
        }

        // Process each guided session+arch group to create optimally paired sequence cards
        guidedGroups.forEach { (groupKey, mediaList) ->
            val arch = groupKey.substringAfterLast("|")
            val guidedSessionId = mediaList.first().guidedSessionId

            android.util.Log.d("GalleryActivity", "Processing guided group: $groupKey with ${mediaList.size} media items")

            // Separate normal and fluorescence images
            val normalImages = mediaList.filter { it.mode == "Normal" }.sortedBy { it.captureTime }
            val fluorescenceImages = mediaList.filter { it.mode == "Fluorescence" }.sortedBy { it.captureTime }

            // Create paired cards - pair each normal with each fluorescence optimally
            val pairedCount = minOf(normalImages.size, fluorescenceImages.size)
            var pairIndex = 0

            // Create complete pairs (normal + fluorescence)
            for (i in 0 until pairedCount) {
                val cardKey = "$groupKey|paired_$i"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = pairIndex + 1, // Start from 1
                    dentalArch = arch,
                    guidedSessionId = guidedSessionId,
                    rgbImage = normalImages[i],
                    fluorescenceImage = fluorescenceImages[i]
                )

                sequenceMapForSession[pairIndex + 1] = card
                android.util.Log.d("GalleryActivity", "Created complete guided pair: ${card.getTitle()}, normal=${card.rgbImage?.fileName}, fluoro=${card.fluorescenceImage?.fileName}")
                pairIndex++
            }

            // Handle remaining unpaired images
            val remainingNormals = normalImages.drop(pairedCount)
            val remainingFluorescences = fluorescenceImages.drop(pairedCount)

            // Create cards for remaining normal images (with null fluorescence)
            remainingNormals.forEachIndexed { index, normalMedia ->
                val cardKey = "$groupKey|unpaired_normal_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = pairIndex + 1 + index,
                    dentalArch = arch,
                    guidedSessionId = guidedSessionId,
                    rgbImage = normalMedia,
                    fluorescenceImage = null
                )

                sequenceMapForSession[pairIndex + 1 + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired guided normal card: ${card.getTitle()}, normal=${card.rgbImage?.fileName}")
            }

            // Create cards for remaining fluorescence images (with null normal)
            remainingFluorescences.forEachIndexed { index, fluoroMedia ->
                val cardKey = "$groupKey|unpaired_fluoro_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = pairIndex + 1 + remainingNormals.size + index,
                    dentalArch = arch,
                    guidedSessionId = guidedSessionId,
                    rgbImage = null,
                    fluorescenceImage = fluoroMedia
                )

                sequenceMapForSession[pairIndex + 1 + remainingNormals.size + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired guided fluoro card: ${card.getTitle()}, fluoro=${card.fluorescenceImage?.fileName}")
            }
        }

        // Process unguided media (group by session + arch and pair normal + fluorescence optimally)
        val unguidedGroups = unguidedMedia.groupBy { media ->
            // Unguided media should not be forced into LOWER; treat as OTHER so they
            // appear in the "Other" arch bucket in the gallery.
            val arch = media.dentalArch ?: "OTHER"
            "unguided_session_${media.sessionId}|$arch"
        }

        // Process each session+arch group to create optimally paired sequence cards
        unguidedGroups.forEach { (groupKey, mediaList) ->
            val arch = groupKey.substringAfterLast("|")
            val sequenceNum = 1 // All unguided pairs get sequence number 1

            android.util.Log.d("GalleryActivity", "Processing unguided group: $groupKey with ${mediaList.size} media items")

            // Separate normal and fluorescence images
            val normalImages = mediaList.filter { it.mode == "Normal" }.sortedBy { it.captureTime }
            val fluorescenceImages = mediaList.filter { it.mode == "Fluorescence" }.sortedBy { it.captureTime }

            // Create paired cards - pair each normal with each fluorescence optimally
            val pairedCount = minOf(normalImages.size, fluorescenceImages.size)
            var pairIndex = 0

            // Create complete pairs (normal + fluorescence)
            for (i in 0 until pairedCount) {
                val cardKey = "$groupKey|paired_$i"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + i,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = normalImages[i],
                    fluorescenceImage = fluorescenceImages[i]
                )

                sequenceMapForSession[sequenceNum + i] = card
                android.util.Log.d("GalleryActivity", "Created complete pair: ${card.getTitle()}, normal=${card.rgbImage?.fileName}, fluoro=${card.fluorescenceImage?.fileName}")
                pairIndex++
            }

            // Handle remaining unpaired images
            val remainingNormals = normalImages.drop(pairedCount)
            val remainingFluorescences = fluorescenceImages.drop(pairedCount)

            // Create cards for remaining normal images (with null fluorescence)
            remainingNormals.forEachIndexed { index, normalMedia ->
                val cardKey = "$groupKey|unpaired_normal_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + pairIndex + index,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = normalMedia,
                    fluorescenceImage = null
                )

                sequenceMapForSession[sequenceNum + pairIndex + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired normal card: ${card.getTitle()}, normal=${card.rgbImage?.fileName}")
            }

            // Create cards for remaining fluorescence images (with null normal)
            remainingFluorescences.forEachIndexed { index, fluoroMedia ->
                val cardKey = "$groupKey|unpaired_fluoro_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + pairIndex + remainingNormals.size + index,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = null,
                    fluorescenceImage = fluoroMedia
                )

                sequenceMapForSession[sequenceNum + pairIndex + remainingNormals.size + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired fluoro card: ${card.getTitle()}, fluoro=${card.fluorescenceImage?.fileName}")
            }
        }
        
        // Flatten and sort by sequence number
        val allSequences = sequenceMap.values.flatMap { it.values }

        // Log sequence card creation summary
        val guidedCount = guidedMedia.size
        val unguidedCount = unguidedMedia.size
        android.util.Log.d(
            "MEDIA_DEBUG",
            "Created ${allSequences.size} sequence cards from ${filteredMedia.size} filtered media items (guided: $guidedCount, unguided: $unguidedCount)"
        )
        allSequences.forEachIndexed { index, card ->
            android.util.Log.d(
                "MEDIA_DEBUG",
                "SEQUENCE_CARD[$index] seq=${card.sequenceNumber}, arch=${card.dentalArch}, rgbId=${card.rgbImage?.mediaId}, fluoId=${card.fluorescenceImage?.mediaId}, guided=${card.guidedSessionId != null}"
            )
        }

        return allSequences.sortedBy { it.sequenceNumber }
    }
    
    fun showDiscardConfirmation(card: SequenceCard, isOtherTab: Boolean, onConfirm: () -> Unit) {
        val (title, message) = if (isOtherTab) {
            "Discard" to "Are you sure you want to discard this image? This action cannot be undone."
        } else {
            "Discard Pair" to "Are you sure you want to discard this sequence pair? This action cannot be undone."
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Discard") { _, _ ->
                onConfirm()
                discardSequencePair(card, isOtherTab)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    fun discardSequencePair(card: SequenceCard, isSingleItem: Boolean = false) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                card.rgbImage?.let { media ->
                    mediaRepository.deleteMediaAndFile(media.mediaId)
                }
                card.fluorescenceImage?.let { media ->
                    mediaRepository.deleteMediaAndFile(media.mediaId)
                }
                withContext(Dispatchers.Main) {
                    val msg = if (isSingleItem) "Image discarded" else "Sequence pair discarded"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    // Live media observation will emit a new list without these records,
                    // and UI will refresh automatically.
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error discarding pair: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    fun performCloudSync() {
        val patient = GlobalPatientManager.getCurrentPatient()
        if (patient == null) {
            Toast.makeText(context, "No patient selected", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            try {
                // Perform two-phase sync
                val result = SyncOrchestrator.syncPatientMediaTwoPhase(
                    context = context,
                    patient = patient,
                    onProgress = { current, total ->
                        onSyncProgress(current, total)
                    },
                    onPhaseChange = {
                        onSyncPhaseChange()
                    }
                )

                val uploadCount = result.uploadResult?.successCount ?: 0
                val downloadCount = result.downloadResult?.successCount ?: 0
                onSyncComplete(result.success, uploadCount, downloadCount, result.error)
            } catch (e: Exception) {
                onSyncComplete(false, 0, 0, e.message)
            }
        }
    }
}
