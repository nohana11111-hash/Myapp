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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videocomposer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val processor by lazy { VideoProcessor(this) }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startProcessing(it) }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startPickVideo()
        else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickVideo.setOnClickListener { ensurePermissionsAndPick() }
    }

    private fun ensurePermissionsAndPick() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.READ_MEDIA_IMAGES
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }

        // MANAGE_EXTERNAL_STORAGE for full access on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        if (needed.isEmpty()) startPickVideo()
        else permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun startPickVideo() {
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
                Toast.makeText(this@MainActivity, "Video saved in vidz/", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                binding.tvStatus.text = "Error: ${err.message}"
                Toast.makeText(this@MainActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
