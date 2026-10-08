# Plan: el móvil como cerebro del telescopio

Objetivo: el Android sabe **dónde está** (GPS), **qué hora es** (GPS) y **hacia dónde apunta el tubo** (sensores + encoders), se alinea con **una estrella** (dos para precisión total) y hace GoTo y seguimiento él mismo. El mando queda como simple controlador de motores.

## 1. Modos de puntería
| Modo | Quién alinea | GoTo | Seguimiento | Cuándo |
|---|---|---|---|---|
| **StarBridge** (por defecto si el mando no está alineado) | La app (1–N estrellas + sensores) | App → `b` (GoTo Az/Alt de montura) | App (velocidad variable `P 3`) | Uso normal |
| **Mando** (por defecto si el mando ya está alineado) | El mando (SkyAlign…) | `r` (RA/Dec) | El mando | Respaldo / compatibilidad |

El usuario puede cambiar de modo en Ajustes. El mando se pone en *tracking off* en modo StarBridge para que no luche con la app.

## 2. Fuentes de datos
- **Ubicación**: GPS del Android (`LocationManager`, sin Google Play). Prioridad: GPS > manual > mando > ninguna (aviso).
- **Hora**: hora UTC del GPS (`Location.time` + reloj monotónico) → offset aplicado al reloj de la app. Sin GPS: reloj del sistema.
- **Mando**: si no está alineado y la versión ≥ 2.3, la app escribe lugar (`W`) y hora (`H`) del GPS → la alineación del mando también mejora.
- **Orientación del tubo**: vector de rotación del Android (acelerómetro + brújula + giroscopio) + declinación magnética (`GeomagneticField`, offline). Montaje configurable: *borde superior hacia la boca del tubo* (por defecto) o *cámara trasera mirando por el tubo*.

## 3. Modelo de puntería (core/pointing)
Coordenadas de montura (encoders, cero = posición al encender) → coordenadas reales (Az/Alt topocéntricas).
- Parámetros: desfase de acimut, desfase de altitud (índice), 2 inclinaciones del eje de acimut (base no nivelada), sentido del acimut.
- 1 estrella → desfases (supone base nivelada). ≥2 estrellas → los 4 parámetros por mínimos cuadrados (Gauss-Newton). Se prueba el sentido del acimut y se queda el de menor residuo.
- Observación "sensor" (orientación del Android) con peso bajo → alineación aproximada automática (error típico: altura ~1°, acimut ~5–10°).
- Calidad: residuo RMS en minutos de arco por estrella; se avisa si una estrella encaja mal (centrada la estrella equivocada).

## 4. GoTo y seguimiento (modo StarBridge)
- GoTo en dos tramos: primero a un punto 0,5° antes (configurable) y luego al objetivo, **siempre llegando en sentido positivo** → la holgura queda tomada siempre del mismo lado.
- Espera con `L` (GoTo en curso). Cancelable con STOP.
- Si el mando no ejecuta `b` (se comprueba en el primer GoTo: ni `L` = 1 ni movimiento en 3 s) o termina lejos del objetivo, el GoTo se hace por software: velocidades fijas de las flechas, de 9 a 4 según la distancia, leyendo los encoders hasta < 0,03°. Más → GoTo: Auto / El mando / StarBridge.
- En Auto, el tramo largo lo hace el mando (`b`) y **la llegada siempre StarBridge**: el mando tiene su propia dirección de aproximación y podía dejar la holgura del lado contrario.
- Sin alinear pero con los sensores del Android: «Ir» hace antes una alineación aproximada con ellos (±5°).
- Seguimiento: cada 1 s, velocidad = **velocidad del objeto en el cielo** (calculada con las posiciones ±30 s, en coordenadas de montura) **+ error / 4 s**. Antes se corregía todo el error en 1 s: con el retardo real del cable y motores DC que no clavan la velocidad, eso daba tirones (en el simulador con motores ±20 % y 120 ms por orden: cambios de 2,2″/s cada segundo; ahora 0,44″/s). Solo se manda una velocidad nueva si cambia más de 0,25″/s o un 1 %, y se refresca cada 15 s. Se pausa durante el movimiento manual y se reanuda en el nuevo punto del cielo al soltar.
- STOP desactiva el seguimiento (si no, lo reanudaría).
- Objetos bajo el horizonte: no se va. Si el objeto seguido se pone, se para el seguimiento.

## 5. Asistente de alineación (iPhone)
1. Comprobaciones: GPS ✓ (precisión), hora ✓, telescopio ✓, sensor de orientación ✓.
2. "Alineación rápida con sensores" (si el Android va sujeto al tubo).
3. Elegir estrella sugerida (brillante, 20–75° de altura, bien separada de las anteriores) → **Ir** (si ya hay modelo) o guía con flechas "gira a la derecha 12°, sube 5°" (sensores).
4. Centrarla con la cruceta (velocidad fina) → **Centrada**.
5. Segunda estrella recomendada → modelo completo. Se muestra la calidad.

En modo Mando: botón **Sync** (`s`, mando ≥ 4.10) para afinar la puntería en una zona.

## 6. Interfaz del iPhone
Barra inferior estilo iOS: **Cielo · Mover · Buscar · Alinear · Más**. STOP flotante siempre visible. Barra superior: conexión, modo, seguimiento.
- **Cielo**: mapa del cielo en tiempo real (cenit en el centro, horizonte, N/E/S/O, estrellas por brillo, planetas, Messier) con la retícula del telescopio. Tocar un objeto → ficha con GoTo.
- **Mover**: cruceta grande, velocidades rápidas (Fina/Media/Rápida/Máx) + ajuste fino, seguimiento on/off, estado de holgura.
- **Buscar**: buscador + categorías (Planetas, Messier, Galaxias, Nebulosas, Cúmulos, Estrellas) + "Lo mejor ahora".
- **Alinear**: asistente paso a paso.
- **Más**: holgura, montaje del Android, flechas invertidas, modo nocturno, lugar/hora, info del telescopio.
- Instalable en pantalla de inicio (manifest + iconos), modo nocturno rojo por defecto.

## 7. Seguridad (se mantiene todo lo anterior)
Watchdog con heartbeat, reintentos de STOP, parada al cerrar, GoTo cancelable, límites de horizonte, el seguimiento nunca rearranca tras un STOP.

## 8. Verificación
- Tests en `core` con un simulador **desalineado y con la base torcida** (desfases e inclinación ocultos): alineación con 1 y 2 estrellas, GoTo a un tercer objeto (error < 0,1°), seguimiento durante 10 min (error < 1′), alineación por sensores ruidosos, sentido de acimut invertido, STOP con seguimiento.
- App Android compilada localmente antes de cada push.
- Revisión independiente final.

## 9. Pendiente de validar en el telescopio real
- Que el mando antiguo acepte `z`/`b`/`P 3` sin alinear (según el protocolo, sí).
- Suavidad del seguimiento por velocidad variable.
- Sentido real de los encoders (lo detecta la alineación de 2 estrellas).
- Calidad de la brújula junto a los motores.
