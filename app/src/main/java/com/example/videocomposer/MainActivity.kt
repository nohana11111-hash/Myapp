package com.example.videocomposer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videocomposer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val processor by lazy { VideoProcessor(this) }

    // 1. Launcher for picking the video (Does NOT require storage permissions)
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startProcessing(it) }
    }

    // 2. Launcher for handling the "All Files Access" settings screen
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-evaluate permissions after user returns from settings
        checkPermissionsAndProceed()
    }

    // 3. Launcher for standard runtime permissions (WRITE_EXTERNAL_STORAGE, POST_NOTIFICATIONS)
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            // Double-check MANAGE_EXTERNAL_STORAGE just in case
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All Files Access is still required to save videos", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            startPickVideo()
        } else {
            Toast.makeText(this, "Permissions denied. Cannot save videos.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickVideo.setOnClickListener { 
            checkPermissionsAndProceed()
        }
    }

    private fun checkPermissionsAndProceed() {
        val permissionsToRequest = mutableListOf<String>()

        // --- STEP 1: Check MANAGE_EXTERNAL_STORAGE (Android 11 / API 30+) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Please enable 'All Files Access' to save videos to the vidz folder", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback to app settings if the specific manage storage intent fails
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    manageStorageLauncher.launch(intent)
                }
                return // Stop here, wait for user to return from settings
            }
        } else {
            // --- STEP 1 (Legacy): Check WRITE_EXTERNAL_STORAGE (Android 10 and below) ---
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // --- STEP 2: Check POST_NOTIFICATIONS (Android 13 / API 33+) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // --- STEP 3: Request permissions if needed, otherwise proceed ---
        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startPickVideo()
        }
    }

    private fun startPickVideo() {
        // GetContent does not require READ_MEDIA_VIDEO permission because the user explicitly selects the file
        pickVideoLauncher.launch("video/*")
    }

    private fun startProcessing(inputUri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnPickVideo.isEnabled = false
        binding.tvStatus.text = "Preparing assets..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                processor.compose(inputUri) { progress ->
                    runOnUiThread {
                        binding.tvStatus.text = "Processing... $progress%"
                    }
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnPickVideo.isEnabled = true

            result.onSuccess { file ->
                binding.tvStatus.text = "Saved to: ${file.absolutePath}"
                Toast.makeText(this@MainActivity, "Video saved in vidz/ folder", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                binding.tvStatus.text = "Error: ${err.message}"
                Toast.makeText(this@MainActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                err.printStackTrace()
            }
        }
    }
}
