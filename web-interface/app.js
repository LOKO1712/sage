// ==================== CONFIGURACIÓN ====================
let config = {
  broker: 'broker.hivemq.com',
  port: 8884,
  clientId: 'AppSeguridad_JDSL_' + Math.random().toString(16).substr(2, 8),
  prefix: 'security'
};

// ==================== ESTADO ====================
let mqttClient = null;
let isConnected = false;

let systemState = {
  state: 'NORMAL',
  armed: true,
  gas: false,
  pir: false,
  door: 'closed',
  seismic: false,
  toma1: 'off',
  toma2: 'off',
  toma3: 'off',
  lampara: 'off',
  contactor: 'on',
  extractor: 'off',
  valvula: 'off'
};

let alerts = [];
let history = [];
let currentAlertFilter = 'all';

// ESP32 heartbeat tracking
let lastEsp32Message = 0;
let esp32Online = false;
const ESP32_TIMEOUT = 10000; // 10 segundos sin mensajes = offline

// ==================== CANVAS BACKGROUND ====================
class ParticleBackground {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.particles = [];
    this.connections = [];
    this.mouse = { x: 0, y: 0 };
    this.resize();
    this.init();
    this.animate();
    window.addEventListener('resize', () => this.resize());
  }

  resize() {
    this.canvas.width = window.innerWidth;
    this.canvas.height = window.innerHeight;
  }

  init() {
    const count = Math.min(50, Math.floor(window.innerWidth / 12));
    for (let i = 0; i < count; i++) {
      this.particles.push({
        x: Math.random() * this.canvas.width,
        y: Math.random() * this.canvas.height,
        vx: (Math.random() - 0.5) * 0.4,
        vy: (Math.random() - 0.5) * 0.4,
        size: Math.random() * 3.5 + 1,
        opacity: Math.random() * 0.7 + 0.2,
        color: this.getRandomColor()
      });
    }
  }

  getRandomColor() {
    const colors = [
      '0, 230, 118',
      '68, 138, 255',
      '179, 136, 255',
      '24, 255, 255'
    ];
    return colors[Math.floor(Math.random() * colors.length)];
  }

  animate() {
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    this.particles.forEach(p => {
      p.x += p.vx;
      p.y += p.vy;

      if (p.x < 0) p.x = this.canvas.width;
      if (p.x > this.canvas.width) p.x = 0;
      if (p.y < 0) p.y = this.canvas.height;
      if (p.y > this.canvas.height) p.y = 0;

      this.ctx.beginPath();
      this.ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
      this.ctx.fillStyle = `rgba(${p.color}, ${p.opacity})`;
      this.ctx.fill();
    });

    for (let i = 0; i < this.particles.length; i++) {
      for (let j = i + 1; j < this.particles.length; j++) {
        const dx = this.particles[i].x - this.particles[j].x;
        const dy = this.particles[i].y - this.particles[j].y;
        const dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < 150) {
          this.ctx.beginPath();
          this.ctx.moveTo(this.particles[i].x, this.particles[i].y);
          this.ctx.lineTo(this.particles[j].x, this.particles[j].y);
          this.ctx.strokeStyle = `rgba(0, 230, 118, ${0.15 * (1 - dist / 150)})`;
          this.ctx.lineWidth = 0.8;
          this.ctx.stroke();
        }
      }
    }

    requestAnimationFrame(() => this.animate());
  }
}

