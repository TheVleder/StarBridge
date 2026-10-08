# Compensación de holgura (backlash) del motor

## El problema (mi 130 SLT)
Al empezar a girar —sobre todo al **cambiar de sentido**— el motor gira pero el tubo no se mueve hasta que los engranajes "cogen" la holgura. El telescopio cree que ya se está moviendo, pero no.

## Por qué la montura no se entera
En las SLT los motores son DC simples con un **encoder óptico en el eje del motor**, no en el eje del telescopio. El encoder cuenta vueltas del motor, no del tubo. Toda la holgura de la caja de engranajes queda **después** del encoder → la montura **no puede detectarla**. Por eso hace falta un sensor externo.

## Solución en 3 capas (de más simple a más avanzada)

### Capa 1 — Anti-backlash nativo del mando (sin sensor)
El firmware del motor ya tiene compensación: al cambiar de sentido, "rebobina" rápido el motor para tragarse la holgura.
- Valores 0–99 por eje y por sentido (`+` horario/arriba, `−` antihorario/abajo).
- La app los **lee y escribe** por pass-through (`0x40/0x41` leer, `0x10/0x11` escribir; ver `PROTOCOLO_NEXSTAR.md`).
- La UI tendrá una pantalla "Holgura" con 4 deslizadores y un botón "probar" (mueve adelante/atrás para ver el efecto).
- Limitación: hay que ajustarlo a ojo y depende de la velocidad (más lenta → más valor).

### Capa 2 — Detectar el inicio real del giro con el giroscopio del Android ⭐
**El propio P20 Pro es el sensor**: si lo sujetamos al tubo (soporte de móvil o velcro), su giroscopio y acelerómetro ven el movimiento **real** del tubo.
- Al mandar un slew, la app registra el instante del comando y el instante en que el giroscopio detecta giro real en ese eje.
- Diferencia = **tiempo muerto de holgura**. Combinado con la velocidad → holgura en grados.
- Así la app **sabe cuándo empieza a girar de verdad** y lo muestra en la UI ("tomando holgura…" → "moviendo").
- Detección: umbral sobre la velocidad angular filtrada (media móvil de ~50 ms) por encima del ruido medido en reposo. A velocidades bajas (1–3) el giro puede quedar bajo el ruido del giroscopio → usar velocidades ≥ 4 para medir.
- El eje del sensor depende de cómo se monte el móvil → **paso de calibración**: "mueve en acimut" / "mueve en altitud" y la app aprende qué eje del giroscopio corresponde a cada uno.
- Alternativa sin montar el móvil: módulo de imagen (desplazamiento de estrellas entre frames) — más lento, solo con cámara.

### Capa 2 bis — Medición a mano: «Medir holgura moviéndolo tú» ⭐ (implementado)
Más → Holgura → **Medir holgura moviéndolo tú**. Es la forma recomendada de medirla:
1. El Android (el del cable) va **apoyado o sujeto en el tubo**; con otro móvil (el iPhone) se abre el modo.
2. Aparecen dos flechas grandes del eje elegido (acimut ◀ ▶ o altitud ▲ ▼) y la velocidad (Media por defecto).
3. Se mantiene pulsada una flecha: la app lee el encoder del motor **antes** de mandar el movimiento y lo sigue (`z` cada ~40 ms) mientras el giroscopio vigila el tubo.
4. Cuando el tubo gira de verdad (velocidad del giroscopio por encima del ruido medido en reposo justo antes, sostenida 150 ms para no confundir un golpe o la vibración del motor), se busca hacia atrás el instante en que empezó a subir y se interpola el encoder en ese instante: **grados de motor hasta que el tubo se movió**. La pantalla dice «¡Se mueve!».
5. Cada **cambio de sentido** es una medida. Las pulsaciones en el mismo sentido que la anterior miden solo el **retardo** del sistema (filtro del sensor, arranque del motor), y su mediana se resta. La primera pulsación de cada eje solo tensa los engranajes y no cuenta.
6. Holgura = mediana de los cambios de sentido − retardo. «Guardar» la usa ya en GoTo y seguimiento.

