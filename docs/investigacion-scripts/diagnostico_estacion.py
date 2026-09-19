"""
Diagnostico: consulta que canales y location codes tiene realmente
una estacion Raspberry Shake, y prueba pedir datos con un pequeno
retraso (para evitar el error "No data available", HTTP 204).
"""

from obspy import UTCDateTime
from obspy.clients.fdsn import Client

NETWORK = "AM"
STATION = "SD990"   # <-- tu estacion

client = Client("RASPISHAKE")

print(f"Consultando metadatos de {NETWORK}.{STATION} ...")
try:
    inv = client.get_stations(network=NETWORK, station=STATION, level="channel")
    print(inv)
    for net in inv:
        for sta in net:
            for cha in sta:
                print(f"  -> location='{cha.location_code}' channel='{cha.code}' "
                      f"sample_rate={cha.sample_rate}")
except Exception as e:
    print(f"Error consultando metadatos: {e}")
    print("Si esto falla, revisa que el codigo de estacion sea correcto.")
    raise SystemExit

print("\nProbando descarga de datos con 90 segundos de retraso de seguridad...")
end = UTCDateTime.now() - 90     # <-- retraso de seguridad
start = end - 60

# Prueba con cada combinacion de location/channel que haya reportado arriba
for net in inv:
    for sta in net:
        for cha in sta:
            try:
                st = client.get_waveforms(NETWORK, STATION, cha.location_code,
                                           cha.code, start, end)
                print(f"OK -> {cha.location_code}.{cha.code}: {st}")
            except Exception as e:
                print(f"FALLA -> {cha.location_code}.{cha.code}: {e}")
