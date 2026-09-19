package com.tuapp.hogarseguro

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Pantalla principal: muestra la interfaz web de Hogar Seguro (empaquetada
 * en assets/) y se asegura de que todos los permisos/ajustes necesarios
 * para que SismoNotificationListener.kt funcione DE FORMA CONTINUA esten
 * activos - notificaciones, listener de notificaciones, optimizacion de
 * bateria, y (en Xiaomi/MIUI) el permiso de Autoinicio.
 *
 * El flujo es: revisar todo en onResume() (asi detecta si el usuario vuelve
 * de ajustes) y mostrar un dialogo "esto no puede funcionar sin X" con un
 * boton que lleva directo al ajuste correspondiente, uno a la vez.
 */
class MainActivity : AppCompatActivity() {

    private val URL_INTERFAZ = "file:///android_asset/index.html"
    private lateinit var webView: WebView

    private val solicitarPermisoNotificaciones =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        configurarWebView()
        webView.loadUrl(URL_INTERFAZ)

        pedirPermisoPostNotifications()
    }

    override fun onResume() {
        super.onResume()
        // Se revisa cada vez que la app vuelve a primer plano (ej: al volver
        // de Ajustes despues de tocar un boton de "Corregir")
        revisarConfiguracionEnCadena()
    }

    private fun configurarWebView() {
        webView.webViewClient = WebViewClient()
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
    }

    private fun pedirPermisoPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val yaConcedido = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!yaConcedido) {
                solicitarPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Revisa los 3 requisitos EN ORDEN, uno a la vez (no bombardea con varios
     * dialogos encima). Apenas el usuario resuelve uno y vuelve a la app,
     * onResume() se dispara de nuevo y pasa al siguiente pendiente.
     */
    private fun revisarConfiguracionEnCadena() {
        if (!accesoNotificacionesActivo()) {
            mostrarDialogoCorregir(
                titulo = "Acceso a notificaciones necesario",
                mensaje = "Esta app no puede detectar alertas sismicas sin permiso " +
                        "para leer notificaciones de otras apps (Sismo Detector, Google). " +
                        "Buscala en la lista como 'Puente Sismico Hogar Seguro' y actívala.",
                onCorregir = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            )
            return
        }

        if (!bateriaSinRestriccion()) {
            mostrarDialogoCorregir(
                titulo = "Optimización de batería",
                mensaje = "Android puede apagar la detección de sismos en segundo plano " +
                        "para ahorrar batería. Para que funcione de forma continua, " +
                        "esta app necesita quedar SIN restricciones de batería.",
                onCorregir = { pedirIgnorarOptimizacionBateria() }
            )
            return
        }

        if (esXiaomi() && !autoInicioConfirmadoPorUsuario) {
            mostrarDialogoCorregir(
                titulo = "Autoinicio (Xiaomi/MIUI)",
                mensaje = "Tu Xiaomi tiene un control adicional llamado 'Autoinicio' que " +
                        "puede matar esta app en segundo plano aunque la batería esté sin " +
                        "restricciones. Actívalo para 'Puente Sismico Hogar Seguro' en la " +
                        "pantalla que se va a abrir.",
                onCorregir = { abrirAutoInicioMiui() }
            )
        }
    }

    // ---- Verificaciones ----

    private fun accesoNotificacionesActivo(): Boolean {
        val habilitados = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return habilitados != null && habilitados.contains(packageName)
    }

    private fun bateriaSinRestriccion(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun esXiaomi(): Boolean =
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("poco", ignoreCase = true)

    // MIUI no expone una API para "consultar" si Autoinicio esta activo, asi
    // que solo podemos pedirlo una vez y confiar en que el usuario lo hizo.
    // Se guarda en preferencias para no insistir en cada apertura de la app.
    private val autoInicioConfirmadoPorUsuario: Boolean
        get() = getSharedPreferences("config", MODE_PRIVATE)
            .getBoolean("autoinicio_confirmado", false)

    // ---- Acciones ----

    private fun pedirIgnorarOptimizacionBateria() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Ve a Ajustes -> Batería y desactiva la optimización a mano", Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirAutoInicioMiui() {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Ve a Ajustes -> Apps -> Permisos -> Autoinicio, y activa esta app a mano",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            // No hay forma de confirmar programaticamente que lo activo, asi
            // que se marca como "ya se le pidio" para no insistir cada vez.
            getSharedPreferences("config", MODE_PRIVATE).edit()
                .putBoolean("autoinicio_confirmado", true).apply()
        }
    }

    private fun mostrarDialogoCorregir(titulo: String, mensaje: String, onCorregir: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setCancelable(false)
            .setPositiveButton("Corregir") { _, _ -> onCorregir() }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
