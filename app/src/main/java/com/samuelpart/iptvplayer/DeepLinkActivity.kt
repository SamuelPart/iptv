package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Puerta de entrada de los enlaces profundos compartidos
 * (lumen://ver?d=...). Solo pasa el titulo a MainActivity a traves del
 * extra "deeplink_title" y se cierra: el usuario nunca ve esta pantalla.
 */
class DeepLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = DeepLink.fromIntent(intent)

        val next = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (title != null) putExtra("deeplink_title", title)
        }
        startActivity(next)
        finish()
    }
}
