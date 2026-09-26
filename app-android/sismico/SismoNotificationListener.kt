package com.tuapp.hogarseguro.sismico

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
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Escucha las notificaciones de apps de alerta sismica (Sismo Detector,
 * GeoShake, y Google) y republica cada alerta como un mensaje MQTT hacia
 * HiveMQ, para que el ESP32 y la interfaz web la reciban.
 *
 * Esta es la version BASE CONFIRMADA FUNCIONANDO (cliente MQTT sincrono,
 * sin Foreground Service) + el agregado de GeoShake, sin tocar nada mas
 * de lo que ya funcionaba.
 *
 * IMPORTANTE - pasos manuales necesarios (no se pueden hacer por codigo):
 * 1. El usuario debe activar el acceso a notificaciones para esta app en:
 *    Ajustes -> Apps y notificaciones -> Acceso especial -> Acceso a notificaciones
 * 2. Declarar el servicio en AndroidManifest.xml (ver comentario al final).
 * 3. Agregar la dependencia de Eclipse Paho MQTT en build.gradle (ver abajo).
 */
class SismoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SismoListener"
        private const val PAQUETE_SISMO_DETECTOR = "com.finazzi.distquake"
        private const val PAQUETE_GEOSHAKE = "com.geoshake"
        // Confirmado por el usuario: el paquete real es Google Play Services,
        // NO com.google.android.apps.safetyhub (paquete enorme y compartido,
        // por eso exigimos SIEMPRE la palabra clave para este especifico)
        private const val PAQUETE_GOOGLE_GMS = "com.google.android.gms"
        private const val CANAL_CONFIRMACION = "sismo_bridge_confirmacion"

        // "sacudida" agregada tras confirmar que GeoShake usa esa palabra
        // en vez de "sismo"/"terremoto"
        private val PALABRAS_CLAVE_SISMO = listOf(
            "sismo", "terremoto", "earthquake", "temblor", "sacudida"
        )

        // --- CONFIGURACION DEL BROKER (mismo que ya usa tu interfaz web) ---
        private const val HIVEMQ_HOST = "broker.hivemq.com"
        private const val HIVEMQ_PORT = 8883

        // Mismo prefijo "security" que usa tu app.js (config.prefix)
        private const val PREFIJO = "security"
        private const val TOPIC_ALERTS = "$PREFIJO/alerts"
        private const val TOPIC_SEISMIC_SENSOR = "$PREFIJO/sensors/seismic"

        // Distancia: "a 150 km" (Sismo Detector) o variantes de Google:
        // "epicentro a 290,2 km" / "a aproximadamente 141,3 km de distancia"
        private val PATRON_DISTANCIA = Pattern.compile(
            "(?:epicentro\\s+a|a)\\s+(?:aproximadamente\\s+)?([\\d]+[.,]?\\d*)\\s*km",
            Pattern.CASE_INSENSITIVE
        )
        // Magnitud: "M4.5" (Sismo Detector) o variantes de Google:
        // "Magnitud estimada de 6,4" / "Magnitud inicial estimada de 5,5"
        private val PATRON_MAGNITUD = Pattern.compile(
            "(?:M\\s*|Magnitud\\s+(?:inicial\\s+)?estimada\\s+de\\s*)([\\d]+[.,]?\\d*)",
            Pattern.CASE_INSENSITIVE
        )
        // Formato GeoShake: "4 estaciones - sacudida moderada"
        private val PATRON_ESTACIONES = Pattern.compile("(\\d+)\\s*estaciones?", Pattern.CASE_INSENSITIVE)
        private val PATRON_SEVERIDAD = Pattern.compile("sacudida\\s+(\\w+)", Pattern.CASE_INSENSITIVE)
    }

    private var mqttClient: MqttClient? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalConfirmacion()
        conectarMqtt()
    }

    private fun crearCanalConfirmacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_CONFIRMACION,
                "Confirmacion de alertas sismicas",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun conectarMqtt() {
        try {
            val clientId = "esp32-bridge-" + System.currentTimeMillis()
            mqttClient = MqttClient(
                "ssl://$HIVEMQ_HOST:$HIVEMQ_PORT",
                clientId,
                MemoryPersistence()
            )
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
            }
            mqttClient?.connect(options)
            Log.i(TAG, "Conectado a HiveMQ correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo conectar a HiveMQ: ${e.message}")
        }
    }

    private var ultimoProcesadoMs = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // FIX CRITICO: nunca procesar notificaciones de la propia app - si no,
        // nuestras propias confirmaciones ("Capturada: ...sacudida...") se
        // vuelven a capturar a si mismas y generan un bucle infinito.
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val titulo = extras.getCharSequence("android.title")?.toString() ?: ""
        val texto = extras.getCharSequence("android.text")?.toString() ?: ""

        val esSismoDetector = sbn.packageName == PAQUETE_SISMO_DETECTOR
        val esGeoShake = sbn.packageName == PAQUETE_GEOSHAKE
        val esGoogleGms = sbn.packageName == PAQUETE_GOOGLE_GMS
        val contienePalabraClave = PALABRAS_CLAVE_SISMO.any {
            titulo.contains(it, ignoreCase = true) || texto.contains(it, ignoreCase = true)
        }

        // Filtro cerrado: SOLO estas 3 apps, ya no cualquier app con palabra
        // clave (eso era util para investigar cuando no sabiamos los paquetes
        // exactos, pero ahora es mas riesgo que ayuda - cualquier chat/noticia
        // que mencione "sismo" generaria una alerta falsa).
        // Sismo Detector: esa app SOLO manda cosas de sismos, confiamos en el
        // paquete solo. GeoShake y Google tambien mandan notificaciones que
        // NO son alertas (estado de conexion, servicio, etc.) - para esas DOS
        // exigimos la palabra clave siempre, sin excepcion.
        val debeProcesar = esSismoDetector ||
                (esGeoShake && contienePalabraClave) ||
                (esGoogleGms && contienePalabraClave)

        if (!debeProcesar) return

        // Segunda red de seguridad: nunca procesar mas de 1 notificacion cada
        // 1 segundo, pase lo que pase. Si algo genera una tormenta de
        // notificaciones por cualquier motivo futuro, esto la corta de raiz.
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoProcesadoMs < 1000) {
            Log.w(TAG, "Notificacion ignorada por debounce (demasiado seguida): ${sbn.packageName}")
            return
        }
        ultimoProcesadoMs = ahora

        Log.i(TAG, "Notificacion capturada [${sbn.packageName}] -> Titulo: $titulo | Texto: $texto")
        mostrarConfirmacionVisual("Capturada: $titulo")

        val esPrueba = titulo.contains("Test", ignoreCase = true) ||
                titulo.contains("Prueba", ignoreCase = true)

        val distanciaKm = extraerNumero(PATRON_DISTANCIA, "$titulo $texto")
        val magnitud = extraerNumero(PATRON_MAGNITUD, "$titulo $texto")
        val estaciones = if (esGeoShake) extraerNumero(PATRON_ESTACIONES, texto)?.toInt() else null
        val severidad = if (esGeoShake) {
            // Busca SOLO en el cuerpo (texto) - el titulo siempre dice
            // "sacudida detectada" y contaminaba la severidad real
            val m = PATRON_SEVERIDAD.matcher(texto)
            if (m.find()) m.group(1) else null
        } else null

        val nombreFuente = when (sbn.packageName) {
            PAQUETE_SISMO_DETECTOR -> "Sismo Detector"
            PAQUETE_GEOSHAKE -> "GeoShake"
            PAQUETE_GOOGLE_GMS -> "Google"
            else -> "App desconocida (${sbn.packageName})"
        }

        // Arma el mensaje legible que vera el usuario en la lista de alertas
        val mensaje = buildString {
            append("$nombreFuente: ")
            if (distanciaKm != null) append("a ${distanciaKm.toInt()} km")
            if (magnitud != null) append(" - M${magnitud}")
            if (estaciones != null) append(" - $estaciones estaciones")
            if (severidad != null) append(" ($severidad)")
            if (esPrueba) append(" (PRUEBA)")
        }

        // "SISMO_P" = alerta temprana (deteccion crowdsourced, antes de sentirse)
        // "SISMO"   = confirmacion mas fuerte (Google solo avisa cuando ya es real/inminente)
        val tipoAlerta = if (esGoogleGms) "SISMO" else "SISMO_P"

        val payloadAlerta = JSONObject().apply {
            put("type", tipoAlerta)
            put("message", mensaje)
            put("fuente", sbn.packageName)
            if (estaciones != null) put("estaciones", estaciones)
            if (severidad != null) put("severidad", severidad)
        }

        val publicoOk = publicarMqtt(TOPIC_ALERTS, payloadAlerta.toString())
        mostrarConfirmacionVisual(
            if (publicoOk) "MQTT publicado: $mensaje" else "ERROR publicando MQTT (revisa conexion)"
        )

        if (!esPrueba) {
            publicarMqtt(TOPIC_SEISMIC_SENSOR, "true")
        }
    }

    /**
     * Confirmacion visual doble: un Toast (rapido, solo si la pantalla esta
     * encendida en ese momento) y una notificacion local que se queda en la
     * barra (para verla despues, aunque no hayas visto el Toast a tiempo).
     */
    private fun mostrarConfirmacionVisual(texto: String) {
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
    }

    private fun extraerNumero(patron: Pattern, texto: String): Double? {
        val matcher = patron.matcher(texto)
        return if (matcher.find()) {
            // Google usa coma decimal ("6,4"), Sismo Detector usa punto - normalizamos
            matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
        } else null
    }

    private fun publicarMqtt(topic: String, payload: String): Boolean {
        return try {
            if (mqttClient?.isConnected != true) {
                conectarMqtt()
            }
            val mensaje = MqttMessage(payload.toByteArray())
            mensaje.qos = 1
            mqttClient?.publish(topic, mensaje)
            Log.i(TAG, "Publicado en $topic: $payload")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando en MQTT: ${e.message}")
            false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener de notificaciones conectado")
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
    android:exported="false">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

Tambien agrega este permiso de internet (fuera de <application>):
<uses-permission android:name="android.permission.INTERNET" />

=====================================================================
PASO 2: build.gradle.kts (Module: app) - agrega dentro de dependencies { }:
=====================================================================

implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

Y en el archivo settings.gradle.kts (a nivel de PROYECTO), busca el bloque
dependencyResolutionManagement { repositories { ... } } y agrega ahi dentro:
maven { url = uri("https://repo.eclipse.org/content/repositories/paho-releases/") }

=====================================================================
PASO 3: Pedir el permiso al usuario (desde tu Activity principal)
=====================================================================

Este permiso NO se puede pedir con un dialogo normal, hay que llevar
al usuario directamente a los ajustes del sistema:

    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
=====================================================================
*/