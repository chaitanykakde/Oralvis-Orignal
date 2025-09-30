package com.oralvis.oralviscamera

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
import com.google.android.material.tabs.TabLayout
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.SessionDao
import com.oralvis.oralviscamera.databinding.ActivitySessionDetailBinding
import com.oralvis.oralviscamera.gallery.MediaAdapter
import com.oralvis.oralviscamera.gallery.GalleryViewModel
import com.oralvis.oralviscamera.database.MediaRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SessionDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var normalAdapter: MediaAdapter
    private lateinit var fluorescenceAdapter: MediaAdapter
    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sessionId = intent.getStringExtra("session_id") ?: ""
        viewModel = ViewModelProvider(this, GalleryViewModel.createFactory(this))[GalleryViewModel::class.java]

        setupUI()
        setupLists()
        observeData()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.sessionTitle.text = "Session ${sessionId.takeLast(8)}"
        binding.sessionDate.text = "Created: ${java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showNormal()
                    1 -> showFluorescence()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupLists() {
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
        binding.recyclerViewNormal.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerViewFluorescence.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerViewNormal.adapter = normalAdapter
        binding.recyclerViewFluorescence.adapter = fluorescenceAdapter
    }

    private fun observeData() {
        viewModel.getNormalMedia(sessionId).observe(this) { list ->
            normalAdapter.submitList(list)
            binding.emptyStateNormal.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.getFluorescenceMedia(sessionId).observe(this) { list ->
            fluorescenceAdapter.submitList(list)
            binding.emptyStateFluorescence.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showNormal() {
        binding.normalMediaSection.visibility = View.VISIBLE
        binding.fluorescenceMediaSection.visibility = View.GONE
    }

    private fun showFluorescence() {
        binding.normalMediaSection.visibility = View.GONE
        binding.fluorescenceMediaSection.visibility = View.VISIBLE
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
                    Toast.makeText(this@SessionDetailActivity, "Media deleted successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SessionDetailActivity, "Failed to delete media: ${e.message}", Toast.LENGTH_SHORT).show()
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
