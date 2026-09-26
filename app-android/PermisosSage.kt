package com.tuapp.hogarseguro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Punto unico de verdad para saber si falta algun permiso/ajuste necesario
 * para que SAGE funcione correctamente en segundo plano. Usado por
 * SplashActivity (para decidir a donde ir) y OnboardingActivity (para saber
 * que pantalla mostrar).
 */
object PermisosSage {

    fun accesoNotificacionesActivo(context: Context): Boolean {
        val habilitados = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )
        return habilitados != null && habilitados.contains(context.packageName)
    }

    fun postNotificationsConcedido(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true // no aplica en versiones viejas
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun bateriaSinRestriccion(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // OR con el flag manual: en MIUI/HyperOS, isIgnoringBatteryOptimizations()
        // no siempre refleja lo que el usuario configuro en Ajustes (capa propia
        // del fabricante encima del sistema base) - por eso confiamos tambien en
        // que el usuario ya toco el boton, igual que con Autoinicio.
        return pm.isIgnoringBatteryOptimizations(context.packageName) ||
                bateriaConfirmadaPorUsuario(context)
    }

    fun bateriaConfirmadaPorUsuario(context: Context): Boolean =
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .getBoolean("bateria_confirmada", false)

    fun marcarBateriaConfirmada(context: Context) {
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .edit().putBoolean("bateria_confirmada", true).apply()
    }

    fun esXiaomi(): Boolean =
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("poco", ignoreCase = true)

    // MIUI/HyperOS no expone una API para consultar si Autoinicio esta
    // activo - solo podemos pedirlo una vez y confiar en que el usuario lo hizo.
    fun autoInicioConfirmadoPorUsuario(context: Context): Boolean =
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .getBoolean("autoinicio_confirmado", false)

    fun marcarAutoInicioConfirmado(context: Context) {
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .edit().putBoolean("autoinicio_confirmado", true).apply()
    }

    fun introCompletada(context: Context): Boolean =
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .getBoolean("intro_completada", false)

    fun marcarIntroCompletada(context: Context) {
        context.getSharedPreferences("config_sage", Context.MODE_PRIVATE)
            .edit().putBoolean("intro_completada", true).apply()
    }

    /** true si falta CUALQUIER cosa por resolver (incluida la intro). */
    fun faltaAlgunPermiso(context: Context): Boolean {
        if (!introCompletada(context)) return true
        if (!postNotificationsConcedido(context)) return true
        if (!accesoNotificacionesActivo(context)) return true
        if (!bateriaSinRestriccion(context)) return true
        if (esXiaomi() && !autoInicioConfirmadoPorUsuario(context)) return true
        return false
    }
}