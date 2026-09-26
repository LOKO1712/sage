# Firmware ESP32 — SAGE

**Estado: 🚧 Parcialmente escrito**

Esta carpeta documenta el diseño del firmware del ESP32, pieza central del
sistema físico de seguridad. Por tratarse de un sistema que controla gas,
corriente eléctrica y accesos reales de una vivienda, **el código fuente
completo no se publica en este repositorio** — ver la nota de seguridad en
el `README.md` raíz.

## Responsabilidades del firmware

- Leer sensores: gas, PIR (movimiento), magnéticos de puerta
- Controlar actuadores: 3 tomas de corriente, lámpara (on/off), ventilador/
  extractor, contactor eléctrico, electroválvula de gas
- Reproducir alertas de voz locales con módulo de audio (DFPlayer + tarjeta SD)
  y mostrar el estado del sistema con LEDs
- Publicar el estado de todos los sensores/actuadores por MQTT
- Suscribirse a comandos de control y al tópico de alertas sísmicas
  (`security/alerts`) para activar protocolos automáticos ante un sismo
  (ej. cortar el gas automáticamente)

## Tópicos MQTT relevantes (mismo broker que el resto del sistema)

| Tópico | Dirección | Propósito |
|---|---|---|
| `security/sensors/all` | ESP32 → app | Estado de todos los sensores |
| `security/status/all` | ESP32 → app | Estado de todos los actuadores |
| `security/alerts` | app → ESP32 | Alertas (incluye las sísmicas) |
| `security/state` | ESP32 ↔ app | Estado general del sistema |
| `security/cmd/*` | app → ESP32 | Comandos de control remoto |

## Pendiente
Ver [`../docs/ROADMAP.md`](../docs/ROADMAP.md).
