# Módulo de imagen y live stacking (opcional)

> **Documento histórico** (las primeras ideas). Lo que se hizo de verdad está en `PLAN_IMAGEN.md`: motor propio en Kotlin puro (`imaging/`), sin OpenCV, con la cámara del móvil por Camera2 y dentro de la misma app.

Es la parte que de verdad le da sentido al telescopio para mí: ver objetos de cielo profundo que a simple vista no llego a ver. Pero es un módulo aparte; el núcleo no depende de él.

## Qué es el live stacking (EAA)
Se capturan muchas exposiciones cortas (unos segundos a ~30 s) y se suman en tiempo real alineándolas. Cada frame añade luz y reduce ruido. Exposiciones cortas = no hace falta un seguimiento perfecto, ideal para una montura altacimutal como la de la SLT.

## Fuentes de imagen (por orden de dificultad)
1. **Cámara del propio móvil** en el ocular (adaptador de móvil). Camera2 API con exposición manual. Limitado: sensor pequeño y el procesado de Huawei interfiere. Bien para Luna y planetas.
2. **Cámara UVC/USB genérica** (webcam o "cámara planetaria" UVC). Más fácil de leer en Android (libuvc / UVCCamera).
3. **Cámara astronómica dedicada** (p. ej. ZWO ASI de gama de entrada). La mejor opción para cielo profundo. Requiere su SDK/driver en Android → **verificar soporte Android/ARM antes de comprar**.

⚠️ La SLT con montura altacimutal produce **rotación de campo**: la alineación debe corregir traslación + rotación, no solo desplazamiento.

⚠️ Para usar cámara y cable de montura a la vez en un solo puerto USB-C hará falta un **hub USB-C con OTG** (idealmente con alimentación).

## Pipeline de stacking (idea inicial; se implementó sin OpenCV)
1. Captura del frame (raw o lo más crudo posible).
2. Opcional: resta de dark frame.
3. Detección de estrellas (umbral + centroides).
4. Emparejado de estrellas con el frame de referencia (triángulos) → transformación (traslación + rotación).
5. Warp del frame y acumulación (media; más adelante sigma-clipping).
6. Estirado de histograma (auto-stretch) para visualizar.
7. Publicar JPEG en el servidor → la web lo refresca.

## Dónde procesar
- Por defecto: en el Android (un solo cerebro).
- Opción futura: si el P20 Pro se queda corto, enviar frames al navegador y procesar ahí (WebAssembly/JS) para aprovechar la potencia del iPhone.

## Referencias de conceptos
SharpCap (live stacking), Siril, ASTAP, DeepSkyStacker.