No hace falta saber cómo va montado el móvil (se usa la velocidad angular total) y nunca se mueve solo: el usuario mantiene la flecha y el watchdog de siempre la protege. Si el motor gira más de 4° sin que el giroscopio note nada, avisa de que el Android no está en el tubo. Código: `core/backlash/SlackMeter.kt`, tests en `SlackMeterTest`.

### Cómo usa StarBridge la holgura medida (implementado, oct 2026)
- **Dónde está el motor dentro de la holgura** (`core/backlash/GearPlay.kt`): con cada lectura del encoder se sigue el desfase motor − tubo, siempre entre ±holgura/2. Un toque corto o un cambio de sentido a medias quedan registrados (antes solo se recordaba "el último sentido", que daba errores de hasta toda la holgura al centrar con toques). Al conectar se desconoce (motores movidos a mano o apagados) hasta el primer movimiento largo.
- **Flechas**: al cambiar de sentido, el motor recorre **primero toda la holgura deprisa** (velocidad 8, la última parte a 6, leyendo el encoder) y después sigue a la velocidad elegida: el tubo responde al momento incluso a velocidad fina. Aunque sea un toque, la holgura se recorre entera; solo STOP la corta. Requiere conocer el sentido del motor (se aprende con el primer movimiento). Más → Holgura → «Recoger la holgura con las flechas» (activado por defecto). Si se usa, el anti-backlash del mando debe quedar a 0.
- **Alineación**: la posición del tubo de cada estrella = encoder − desfase actual.
- **GoTo**: la llegada final la hace siempre StarBridge (aproximación + holgura en el sentido del seguimiento), así los engranajes quedan cargados del lado bueno. El mando tiene su propio ajuste «GoTo Approach» y podía terminar moviéndose al revés: un GoTo al mismo objeto recién centrado fallaba en toda la holgura (en el simulador, 0,96° con 1° de holgura; ahora < 0,08°).
- **Seguimiento**: empuja siempre del lado hacia el que se mueve el tubo; si se invierte (altitud en el meridiano), la holgura se recoge dentro de la corrección.

### Capa 3 — Calibración automática + compensación por software
Con la Capa 2 la app puede **medir la holgura sola**:
1. Mueve el eje en un sentido hasta que el giroscopio confirma movimiento (holgura tomada).
2. Invierte el sentido y mide cuántos grados/ms gira el motor (`MC_GET_POSITION`, `0x01`) antes de que el tubo se mueva.
3. Repite 3–5 veces por eje y sentido; usa la mediana.
4. Con eso, **dos opciones** (configurable, nunca las dos a la vez):
   - **A (recomendada)**: traducir la medida a valor 0–99 y escribirlo en el anti-backlash nativo (Capa 1). Afecta también al mando físico y a los GoTo.
   - **B**: compensación en la app: al invertir sentido, un "golpe" corto a velocidad alta del tamaño medido antes del slew normal. Útil si el nativo se queda corto.

Guardar los valores por montura y avisar si cambian mucho entre sesiones (indicaría desgaste o tornillos flojos).

## Modelo de software
- `BacklashSettings` (por eje: positivo, negativo, modo: off / nativo / software).
- `MotionSensor` (interfaz) → `AndroidGyroSensor`, `SimulatedMotionSensor` (el simulador de montura simula también la holgura para poder probar sin telescopio).
- `BacklashCalibrator` — ejecuta el procedimiento de la Capa 3, siempre cancelable con STOP.
- Estado por eje publicado al WebSocket: `idle | taking_up_slack | moving`.

## Seguridad
- La calibración mueve la montura sola: avisar antes, límites de recorrido (máx. ±10°) y STOP siempre activo.
- Escribir valores nativos solo tras leer y guardar los anteriores (botón "restaurar").

## Mecánica (complementario)
Antes de compensar por software, revisar físicamente: los tornillos del motor/caja de engranajes de la SLT y el embrague de acimut. Mucha gente reduce bastante la holgura apretando/ajustando; el software compensa lo que quede.
