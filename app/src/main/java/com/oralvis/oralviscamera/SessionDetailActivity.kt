package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.databinding.ActivitySessionDetailBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.database.Session
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SessionDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var mediaDatabase: MediaDatabase
    private lateinit var mediaAdapter: SessionMediaGridAdapter
    private var sessionId: Long = -1
    private var session: Session? = null
    private val mediaList = mutableListOf<MediaRecord>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        mediaDatabase = MediaDatabase.getInstance(this)
        sessionId = intent.getLongExtra("SESSION_ID", -1)
        
        if (sessionId == -1L) {
            Toast.makeText(this, "Invalid session", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        loadSessionData()
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Setup RecyclerView with grid layout
        mediaAdapter = SessionMediaGridAdapter(mediaList) { mediaRecord ->
            // Open media viewer
            openMediaViewer(mediaRecord)
        }
        
        binding.rvSessionMedia.apply {
            layoutManager = GridLayoutManager(this@SessionDetailActivity, 3) // 3 columns
            adapter = mediaAdapter
        }
    }
    
    private fun loadSessionData() {
        lifecycleScope.launch {
            try {
                // Load session details
                session = mediaDatabase.sessionDao().getById(sessionId)
                session?.let { sess ->
                    // Update UI with session details
                    binding.txtSessionTitle.text = sess.displayName ?: "Session"
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                    binding.txtSessionDate.text = dateFormat.format(sess.createdAt)
                    binding.txtMediaCount.text = "${sess.mediaCount} items"
                    
                    // Load media for this session
                    mediaDatabase.mediaDao().getMediaBySession(sess.sessionId).collect { mediaRecords ->
                        mediaList.clear()
                        mediaList.addAll(mediaRecords)
                        mediaAdapter.notifyDataSetChanged()
                        
                        // Show/hide empty state
                        if (mediaList.isEmpty()) {
                            binding.rvSessionMedia.visibility = View.GONE
                            binding.txtEmptyState.visibility = View.VISIBLE
                        } else {
                            binding.rvSessionMedia.visibility = View.VISIBLE
                            binding.txtEmptyState.visibility = View.GONE
                        }
                    }
                } ?: run {
                    Toast.makeText(this@SessionDetailActivity, "Session not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SessionDetailActivity, "Error loading session: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun openMediaViewer(mediaRecord: MediaRecord) {
        val intent = Intent(this, MediaViewerActivity::class.java)
        intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, mediaRecord.filePath)
        intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, if (mediaRecord.mediaType == "Video") "video" else "image")
        intent.putExtra(MediaViewerActivity.EXTRA_SESSION_ID, session?.sessionId)
        intent.putExtra(MediaViewerActivity.EXTRA_FILENAME, mediaRecord.fileName)
        startActivity(intent)
    }
}
