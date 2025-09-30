package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Session
import com.oralvis.oralviscamera.database.SessionDao
import com.oralvis.oralviscamera.databinding.ActivityHomeBinding
import com.oralvis.oralviscamera.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionManager: SessionManager
    private lateinit var adapter: SessionListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        sessionManager = SessionManager(this)
        sessionDao = MediaDatabase.getDatabase(this).sessionDao()
        
        setupRecycler()
        setupActions()
        observeSessions()
    }

    private fun setupRecycler() {
        adapter = SessionListAdapter { session ->
            openSession(session.sessionId)
        }
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter
    }

    private fun setupActions() {
        binding.btnNewSession.setOnClickListener {
            startNewSession()
        }
        
        binding.fabNewSession.setOnClickListener {
            startNewSession()
        }
        
        binding.btnSettings.setOnClickListener {
            // TODO: Open settings
            Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startNewSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            val newSessionId = "session_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0,8)}"
            val session = Session(sessionId = newSessionId, createdAt = Date(), displayName = null)
            sessionDao.insert(session)
            // set as current
            sessionManager.setCurrentSession(newSessionId)
            // route to camera
            runOnUiThread {
                openCamera()
            }
        }
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            sessionDao.getAllSessions().collectLatest { list ->
                adapter.submitList(list)
                binding.emptyStateLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.sessionCount.text = "${list.size} sessions"
            }
        }
    }

    private fun openSession(sessionId: String) {
        val intent = Intent(this, SessionDetailActivity::class.java)
        intent.putExtra("session_id", sessionId)
        startActivity(intent)
    }

    private fun openCamera() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }
}
