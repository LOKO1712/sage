package com.tuapp.hogarseguro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Servicio MQTT NATIVO en segundo plano (foreground service).
 *
 * Se suscribe a security/alerts del mismo broker que usa el ESP32 y la
 * interfaz web, y muestra las notificaciones de gas/intruso/sismo
 * INDEPENDIENTEMENTE de que la pagina web este abierta: el unico
 * requisito es que la app se haya abierto al menos una vez desde que
 * el telefono se encendio (para arrancar este servicio).
 *
 * Anti-bucle: este servicio SOLO recibe (subscribe), nunca publica, y
 * las notificaciones que muestra son de la propia app - el
 * SismoNotificationListener las ignora por packageName.
 */
class AlertasMqttService : Service() {

    companion object {
        private const val TAG = "AlertasMqtt"
        private const val CANAL_SERVICIO = "servicio_monitoreo"
        private const val HOST = "broker.hivemq.com"
        private const val PORT = 1883
        private const val PREFIJO = "security"
        private const val ID_NOTIF_SERVICIO = 1

        /** Arranca el servicio (se puede llamar varias veces sin problema). */
        fun iniciar(context: Context) {
            val intent = Intent(context, AlertasMqttService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var client: MqttAsyncClient? = null
    private var conectando = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // El servicio DEBE entrar en foreground en menos de 5s despues de
        // startForegroundService, antes de cualquier conexion de red.
        crearCanalServicio()
        val notif = notificacionServicio()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                ID_NOTIF_SERVICIO, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ID_NOTIF_SERVICIO, notif)
        }

        conectar()
        // Si Android lo mata por cualquier motivo, lo relanza.
        return START_STICKY
    }

    private fun crearCanalServicio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_SERVICIO,
                "Monitoreo en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene vivo el monitoreo de alertas"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun notificacionServicio() =
        NotificationCompat.Builder(this, CANAL_SERVICIO)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hogar Seguro")
            .setContentText("Monitoreo de alertas activo")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun conectar() {
        try {
            if (client?.isConnected == true || conectando) return

            if (client == null) {
                val c = MqttAsyncClient(
                    "tcp://$HOST:$PORT",
                    "hogarseguro-alertas-" + System.currentTimeMillis(),
                    MemoryPersistence()
                )
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        conectando = false
                        Log.i(TAG, "MQTT conectado (reconnect=$reconnect)")
                        c.subscribe("$PREFIJO/alerts", 1, null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Log.i(TAG, "Suscrito a $PREFIJO/alerts")
                            }

                            override fun onFailure(
                                asyncActionToken: IMqttToken?,
                                exception: Throwable?
                            ) {
                                Log.e(TAG, "Error al suscribir: ${exception?.message}")
                            }
                        })
                    }

                    override fun connectionLost(cause: Throwable?) {
                        // isAutomaticReconnect se encarga de reconectar
                        Log.w(TAG, "Conexion perdida: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && topic.endsWith("/alerts") && message != null) {
                            procesarAlerta(String(message.payload))
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client = c
            }

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                isAutomaticReconnect = true
            }
            conectando = true
            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    conectando = false
                    Log.i(TAG, "Conectado al broker")
                }

                override fun onFailure(
                    asyncActionToken: IMqttToken?,
                    exception: Throwable?
                ) {
                    conectando = false
                    Log.e(TAG, "Fallo de conexion: ${exception?.message} (reintento en 15s)")
                    handler.postDelayed({ conectar() }, 15000)
                }
            })
        } catch (e: Exception) {
            conectando = false
            Log.e(TAG, "Error MQTT: ${e.message} (reintento en 15s)")
            handler.postDelayed({ conectar() }, 15000)
        }
    }

    /** Traduce el JSON {"type":..,"message":..} del ESP32 a notificacion. */
    private fun procesarAlerta(payload: String) {
        try {
            val json = JSONObject(payload)
            val tipo = json.optString("type", "").uppercase()
            val mensaje = json.optString("message", "Alerta")
            when {
                tipo.contains("GAS") && !tipo.contains("NORMAL") ->
                    NotificadorAlertas.mostrar(this, "gas", "Fuga de gas detectada", mensaje)

                tipo.contains("INTRUSO") ->
                    NotificadorAlertas.mostrar(this, "intruso", "Intruso detectado", mensaje)

                tipo.contains("SISMO") && !tipo.contains("OFF") ->
                    NotificadorAlertas.mostrar(this, "sismo", "Actividad sísmica detectada", mensaje)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alerta ilegible: $payload")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
