package com.tuapp.hogarseguro

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de carga. Aca SI se hace la verificacion real de permisos
 * (a diferencia de OnboardingActivity, que ya no verifica nada mientras
 * el usuario la recorre - solo avanza).
 *
 * Logica:
 * - Si la intro nunca se completo (primera instalacion) -> Onboarding.
 * - Si la intro ya paso, pero el usuario "fallo" activando notificaciones
 *   o el acceso a notificaciones (los 2 unicos permisos que Android SI
 *   reporta de forma confiable) -> lo manda a Onboarding de nuevo para
 *   que lo intente otra vez.
 * - Si todo esta en orden -> directo a MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Arranca el servicio MQTT nativo lo antes posible: con el se
        // reciben las notificaciones de gas/intruso/sismo aunque la
        // pagina web no este abierta.
        AlertasMqttService.iniciar(this)

        Handler(Looper.getMainLooper()).postDelayed({
            val siguiente = if (necesitaOnboarding()) {
                Intent(this, OnboardingActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
            startActivity(siguiente)
            finish()
        }, 1200)
    }

    private fun necesitaOnboarding(): Boolean {
        if (!PermisosSage.introCompletada(this)) return true

        // Solo verificamos de verdad los 2 permisos que Android SI reporta
        // de forma confiable. Bateria/Autoinicio no se vuelven a chequear
        // (una vez el usuario toco el boton, se confia en eso para siempre -
        // ver PermisosSage para el porque).
        if (!PermisosSage.postNotificationsConcedido(this)) return true
        if (!PermisosSage.accesoNotificacionesActivo(this)) return true

        return false
    }
}