// ==================== MQTT ====================
function connectMQTT() {
  const protocol = config.port == 8884 ? 'wss' : 'ws';
  const url = `${protocol}://${config.broker}:${config.port}/mqtt`;

  mqttClient = mqtt.connect(url, {
    clientId: config.clientId,
    clean: true,
    connectTimeout: 8000,
    reconnectPeriod: 5000
  });

  mqttClient.on('connect', () => {
    isConnected = true;
    updateConnectionStatus(true);
    showToast('Conectado al broker');

    const p = config.prefix;
    mqttClient.subscribe(`${p}/state`);
    mqttClient.subscribe(`${p}/sensors/#`);
    mqttClient.subscribe(`${p}/status/#`);
    mqttClient.subscribe(`${p}/armed`);
    mqttClient.subscribe(`${p}/alerts`);
    mqttClient.subscribe(`${p}/sensors/all`);
    mqttClient.subscribe(`${p}/status/all`);

    requestSensorUpdate();
  });

  mqttClient.on('message', (topic, message) => {
    const msg = message.toString();

    // Cualquier mensaje de sensores/status indica que el ESP32 está vivo
    if (topic.includes('/sensors/') || topic.includes('/status/') || topic.endsWith('/state') || topic.endsWith('/armed')) {
      lastEsp32Message = Date.now();
      esp32Online = true;
    }

    if (topic.endsWith('/state')) {
      systemState.state = msg;
      updateDashboard();
    }
    else if (topic.endsWith('/sensors/gas')) {
      systemState.gas = msg === 'true';
      updateDashboard();
    }
    else if (topic.endsWith('/sensors/pir')) {
      systemState.pir = msg === 'true';
      updateDashboard();
    }
    else if (topic.endsWith('/sensors/door')) {
      systemState.door = msg;
      updateDashboard();
    }
    else if (topic.endsWith('/sensors/seismic')) {
      systemState.seismic = msg === 'true';
      updateDashboard();
    }
    else if (topic.endsWith('/sensors/all')) {
      try {
        const data = JSON.parse(msg);
        systemState.gas = data.gas === true || data.gas === 'true';
        systemState.pir = data.pir === true || data.pir === 'true';
        systemState.door = data.door;
        systemState.seismic = data.seismic === true || data.seismic === 'true';
        systemState.state = data.state;
        systemState.armed = data.armed === true || data.armed === 'true';
        updateDashboard();
        updateControlToggles();
      } catch (e) {}
    }
    else if (topic.endsWith('/armed')) {
      systemState.armed = msg === 'true';
      updateDashboard();
    }
    else if (topic.endsWith('/status/all')) {
      try {
        const data = JSON.parse(msg);
        Object.assign(systemState, data);
        updateControlToggles();
      } catch (e) {}
    }
    else if (topic.endsWith('/status/toma1')) { systemState.toma1 = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/toma2')) { systemState.toma2 = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/toma3')) { systemState.toma3 = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/lampara')) { systemState.lampara = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/contactor')) { systemState.contactor = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/extractor')) { systemState.extractor = msg; updateControlToggles(); }
    else if (topic.endsWith('/status/valvula')) { systemState.valvula = msg; updateControlToggles(); }
    else if (topic.endsWith('/alerts')) {
      try {
        const data = JSON.parse(msg);
        addAlert(data);
      } catch (e) {}
    }
  });

  mqttClient.on('error', () => {
    isConnected = false;
    updateConnectionStatus(false);
  });

  mqttClient.on('close', () => {
    isConnected = false;
    updateConnectionStatus(false);
  });

  mqttClient.on('reconnect', () => {
    isConnected = true;
    updateConnectionStatus(true);
  });
}

function publish(topic, message) {
  if (mqttClient && isConnected) {
    mqttClient.publish(topic, message);
  }
}

function requestSensorUpdate() {
  publish(`${config.prefix}/cmd/status`, 'request');
}

