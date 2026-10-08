# Protocolo NexStar (resumen)

> Verificado (oct 2026) contra el documento oficial de Celestron "NexStar Communication Protocol" y el documento comunitario "NexStar AUX Command Set" (Andre Paquette). Ante cualquier duda, manda el PDF oficial.

## Conexión
- 9600 baudios, 8N1, por el puerto RS-232 de la **base del mando** (hand controller).
- Respuestas terminan en `#`. Un comando a la vez.
- **Timeout: 3,5 s** (Celestron: "drivers should be prepared to wait up to 3.5 s"). Si se envían comandos sin esperar respuesta, se pierden o las respuestas se desfasan.
- Si el cable va al puerto AUX de la montura (no al mando), el protocolo es el bus AUX → comprobar en Fase 0.

## Comandos (con versión mínima del mando)
| Comando | Envío | Respuesta | Versión |
|---|---|---|---|
| Eco | `K` + `x` | `x#` | 1.2+ |
| Versión mando | `V` | 2 bytes binarios + `#` | 1.2+ |
| Modelo | `m` | 1 byte + `#` (7 = SLT) | 2.2+ |
| ¿Alineado? | `J` | byte 0/1 + `#` | 1.2+ |
| ¿GoTo en curso? | `L` | ASCII `0#`/`1#` | 1.2+ |
| Cancelar GoTo | `M` | `#` | 1.2+ |
| RA/Dec (16 bits) | `E` | `34AB,12CE#` | 1.2+ |
| RA/Dec precisa | `e` | `34AB0500,12CE0500#` | 1.6+ |
| Az/Alt (16 bits) | `Z` | `12AB,4000#` | 1.2+ |
| Az/Alt precisa | `z` | `12AB0500,40000500#` | **2.2+** |
| GoTo RA/Dec precisa | `rRRRRRRRR,DDDDDDDD` | `#` | 1.6+ (requiere alineado) |
| GoTo Az/Alt precisa | `bAAAAAAAA,EEEEEEEE` | `#` | 2.2+ |
| Sync RA/Dec precisa | `sRRRRRRRR,DDDDDDDD` | `#` | **4.10+** |
| Leer tracking | `t` | modo + `#` | **2.3+** |
| Fijar tracking | `T` + modo | `#` | 1.6+ |
| Leer/fijar lugar | `w` / `W`+8 bytes | | 2.3+ |
| Leer/fijar hora | `h` / `H`+8 bytes | | 2.3+ |

Modos de tracking: 0 off, 1 Alt/Az, 2 EQ Norte, 3 EQ Sur.

**Coordenadas precisas:** valor = (ángulo / 360°) × 2³² en hex (8 dígitos). Solo se usan los 24 bits altos (~0,08″). RA en horas ×15 → grados. **Dec/Alt > 180° son negativos** (restar 360).

Sin alinear, RA/Dec no significan nada y Az/Alt son relativos a donde se encendió.

**`b` sin alinear — por verificar en el mando antiguo del usuario.** Según el documento, `b` funciona sin alinear, pero en la primera prueba real un GoTo a una estrella no movió el telescopio. Por eso StarBridge no da por hecho que funcione: en el primer GoTo comprueba durante 3 s que el mando informa de un GoTo en curso (`L` → `1`) o que los encoders se mueven. Si no, cancela (`M`) y hace el GoTo **por software** con las mismas órdenes que las flechas (`P 2` + lecturas `z`). Más → Diagnóstico muestra qué contestó el mando a `b` y a `L`.

## Movimiento manual (slew)
Celestron recomienda **desactivar el tracking antes de hacer slew** y reactivarlo después (en alt-az el slew choca con el seguimiento).

Velocidad fija (0–9): `'P', 2, eje, dir, velocidad, 0, 0, 0` → `#`
- eje: `16` acimut, `17` altitud · dir: `36` +, `37` − · velocidad `0` = parar ese eje.

Velocidad variable: `'P', 3, eje, dir(6/7), rateHigh, rateLow, 0, 0` con rate = arcseg/s × 4.

## Pass-through a los controladores de motor
Formato: `'P', n, dispositivo, msgId, d1, d2, d3, bytesRespuesta` donde `n` = 1 + nº de bytes de datos.
Dispositivos: `16` motor AZM, `17` motor ALT, `176` GPS.

**Error:** si el dispositivo no responde, el mando devuelve **un byte extra antes de `#`** y los datos son basura. El driver debe comprobar la longitud de la respuesta.

| msgId | Nombre | Datos | Respuesta | Uso en StarBridge |
|---|---|---|---|---|
| `0xFE` | MC_GET_VER | — | 2 bytes | Versión firmware motor |
| `0x01` | MC_GET_POSITION | — | 24 bits | Cuentas del encoder del motor (diagnóstico holgura) |
| `0x13` | MC_SLEW_DONE | — | `0x00` no / `0xFF` sí | Saber si el motor terminó |
| `0x10` | MC_SET_POS_BACKLASH | 1 byte, **0–99** | ack | Anti-holgura positiva (horario/arriba) |
| `0x11` | MC_SET_NEG_BACKLASH | 1 byte, **0–99** | ack | Anti-holgura negativa (antihorario/abajo) |
| `0x40` | MC_GET_POS_BACKLASH | — | 1 byte | Leer anti-holgura + |
| `0x41` | MC_GET_NEG_BACKLASH | — | 1 byte | Leer anti-holgura − |

⚠️ Los comandos `0x01`, `0x10/0x11`, `0x40/0x41` y `0x13` vienen del documento AUX comunitario, no del oficial. **Probar primero la lectura (`0x40/0x41`) en Fase 0** antes de escribir nada. Ver `HOLGURA.md`.
