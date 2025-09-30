package com.oralvis.oralviscamera.gallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.databinding.ActivityGalleryBinding
import com.oralvis.oralviscamera.session.SessionManager
import com.oralvis.oralviscamera.database.MediaRecord
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GalleryActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityGalleryBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var normalAdapter: MediaAdapter
    private lateinit var fluorescenceAdapter: MediaAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        sessionManager = SessionManager(this)
        viewModel = ViewModelProvider(this, GalleryViewModel.createFactory(this))[GalleryViewModel::class.java]
        
        setupUI()
        setupRecyclerViews()
        observeViewModel()
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showNormalMedia()
                    1 -> showFluorescenceMedia()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun setupRecyclerViews() {
        normalAdapter = MediaAdapter(
            onItemClick = { mediaRecord ->
                openMediaViewer(mediaRecord)
            },
            onDeleteClick = { mediaRecord ->
                deleteMedia(mediaRecord)
            }
        )
        
        fluorescenceAdapter = MediaAdapter(
            onItemClick = { mediaRecord ->
                openMediaViewer(mediaRecord)
            },
            onDeleteClick = { mediaRecord ->
                deleteMedia(mediaRecord)
            }
        )
        
        binding.recyclerViewNormal.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = normalAdapter
        }
        
        binding.recyclerViewFluorescence.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = fluorescenceAdapter
        }
    }
    
    private fun observeViewModel() {
        val sessionId = sessionManager.getCurrentSessionId()
        
        viewModel.getNormalMedia(sessionId).observe(this) { mediaList ->
            normalAdapter.submitList(mediaList)
            binding.emptyStateNormal.visibility = if (mediaList.isEmpty()) View.VISIBLE else View.GONE
        }
        
        viewModel.getFluorescenceMedia(sessionId).observe(this) { mediaList ->
            fluorescenceAdapter.submitList(mediaList)
            binding.emptyStateFluorescence.visibility = if (mediaList.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    private fun showNormalMedia() {
        binding.recyclerViewNormal.visibility = View.VISIBLE
        binding.recyclerViewFluorescence.visibility = View.GONE
        binding.emptyStateNormal.visibility = View.GONE
        binding.emptyStateFluorescence.visibility = View.GONE
    }
    
    private fun showFluorescenceMedia() {
        binding.recyclerViewNormal.visibility = View.GONE
        binding.recyclerViewFluorescence.visibility = View.VISIBLE
        binding.emptyStateNormal.visibility = View.GONE
        binding.emptyStateFluorescence.visibility = View.GONE
    }
    
    private fun deleteMedia(mediaRecord: MediaRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete Media")
            .setMessage("Are you sure you want to delete ${mediaRecord.fileName}? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                performDelete(mediaRecord)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performDelete(mediaRecord: MediaRecord) {
        // Delete file from storage
        val file = java.io.File(mediaRecord.filePath)
        if (file.exists()) {
            file.delete()
        }
        
        // Delete from database
        CoroutineScope(Dispatchers.IO).launch {
            try {
                viewModel.deleteMedia(mediaRecord)
                runOnUiThread {
                    Toast.makeText(this@GalleryActivity, "Media deleted successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@GalleryActivity, "Failed to delete media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun openMediaViewer(mediaRecord: MediaRecord) {
        val intent = Intent(this, com.oralvis.oralviscamera.MediaViewerActivity::class.java)
        intent.putExtra("media_path", mediaRecord.filePath)
        intent.putExtra("media_type", mediaRecord.mediaType)
        startActivity(intent)
    }
}
