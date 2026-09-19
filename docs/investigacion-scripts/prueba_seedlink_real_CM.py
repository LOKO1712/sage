"""
Prueba de conexion SeedLink REAL usando el cliente base de ObsPy (SLClient),
que es el modulo activamente mantenido (a diferencia de 'easyseedlink' que
tiene un bug de compatibilidad con versiones nuevas de Python).

Prueba contra el servidor publico de EarthScope, suscribiendose a la
estacion colombiana CM.BRR (Barrancabermeja, Santander).

Instalacion:
    pip install obspy
"""

import threading
import time

from obspy.clients.seedlink.slclient import SLClient
from obspy import Stream

SEEDLINK_SERVER = "rtserve.earthscope.org:18000"
NETWORK = "CM"
STATION = "BRR"
CHANNEL = "BHZ"

TIEMPO_DE_PRUEBA = 30  # segundos

recibido = {"count": 0}


class MiClienteSeedLink(SLClient):
    def __init__(self):
        super().__init__(loglevel="INFO")
        self.stream = Stream()

    def packet_handler(self, count, slpack):
        """Se llama automaticamente cada vez que llega un paquete."""
        packet_type, trace = super().packet_handler(count, slpack)

        if trace is not None:
            recibido["count"] += 1
            print(f"[PAQUETE #{recibido['count']}] {trace}")
            self.stream += trace
            self.stream.merge(method=1, fill_value="interpolate")

        return False  # False = seguir escuchando


def main():
    print(f"Conectando a {SEEDLINK_SERVER} y suscribiendo a "
          f"{NETWORK}.{STATION}.{CHANNEL} ...\n")

    client = MiClienteSeedLink()
    client.slconn.set_sl_address(SEEDLINK_SERVER)
    client.slconn.timeout = 30.0          # <-- evita el bug del timeout None
    client.multiselect = f"{NETWORK}_{STATION}:{CHANNEL}"

    try:
        client.initialize()
    except Exception as e:
        print(f"No se pudo inicializar/conectar: {e}")
        return

    # SLClient.run() es bloqueante -> lo corremos en un hilo aparte
    hilo = threading.Thread(target=client.run, daemon=True)
    hilo.start()

    print(f"Conectado. Esperando datos en vivo ({TIEMPO_DE_PRUEBA}s de prueba)...\n")
    time.sleep(TIEMPO_DE_PRUEBA)

    if recibido["count"] > 0:
        print(f"\n>>> EXITO: se recibieron {recibido['count']} paquetes en vivo. <<<")
        print(client.stream)
    else:
        print("\n>>> No llegaron datos en el tiempo de prueba. La estacion "
              "puede no estar transmitiendo a este servidor en tiempo real, "
              "o el codigo de red/estacion/canal no coincide con lo que el "
              "servidor tiene disponible. <<<")


if __name__ == "__main__":
    main()
