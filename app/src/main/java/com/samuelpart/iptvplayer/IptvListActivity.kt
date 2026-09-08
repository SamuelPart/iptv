package com.samuelpart.iptvplayer

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityIptvListBinding

/**
 * Pantalla dedicada para AÑADIR/CAMBIAR la lista IPTV:
 * enlace M3U, conectar, limpiar, escanear QR y listas rápidas.
 *
 * No carga la lista aquí: le deja la petición a MainActivity vía
 * [SettingsSync] para que mantenga el estado de canales/filtros.
 */
class IptvListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIptvListBinding

    private val urlSpain = "https://iptv-org.github.io/iptv/countries/es.m3u"
    private val urlGlobal = "https://iptv-org.github.io/iptv/index.m3u"
    private val urlNews = "https://iptv-org.github.io/iptv/categories/news.m3u"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            qrScanLauncher.launch(qrScanOptions())
        } else {
            Toast.makeText(this, "Permiso de cámara requerido para escanear QR", Toast.LENGTH_LONG).show()
        }
    }

    private val qrScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val contents = result.contents
        if (!contents.isNullOrEmpty()) {
            SettingsSync.requestLoadList(this, contents)
            finish()
        }
    }

    private fun qrScanOptions(): com.journeyapps.barcodescanner.ScanOptions =
        com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt("Apunta la cámara al QR de tu lista")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIptvListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnIptvBack.setOnClickListener { finish() }

        // Pre-rellena con la última lista cargada (si la hay).
        val lastUrl = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            .getString("last_url", null)
        if (!lastUrl.isNullOrEmpty()) binding.edtIptvUrl.setText(lastUrl)

        binding.btnConnect.setOnClickListener {
            val url = binding.edtIptvUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                SettingsSync.requestLoadList(this, url)
                finish()
            } else {
                Toast.makeText(this, "Por favor, ingresa una URL válida", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearList.setOnClickListener {
            SettingsSync.requestClearList(this)
            finish()
        }

        binding.btnScanQr.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnQuickSpain.setOnClickListener { loadQuick(urlSpain) }
        binding.btnQuickGlobal.setOnClickListener { loadQuick(urlGlobal) }
        binding.btnQuickNews.setOnClickListener { loadQuick(urlNews) }
    }

    private fun loadQuick(url: String) {
        SettingsSync.requestLoadList(this, url)
        finish()
    }
}
