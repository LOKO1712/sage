# Arquitectura del sistema

## Diagrama general

```mermaid
graph TD
    subgraph Fisico["Capa física (ESP32)"]
        S1[Sensor de gas]
        S2[Sensor PIR]
        S3[Sensores magnéticos de puerta]
        A1[3 tomas de corriente]
        A2[Lámpara on/off]
        A3[Ventilador/extractor]
        A4[Contactor eléctrico]
        A5[Electroválvula de gas]
        ESP[ESP32]
        S1 & S2 & S3 --> ESP
        ESP --> A1 & A2 & A3 & A4 & A5
    end

    subgraph Broker["Comunicación"]
        MQTT[broker.hivemq.com<br/>tópicos bajo 'security/']
    end

    subgraph Sismico["Módulo sísmico (fuentes redundantes)"]
        SD[Sismo Detector<br/>com.finazzi.distquake]
        GG[Android Earthquake Alerts<br/>Google]
        GS[GeoShake<br/>feed MQTT geoshake/events]
        RS[RaspberryShake S99D0<br/>sismograma embebido]
        NLS[NotificationListenerService]
        SD --> NLS
        GG --> NLS
        NLS --> MQTT
        GS -->|MQTT directo (integrado)| MQTT
        RS -.->|solo visualización| WV
    end

    subgraph App["App Android"]
        WV[WebView<br/>interfaz local en assets/]
        SVC[Servicio nativo AlertasMqttService<br/>MQTT de bajo nivel + notificaciones]
        NLS
        WV <--> MQTT
        SVC <--> MQTT
    end

    ESP <--> MQTT
```

## Componentes

### 1. ESP32 (capa física)
Controla los sensores y actuadores del sistema. Se comunica exclusivamente vía MQTT.
*(Ver nota de seguridad en el README raíz — detalles de pines y lógica de armado no publicados.)*

### 2. Broker MQTT
Se usa el broker público `broker.hivemq.com` como punto de encuentro entre todos los componentes, bajo el prefijo de tópicos `security/` (ej. `security/alerts`, `security/sensors/seismic`, `security/state`).

> Nota de diseño: usar un broker público simplifica el desarrollo, pero para producción se recomienda migrar a un broker privado con autenticación.

### 3. App Android
Cumple tres roles en un solo APK:
- **Interfaz visual**: un `WebView` que carga la interfaz web empaquetada localmente (no depende de internet para renderizar la UI, solo para la conexión MQTT en sí).
- **Puente de alertas sísmicas**: un `NotificationListenerService` en segundo plano que intercepta notificaciones de apps de terceros relacionadas con sismos, las interpreta, y las republica en el tópico `security/alerts`.
- **Servicio nativo de alertas** (`AlertasMqttService`): cliente MQTT de bajo nivel (Paho) que mantiene la suscripción a `security/alerts` de forma independiente de la UI y emite **notificaciones nativas** (`NotificadorAlertas`) aunque la app esté cerrada o la pantalla apagada.

### 4. Módulo sísmico
Ver [`INVESTIGACION.md`](INVESTIGACION.md) para el porqué de este diseño. En resumen: no existe una fuente pública de datos sísmicos crudos en tiempo real para Colombia, así que el sistema combina **cuatro fuentes redundantes**: Sismo Detector y Android Earthquake Alerts (capturadas por notificaciones), GeoShake (feed MQTT directo, `geoshake/events`) y la estación RaspberryShake S99D0 (sismograma embebido para visualización en el panel web).

### 5. Interfaz web
HTML/CSS/JS puro (sin framework), conectado directamente al broker MQTT vía WebSocket (`mqtt.js`). Se desarrolla y prueba de forma independiente (hosteada en Netlify) y luego se empaqueta dentro de la app Android.
