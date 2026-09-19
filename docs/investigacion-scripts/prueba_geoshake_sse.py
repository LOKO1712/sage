"""
Prueba del stream SSE (Server-Sent Events) publico y anonimo de GeoShake.
No requiere registro ni API key. Escucha eventos sismicos CONFIRMADOS
(3+ estaciones GeoShake de acuerdo) en tiempo real.

Instalacion:
    pip install sseclient-py requests
"""

import requests
import sseclient
import json

URL = "https://api.geoshake.org/api/live"


def escuchar():
    print(f"Conectando a {URL} ...\n")
    respuesta = requests.get(URL, stream=True, headers={"Accept": "text/event-stream"})
    cliente = sseclient.SSEClient(respuesta)

    print("Conectado. Esperando eventos sismicos confirmados en vivo...\n"
          "(puede tardar - solo llegan sismos que GeoShake confirmo con "
          "3+ estaciones de acuerdo)\n")

    for evento in cliente.events():
        try:
            data = json.loads(evento.data)
            print(f"[EVENTO CONFIRMADO] {json.dumps(data, indent=2, ensure_ascii=False)}")
        except Exception as e:
            print(f"Mensaje crudo (no JSON): {evento.data} | error: {e}")


if __name__ == "__main__":
    try:
        escuchar()
    except KeyboardInterrupt:
        print("\nDetenido por el usuario.")
