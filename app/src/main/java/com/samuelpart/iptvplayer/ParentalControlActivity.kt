package com.samuelpart.iptvplayer

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityParentalControlBinding

/**
 * Pantalla de CONTROL PARENTAL: activar/desactivar, configurar PIN y
 * ocultar categorías completas. Los cambios se guardan en SharedPreferences
 * y se marcan con [SettingsSync.mark] para que MainActivity reaplique los
 * filtros al volver.
 */
class ParentalControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentalControlBinding

    private val prefs by lazy { getSharedPreferences("iptv_pref", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnParentalBack.setOnClickListener { finish() }
        binding.btnToggleParental.setOnClickListener { toggleParentalControlState() }
        binding.btnConfigPin.setOnClickListener { configureOrChangePin() }
        binding.btnHideCategories.setOnClickListener { showHideCategoriesDialog() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val active = prefs.getBoolean("parental_active", false)
        val savedPin = prefs.getString("parental_pin", null)
        binding.txtParentalStatus.text = if (active) "ACTIVO 🔒" else "DESACTIVADO 🔓"
        binding.txtParentalStatus.setTextColor(
            if (active) 0xFF30D158.toInt() else 0xFFFF453A.toInt()
        )
        binding.txtParentalPin.text = if (savedPin.isNullOrEmpty()) "PIN: Sin configurar" else "PIN: Configurado"
    }

    private fun toggleParentalControlState() {
        val isParentalActive = prefs.getBoolean("parental_active", false)
        val savedPin = prefs.getString("parental_pin", null)

        if (savedPin.isNullOrEmpty()) {
            Toast.makeText(this, "Por favor, primero configura un PIN de seguridad.", Toast.LENGTH_LONG).show()
            configureOrChangePin()
            return
        }

        if (isParentalActive) {
            // Desactivar requiere verificar el PIN.
            val pinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Escribe tu PIN de 4 dígitos"
                filters = arrayOf(InputFilter.LengthFilter(4))
            }
            AlertDialog.Builder(this)
                .setTitle("🔒 Desactivar Control Parental")
                .setMessage("Ingresa tu PIN de seguridad de 4 dígitos para desactivar el filtro de adultos:")
                .setView(pinInput)
                .setPositiveButton("Verificar") { _, _ ->
                    if (pinInput.text.toString().trim() == savedPin) {
                        prefs.edit().putBoolean("parental_active", false).apply()
                        SettingsSync.mark(this)
                        refreshStatus()
                        Toast.makeText(this, "Control Parental Desactivado con éxito.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "PIN Incorrecto. El control parental sigue activo.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .setCancelable(false)
                .show()
        } else {
            prefs.edit().putBoolean("parental_active", true).apply()
            SettingsSync.mark(this)
            refreshStatus()
            Toast.makeText(this, "Control Parental Activado. Canales de adultos bloqueados.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrChangePin() {
        val savedPin = prefs.getString("parental_pin", null)

        if (savedPin.isNullOrEmpty()) {
            val pinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Nuevo PIN de 4 dígitos"
                filters = arrayOf(InputFilter.LengthFilter(4))
            }
            AlertDialog.Builder(this)
                .setTitle("🔑 Configurar PIN de Seguridad")
                .setMessage("Ingresa un PIN único de 4 dígitos para activar/desactivar el control parental:")
                .setView(pinInput)
                .setPositiveButton("Guardar") { _, _ ->
                    val enteredPin = pinInput.text.toString().trim()
                    if (enteredPin.length == 4) {
                        prefs.edit().putString("parental_pin", enteredPin).apply()
                        refreshStatus()
                        Toast.makeText(this, "¡PIN de seguridad configurado con éxito! 🔒", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error: El PIN debe tener exactamente 4 dígitos.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
            val currentPinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "PIN Actual"
                filters = arrayOf(InputFilter.LengthFilter(4))
            }
            val newPinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Nuevo PIN"
                filters = arrayOf(InputFilter.LengthFilter(4))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 20 }
            }
            container.addView(currentPinInput)
            container.addView(newPinInput)

            AlertDialog.Builder(this)
                .setTitle("🔑 Cambiar PIN de Seguridad")
                .setMessage("Para cambiar el PIN, debes ingresar tu clave actual:")
                .setView(container)
                .setPositiveButton("Actualizar") { _, _ ->
                    val currentPinEntered = currentPinInput.text.toString().trim()
                    val newPinEntered = newPinInput.text.toString().trim()
                    if (currentPinEntered == savedPin) {
                        if (newPinEntered.length == 4) {
                            prefs.edit().putString("parental_pin", newPinEntered).apply()
                            refreshStatus()
                            Toast.makeText(this, "¡PIN actualizado con éxito! 🔒", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Error: El nuevo PIN debe tener exactamente 4 dígitos.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "Error: El PIN actual es incorrecto.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun showHideCategoriesDialog() {
        val categories = prefs.getStringSet("categories_list", emptySet())?.toList()?.sorted() ?: emptyList()
        if (categories.isEmpty()) {
            Toast.makeText(this, "Primero carga tu lista de canales", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = prefs.getStringSet("parental_hidden_categories", emptySet())?.toMutableSet() ?: mutableSetOf()
        val names = categories.toTypedArray()
        val checked = BooleanArray(names.size) { selected.contains(names[it]) }
        AlertDialog.Builder(this)
            .setTitle("📁 Categorías ocultas (Parental)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selected.add(names[which]) else selected.remove(names[which])
            }
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putStringSet("parental_hidden_categories", selected).apply()
                SettingsSync.mark(this)
                Toast.makeText(this, "Categorías ocultas actualizadas 🔒", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
