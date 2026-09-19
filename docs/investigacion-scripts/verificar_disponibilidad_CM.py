"""
Verifica si CM.BRR tiene CUALQUIER dato reciente en el archivo,
probando ventanas de hace 1 hora, 1 dia y 7 dias.
Esto nos dice si la estacion transmite continuamente o de forma
intermitente/con mucho retraso.
"""

from obspy import UTCDateTime
from obspy.clients.fdsn import Client

NETWORK = "CM"
STATION = "BRR"
LOCATION = "00"
CHANNEL = "BHZ"

client = Client("EARTHSCOPE")

pruebas = [
    ("hace 1 hora", 60 * 60),
    ("hace 6 horas", 6 * 60 * 60),
    ("hace 1 dia", 24 * 60 * 60),
    ("hace 3 dias", 3 * 24 * 60 * 60),
    ("hace 7 dias", 7 * 24 * 60 * 60),
]

print(f"Verificando disponibilidad de datos de {NETWORK}.{STATION}.{LOCATION}.{CHANNEL}\n")

for etiqueta, segundos_atras in pruebas:
    end = UTCDateTime.now() - segundos_atras
    start = end - 60

    try:
        st = client.get_waveforms(NETWORK, STATION, LOCATION, CHANNEL, start, end)
        if len(st) > 0:
            print(f"OK  -> {etiqueta}: SI hay datos -> {st[0]}")
        else:
            print(f"---  -> {etiqueta}: respuesta vacia")
    except Exception as e:
        print(f"FALLA -> {etiqueta}: {e}")

print("\nTambien probamos otra estacion cercana (CM.RUS, La Rusia, Boyaca) "
      "por si BRR esta caida:")
for etiqueta, segundos_atras in [("hace 1 dia", 24 * 60 * 60), ("hace 7 dias", 7 * 24 * 60 * 60)]:
    end = UTCDateTime.now() - segundos_atras
    start = end - 60
    try:
        st = client.get_waveforms("CM", "RUS", "00", "BHZ", start, end)
        if len(st) > 0:
            print(f"OK  -> CM.RUS {etiqueta}: SI hay datos -> {st[0]}")
        else:
            print(f"---  -> CM.RUS {etiqueta}: respuesta vacia")
    except Exception as e:
        print(f"FALLA -> CM.RUS {etiqueta}: {e}")
