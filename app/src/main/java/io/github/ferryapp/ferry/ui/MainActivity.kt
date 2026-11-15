package io.github.ferryapp.ferry.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.ferryapp.ferry.FerryApp
import io.github.ferryapp.ferry.ui.theme.FerryTheme

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application as FerryApp) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val incomingUri = intent?.data?.toString()

        setContent {
            val settings = viewModel.settings
            var darkOverride by remember { mutableStateOf(settings.darkMode) }
            val dark = when (darkOverride) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            FerryTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(viewModel = viewModel, incomingUri = incomingUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { viewModel.onUriReceived(it.toString()) }
        val files = collectSharedFiles(intent)
        if (files.isNotEmpty()) viewModel.onFilesShared(files)
    }

    private fun collectSharedFiles(intent: Intent?): List<String> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString())
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    ?.mapNotNull { it?.toString() }
                    .orEmpty()
            else -> emptyList()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLockState()
    }
}
