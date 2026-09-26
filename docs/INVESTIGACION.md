# Investigación: en busca de datos sísmicos en tiempo real

Este documento resume el proceso real de investigación detrás del módulo de
alerta sísmica del proyecto. Se documenta a propósito, porque el camino
recorrido — y sobre todo los callejones sin salida descartados con evidencia —
es tan valioso como la solución final.

## El objetivo original

La idea inicial era simple: conectarse a una red pública de sismómetros
(tipo Raspberry Shake), procesar la forma de onda con Python, y disparar una
alerta antes de que la sacudida fuerte llegara a la ubicación del sistema.

## Fuentes descartadas (con evidencia)

### Raspberry Shake — SeedLink público
**No existe.** Raspberry Shake no ofrece un servidor SeedLink público para
consumir estaciones de terceros; el protocolo solo está disponible en la red
local de cada equipo.

### Raspberry Shake — FDSN
Sí es público, pero su documentación oficial establece que **solo sirve datos
de hace 30 minutos en adelante, por diseño** — inútil para alerta temprana.

### EarthScope / IRIS (SeedLink real)
Servidor SeedLink genuinamente en tiempo real (`rtserve.earthscope.org`).
Se probó específicamente contra `CM.BRR` (Barrancabermeja, Santander, la
estación colombiana más cercana), usando el cliente base de ObsPy
(`SLClient`, no el wrapper `easyseedlink` que tiene un bug de compatibilidad
con Python recientes). **Resultado: sin datos** — la red colombiana (CM,
operada por el SGC) no transmite a este servidor en tiempo real.

### GEOFON / GFZ (SeedLink real)
Otro servidor SeedLink genuinamente en tiempo real, con latencias reales de
2-30 segundos confirmadas en su monitor público. Se revisó el catálogo
completo de +30 redes contribuyentes: **cero estaciones colombianas o
cercanas con utilidad real**.

### Red profesional CM (SGC) vía FDSN
Se confirmó que la red existe y tiene 27 estaciones catalogadas (incluida
`CM.BRR`), pero **no publica datos casi-tiempo-real** — se probaron ventanas
de 30 segundos hasta 7 días atrás, todas sin resultado.

### Visor en vivo del SGC (`trazas.sgc.gov.co`)
Se inspeccionó el tráfico de red real del visor (vía DevTools). Resultado:
**es un stream MJPEG** (una imagen que se refresca), no datos numéricos —
no hay forma práctica de extraer una serie de tiempo utilizable de ahí.

### Apps de alerta post-evento
Apps como "Mis Alertas de Terremoto" resultaron ser agregadores de catálogos
oficiales (USGS/EMSC) sin API pública — confirmado por reseñas de usuarios
que reportan alarmas que suenan "hasta dos minutos después" del sismo.

### EMSC SeismicPortal (WebSocket)
Este sí es una fuente pública, oficial y funcional
(`wss://www.seismicportal.eu/standing_order/websocket`) — se probó en vivo y
funciona. Pero es notificación **post-detección** (el sismo ya fue ubicado y
procesado por un centro sismológico), no alerta previa a la sacudida.

### Android Earthquake Alerts (Google)
Confirmado que cubre Colombia y funciona en la práctica. Pero no existe una
API pública para desarrolladores — hay un hilo abierto en el rastreador de
errores oficial de Google (2025) pidiéndola, sin respuesta.

## El giro: interceptar notificaciones en vez de pedir acceso a datos

El cambio de enfoque clave fue notar que **no se necesita acceso a los datos
crudos de estas redes — solo a su conclusión ya procesada**, y esa
conclusión sí llega al teléfono en forma de notificación push. Android ofrece
una API pública y legítima (`NotificationListenerService`, la misma que usan
apps como Pushbullet) para leer notificaciones de cualquier app instalada,
con consentimiento explícito del usuario.

Esto llevó a la arquitectura final: una app propia que intercepta las
notificaciones de **Sismo Detector** (Earthquake Network) y de **Android
Earthquake Alerts** (Google), las interpreta, y las republica por MQTT.

## GeoShake — el hallazgo más reciente

Sobre la marcha se descubrió [GeoShake](https://geoshake.org): una red
sísmica ciudadana con hardware **basado en ESP32-S3** (mismo ecosistema del
resto del proyecto), con:
- Servidor SeedLink público real (`seedlink.geoshake.org:18000`)
- Un feed **MQTT** de eventos confirmados (`geoshake/events`)
- Un stream **SSE** anónimo (`/api/live`)
- Firmware open-source (GPL-3.0) con un detector STA/LTA ya implementado

Esto se convirtió en dos resultados prácticos:
1. **Integrado:** su feed MQTT (`geoshake/events`) funciona hoy como **cuarta
   fuente redundante** del sistema — validado en vivo con un evento sísmico
   real el 24 de septiembre de 2026.
2. **En el roadmap:** construir un nodo GeoShake propio (mismo ESP32-S3,
   ~$15-30 USD en componentes) para tener detección local genuina —
   ver [`ROADMAP.md`](ROADMAP.md).

## Conclusión

No existe (a la fecha) una red pública, gratuita, y accesible por API que dé
datos sísmicos crudos en tiempo real para Colombia. La solución robusta no
fue encontrar "la fuente perfecta", sino **combinar varias fuentes
imperfectas y redundantes**, aprovechando infraestructura que ya existe
(las apps de terceros) en vez de reconstruirla desde cero.
