# Arquitectura

## Principio: un solo cerebro
Todo (hardware, lógica, procesado) vive en el Android. El resto de dispositivos son "pantallas tontas" que abren una web. Así no hay que escribir y mantener dos apps (Android + iOS), y funciona en cualquier dispositivo con navegador.

## Módulos Gradle
| Módulo | Qué es | Depende de |
|---|---|---|
| `imaging/` | Motor de apilado (EAA) en Kotlin puro | — |
| `core/` | Protocolo NexStar, driver, cola de comandos, watchdog, cerebro (puntería, GoTo, seguimiento), holgura, catálogo, sesión de imagen y `SessionHub` (mensajes JSON, independiente del transporte) | `imaging` |
| `core/src/testFixtures` | Simuladores (mando + motores a nivel de bytes, giroscopio, cámara). Solo para tests y `devserver`: **no van en el APK** | `core` |
| `server/` | Rutas HTTP + WebSocket (Ktor) **compartidas** por la app y el `devserver` | `core` |
| `app/` | Android: servicio, USB, Camera2, sensores, pantalla del QR | `core`, `server` |
| `devserver/` | El núcleo real contra los simuladores, sirviendo la web desde disco (http://localhost:8099) | `server`, simuladores |

## Componentes en el Android

### 1. `MountDriver` (núcleo)
Interfaz que abstrae el telescopio (`core/mount/MountDriver.kt`):
- `connect()`, `disconnect()`, `isAligned()`
- `getAltAz()` (encoders en bruto si el mando no está alineado), `getRaDec()`
- `gotoAltAz()` (`b`), `gotoRaDec()` (`r`), `isGotoInProgress()`, `cancelGoto()`
- `slew(eje, sentido, velocidad 1–9)`, `slewVariable()` (seguimiento), `stop()`, `emergencyStop()`
- tracking, lugar y hora del mando, sync
- pass-through a los motores: anti-backlash nativo, posición del motor
- `trace()`: últimos intercambios con el mando, para el diagnóstico

Implementación: `NexStarDriver` sobre un `SerialTransport` (`UsbSerialTransport` en Android, el simulador en los tests).

Todas las llamadas serie pasan por **una única cola** (`CommandQueue`: un comando a la vez, timeout **3,5 s** según Celestron). El protocolo no admite comandos solapados. Los STOP **se cuelan al principio de la cola** y anulan los movimientos que estaban esperando detrás.

Al conectar, el driver lee `V` (versión del mando) y `m` (modelo) y activa solo las funciones soportadas (ver `PROTOCOLO_NEXSTAR.md`).

### 2. El cerebro (`TelescopeBrain`)
Lugar y hora (GPS), modelo de puntería propio (1–N estrellas, base inclinada, sensores del Android), GoTo en dos tramos que siempre llega en el sentido del seguimiento, seguimiento por velocidad variable y holgura compensada. Detalle: `PLAN_CEREBRO.md`.

**Cómo se mueven los motores en un GoTo** (Más → GoTo):
- **Auto** (por defecto): el tramo largo con `b` (comprobando en 3 s que se mueve; si el mando no lo ejecuta, StarBridge hace los GoTos **por software**) y la llegada final siempre por software, para dejar la holgura recogida del lado del seguimiento.
- **El mando**: siempre `b`.
- **StarBridge (software)**: bucle cerrado con las mismas órdenes que las flechas (`P 2`, velocidades 9→4 según la distancia) leyendo los encoders (`z`) hasta llegar (< 0,03°). Aprende si un motor está cableado al revés y se rinde si un encoder no se mueve. Siempre deja los ejes parados.

### 3. Servicio en primer plano
- `ForegroundService` con notificación persistente, wake lock parcial y WiFi lock.
- Gestiona el permiso USB y reconecta si se suelta el cable o el mando no contesta.
- Vigila la red: si el Android se une a la WiFi más tarde o cambia de red, el QR se actualiza solo.
- Al cerrarse, para la montura **antes** que nada (hasta 1,5 s) y libera el puerto 8080; lo demás (cámara, guardar) se cierra en segundo plano para no congelar la pantalla.
- EMUI (Huawei) mata apps en segundo plano: la pantalla principal pide la exención de batería mientras falte.
- **Pantalla negra** (`BlackScreenActivity`): pantalla encendida pero negra a brillo mínimo (en OLED no da luz), sin barras del sistema; doble toque para salir. Para móviles que fallan con la pantalla apagada. El diagnóstico apunta cuándo se apaga/enciende la pantalla y cuándo se pierde la WiFi.

### 4. Servidor web (`:server`)
Ktor CIO en `0.0.0.0:8080` (la app) o `127.0.0.1:8099` (devserver), con las **mismas rutas**:
- `/` y `/{fichero}`: la web (pública, sin secretos; `Cache-Control: no-cache` para que un APK nuevo traiga la web nueva).
- `/ws?k=CLAVE`: WebSocket con mensajes JSON hacia `SessionHub` (lista completa en su comentario).
- `/qr.svg?k=CLAVE`, `/img/...?k=CLAVE` (apilado y exportaciones), `/diag.txt?k=CLAVE` (diagnóstico).

#### Watchdog de movimiento (seguridad — obligatorio)
No basta con detectar que el WebSocket se cierra: si el iPhone bloquea la pantalla o Safari pasa a segundo plano mientras mantienes pulsado, el "soltar" nunca llega y el socket puede tardar mucho en cerrarse.
- **Heartbeat del cliente**: mientras un botón está pulsado, el cliente envía `hold` cada **250 ms**.
- **Deadline en el servidor**: si pasan **750 ms** sin `hold` (o sin el `slew` inicial), el servidor envía STOP a ese eje. Valores configurables.
- **Página oculta**: el cliente envía `stop` en `visibilitychange` (hidden), `pagehide`, `blur`, `pointercancel` y `touchcancel`.
- **Cierre del WebSocket** o desconexión del cliente que inició el movimiento → STOP de todos los ejes.
- **Pérdida del cable USB** → estado "desconectado" en la UI y el slew queda anulado.
- **Ping de nivel WebSocket** cada 5 s para detectar clientes muertos.
- **Un solo controlador** activo a la vez (el último que toma el control); el resto, solo lectura. STOP funciona desde cualquiera.
- El STOP de emergencia envía velocidad 0 en ambos ejes **y** `M` (cancelar GoTo), y nada (seguimiento, GoTo, mediciones) puede volver a mover los motores después.
- Un STOP que falla (timeout del mando) **se reintenta** cada ~250 ms hasta que funciona.
- ⚠️ **Límite físico**: los slews por pass-through siguen hasta recibir velocidad 0. Si el propio Android muere (sin batería, cuelgue) o se suelta el cable OTG, ningún software puede pararlo: **los botones del mando físico son el respaldo**. Mejora futura: movimientos acotados (GoTo cortos que se renuevan con cada heartbeat) para que, si se pierde el enlace, se pare a los pocos grados.
- Prueba obligatoria en la Fase 2: mantener pulsado y bloquear el iPhone → la montura debe pararse en < 1 s.

### 5. Web UI (`app/src/main/assets/web`)
HTML + JS + CSS sin framework ni build. Pestañas Cielo · Mover · Imagen · Buscar · Más (se adaptan a lo conectado); Alinear es un asistente. STOP siempre visible, modo nocturno rojo por defecto. Necesita un navegador de 2020 o posterior (Safari 13.1+, Chrome/WebView 80+); con un WebView más antiguo, «Usar este móvil como mando» pide actualizarlo.

### 6. Diagnóstico
Más → Diagnóstico (o `/diag.txt?k=CLAVE`): estado, avisos y errores recientes y el tráfico con el mando (órdenes y últimas lecturas de posición por separado). Es lo primero que hay que mirar si algo no se mueve.

## Red
- Por defecto: el **iPhone crea el hotspot**, el Android se une. Alternativa: el Android crea la red.
- El QR lleva `http://IP:8080/?k=CLAVE`. La IP es la de la **WiFi** (se descartan WiFi Direct, VPN y datos móviles, que el iPhone no puede alcanzar: `core/share/LanAddress.kt`).
- mDNS (`starbridge.local`) no está implementado: el QR lo sustituye.
- No requiere internet ni SIM.

## Alineación
La hace el propio Android (`PLAN_CEREBRO.md`): sensores para una aproximación (±5°) y 1–N estrellas centradas para precisión. Si el mando ya está alineado (SkyAlign), se puede usar su alineación (modo «Alinea el mando»).

## Varios cerebros en la misma WiFi (PC y Android)
El cerebro es quien tiene el cable del mando: el programa del PC (`desktop/`) o la app Android. Cualquier aparato
(iPhone, Android, PC) maneja al que lo tenga:

- **Descubrimiento (`core/net/Beacon`)**: cada cerebro emite cada 2 s un datagrama UDP de difusión al puerto **47821**
  y escucha los de los demás. JSON: `{"starbridge":1,"id","kind":"pc|android","name","http":8080,"telescope":true,"model"?,"host"?}`.
  Nunca lleva la clave de acceso. En Android se toma un `MulticastLock` para recibirlos.
- **Hub**: mensaje `peers{selfKind, selfTelescope, items:[{id,kind,name,url,telescope}]}` a cada página (al conectar y
  cuando cambia); `info.brainKind` dice qué cerebro es.
- **Panel del PC (`desk.html`)**: lo sirven los dos cerebros (el APK lo empaqueta desde `desktop/src/main/resources`).
  Sin telescopio propio y con otro cerebro que sí lo tiene: «El telescopio está en … · Usarlo» abre su panel con
  `?from=<este panel>`; la primera vez pide su código (se recuerda por dirección). «Volver al PC», o volver solo si ese
  cerebro desaparece 20 s.
- **Web del móvil**: el mismo aviso (banner); en la pantalla del Android, «El telescopio está en el PC · Usar este móvil
  como mando» abre la web del PC.
- `/qr-app.svg` (QR para la app del iPhone) está en las rutas compartidas (`server/`).
