package com.samuelpart.iptvplayer

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** QR generation + iPhone-style popup to share any link. */
object QrHelper {

    /** Renders [text] as a QR bitmap (white background, black modules). */
    fun qrBitmap(text: String, size: Int = 640): Bitmap {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    /** Shows the modern iPhone popup with the QR code of [link]. */
    fun showQrDialog(activity: Activity, title: String, link: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_qr, null)
        view.findViewById<TextView>(R.id.txtQrTitle).text = title
        view.findViewById<TextView>(R.id.txtQrLink).text = link
        view.findViewById<ImageView>(R.id.imgQrCode).setImageBitmap(qrBitmap(link))

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(view)
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val attrs = w.attributes
            attrs.windowAnimations = R.style.iOSPopupAnimation
            w.attributes = attrs
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
}
