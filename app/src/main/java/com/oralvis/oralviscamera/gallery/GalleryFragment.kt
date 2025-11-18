package com.oralvis.oralviscamera.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.databinding.FragmentGalleryBinding
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.session.SessionManager

class GalleryFragment : Fragment() {
    
    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: GalleryViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var mediaAdapter: MediaAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        android.util.Log.d("GalleryFragment", "GalleryFragment created")
        
        sessionManager = SessionManager(requireContext())
        viewModel = ViewModelProvider(this, GalleryViewModel.createFactory(requireContext()))[GalleryViewModel::class.java]
        
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }
    
    private fun setupRecyclerView() {
        android.util.Log.d("GalleryFragment", "Setting up RecyclerView")
        
        mediaAdapter = MediaAdapter(
            onItemClick = { mediaRecord ->
                android.util.Log.d("GalleryFragment", "Media clicked: ${mediaRecord.fileName}")
                // Open media preview dialog
                val previewDialog = MediaPreviewDialog(requireContext())
                previewDialog.show(mediaRecord)
            },
            onDeleteClick = { mediaRecord ->
                android.util.Log.d("GalleryFragment", "Delete clicked: ${mediaRecord.fileName}")
                // TODO: Implement delete
                Toast.makeText(requireContext(), "Delete: ${mediaRecord.fileName}", Toast.LENGTH_SHORT).show()
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = mediaAdapter
        }
        
        android.util.Log.d("GalleryFragment", "RecyclerView setup complete")
    }
    
    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            android.util.Log.d("GalleryFragment", "Back button clicked")
            // Hide the gallery fragment
            parentFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }
        
        binding.btnRefresh.setOnClickListener {
            android.util.Log.d("GalleryFragment", "Refresh button clicked")
            refreshGallery()
        }
    }
    
    private fun observeViewModel() {
        val sessionId = sessionManager.getCurrentSessionId()
        android.util.Log.d("GalleryFragment", "Observing media for session: $sessionId")
        
        if (sessionId != null) {
            viewModel.getMediaBySession(sessionId).observe(viewLifecycleOwner) { mediaList ->
                android.util.Log.d("GalleryFragment", "Received ${mediaList.size} media items")
                
                // Update the adapter
                mediaAdapter.submitList(mediaList) {
                    android.util.Log.d("GalleryFragment", "Adapter updated with ${mediaList.size} items")
                }
                
                // Update empty state
                binding.emptyState.visibility = if (mediaList.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (mediaList.isEmpty()) View.GONE else View.VISIBLE
                
                // Update title
                binding.titleText.text = "Gallery (${mediaList.size} items)"
                
                android.util.Log.d("GalleryFragment", "Gallery updated - ${mediaList.size} items")
            }
        } else {
            android.util.Log.d("GalleryFragment", "No session ID available")
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.titleText.text = "No Current Session"
        }
    }
    
    private fun refreshGallery() {
        android.util.Log.d("GalleryFragment", "Refreshing gallery")
        observeViewModel()
        Toast.makeText(requireContext(), "Gallery refreshed", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
