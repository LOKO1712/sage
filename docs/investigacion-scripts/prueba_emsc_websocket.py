"""
Prueba del WebSocket publico y oficial de EMSC (SeismicPortal).
Envia un mensaje JSON cada vez que se detecta/actualiza un sismo
en cualquier parte del mundo. Es notificacion POST-deteccion
(no forma de onda cruda), pero suele publicarse en menos de 1-2
minutos tras el evento - mas rapido que consultar la API de USGS
por polling.

Instalacion:
    pip install websockets
"""

import asyncio
import json
import websockets

WS_URL = "wss://www.seismicportal.eu/standing_order/websocket"

# Caja aproximada alrededor de Colombia, para resaltar eventos locales
COLOMBIA_LAT_MIN, COLOMBIA_LAT_MAX = -5.0, 13.0
COLOMBIA_LON_MIN, COLOMBIA_LON_MAX = -82.0, -66.0


def es_cerca_de_colombia(lat, lon):
    return (COLOMBIA_LAT_MIN <= lat <= COLOMBIA_LAT_MAX and
            COLOMBIA_LON_MIN <= lon <= COLOMBIA_LON_MAX)


async def escuchar():
    print(f"Conectando a {WS_URL} ...\n")
    async with websockets.connect(WS_URL, ping_interval=15) as ws:
        print("Conectado. Esperando eventos sismicos globales en vivo...\n"
              "(puede tardar varios minutos en llegar el primero, los sismos "
              "grandes no ocurren a cada rato)\n")
        async for mensaje in ws:
            try:
                data = json.loads(mensaje)
                props = data["data"]["properties"]
                accion = data.get("action", "?")
                lat, lon = props["lat"], props["lon"]
                cerca = " <<<< CERCA DE COLOMBIA" if es_cerca_de_colombia(lat, lon) else ""

                print(f"[{accion.upper()}] M{props['mag']} {props['flynn_region']} "
                      f"- {props['time']} (auth: {props['auth']}){cerca}")
            except Exception as e:
                print(f"No se pudo interpretar el mensaje: {e}")


if __name__ == "__main__":
    try:
        asyncio.run(escuchar())
    except KeyboardInterrupt:
        print("\nDetenido por el usuario.")
