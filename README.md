<p align="center">
  <img src="assets/banner.png" alt="SAGE — Smart Alert & Guard Ecosystem" width="100%">
</p>

# SAGE — Smart Alert & Guard Ecosystem

Plataforma de seguridad inteligente para el hogar, basada en **ESP32**, con monitoreo en tiempo real, control remoto de dispositivos, y un módulo de **alerta temprana sísmica** construido combinando múltiples fuentes de detección crowdsourced.

**Autor:** Juan Diego Silva
**Estado:** 🚧 En desarrollo activo

> Este proyecto se conoció durante sus primeras etapas de desarrollo como
> "Hogar Seguro" — el nombre evolucionó a **SAGE** pensando en su
> potencial como marca modular (SAGE Gas, SAGE Energy, SAGE Home, etc.).

---

## 📖 Descripción

SAGE nace como proyecto personal luego de avances con TelegramBotMaster para ESP32, evolucionando hacia una plataforma de seguridad doméstica completa e IoT. Integra:

- **Microcontrolador ESP32** — cerebro del sistema físico (sensores, actuadores)
- **Protocolo MQTT** — comunicación en tiempo real entre todos los componentes
- **App Android nativa** — interfaz de control + puente de alertas sísmicas
- **Interfaz web** — panel de control visual, empaquetado también dentro de la app

Uno de los retos más interesantes del proyecto fue diseñar la **alerta temprana de sismos**: ver [`docs/INVESTIGACION.md`](docs/INVESTIGACION.md) para el proceso completo de investigación de fuentes de datos sísmicos en tiempo real (spoiler: la mayoría de fuentes "públicas" resultaron no ser viables, y la solución final combina varias fuentes crowdsourced de forma redundante).

## 🏗️ Arquitectura

Ver [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) para el diagrama completo y la explicación de cada componente.

En resumen:

```
[Sensores físicos] ──┐
                      ├──► ESP32 ──► MQTT (broker.hivemq.com) ◄──► App Android / Web
[Actuadores]  ────────┘                    ▲
                                            │
        Apps de terceros (Sismo Detector, Google,
        GeoShake) ──► NotificationListenerService
        ──► puente MQTT
```

## 📡 Módulo de alerta sísmica

Este es el componente más investigado del proyecto. En vez de depender de una sola fuente (que resultó no existir de forma pública y en tiempo real para Colombia), el sistema combina **tres fuentes redundantes**, todas capturadas mediante interceptación de notificaciones en Android:

| Fuente | Rol |
|---|---|
| [Sismo Detector](https://play.google.com/store/apps/details?id=com.finazzi.distquake) (Earthquake Network) | Detección temprana crowdsourced |
| Android Earthquake Alerts (Google) | Confirmación de movimiento inminente/real |
| [GeoShake](https://geoshake.org) | Red abierta de sensores ESP32 + API pública (MQTT/SSE) |

Ver [`docs/CREDITOS.md`](docs/CREDITOS.md) para el detalle de cada proyecto de terceros utilizado.

## 📂 Estructura del repositorio

```
sage/
├── assets/                → banner e imágenes de marca
├── app-android/           → App Android (Kotlin): UI + puente de notificaciones sísmicas
├── web-interface/         → Interfaz web (HTML/CSS/JS), empaquetada dentro de la app
├── firmware-esp32/        → Firmware del ESP32 (parcial - ver nota de seguridad abajo)
└── docs/
    ├── ARQUITECTURA.md
    ├── INVESTIGACION.md   → El proceso completo de búsqueda de fuentes sísmicas
    ├── ROADMAP.md
    ├── CREDITOS.md
    └── investigacion-scripts/  → Scripts Python usados durante la investigación
```

## ⚠️ Nota sobre el firmware del ESP32

Por tratarse de un sistema de seguridad física real (control de gas, corriente eléctrica, cerraduras), el firmware completo del ESP32 **no se publica en su totalidad** — se documenta la arquitectura y el enfoque general, pero se omiten detalles específicos de pines, lógica de armado/desarmado y credenciales. Ver [`docs/ROADMAP.md`](docs/ROADMAP.md) para el estado actual.

## 📜 Licencia

Ver [`LICENSE`](LICENSE) — todos los derechos reservados, proyecto compartido con fines de portafolio.
