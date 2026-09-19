package com.tuapp.hogarseguro.sismico

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Escucha notificaciones de apps de alerta sismica (Sismo Detector, Google,
 * o cualquier otra que mencione un sismo) y republica cada alerta como un
 * mensaje MQTT hacia el broker publico de HiveMQ, para que el ESP32 y la
 * interfaz web las reciban.
 *
 * VERSION 2 - usa MqttAndroidClient (asincrono) en vez de MqttClient
 * (bloqueante), y corre como Foreground Service - esto evita que Android
 * mate el servicio por ANR o por ahorro de bateria, que era la causa de
 * que dejara de funcionar despues de un rato.
 *
 * IMPORTANTE - pasos manuales necesarios (no se pueden hacer por codigo):
 * 1. Activar el acceso a notificaciones para esta app en:
 *    Ajustes -> Apps y notificaciones -> Acceso especial -> Acceso a notificaciones
 * 2. Declarar el servicio en AndroidManifest.xml (ver comentario al final).
 * 3. Dependencias de Eclipse Paho en build.gradle (ver comentario al final).
 * 4. Desactivar la optimizacion de bateria y activar Autoinicio (MIUI) -
 *    esto lo pide la propia app en MainActivity.kt, no hace falta hacerlo a mano.
 */
class SismoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SismoListener"
        private const val PAQUETE_SISMO_DETECTOR = "com.finazzi.distquake"
        private const val PAQUETE_GOOGLE_SAFETY = "com.google.android.apps.safetyhub"
        private const val CANAL_CONFIRMACION = "sismo_bridge_confirmacion"
        private const val CANAL_SERVICIO = "sismo_bridge_servicio_activo"
        private const val ID_NOTIFICACION_SERVICIO = 1001

        private val PALABRAS_CLAVE_SISMO = listOf("sismo", "terremoto", "earthquake", "temblor")

        private const val HIVEMQ_HOST = "broker.hivemq.com"
        private const val HIVEMQ_PORT = 8883

        private const val PREFIJO = "security"
        private const val TOPIC_ALERTS = "$PREFIJO/alerts"
        private const val TOPIC_SEISMIC_SENSOR = "$PREFIJO/sensors/seismic"

        private val PATRON_DISTANCIA = Pattern.compile("a\\s+(\\d+)\\s*km", Pattern.CASE_INSENSITIVE)
        private val PATRON_MAGNITUD = Pattern.compile("M\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
    }

    private var mqttClient: MqttAndroidClient? = null
    private var mqttConectado = false

    override fun onCreate() {
        super.onCreate()
        crearCanales()
        volverseForeground()
        conectarMqtt()
    }

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CANAL_CONFIRMACION,
                    "Confirmacion de alertas sismicas",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CANAL_SERVICIO,
                    "Puente sismico en segundo plano",
                    NotificationManager.IMPORTANCE_LOW // sin sonido, es solo para mantenerlo vivo
                )
            )
        }
    }

    /** Convierte este servicio en Foreground Service - mucho mas dificil de matar. */
    private fun volverseForeground() {
        try {
            val notif: Notification = NotificationCompat.Builder(this, CANAL_SERVICIO)
                .setContentTitle("Puente Sismico activo")
                .setContentText("Escuchando alertas de sismo en segundo plano")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ID_NOTIFICACION_SERVICIO,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(ID_NOTIFICACION_SERVICIO, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar como foreground: ${e.message}")
        }
    }

    private fun conectarMqtt() {
        try {
            val clientId = "esp32-bridge-" + System.currentTimeMillis()
            mqttClient = MqttAndroidClient(applicationContext, "ssl://$HIVEMQ_HOST:$HIVEMQ_PORT", clientId)

            mqttClient?.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    mqttConectado = true
                    Log.i(TAG, if (reconnect) "Reconectado a HiveMQ" else "Conectado a HiveMQ")
                }

                override fun connectionLost(cause: Throwable?) {
                    mqttConectado = false
                    Log.w(TAG, "Conexion MQTT perdida: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true // clave: se reconecta solo si se cae la red
                connectionTimeout = 10
            }

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    mqttConectado = true
                    Log.i(TAG, "Conexion inicial a HiveMQ exitosa")
                    try {
                        val bufferOptions = DisconnectedBufferOptions().apply {
                            isBufferEnabled = true
                            bufferSize = 20
                            isPersistBuffer = false
                            isDeleteOldestMessages = true
                        }
                        mqttClient?.setBufferOpts(bufferOptions)
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo configurar el buffer: ${e.message}")
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    mqttConectado = false
                    Log.e(TAG, "Fallo al conectar a HiveMQ: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Excepcion creando cliente MQTT: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            procesarNotificacion(sbn)
        } catch (e: Exception) {
            // Blindaje: un fallo aca NUNCA debe tumbar el servicio completo
            Log.e(TAG, "Error procesando notificacion: ${e.message}", e)
        }
    }

    private fun procesarNotificacion(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val titulo = extras.getCharSequence("android.title")?.toString() ?: ""
        val texto = extras.getCharSequence("android.text")?.toString() ?: ""

        val esFuenteConocida = sbn.packageName == PAQUETE_SISMO_DETECTOR ||
                sbn.packageName == PAQUETE_GOOGLE_SAFETY
        val contienePalabraClave = PALABRAS_CLAVE_SISMO.any {
            titulo.contains(it, ignoreCase = true) || texto.contains(it, ignoreCase = true)
        }

        if (!esFuenteConocida && !contienePalabraClave) return

        if (!esFuenteConocida && contienePalabraClave) {
            Log.w(TAG, "PAQUETE NUEVO con palabra clave sismica: ${sbn.packageName}")
            mostrarConfirmacionVisual("Paquete detectado: ${sbn.packageName}\n$titulo")
        }

        Log.i(TAG, "Notificacion capturada [${sbn.packageName}] -> Titulo: $titulo | Texto: $texto")
        mostrarConfirmacionVisual("Capturada: $titulo")

        val esPrueba = titulo.contains("Test", ignoreCase = true)
        val distanciaKm = extraerNumero(PATRON_DISTANCIA, "$titulo $texto")
        val magnitud = extraerNumero(PATRON_MAGNITUD, "$titulo $texto")

        val nombreFuente = when (sbn.packageName) {
            PAQUETE_SISMO_DETECTOR -> "Sismo Detector"
            PAQUETE_GOOGLE_SAFETY -> "Google"
            else -> "App desconocida (${sbn.packageName})"
        }

        val mensaje = buildString {
            append("$nombreFuente: $titulo")
            if (distanciaKm != null) append(" - a ${distanciaKm.toInt()} km")
            if (magnitud != null) append(" - M${magnitud}")
            if (esPrueba) append(" (PRUEBA)")
        }

        val tipoAlerta = if (sbn.packageName == PAQUETE_GOOGLE_SAFETY) "SISMO" else "SISMO_P"

        val payloadAlerta = JSONObject().apply {
            put("type", tipoAlerta)
            put("message", mensaje)
            put("fuente", sbn.packageName)
        }

        val publicoOk = publicarMqtt(TOPIC_ALERTS, payloadAlerta.toString())
        mostrarConfirmacionVisual(
            if (publicoOk) "MQTT publicado: $mensaje" else "ERROR publicando MQTT (revisa conexion)"
        )

        if (!esPrueba) {
            publicarMqtt(TOPIC_SEISMIC_SENSOR, "true")
        }
    }

    private fun extraerNumero(patron: Pattern, texto: String): Double? {
        val matcher = patron.matcher(texto)
        return if (matcher.find()) matcher.group(1)?.toDoubleOrNull() else null
    }

    private fun publicarMqtt(topic: String, payload: String): Boolean {
        return try {
            val cliente = mqttClient ?: return false
            val mensaje = MqttMessage(payload.toByteArray())
            mensaje.qos = 1
            // Con isAutomaticReconnect + buffer, publish funciona incluso si
            // en este instante esta reconectando - Paho encola el mensaje.
            cliente.publish(topic, mensaje)
            Log.i(TAG, "Publicado en $topic: $payload")
            true
        } catch (e: MqttException) {
            Log.e(TAG, "Error publicando en MQTT: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado publicando: ${e.message}")
            false
        }
    }

    private fun mostrarConfirmacionVisual(texto: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show()
            }

            val notif = NotificationCompat.Builder(this, CANAL_CONFIRMACION)
                .setContentTitle("Puente Sismico")
                .setContentText(texto)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(System.currentTimeMillis().toInt(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando confirmacion visual: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener de notificaciones conectado")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            // ignorar
        }
    }
}

/*
=====================================================================
PASO 1: AndroidManifest.xml - agrega dentro de <application>:
=====================================================================

<service
    android:name=".sismico.SismoNotificationListener"
    android:label="Puente Sismico Hogar Seguro"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

Y estos permisos (fuera de <application>):
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

=====================================================================
PASO 2: build.gradle.kts (Module: app) - agrega dentro de dependencies { }:
=====================================================================

implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

Y en settings.gradle.kts, dentro de dependencyResolutionManagement -> repositories:
maven { url = uri("https://repo.eclipse.org/content/repositories/paho-releases/") }

=====================================================================
PASO 3: El permiso de notificaciones NO se pide con dialogo normal:
=====================================================================
    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
=====================================================================
*/
