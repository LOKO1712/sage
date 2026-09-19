"""
Descubrimiento de estaciones sismicas colombianas (red CM) y prueba de
disponibilidad en tiempo real vía el servidor SeedLink publico de
EarthScope (antes IRIS). Esta red SI es un servidor SeedLink real
(streaming continuo), a diferencia del FDSN de Raspberry Shake que
tiene 30 minutos de retraso por diseno.

Instalacion:
    pip install obspy
"""

import time
from obspy.clients.fdsn import Client
from obspy.clients.seedlink.easyseedlink import create_client

# ---------------------------------------------------------------------------
# PASO 1: listar estaciones de la red sismologica nacional de Colombia (CM)
# ---------------------------------------------------------------------------
print("Consultando estaciones de la red CM (Colombia) en el catalogo IRIS...")
iris = Client("IRIS")
try:
    inv = iris.get_stations(network="CM", level="channel")
    print(inv)
    estaciones = []
    for net in inv:
        for sta in net:
            for cha in sta:
                estaciones.append((sta.code, cha.location_code, cha.code))
    print(f"\nTotal de canales encontrados: {len(estaciones)}")
except Exception as e:
    print(f"No se pudo consultar el catalogo: {e}")
    estaciones = []

# ---------------------------------------------------------------------------
# PASO 2: probar conexion en vivo al servidor SeedLink de EarthScope
# ---------------------------------------------------------------------------
SEEDLINK_SERVER = "rtserve.earthscope.org:18000"
recibido = {"ok": False}


def on_data(trace):
    recibido["ok"] = True
    print(f"[DATO RECIBIDO EN VIVO] {trace}")


print(f"\nProbando conexion en tiempo real a {SEEDLINK_SERVER} ...")
try:
    client = create_client(SEEDLINK_SERVER, on_data=on_data)

    # Prueba con las primeras estaciones CM encontradas (o unas conocidas si
    # la consulta de arriba fallo)
    candidatas = estaciones[:5] if estaciones else [
        ("BAR2", "00", "BHZ"),
        ("URMC", "00", "BHZ"),
    ]

    for sta, loc, cha in candidatas:
        try:
            client.select_stream("CM", sta, cha)
            print(f"  Suscrito a CM.{sta}.{loc}.{cha}")
        except Exception as e:
            print(f"  No se pudo suscribir a CM.{sta}: {e}")

    print("\nEsperando 20 segundos por datos en vivo...")
    start = time.time()
    while time.time() - start < 20:
        client.run(timeout=1)
        if recibido["ok"]:
            break

    if recibido["ok"]:
        print("\n>>> Hay datos en tiempo real disponibles para Colombia. <<<")
    else:
        print("\n>>> No llegaron datos en 20s. Puede que CM no transmita en "
              "vivo a este servidor, o que estas estaciones especificas "
              "esten fuera de linea. Revisa el listado del PASO 1 para "
              "probar otros codigos de estacion. <<<")

except Exception as e:
    print(f"No se pudo conectar al servidor SeedLink: {e}")
