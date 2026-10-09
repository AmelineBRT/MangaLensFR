package app.mangalens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.settings.SettingsRepository
import app.mangalens.ui.HomeScreen
import app.mangalens.ui.MangaLensTheme

class MainActivity : ComponentActivity() {

    private lateinit var repo: SettingsRepository

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(
                    this,
                    "MangaLens est activé — passez sur Brave et commencez à lire",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "L'autorisation de capture d'écran est nécessaire", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)
        setContent {
            MangaLensTheme {
                HomeScreen(
                    repo = repo,
                    onStart = { startFlow() },
                    onArrêter = { stopCapture() },
                    onGrantOverlay = { openOverlaySettings() },
                )
            }
        }
    }

    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (!isAccessibilityOverlayEnabled()) {
            openAccessibilitySettings()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun isAccessibilityOverlayEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val component = ComponentName(
            this,
            app.mangalens.overlay.MangaLensAccessibilityService::class.java
        ).flattenToString()
        return Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(
            this,
            "Active le service d’accessibilité de MangaLens pour rendre les bulles totalement opaques, puis reviens ici et appuie de nouveau sur Démarrer.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        Toast.makeText(
            this,
            "Autorisez \"Afficher par-dessus les autres applications\" pour MangaLens, puis revenez ici",
            Toast.LENGTH_LONG
        ).show()
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun stopCapture() {
        startService(
            Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
        )
    }
}
