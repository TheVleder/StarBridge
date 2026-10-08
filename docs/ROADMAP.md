# Roadmap

> Estado (oct 2026): fases 1–3 y 1.5 **programadas y probadas con simulador**; además el "cerebro" (`PLAN_CEREBRO.md`) y la imagen (fases 5–6, `PLAN_IMAGEN.md`). Primera prueba real: la app conecta y mueve el telescopio; un GoTo a una estrella no se movía → GoTo con comprobación y respaldo por software, más diagnóstico del tráfico con el mando. Falta validar el resto en el telescopio real, en orden.

Cada fase debe funcionar en el telescopio real antes de pasar a la siguiente.

## Fase 0 — Verificar hardware (portátil)
- Conectar el cable al portátil y ejecutar `tools/nexstar_test.py`.
- Confirmar: chip del adaptador (FTDI/PL2303/CP210x/CH34x), eco (`Kx` → `x#`), versión del mando, modelo, posición.
- Confirmar que el pass-through a los motores funciona: versión de motor y valores anti-backlash actuales (el script los imprime). **Apuntarlos.**
✅ Hecho cuando el portátil lee la posición y mueve la montura.

## Fase 1 — Android habla con el telescopio
- Proyecto Android en Kotlin, `usb-serial-for-android`.
- Detectar el adaptador, pedir permiso USB, abrir puerto.
- Pantalla mínima de debug: versión, posición, botones de mover/parar.
✅ Hecho cuando el P20 Pro mueve la montura con el cable OTG.

## Fase 1.5 — Holgura (ver `HOLGURA.md`)
- Pantalla de anti-backlash nativo (leer/escribir 0–99, restaurar).
- Sensor de giro con el giroscopio del Android sujeto al tubo; estado `taking_up_slack` / `moving`.
- Calibración automática (opcional, al final de la fase).
✅ Hecho cuando la app detecta el momento real en que el tubo empieza a girar y al invertir el sentido el tubo responde sin retraso notable.

## Fase 2 — Control inalámbrico
- Foreground Service + servidor HTTP/WebSocket.
- Web UI con cruceta, velocidad y STOP. Watchdog con heartbeat (ver `ARQUITECTURA.md`).
- ~~mDNS para encontrar el Android desde el iPhone~~ → sustituido por el QR con la IP y la clave (se actualiza solo al cambiar de red).
✅ Hecho cuando muevo el telescopio desde Safari en el iPhone con la pantalla del Android apagada.

## Fase 3 — GoTo y catálogo
- Catálogo embebido (Messier + NGC brillantes + planetas calculados).
- GoTo, estado "en curso", cancelar.
- Modo nocturno rojo.
✅ Hecho cuando hago GoTo a un objeto desde el iPhone.

## Fase 4 — Publicar el núcleo (open source)
- Limpiar, README en inglés, licencia, capturas, guía de cables.
- Pedir a la comunidad que pruebe en otras monturas NexStar.

## Plan de imagen propio
Las fases 5 y 6 se detallan en `PLAN_IMAGEN.md` (apilado propio, dos versiones: Control e Imaging).

## Fase 5 — Captura de imagen (módulo opcional)
- ~~Evaluar OpenLiveStacker~~ → decidido: motor propio en Kotlin (`imaging/`), ver `PLAN_IMAGEN.md`. Programado; falta probarlo con la cámara del P20 Pro.
- Elegir fuente de imagen (ver `IMAGEN_Y_STACKING.md`).
- Capturar frames y mostrarlos en la web.

## Fase 6 — Live stacking
- Detección de estrellas, alineación de frames, acumulación, estirado del histograma.
- Imagen apilada actualizándose en directo en el iPhone.
