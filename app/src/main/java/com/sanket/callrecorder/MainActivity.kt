package com.sanket.callrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanket.callrecorder.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: RecordingsAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshList()
        if (prefs.autoRecord && hasAudioPermission()) startService(RecordingService.ACTION_ENABLE)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.switchAuto.isChecked = prefs.autoRecord
        binding.switchAuto.setOnCheckedChangeListener { _, v ->
            prefs.autoRecord = v
            if (v) {
                if (hasAudioPermission()) startService(RecordingService.ACTION_ENABLE)
                else requestPermissions()
            } else startService(RecordingService.ACTION_DISABLE)
        }

        binding.btnRecordNow.setOnClickListener {
            if (hasAudioPermission()) startService(RecordingService.ACTION_RECORD_NOW)
            else { requestPermissions(); Toast.makeText(this, "Grant mic permission, then tap Record", Toast.LENGTH_SHORT).show() }
        }
        binding.btnStopNow.setOnClickListener {
            startService(RecordingService.ACTION_STOP_NOW)
            binding.recycler.postDelayed({ refreshList() }, 800)
        }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.switchUpdate.isChecked = prefs.autoUpdateCheck
        binding.switchUpdate.setOnCheckedChangeListener { _, v -> prefs.autoUpdateCheck = v }

        binding.txtVersion.text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        binding.txtPath.text = "Saved in: Android/data/${packageName}/files/recordings"

        adapter = RecordingsAdapter(emptyList(), ::playFile, ::shareFile, ::deleteFile)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnCheckUpdate.setOnClickListener { checkForUpdate(manual = true) }

        requestPermissions()
        if (prefs.autoRecord && hasAudioPermission()) startService(RecordingService.ACTION_ENABLE)
        if (prefs.autoUpdateCheck) checkForUpdate(manual = false)
    }

    private fun startService(action: String) {
        val i = Intent(this, RecordingService::class.java).apply { this.action = action }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } else {
                Toast.makeText(this, "Already unrestricted 👍", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Fall back to the general settings screen.
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshList() {
        val dir = RecordingService.recordingsDir(this)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.update(files)
        binding.emptyView.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun playFile(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Play with"))
    }

    private fun shareFile(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share recording"))
    }

    private fun deleteFile(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(f.name)
            .setPositiveButton("Delete") { _, _ ->
                f.delete()
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkForUpdate(manual: Boolean) {
        lifecycleScope.launch {
            val release = UpdateChecker.findUpdate()
            if (release == null) {
                if (manual) Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Update available")
                .setMessage("New version ${release.name} is available. Download and install now?")
                .setPositiveButton("Update") { _, _ ->
                    Toast.makeText(this@MainActivity, "Downloading…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch { UpdateChecker.downloadAndInstall(this@MainActivity, release) }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
}
