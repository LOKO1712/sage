# Roadmap

## ✅ Completado
- [x] Investigación exhaustiva de fuentes de datos sísmicos (ver `INVESTIGACION.md`)
- [x] Interfaz web funcional (HTML/CSS/JS + MQTT vía WebSocket)
- [x] App Android: WebView cargando la interfaz empaquetada localmente
- [x] `NotificationListenerService` capturando alertas de Sismo Detector
- [x] Filtro por palabra clave para detectar fuentes desconocidas automáticamente
- [x] Publicación MQTT hacia `broker.hivemq.com` (tópico `security/alerts`)
- [x] Migración a `MqttAndroidClient` (asíncrono) + reconexión automática
- [x] Flujo de onboarding: permisos de notificaciones, batería sin restricciones, autoinicio (Xiaomi/MIUI)
- [x] Confirmación visual (Toast + notificación local) de cada alerta capturada

## 🚧 En progreso / pendiente de validar
- [ ] Confirmar que el Foreground Service se mantiene activo indefinidamente (notificación fija "Puente Sismico activo" no aparece aún — pendiente de diagnóstico, probablemente falta declarar `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` en el manifest para Android 14+)
- [ ] Confirmar comportamiento en HyperOS (no MIUI clásico) para el permiso de Autoinicio
- [ ] Validar persistencia con pantalla apagada por periodos largos (30+ min)
- [ ] Confirmar el paquete exacto de Android Earthquake Alerts (candidato actual: `com.google.android.apps.safetyhub`, sin confirmar con una alerta real)

## 📋 Por hacer
- [ ] Integrar GeoShake como cuarta fuente (feed MQTT `geoshake/events` o SSE `/api/live`)
- [ ] Evaluar construir un nodo GeoShake propio (ESP32-S3 + LSM6DSO) para detección local genuina
- [ ] Completar el firmware del ESP32 (sensores + actuadores + cliente MQTT)
- [ ] Migrar de broker público a broker privado con autenticación para producción
- [ ] Pulir la interfaz visual (revisar `-webkit-tap-highlight-color` y otros detalles de "sensación nativa")
- [ ] Evaluar migración de WebView a UI 100% nativa (Jetpack Compose) — decisión pendiente, no urgente
