package com.tuapp.hogarseguro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Muestra como notificacion NATIVA de Android las alertas que la interfaz
 * web recibe por MQTT (fuga de gas, intruso, actividad sismica).
 *
 * El puente es MainActivity.PuenteAlertas, expuesto al app.js como
 * window.AndroidAlertas. En un navegador comun no existe ese objeto, asi
 * que el mismo app.js funciona ahi sin intentar notificar.
 *
 * ANTI-BUCLE: el SismoNotificationListener solo re-procesa notificaciones
 * de OTRAS apps (ignora las de packageName == el nuestro), por lo que
 * estas notificaciones nuestras jamas se re-publican por MQTT.
 */
object NotificadorAlertas {

    private const val CANAL_ID = "alertas_seguridad"
    private const val CANAL_NOMBRE = "Alertas de seguridad"

    fun crearCanal(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                CANAL_NOMBRE,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fugas de gas, intrusos y actividad sismica"
                enableVibration(true)
                // Tono por defecto del sistema: sin esto algunos telefonos
                // crean el canal sin sonido. (Uri literal = DEFAULT_NOTIFICATION_URI;
                // RingtoneManager fue eliminado de los stubs del SDK 37.)
                setSound(
                    Uri.parse("content://settings/system/notification_sound"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    /**
     * Muestra (o actualiza) la notificacion. `tipo` es la categoria estable
     * que manda el app.js ("gas" / "intruso" / "sismo"): con ese id se
     * reemplaza la notificacion anterior de la misma categoria, en vez de
     * apilar copias identicas en la bandeja.
     */
    fun mostrar(context: Context, tipo: String, titulo: String, mensaje: String) {
        // Android 13+: sin POST_NOTIFICATIONS no se notifica (el permiso ya
        // se pide en el onboarding y lo re-verifica SplashActivity).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val notificacion = NotificationCompat.Builder(context, CANAL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mensaje))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(tipo.hashCode(), notificacion)
    }
}