// ==================== CONTROLES ====================
function toggleOutput(output) {
  const toggleMap = {
    toma1: 'toggleToma1',
    toma2: 'toggleToma2',
    toma3: 'toggleToma3',
    lampara: 'toggleLamp',
    extractor: 'toggleExtractor',
    valvula: 'toggleValvula',
    contactor: 'toggleContactor'
  };

  const checkbox = document.getElementById(toggleMap[output]);
  const isOn = checkbox.checked;

  publish(`${config.prefix}/cmd/${output}`, isOn ? 'on' : 'off');

  const statusMap = {
    toma1: 'toma1Status',
    toma2: 'toma2Status',
    toma3: 'toma3Status',
    lampara: 'lampStatus',
    extractor: 'extractorStatus',
    valvula: 'valvulaStatus',
    contactor: 'contactorStatus'
  };

  const labelMap = {
    toma1: 'Toma 1',
    toma2: 'Toma 2',
    toma3: 'Toma 3',
    lampara: 'Lampara',
    extractor: 'Ventilador',
    valvula: 'Electrovalvula',
    contactor: 'Contactor'
  };

  const statusLabels = {
    toma1: ['Apagado', 'Encendido'],
    toma2: ['Apagado', 'Encendido'],
    toma3: ['Apagado', 'Encendido'],
    lampara: ['Apagado', 'Encendido'],
    extractor: ['Apagado', 'Encendido'],
    valvula: ['Cerrada', 'Abierta'],
    contactor: ['Cortado', 'Encendido']
  };

  const labels = statusLabels[output] || ['Off', 'On'];
  document.getElementById(statusMap[output]).textContent = isOn ? labels[1] : labels[0];
  systemState[output] = isOn ? 'on' : 'off';

  addHistoryItem(
    isOn ? 'sistema' : 'info',
    `${labelMap[output]} ${isOn ? 'encendido' : 'apagado'}`,
    `Control manual desde la app`
  );
}

function toggleAlarm() {
  const isActive = systemState.state === 'MANUAL_ALARM';
  publish(`${config.prefix}/cmd/alarm`, isActive ? 'off' : 'on');
}

function toggleArm() {
  if (systemState.armed) {
    publish(`${config.prefix}/cmd/disarm`, 'disarm');
  } else {
    publish(`${config.prefix}/cmd/arm`, 'arm');
  }
}

