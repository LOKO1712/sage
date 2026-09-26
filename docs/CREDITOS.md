# Créditos y agradecimientos

Este proyecto no reemplaza ni compite con ninguna de las siguientes
iniciativas — al contrario, su módulo sísmico existe *gracias* a la
infraestructura que ya construyeron. Este documento reconoce
explícitamente ese trabajo.

## Fuentes de datos y detección sísmica

- **[Earthquake Network / Sismo Detector](https://sismo.app)** — Francesco
  Finazzi, proyecto de ciencia ciudadana desde 2012 con más de 25 millones
  de usuarios. Su app (`com.finazzi.distquake`) es una de las fuentes de
  detección temprana que este proyecto intercepta y reenvía.

- **Android Earthquake Alerts System** — Google / Android. Sistema de
  alerta temprana crowdsourced integrado en el sistema operativo, usado
  como segunda fuente de confirmación.

- **[GeoShake](https://geoshake.org)** — Red sísmica ciudadana de hardware
  abierto (ESP32-S3 + acelerómetros LSM6DSO), con servidor SeedLink
  público, API REST/MQTT/SSE, y firmware open-source (GPL-3.0). Su feed
  MQTT está integrado como cuarta fuente redundante del sistema.

- **[RaspberryShake](https://raspberryshake.net)** — Su estación en línea
  **S99D0** se embebe en el panel web (sismograma en vivo) como fuente de
  visualización complementaria.

- **[EMSC - SeismicPortal](https://www.seismicportal.eu)** — Centro
  Sismológico Euro-Mediterráneo, por su WebSocket público de notificación
  de eventos sísmicos en tiempo real.

## Infraestructura

- **[HiveMQ](https://www.hivemq.com)** — por el broker MQTT público
  (`broker.hivemq.com`) usado durante el desarrollo y pruebas del sistema.

- **[Eclipse Paho](https://www.eclipse.org/paho/)** — cliente MQTT para
  Android usado en la app.

## Investigación (fuentes consultadas, no integradas)

Durante la investigación también se evaluaron — y finalmente se
descartaron por no ser viables para este caso de uso — las siguientes
redes e instituciones, cuyo trabajo es igualmente valioso para la
sismología global:

- Raspberry Shake (como fuente de datos crudos en tiempo real — descartada
  para alerta; su estación en línea S99D0 sí se usa para visualización,
  ver arriba)
- EarthScope (antes IRIS)
- GEOFON / GFZ Potsdam
- Servicio Geológico Colombiano (SGC)

---

Si eres parte de alguno de estos proyectos y tienes comentarios sobre
cómo se describe o utiliza tu trabajo aquí, no dudes en abrir un issue.
