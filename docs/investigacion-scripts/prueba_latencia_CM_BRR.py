"""
Prueba de latencia real para la estacion sismica CM.BRR (Barrancabermeja,
Santander), la red profesional del SGC catalogada en EarthScope/IRIS.

A diferencia de Raspberry Shake (30 min de retraso fijo por politica),
las redes profesionales normalmente no tienen ese limite artificial.
Este script prueba distintos retrasos para encontrar la latencia real.

Instalacion:
    pip install obspy
"""

from obspy import UTCDateTime
from obspy.clients.fdsn import Client

NETWORK = "CM"
STATION = "BRR"     # Barrancabermeja, Santander - la mas cercana a ti
LOCATION = "00"
CHANNEL = "BHZ"     # componente vertical de banda ancha

client = Client("EARTHSCOPE")  # antes 'IRIS', incluye el catalogo CM

# Probamos varios retrasos: 30s, 60s, 2min, 5min, 10min
retrasos_a_probar = [30, 60, 120, 300, 600]

print(f"Probando latencia real de {NETWORK}.{STATION}.{LOCATION}.{CHANNEL} ...\n")

for delay in retrasos_a_probar:
    end = UTCDateTime.now() - delay
    start = end - 30  # pide una ventana de 30s terminando "delay" segundos atras

    try:
        st = client.get_waveforms(NETWORK, STATION, LOCATION, CHANNEL, start, end)
        if len(st) > 0:
            print(f"OK  -> con {delay:>4}s de retraso SI hay datos: {st[0]}")
        else:
            print(f"---  -> con {delay:>4}s de retraso: respuesta vacia")
    except Exception as e:
        print(f"FALLA -> con {delay:>4}s de retraso: {e}")

print("\nUsa el menor retraso que haya funcionado como referencia para el "
      "script principal (variable DATA_DELAY_SECONDS).")
