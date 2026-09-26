package com.tuapp.hogarseguro

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pantalla principal: muestra la interfaz web de SAGE empaquetada dentro
 * de la app (carpeta assets/). Ya NO maneja permisos aqui - eso lo resuelve
 * SplashActivity + OnboardingActivity ANTES de llegar a esta pantalla.
 *
 * NOTCH: el contenido NO entra en la zona del notch. Los insets del
 * sistema se aplican como padding NATIVO sobre el propio WebView y el
 * fondo del WebView queda negro en esa zona. Lo acomoda el sistema al
 * abrir (sin variables CSS ni JS): no hay parpadeos ni espacio de mas.
 *
 * NOTIFICACIONES NATIVAS: las muestra AlertasMqttService (servicio
 * MQTT nativo) con la pagina abierta o cerrada. El
 * SismoNotificationListener ignora las notificaciones de la propia
 * app, asi que no hay bucles.
 */
class MainActivity : AppCompatActivity() {

    private val URL_INTERFAZ = "file:///android_asset/index.html"
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // La zona de arriba (barra de estado / isla flotante) queda negra:
        // es el fondo de la ventana por detras del WebView.
        window.setBackgroundDrawableResource(android.R.color.black)
        // Iconos blancos en las barras del sistema (el fondo de la zona
        // del notch y la barra inferior son negros).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        NotificadorAlertas.crearCanal(this)

        // Servicio MQTT nativo: notificaciones con la pagina abierta o
        // cerrada (arranca una sola vez, luego queda vivo solo).
        AlertasMqttService.iniciar(this)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        setContentView(webView)

        configurarWebView()

        // El WebView se acomoda por debajo del notch/barra de estado y las
        // bandas que queden a los lados/abajo quedan negras (fondo de la
        // ventana). Se usa MARGEN y no padding porque Chromium ignora el
        // padding del WebView para el contenido de la pagina.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            Log.i("MainActivity", "Insets top=${sys.top} left=${sys.left} bottom=${sys.bottom}")
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.setMargins(sys.left, sys.top, sys.right, sys.bottom)
            v.layoutParams = lp
            insets
        }

        webView.loadUrl(URL_INTERFAZ)
    }

    private fun configurarWebView() {
        webView.webViewClient = WebViewClient()
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