// ==================== UI UPDATE ====================
function updateDashboard() {
  const card = document.getElementById('systemStatusCard');
  const title = document.getElementById('systemTitle');
  const subtitle = document.getElementById('systemSubtitle');

  card.classList.remove('alarm', 'warning', 'seismic', 'disarmed');

  const alertStates = ['GAS_DETECTED', 'INTRUSO_ALERTA', 'MANUAL_ALARM', 'SEISMIC_ALERT', 'TEST_AUDIO', 'TEST_ALARM'];
  const isAlert = alertStates.includes(systemState.state);
  const isDisarmed = !systemState.armed && !isAlert;

  document.body.classList.toggle('alert-mode', isAlert);
  document.body.classList.toggle('disarmed-mode', isDisarmed);

  if (isDisarmed) card.classList.add('disarmed');

  const shieldSvg = document.getElementById('shieldIcon').querySelector('svg');
  const checkPath = shieldSvg.querySelector('#shieldCheck');
  if (isDisarmed) {
    checkPath.setAttribute('d', 'M18 6L6 18M6 6l12 12');
    checkPath.style.animation = 'xAppear 0.6s ease-out';
  } else {
    checkPath.setAttribute('d', 'M9 12l2 2 4-4');
    checkPath.style.animation = '';
  }

  switch (systemState.state) {
    case 'NORMAL':
      title.textContent = systemState.armed ? 'SISTEMA ARMADO' : 'SISTEMA DESARMADO';
      subtitle.textContent = 'Todo en orden';
      break;
    case 'GAS_DETECTED':
      card.classList.add('alarm');
      title.textContent = 'ALERTA DE GAS';
      subtitle.textContent = 'Evacue el domicilio';
      break;
    case 'MOVIMIENTO_DETECTADO':
      card.classList.add('warning');
      title.textContent = 'MODO GUARDIA';
      subtitle.textContent = 'Monitoreando...';
      break;
    case 'INTRUSO_ALERTA':
      card.classList.add('alarm');
      title.textContent = 'INTRUSO DETECTADO';
      subtitle.textContent = 'Verifique inmediatamente';
      break;
    case 'MANUAL_ALARM':
      card.classList.add('alarm');
      title.textContent = 'ALARMA ACTIVA';
      subtitle.textContent = 'Alarma manual activada';
      break;
    case 'SEISMIC_ALERT':
      card.classList.add('seismic');
      title.textContent = 'ALERTA SISMICA';
      subtitle.textContent = 'Onda P detectada - Protejase';
      break;
    case 'TEST_AUDIO':
    case 'TEST_ALARM':
      card.classList.add('warning');
      title.textContent = 'MODO PRUEBA';
      subtitle.textContent = 'Probando sistema...';
      break;
  }

  document.getElementById('sensorPir').textContent = systemState.pir ? 'Detectado' : 'No detectado';
  document.getElementById('sensorDoor').textContent = systemState.door === 'open' ? 'Abierta' : 'Cerrada';
  document.getElementById('sensorGas').textContent = systemState.gas ? 'FUGA DETECTADA' : 'Normal';
  document.getElementById('sensorSeismic').textContent = systemState.seismic ? 'ALERTA SISMICA' : '4 fuentes activas';

  document.getElementById('sensor-movement').classList.toggle('alert', systemState.pir);
  document.getElementById('sensor-gas').classList.toggle('alert', systemState.gas);
  document.getElementById('sensor-seismic').classList.toggle('alert', systemState.seismic);

  const armBtn = document.getElementById('btnDisarm');
  document.getElementById('armBtnText').textContent = systemState.armed ? 'Desarmar' : 'Armar';
  armBtn.classList.toggle('active', systemState.armed);
  document.getElementById('btnSOS').classList.toggle('active', systemState.state === 'MANUAL_ALARM');

  document.getElementById('detailPirStatus').textContent = systemState.pir ? 'Movimiento detectado' : 'No detectado';
  document.getElementById('detailDoorStatus').textContent = systemState.door === 'open' ? 'Abierta' : 'Cerrada';
  document.getElementById('detailGasStatus').textContent = systemState.gas ? 'FUGA DETECTADA' : 'Normal';
  document.getElementById('detailSeismicStatus').textContent = systemState.seismic ? 'ALERTA SISMICA' : 'GeoShake, Google, Sismo Detector, RaspberryShake';
  document.getElementById('detailSystemStatus').textContent = getStateLabel(systemState.state);

  document.getElementById('detailPirBadge').className = 'sensor-detail-status ' + (systemState.pir ? 'alert' : 'offline');
  document.getElementById('detailDoorBadge').className = 'sensor-detail-status ' + (systemState.door === 'open' ? 'alert' : 'offline');
  document.getElementById('detailGasBadge').className = 'sensor-detail-status ' + (systemState.gas ? 'alert' : 'offline');
  document.getElementById('detailSeismicBadge').className = 'sensor-detail-status ' + (systemState.seismic ? 'alert' : 'online');
}

function updateControlToggles() {
  document.getElementById('toggleToma1').checked = systemState.toma1 === 'on';
  document.getElementById('toggleToma2').checked = systemState.toma2 === 'on';
  document.getElementById('toggleToma3').checked = systemState.toma3 === 'on';
  document.getElementById('toggleLamp').checked = systemState.lampara === 'on';
  document.getElementById('toggleExtractor').checked = systemState.extractor === 'on';
  document.getElementById('toggleValvula').checked = systemState.valvula === 'on';
  document.getElementById('toggleContactor').checked = systemState.contactor === 'on';

  document.getElementById('toma1Status').textContent = systemState.toma1 === 'on' ? 'Encendido' : 'Apagado';
  document.getElementById('toma2Status').textContent = systemState.toma2 === 'on' ? 'Encendido' : 'Apagado';
  document.getElementById('toma3Status').textContent = systemState.toma3 === 'on' ? 'Encendido' : 'Apagado';
  document.getElementById('lampStatus').textContent = systemState.lampara === 'on' ? 'Encendido' : 'Apagado';
  document.getElementById('extractorStatus').textContent = systemState.extractor === 'on' ? 'Encendido' : 'Apagado';
  document.getElementById('valvulaStatus').textContent = systemState.valvula === 'on' ? 'Abierta' : 'Cerrada';
  document.getElementById('contactorStatus').textContent = systemState.contactor === 'on' ? 'Encendido' : 'Cortado';
}

