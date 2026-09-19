"""
Procesamiento de datos sismicos en tiempo real - Script de prueba (v2)
========================================================================
Fuente de datos: servicio FDSN publico de Raspberry Shake (HTTPS)
Objetivo: 1) graficar la senal casi en vivo (polling cada pocos segundos)
          2) detectar posibles eventos sismicos con STA/LTA
Este script es el paso previo a publicar la alerta por MQTT al ESP32
(el punto donde se debe enganchar esa publicacion esta marcado abajo).

NOTA IMPORTANTE:
Raspberry Shake NO tiene un servidor SeedLink publico para estaciones de
terceros (SeedLink solo esta disponible en la red local de cada equipo).
Lo que si es publico es su servicio FDSN (HTTP/HTTPS), que es lo que usa
este script. No es streaming continuo: se hace una consulta periodica
("polling") pidiendo los ultimos N segundos de la estacion elegida.

Instalacion:
    pip install obspy matplotlib numpy

Antes de correrlo:
    - Busca una estacion Raspberry Shake activa en:
      https://raspberryshakedata.com/ (mapa de estaciones publicas)
    - Reemplaza STATION con su codigo (ej: "R50D4", "RCB43", etc.)
    - Ojo: el buffer publico de cada estacion puede tener algunos segundos
      a minutos de retraso segun su conexion a internet.
"""

import matplotlib.pyplot as plt
import matplotlib.animation as animation

from obspy import UTCDateTime
from obspy.clients.fdsn import Client
from obspy.signal.trigger import classic_sta_lta, trigger_onset

# ---------------------------------------------------------------------------
# CONFIGURACION
# ---------------------------------------------------------------------------
NETWORK = "AM"          # red publica de Raspberry Shake
STATION = "R50D4"       # <-- CAMBIA esto por una estacion cercana a ti
LOCATION = "00"
CHANNEL = "EHZ"         # canal vertical (geofono), el mas usado para triggers

FETCH_INTERVAL = 5      # segundos entre cada consulta al servidor FDSN
WINDOW_SECONDS = 120    # segundos de historia solicitados en cada consulta

STA_SECONDS = 1         # ventana corta (Short Term Average)
LTA_SECONDS = 10        # ventana larga (Long Term Average)
TRIGGER_ON = 3.5        # umbral de disparo de alerta (subir si hay falsos positivos)
TRIGGER_OFF = 1.0       # umbral de fin de evento

BANDPASS_LOW = 1.0      # Hz - filtra ruido de baja frecuencia
BANDPASS_HIGH = 10.0    # Hz - filtra ruido de alta frecuencia

# ---------------------------------------------------------------------------
client = Client("RASPISHAKE")
last_alert_time = None


def send_mqtt_alert(alert_time, max_ratio):
    """
    Punto de enganche para el siguiente paso del proyecto.
    Aqui es donde, mas adelante, se publicara el mensaje MQTT al ESP32,
    por ejemplo con paho-mqtt:

        import paho.mqtt.publish as publish
        publish.single("hogar-seguro/sismo/alerta",
                        payload=f"SISMO_DETECTADO;{alert_time};{max_ratio:.2f}",
                        hostname="IP_DEL_BROKER")

    Por ahora solo se imprime en consola para probar la deteccion.
    """
    print(f"[ALERTA] Posible sismo detectado - {alert_time} "
          f"(STA/LTA maximo: {max_ratio:.2f})")


def main():
    global last_alert_time

    fig, (ax_wave, ax_sta) = plt.subplots(2, 1, figsize=(10, 6))
    fig.suptitle(f"Estacion {NETWORK}.{STATION} - canal {CHANNEL} (FDSN polling)")

    def update(frame):
        global last_alert_time
        end = UTCDateTime.now()
        start = end - WINDOW_SECONDS

        try:
            st = client.get_waveforms(NETWORK, STATION, LOCATION, CHANNEL, start, end)
        except Exception as e:
            print(f"[AVISO] No se pudo consultar la estacion todavia: {e}")
            return

        if len(st) == 0:
            print("[AVISO] Respuesta vacia, reintentando en el proximo ciclo...")
            return

        st.merge(method=1, fill_value="interpolate")
        tr = st[0].copy()
        tr.detrend("demean")

        try:
            tr.filter("bandpass", freqmin=BANDPASS_LOW, freqmax=BANDPASS_HIGH)
        except Exception:
            return  # datos insuficientes todavia para filtrar

        times = tr.times()

        ax_wave.cla()
        ax_wave.plot(times, tr.data, linewidth=0.7, color="steelblue")
        ax_wave.set_ylabel("Cuentas")
        ax_wave.set_title("Forma de onda (ultimos %d s)" % WINDOW_SECONDS)

        sr = tr.stats.sampling_rate
        if sr <= 0 or tr.stats.npts / sr < LTA_SECONDS * 2:
            return  # aun no hay suficiente historia para calcular STA/LTA

        nsta = max(1, int(STA_SECONDS * sr))
        nlta = max(1, int(LTA_SECONDS * sr))
        cft = classic_sta_lta(tr.data, nsta, nlta)

        ax_sta.cla()
        ax_sta.plot(times, cft, color="darkorange")
        ax_sta.axhline(TRIGGER_ON, color="red", linestyle="--", label="Umbral ON")
        ax_sta.axhline(TRIGGER_OFF, color="green", linestyle="--", label="Umbral OFF")
        ax_sta.set_ylabel("STA/LTA")
        ax_sta.set_xlabel("Tiempo (s)")
        ax_sta.legend(loc="upper right")

        onsets = trigger_onset(cft, TRIGGER_ON, TRIGGER_OFF)
        if len(onsets) > 0:
            now = UTCDateTime.now()
            if last_alert_time is None or (now - last_alert_time) > LTA_SECONDS:
                last_alert_time = now
                send_mqtt_alert(now, float(cft.max()))

    ani = animation.FuncAnimation(
        fig, update, interval=FETCH_INTERVAL * 1000, cache_frame_data=False
    )
    plt.tight_layout()
    plt.show()


if __name__ == "__main__":
    main()