function updateConnectionStatus(connected) {
  const el = document.getElementById('connectionStatus');
  el.innerHTML = connected
    ? '<span class="status-dot online"></span> Conectado'
    : '<span class="status-dot offline"></span> Desconectado';
}

function getStateLabel(state) {
  const labels = {
    'NORMAL': 'Normal',
    'GAS_DETECTED': 'Fuga de gas',
    'MOVIMIENTO_DETECTADO': 'Modo guardia',
    'INTRUSO_ALERTA': 'Intruso detectado',
    'MANUAL_ALARM': 'Alarma manual',
    'SEISMIC_ALERT': 'Alerta sismica',
    'TEST_AUDIO': 'Test de audio',
    'TEST_ALARM': 'Test de alarma'
  };
  return labels[state] || state;
}

// ==================== ALERTAS ====================
function addAlert(data) {
  const alert = {
    type: data.type || 'sistema',
    message: data.message || 'Alerta',
    time: new Date(),
    category: getAlertCategory(data.type)
  };

  alerts.unshift(alert);
  if (alerts.length > 50) alerts.pop();

  renderAlerts();

  if (alert.category === 'gas' || data.type === 'INTRUSO' || alert.category === 'sismo') {
    showToast(data.message);
  }

  addHistoryItem(alert.category, data.type, data.message);
}

function getAlertCategory(type) {
  if (type && type.includes('GAS')) return 'gas';
  if (type && type.includes('SISMO')) return 'sismo';
  if (type && (type.includes('INTRUSO') || type.includes('MOVIMIENTO') || type.includes('PUERTA'))) return 'seguridad';
  return 'sistema';
}

function renderAlerts() {
  const list = document.getElementById('alertsList');
  const filtered = currentAlertFilter === 'all'
    ? alerts
    : alerts.filter(a => a.category === currentAlertFilter);

  if (filtered.length === 0) {
    list.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.2">
            <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/>
            <path d="M13.73 21a2 2 0 01-3.46 0"/>
          </svg>
        </div>
        <p>No hay alertas</p>
      </div>`;
    return;
  }

  const iconMap = {
    'GAS': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22c-4.97 0-9-3.58-9-8 0-3.19 2.13-6.04 4-8.05C8.46 4.44 10.19 2 12 2c1.81 0 3.54 2.44 5 3.95 1.87 2.01 4 4.86 4 8.05 0 4.42-4.03 8-9 8z"/></svg>',
    'GAS_PERSISTENTE': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 9v4M12 17h.01"/><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/></svg>',
    'GAS_NORMAL': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 11-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
    'INTRUSO': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/><line x1="12" y1="2" x2="12" y2="4"/><line x1="12" y1="20" x2="12" y2="22"/><line x1="4" y1="4" x2="5.5" y2="5.5"/><line x1="18.5" y1="18.5" x2="20" y2="20"/><line x1="2" y1="12" x2="4" y2="12"/><line x1="20" y1="12" x2="22" y2="12"/></svg>',
    'MOVIMIENTO': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="2"/><path d="M8.5 8.5a5 5 0 000 7M15.5 8.5a5 5 0 010 7"/><path d="M5.5 5.5a9 9 0 000 13M18.5 5.5a9 9 0 010 13"/></svg>',
    'PUERTA': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 21V4a1 1 0 011-1h10a1 1 0 011 1v17"/><path d="M3 21h18"/><circle cx="14" cy="12" r="1.5"/></svg>',
    'SISMO': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12h2l2-4 3 8 2-6 2 4h2l2-3 2 2h3"/></svg>',
    'SISMO_P': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
    'ALARMA': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/></svg>',
    'SISTEMA': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>'
  };

  const defaultIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/></svg>';

  list.innerHTML = filtered.map(a => `
    <div class="alert-item ${a.category}">
      <div class="alert-icon">${iconMap[a.type] || defaultIcon}</div>
      <div class="alert-content">
        <h4>${a.type.replace(/_/g, ' ')}</h4>
        <p>${a.message}</p>
      </div>
      <span class="alert-time">${formatTime(a.time)}</span>
    </div>
  `).join('');
}

function toggleAlertFilter() {
  const filters = document.getElementById('alertFilters');
  filters.style.display = filters.style.display === 'none' ? 'flex' : 'none';
}

// ==================== HISTORIAL ====================
function addHistoryItem(category, title, description) {
  history.unshift({
    category,
    title: title || '',
    description: description || '',
    time: new Date()
  });

  if (history.length > 100) history.pop();
  renderHistory();
}

function renderHistory() {
  const timeline = document.getElementById('historyTimeline');

  if (history.length === 0) {
    timeline.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.2">
            <circle cx="12" cy="12" r="10"/>
            <polyline points="12,6 12,12 16,14"/>
          </svg>
        </div>
        <p>Sin eventos registrados</p>
      </div>`;
    return;
  }

  timeline.innerHTML = history.map(h => `
    <div class="history-item">
      <div class="history-time">
        <div class="time">${formatTime(h.time)}</div>
      </div>
      <div class="history-dot ${h.category}"></div>
      <div class="history-content">
        <h4>${h.title}</h4>
        <p>${h.description}</p>
      </div>
    </div>
  `).join('');
}

function clearHistory() {
  history = [];
  renderHistory();
  showToast('Historial limpiado');
}

// ==================== NAVEGACIÓN ====================
function showScreen(screenName) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  document.getElementById(`screen-${screenName}`).classList.add('active');

  document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
  const navBtn = document.querySelector(`.nav-btn[data-screen="${screenName}"]`);
  if (navBtn) {
    navBtn.classList.add('active');
    moveNavIndicator(navBtn);
  }

  if (screenName === 'alerts') renderAlerts();
  if (screenName === 'history') renderHistory();
}

function moveNavIndicator(btn) {
  const indicator = document.getElementById('navIndicator');
  const nav = document.querySelector('.bottom-nav');
  const navRect = nav.getBoundingClientRect();
  const btnRect = btn.getBoundingClientRect();
  const offset = btnRect.left - navRect.left + (btnRect.width / 2) - (navRect.width / 10);
  indicator.style.transform = `translateX(${offset}px)`;
}

// ==================== UTILIDADES ====================
function formatTime(date) {
  const h = date.getHours().toString().padStart(2, '0');
  const m = date.getMinutes().toString().padStart(2, '0');
  return `${h}:${m}`;
}

function showToast(text) {
  const toast = document.getElementById('toast');
  document.getElementById('toastText').textContent = text;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 3000);
}

let modalCallback = null;

function showModal(title, message, onConfirm) {
  modalCallback = onConfirm;
  document.getElementById('modalTitle').textContent = title;
  document.getElementById('modalMessage').textContent = message;
  document.getElementById('modalOverlay').classList.add('show');
}

function hideModal() {
  document.getElementById('modalOverlay').classList.remove('show');
  modalCallback = null;
}

function confirmModal() {
  if (modalCallback) modalCallback();
  hideModal();
}

// ==================== SETTINGS ====================
function saveSettings() {
  config.broker = document.getElementById('settingBroker').value;
  config.port = parseInt(document.getElementById('settingPort').value);
  config.clientId = document.getElementById('settingClientId').value;
  config.prefix = document.getElementById('settingPrefix').value;

  localStorage.setItem('mqttConfig', JSON.stringify(config));

  if (mqttClient) mqttClient.end();
  connectMQTT();
  showToast('Configuracion guardada');
}

function loadSettings() {
  const saved = localStorage.getItem('mqttConfig');
  if (saved) {
    try {
      config = JSON.parse(saved);
      document.getElementById('settingBroker').value = config.broker;
      document.getElementById('settingPort').value = config.port;
      document.getElementById('settingClientId').value = config.clientId;
      document.getElementById('settingPrefix').value = config.prefix;
    } catch (e) {}
  }
}

// ==================== INIT ====================
document.addEventListener('DOMContentLoaded', () => {
  const canvas = document.getElementById('bgCanvas');
  if (canvas) new ParticleBackground(canvas);

  loadSettings();
  connectMQTT();
  updateDashboard();
  updateControlToggles();
  updateEsp32Badge();

  setTimeout(() => moveNavIndicator(document.querySelector('.nav-btn.active')), 100);

  document.querySelectorAll('.filter-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.filter-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      currentAlertFilter = chip.dataset.filter;
      renderAlerts();
    });
  });
});

// ==================== CARD EXPANDIBLE ====================
function toggleCardDetails(type) {
  const card = document.getElementById('detail-' + type);
  if (!card) return;

  const wasOpen = card.classList.contains('open');

  document.querySelectorAll('.sensor-detail-card.expandable').forEach(c => {
    c.classList.remove('open');
  });

  if (!wasOpen) {
    card.classList.add('open');
    refreshCardDetails(type);
  }
}

function refreshCardDetails(type) {
  if (type === 'pir') updatePirDetails();
  else if (type === 'door') updateDoorDetails();
  else if (type === 'gas') updateGasDetails();
  else if (type === 'seismic') updateSeismicDetails();
  else if (type === 'system') updateSystemDetails();
}

// ==================== CONTADORES LOCALSTORAGE ====================
function getTodayKey() {
  return new Date().toISOString().split('T')[0];
}

function getCounter(name) {
  const today = getTodayKey();
  const data = JSON.parse(localStorage.getItem('counters_' + name) || '{}');
  if (data.date !== today) return 0;
  return data.count || 0;
}

function incrementCounter(name) {
  const today = getTodayKey();
  const data = JSON.parse(localStorage.getItem('counters_' + name) || '{}');
  if (data.date !== today) {
    localStorage.setItem('counters_' + name, JSON.stringify({ date: today, count: 1 }));
  } else {
    localStorage.setItem('counters_' + name, JSON.stringify({ date: today, count: (data.count || 0) + 1 }));
  }
}

function getLastEvent(name) {
  return localStorage.getItem('lastEvent_' + name) || 'Nunca';
}

function setLastEvent(name) {
  const now = new Date();
  const time = now.toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' });
  const date = now.toLocaleDateString('es', { day: '2-digit', month: '2-digit' });
  localStorage.setItem('lastEvent_' + name, date + ' ' + time);
}

function resetCounters() {
  showModal(
    '¿Reiniciar contadores?',
    'Se borrarán aperturas, detecciones y alertas de gas.',
    () => {
      ['pir', 'door', 'gas', 'seismic'].forEach(name => {
        localStorage.removeItem('counters_' + name);
        localStorage.removeItem('lastEvent_' + name);
      });

      document.querySelectorAll('.sensor-detail-card.expandable.open').forEach(card => {
        const type = card.id.replace('detail-', '');
        refreshCardDetails(type);
      });

      showToast('Contadores reiniciados');
    }
  );
}

// ==================== ACTUALIZAR DETALLES ====================
function updatePirDetails() {
  document.getElementById('pirLastDetection').textContent = getLastEvent('pir');
  document.getElementById('pirCountToday').textContent = getCounter('pir');
  document.getElementById('pirCooldown').textContent = systemState.pir ? 'Activo' : 'Inactivo';
}

function updateDoorDetails() {
  document.getElementById('doorLastOpen').textContent = getLastEvent('door');
  document.getElementById('doorCountToday').textContent = getCounter('door');
  document.getElementById('doorCurrentState').textContent = systemState.door === 'open' ? 'Abierta' : 'Cerrada';
}

function updateGasDetails() {
  document.getElementById('gasLastAlert').textContent = getLastEvent('gas');
  document.getElementById('gasCountToday').textContent = getCounter('gas');
  document.getElementById('gasCurrentState').textContent = systemState.gas ? 'Fuga detectada' : 'Normal';
}

function updateSeismicDetails() {
  document.getElementById('seismicLastEvent').textContent = getLastEvent('seismic');
}

function updateSystemDetails() {
  const estados = {
    'NORMAL': 'Normal',
    'GAS_DETECTED': 'Alerta de Gas',
    'MANUAL_ALARM': 'Alarma Manual',
    'INTRUSO_ALERTA': 'Intruso',
    'MOVIMIENTO_DETECTADO': 'Guardia',
    'SEISMIC_ALERT': 'Alerta Sísmica',
    'TEST_AUDIO': 'Prueba Audio',
    'TEST_ALARM': 'Prueba Alarma'
  };
  document.getElementById('systemCurrentState').textContent = estados[systemState.state] || systemState.state;
  document.getElementById('systemArmed').textContent = systemState.armed ? 'Sí' : 'No';
  document.getElementById('systemMqtt').textContent = isConnected ? 'Conectado' : 'Desconectado';

  const esp32El = document.getElementById('systemEsp32');
  if (esp32El) {
    esp32El.textContent = esp32Online ? 'En línea' : 'Desconectado';
    esp32El.style.color = esp32Online ? 'var(--accent-green)' : 'var(--accent-red)';
  }

  // Actualizar badge en header
  updateEsp32Badge();
}

function updateEsp32Badge() {
  const badge = document.getElementById('esp32Badge');
  const text = document.getElementById('esp32Text');
  if (!badge || !text) return;

  if (esp32Online) {
    badge.classList.remove('offline');
    badge.classList.add('online');
    text.textContent = 'ESP32 En línea';
  } else {
    badge.classList.remove('online');
    badge.classList.add('offline');
    text.textContent = 'ESP32 Desconectado';
  }
}

// ==================== RASTREO DE EVENTOS ====================
let prevPir = false;
let prevDoor = 'closed';
let prevGas = false;

// Verificar ESP32 online/offline cada 3 segundos
setInterval(() => {
  const wasOnline = esp32Online;
  if (lastEsp32Message > 0) {
    esp32Online = (Date.now() - lastEsp32Message) < ESP32_TIMEOUT;
  }

  // Siempre actualizar badge del header
  updateEsp32Badge();

  // Si cambió el estado, toast + actualizar card si está abierta
  if (wasOnline !== esp32Online) {
    const systemCard = document.getElementById('detail-system');
    if (systemCard && systemCard.classList.contains('open')) {
      updateSystemDetails();
    }
    showToast(esp32Online ? 'ESP32 en línea' : 'ESP32 desconectado');
  }

  // Actualizar card sistema si está abierta
  const systemCard = document.getElementById('detail-system');
  if (systemCard && systemCard.classList.contains('open')) {
    updateSystemDetails();
  }
}, 3000);

// Contadores de eventos
setInterval(() => {
  if (systemState.pir && !prevPir) {
    incrementCounter('pir');
    setLastEvent('pir');
  }
  prevPir = systemState.pir;

  if (systemState.door === 'open' && prevDoor === 'closed') {
    incrementCounter('door');
    setLastEvent('door');
  }
  prevDoor = systemState.door;

  if (systemState.gas && !prevGas) {
    incrementCounter('gas');
    setLastEvent('gas');
  }
  prevGas = systemState.gas;
}, 1000);

// Detectar alertas sísmicas del historial
const origAddHistory = addHistoryItem;
addHistoryItem = function(category, title, description) {
  if (category === 'sismo' || (title && title.includes('SISMO'))) {
    setLastEvent('seismic');
  }
  origAddHistory(category, title, description);
};
