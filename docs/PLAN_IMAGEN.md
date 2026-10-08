# Plan: imagen y live stacking (EAA) con la cámara del móvil

**Objetivo:** Astronomía Asistida Electrónicamente (EAA) de verdad. El Huawei P20 Pro va en el ocular del telescopio y hace fotos **RAW** de exposición configurable (de 1 a 30 s). El propio móvil limpia, alinea y apila cada foto. En el iPhone se ve cómo aparece una galaxia o nebulosa que a simple vista no se ve.

**Principios:**
1. Código propio: solo técnicas documentadas, sin copiar código de nadie, para poder publicarlo luego con la licencia que elijamos.
2. Primero **medir** (qué permite la cámara) y después decidir.
3. **Nunca saturar el móvil.** El control de la montura y el STOP tienen prioridad absoluta.
4. Cada etapa del algoritmo se valida con tests numéricos sobre cielo sintético antes de usarse de noche.

## 0. Estado de la implementación (oct 2026)
**Hecho (en la misma app, sin versiones separadas):**
- Motor `imaging/` completo, con los tests de §5 en verde (23 tests): centroides < 0,1 px, registro con giro y deriva, emparejado ciego, SNR ≈ √N, galaxia invisible en 1 foto y clara en 64, píxeles calientes aprendidos (también pares, bordes y junto a estrellas) sin confundir estrellas bien seguidas, 0 descartes de fotos buenas/débiles/con neblina y ≥ 95 % de las malas detectadas, filtro nunca peor que sin filtro, exposición automática a < 10 % del óptimo en 6 escenarios, ruido de lectura ±10 %, enfoque en ≤ 1 paso en ≥ 95 % de los casos. Rendimiento: ~0,2 s por foto de 2 MP en el PC.
- Sesión de imagen (`core/imaging/ImagingSession`): cámara elegible (lógicas y físicas), exposición e ISO automáticos (ruido de lectura medido con 2 fotos cortas, foto de prueba, límites de rotación de campo, saturación y cielo brillante), enfoque del móvil por barrido grueso + fino, asistente de enfoque del telescopio, «Probar en el cielo» (3 exposiciones, la mejor señal por minuto), darks guiados (biblioteca en disco, por cámara/ISO/exposición/agrupado), apilado en directo con captura y proceso en paralelo, pausa con GoTo, fotos movidas descartadas, gestión de temperatura y batería, «Recuperar», exportar FITS 32 bits + TIFF 16 bits lineales + JPEG (Descargas/StarBridge y descarga desde el iPhone), DNG opcional por foto. 5 tests de extremo a extremo con la cámara simulada.
- Android: `Camera2Source` (RAW_SENSOR o luma YUV, exposición manual, enfoque fijo, sin reducción de ruido ni corrección de píxeles calientes del fabricante, cámaras físicas), servicio en primer plano con tipo cámara.
- Web: pestaña Imagen (imagen de borde a borde con zoom, pastillas, barra Empezar/Pausar · Ajustes · Encuadre · Sesión · Guardar), hojas de ajustes, sesión, cámaras, darks y guardado, miniatura del apilado en las pestañas de control, modo nocturno también en la imagen.

**Pendiente de comprobar en el P20 Pro (I0/I4):** exposición máxima real por Camera2 y si deja usar la cámara monocromo por separado; captura con la pantalla apagada; temperatura en sesiones largas; tiempo real por foto en el móvil.

**No hecho (a propósito, de momento):** flats; dithering con la montura (en altacimutal la rotación de campo y la deriva ya desplazan las fotos, y el mapa de píxeles calientes se aprende solo); plate solving (I6); análisis de máscara de Bahtinov.

---

## 1. Hechos y supuestos sobre el hardware

| Elemento | Lo que sabemos | Lo que hay que **medir** (fase I0) |
|---|---|---|
| P20 Pro, cámara monocromo | Sensor de 20 MP sin filtro de color: más luz por píxel, sin mezcla de colores | ¿Android la expone por separado a apps externas (Camera2) o solo a través de la cámara "lógica"? |
| P20 Pro, cámara principal | 40 MP Quad-Bayer (en RAW suele salir a 10 MP agrupado) | Resolución RAW real, nivel de soporte de Camera2 (LIMITED/FULL/LEVEL_3) |
| Exposición | El usuario ha hecho fotos buenas de 15–30 s en el modo Pro de Huawei | **Exposición máxima por Camera2** (puede ser menor que en el modo Pro) y si se respeta de verdad (medir la duración real) |
| ISO | — | Rango de ISO y la ganancia analógica máxima (por encima de ella solo se amplifica ruido) |
| RAW | Imprescindible: el JPEG de Huawei borra las estrellas débiles | Si da `RAW_SENSOR`, nivel de negro, nivel de blanco y patrón Bayer |
| Enfoque / estabilizador | — | Si se puede fijar el enfoque a infinito y apagar OIS, reducción de ruido y corrección de píxeles calientes |
| Cámara con pantalla apagada | Android 10 | Si captura desde el servicio en primer plano con la pantalla apagada |
| Temperatura | Las exposiciones largas calientan el sensor (más ruido térmico y más píxeles calientes) | Temperatura de la batería como referencia y estado térmico de Android (`PowerManager`, API 29) |

➡️ **Fase I0, "Diagnóstico de cámara":** un botón en la app que lista cada cámara con todo lo anterior y hace una **prueba real**: pide una exposición de 10 s y mide cuánto dura de verdad. Hay que probarlo en casa antes de programar el resto.

---

## 1 bis. Cualquier Android, cualquier cámara (open source)
La app **no está hecha para el P20 Pro**: descubre las cámaras de cada móvil y se adapta a lo que cada una permite.

- **Descubrimiento**: se enumeran todas las cámaras de Camera2, **incluidas las físicas dentro de una "lógica"**. Desde Android 9 los móviles multicámara exponen sus sensores individuales: el monocromo, el gran angular, el tele…
- **Ficha de cada cámara** (la misma que el diagnóstico I0):
  - Orientación y focal.
  - Resolución RAW.
  - **Exposición mínima y máxima**.
  - Rango de ISO y ganancia analógica máxima.
  - **Monocromo** (Android lo indica con una capacidad específica) o Bayer, y patrón de color.
  - Si permite enfoque manual, apagar el OIS o apagar la reducción de ruido.
  - Nivel de Camera2 (LIMITED, FULL o LEVEL_3).
- **Selector en el iPhone**: lista de cámaras con su ficha resumida y una recomendación automática para EAA: RAW > monocromo > exposición más larga > sensor más grande. El usuario **cambia de cámara cuando quiere** (con el apilado parado).
- **Los ajustes se adaptan a la cámara elegida**: el deslizador de exposición llega solo hasta el máximo real de esa cámara, el ISO a su rango, y las opciones que no admite se ocultan con su motivo ("esta cámara no da RAW").
- **Perfiles por cámara**: los últimos ajustes, darks y píxeles calientes se guardan **por cámara**, porque cada sensor tiene los suyos.
- **Cámaras externas** con la misma interfaz `FrameSource`: webcams UVC y cámaras astronómicas (ZWO, SVBony, ToupTek) a través de sus SDK de Android. El pipeline no distingue de dónde viene la foto.
- **Base de datos comunitaria (futuro)**: cada usuario puede exportar la ficha de su móvil ("Compartir diagnóstico") para recopilar qué móviles funcionan mejor para EAA y con qué ajustes.
- **Mínimos**: el modo básico funciona con cualquier cámara Camera2. RAW y exposiciones largas se aprovechan donde existen, y donde no, se usa el plan B de §7 (muchas fotos cortas).

---

## 2. Pipeline completo (por foto)

```
Captura RAW ─► Limpieza ─► Detección de estrellas ─► Control de calidad ─► Alineación ─► Normalización ─► Apilado ─► Revelado (solo vista)
   (I/O)       (calibración)     (encontrar estrellas)    (¿la usamos?)      (registro)      (igualar fondo)   (sumar)     (para el iPhone)
```

### 2.1 Captura
- **Ajustes del usuario** (todos personalizables):
  - Exposición (1–30 s, limitada al máximo real de la cámara).
  - ISO / ganancia.
  - Cámara (monocromo o color).
  - Agrupado de píxeles (*binning*) 1×1, 2×2 o 3×3.
  - Número de fotos (o sin límite) y pausa entre fotos.
- **Fijados por la app**:
  - Enfoque manual a infinito, autoexposición apagada y balance de blancos apagado.
  - Reducción de ruido, nitidez, corrección de píxeles calientes y OIS **apagados**: la limpieza la hacemos nosotros, que sabemos lo que es una estrella.
- **Metadatos por foto**:
  - Hora exacta (GPS), exposición, ISO y temperatura de la batería.
  - Posición de la montura y si se movió durante la foto (giroscopio y estado de la montura).
  - Nivel de negro y nivel de blanco.
- Si el giroscopio detecta un golpe o vibración durante la exposición, la foto se marca como dudosa antes de procesarla.

### 2.2 Limpieza (calibración)
Orden y motivo de cada paso:
1. **Nivel de negro**: restar el negro del sensor (lo da Camera2, a veces por canal y a veces dinámico).
2. **Máscara de saturación**: los píxeles al nivel de blanco se marcan, no se usan para medir y no envenenan el apilado.
3. **Dark maestro**: mediana de 10–20 fotos con el telescopio tapado, **misma exposición e ISO**.
   - Se guarda una **biblioteca de darks** por exposición, ISO y temperatura. Se avisa si la temperatura actual difiere más de unos 5 °C.
   - **Optimización del dark**: se escala por el factor que mejor elimina los píxeles calientes de la foto, porque los móviles no tienen sensor refrigerado.
4. **Mapa de píxeles calientes y fríos**: se sacan del dark (más de 5σ sobre la mediana) y se completan **en vivo**. Un píxel que es anómalo en la misma posición del sensor en muchas fotos, mientras el cielo se mueve, es defectuoso. Se sustituye por la mediana de sus vecinos **antes** de detectar estrellas, para que no se confunda con una.
5. **Flat (opcional, más adelante)**: corrige el viñeteado y el polvo. En una cámara afocal el viñeteado del ocular es enorme. Sin flat, se compensa en el revelado con el modelo de fondo (2.8).
6. **Máscara del ocular**: se detecta automáticamente el **círculo útil** del ocular (umbral sobre la imagen apilada y ajuste de un círculo) y se ignora todo lo de fuera.
7. **Binning por software** (si se eligió): suma de bloques de 2×2 o 3×3. Da más señal por píxel, menos memoria y más velocidad. Para EAA casi siempre compensa.
8. Si la cámara es de color: **debayer "superpíxel"** (cada bloque 2×2 RGGB se convierte en un píxel RGB, sin inventar datos). Para detectar estrellas y alinear se usa la luminancia.

### 2.3 Detección de estrellas
1. **Fondo**: cuadrícula de 64×64 px con la mediana de cada celda (descartando los valores altos) y una superficie suave interpolada. Se resta.
2. **Ruido**: σ = 1,4826 × MAD del fondo. Es robusto: las estrellas no lo inflan.
3. **Filtro adaptado**: suavizado gaussiano del tamaño típico de una estrella, que realza las estrellas débiles frente al ruido.
4. **Candidatas**: máximos locales por encima de k·σ (k ≈ 5, ajustable) dentro del círculo del ocular y lejos de los bordes.
5. **Medida de cada estrella**:
   - Centroide subpíxel por momentos ponderados en una ventana. Objetivo: error menor de 0,1 px en estrellas con buena señal.
   - Flujo, pico, **FWHM** o **HFR** (nitidez) y **elongación** (relación de momentos: redonda o alargada).
6. **Rechazo de candidatas**:
   - Saturadas (centroide poco fiable).
   - FWHM menor de 1 px: píxel caliente o rayo cósmico.
   - Muy alargadas: satélite, borde de una galaxia o error.
   - Cerca de la máscara.
7. **Resultado**: lista de las 50–150 estrellas más brillantes y fiables, con sus medidas.

### 2.4 Control de calidad: descartar solo lo que estropea (sin falsos descartes)
La regla de oro: **una foto con poca luz o pocas estrellas NO es una foto mala.** Sumada con el peso adecuado, siempre aporta señal. Solo se descarta lo que **empeoraría** la imagen.

**1. Separar "peor" de "dañina"**

| Tipo de foto | Qué se hace | Por qué |
|---|---|---|
| Poca luz, cielo más claro, neblina fina, pocas estrellas en un campo pobre | **Se acepta con menos peso** (nunca se descarta por esto) | Con pesos óptimos (inverso de la varianza), añadir una foto más débil **nunca baja** la señal/ruido del total, solo sube menos |
| Estrellas alargadas (viento, golpe, movimiento durante la exposición) | Se descarta | Emborrona y alarga las estrellas del apilado |
| Muy desenfocada respecto a la sesión | Se descarta | Engorda las estrellas y quita detalle |
| No se puede alinear con fiabilidad | Se descarta | Una foto mal alineada crea estrellas dobles: es lo peor que puede pasar |
| Prácticamente opaca (nube densa: casi ninguna estrella de referencia visible) | Se descarta | No aporta y no se puede alinear |

**2. Todo relativo a la propia sesión, nunca umbrales fijos.**
- Cada métrica se compara con la **mediana de las últimas fotos aceptadas**, con su dispersión robusta (MAD).
- En un campo con pocas estrellas (una nebulosa planetaria pequeña) o con poca luz, la referencia ya es "pocas estrellas", así que no se penaliza.
- Se cuentan las estrellas **de la referencia que deberían verse** según su brillo, no el total. Así se distingue "el campo es pobre" de "ha pasado una nube".
- La **transparencia** se mide con el brillo de las estrellas comunes (factor fotométrico). Si es 0,6, la foto entra con peso 0,6², no se descarta.

**3. Decisiones con margen y en tres niveles.**
- **Aceptar**: dentro de lo normal.
- **Aceptar con peso reducido**: zona dudosa. Ante la duda no se descarta.
- **Descartar**: solo si la métrica está **claramente** fuera (por ejemplo, a más de 4–5 MAD de la mediana de la sesión) **y** es de las que dañan (elongación, enfoque, alineación).
- Las primeras fotos de la sesión no se descartan por comparación (todavía no hay referencia); solo por alineación imposible.

**4. Mínimo de estrellas para alinear, con plan B.**
- Lo normal son 6 o más parejas de estrellas.
- Con **3–5** estrellas se alinea igual si la predicción (foto anterior + montura) coincide.
- Con **menos de 3** no se puede asegurar la alineación: la foto **se guarda aparte** (no se tira) y se reintenta cuando haya más referencias.

**5. El veredicto final lo da la señal/ruido.**
- Antes de sumar una foto se calcula si **mejora la SNR estimada** del apilado.
- Si una regla dice "descartar" pero la foto mejoraría la SNR y está bien alineada y nítida, gana la SNR.

**6. Nada se pierde y el usuario manda.**
- Las descartadas se guardan con miniatura, **motivo** y métricas. En el iPhone verás "Descartadas (3)", el porqué de cada una y un botón **Recuperar** para sumarla igualmente.
- Tres modos de filtro:
  - **Conservador** (por defecto): solo descarta lo dañino evidente.
  - **Normal**.
  - **Desactivado**: solo pondera, nunca descarta.
- Opción de guardar todas las RAW para reprocesar la sesión entera con otros ajustes.

**7. Cómo se demuestra que no se equivoca** (tests en §5):
- Conjunto de fotos sintéticas **etiquetadas** (buenas-débiles, buenas-pocas-estrellas, neblina, nube densa, viento, golpe, desenfoque, mal alineada), con miles de casos aleatorios.
- **Criterio obligatorio: 0 descartes de fotos buenas-débiles y buenas-pocas-estrellas.** El resto debe descartarse en ≥ 95 % de los casos.
- **Criterio de calidad global**: la SNR y la nitidez (FWHM) del apilado **con** el filtro deben ser **iguales o mejores** que sin filtro en todos los escenarios. Si el filtro empeora un solo escenario, no vale.
- **Reproducción de sesiones reales**: tus primeras noches se graban (RAW) y se reprocesan en el PC con el devserver para ajustar umbrales con datos de verdad, sin perder nada.

### 2.5 Alineación (registro), la pieza clave
Debe aguantar la **rotación de campo** de la montura altacimutal (el campo gira poco a poco), el desplazamiento por el seguimiento imperfecto, estrellas que entran y salen, ruido y fotos con pocas estrellas.

1. **Predicción**: la foto anterior ya alineada y la montura (el ángulo de rotación de campo se calcula a partir de la altura y el acimut) dan una transformación esperada.
2. **Emparejado rápido**: se aplica la predicción y se busca para cada estrella la más cercana de la referencia (en menos de unos pocos px). Si salen 10 o más parejas buenas, se pasa al paso 4. Es el caso normal: rápido y robusto.
3. **Emparejado ciego (si falla la predicción)**, por ejemplo tras un GoTo, un golpe o la primera foto:
   - Con las N estrellas más brillantes se forman **triángulos** con sus vecinas más cercanas.
   - Cada triángulo se describe por invariantes que no cambian al girar, desplazar o escalar: cocientes de sus lados ordenados.
   - Se buscan triángulos parecidos entre la foto y la referencia (árbol k-d) y cada coincidencia "vota" por parejas de estrellas.
   - **RANSAC**: se prueban transformaciones a partir de 2–3 parejas y se queda la que más estrellas explica dentro de 1 px.
4. **Ajuste fino**: mínimos cuadrados con todas las parejas válidas.
   - Transformación de **semejanza**: giro + desplazamiento + escala casi 1, que absorbe pequeños cambios de enfoque.
   - Opción **afín** si hace falta.
   - Se calcula el RMS residual, que debe ser de 0,1–0,3 px con buena señal.
5. **Comprobación de coherencia**: el giro medido debe parecerse al esperado por la rotación de campo. Si no, se avisa.
6. **Remuestreo**: la foto se transforma al marco de la referencia.
   - Bilineal por defecto: rápido y suaviza un poco el ruido.
   - Lanczos-3 opcional: más nítido, más lento.
   - Se acompaña de un **mapa de cobertura**: los bordes que se quedan fuera por el giro cuentan menos.
7. **Referencia**: la mejor de las primeras fotos (más estrellas y menor FWHM). Si el objeto se aleja mucho por el giro, se puede volver a elegir.

### 2.6 Normalización
Antes de sumar, cada foto se ajusta al nivel de la referencia:
- **Aditiva**: igualar el nivel del fondo.
- **Multiplicativa**: igualar el brillo de las estrellas comunes, para compensar neblina o transparencia variable.

Sin esto, el rechazo sigma confundiría "la noche se ha puesto más clara" con "esta foto está mal".

### 2.7 Apilado
- **Media ponderada con rechazo sigma incremental** (algoritmo de Welford: media y varianza por píxel actualizadas foto a foto). No hace falta guardar todas las fotos.
- Desde la foto 5: un píxel que se aleja más de κ·σ (κ ≈ 3) de la media se ignora en esa foto. Así **desaparecen satélites, aviones, rayos cósmicos y píxeles calientes residuales** (el giro del campo los desplaza respecto al cielo, como un *dithering* natural).
- Memoria **preasignada** y reutilizada, sin crear objetos por foto para no sobrecargar el recolector de basura.

### 2.8 Revelado (solo para verlo; los datos no se tocan)
1. **Gradiente**: muestras de fondo en una cuadrícula (sin estrellas ni nebulosa) y una superficie suave (polinomio 2D de grado 2–3) que se resta. Quita la contaminación lumínica, la luna y el viñeteado del ocular.
2. **Auto-estirado**: función de tonos medios calculada con la mediana y el MAD, para que el fondo quede gris oscuro y las nebulosas salgan sin quemar las estrellas. Brillo y contraste ajustables desde el iPhone.
3. Reducción de ruido suave (opcional) **solo en la vista previa**.
4. **Vista previa** JPEG de ≤ 1600 px con el número de fotos, el tiempo total y la nitidez.

---

## 3. Que el móvil no se sature (presupuesto de recursos)

| Recurso | Estrategia |
|---|---|
| **CPU** | Un hilo dedicado de **baja prioridad** para el procesado. El control de la montura, el STOP y el servidor no esperan nunca por él. Objetivo: procesar cada foto en **menos del 30 % de su exposición** (≤ 5 s para 15 s). |
| **Cola** | Máximo **1 foto pendiente**. Si el procesado no llega a tiempo, se avisa y se pausa la captura en vez de acumular. |
| **Memoria** | Buffers float32 preasignados. Mono 20 MP agrupado 2×2 (5 MP): unos 6 buffers de 20 MB, **≈ 120 MB**. Sin agrupar (20 MP): ≈ 480 MB, que **no se recomienda**. El agrupado 2×2 es el valor por defecto. |
| **Temperatura** | Se vigila el estado térmico de Android. Si sube, la app aumenta el agrupado, refresca la vista previa con menos frecuencia, añade pausas entre fotos y **avisa**. Un móvil caliente también tiene más ruido: lo notaría la imagen. |
| **Batería** | Nivel y temperatura en la app. Aviso al 20 % y estimación del tiempo restante. Pantalla apagada siempre que se pueda. Recomendable una batería externa. |
| **Almacenamiento** | Guardar las fotos RAW (DNG) es **opcional**, para procesarlas luego en el ordenador con Siril. Se comprueba el espacio libre. |
| **Red** | Solo viaja la vista previa JPEG (≈ 200–400 KB) cuando hay una nueva, nunca las RAW. |

Se medirá con **pruebas de rendimiento** en el PC (tests) y luego en el P20 Pro: tiempo por etapa, memoria máxima y temperatura en una sesión de 30 minutos.

---

## 4. Ayudas para el usuario (lo que hace que sea usable de noche)
- **Asistente de enfoque** (detallado en §4·0): nitidez de las estrellas en grande, gráfica en V y marca del mejor punto.
- **Asistente de darks**: "Tapa el telescopio" → 15 fotos → biblioteca guardada.
- **Asistente de encuadre**: fotos rápidas en vivo para centrar el objeto antes de apilar.
- **Integración con el cerebro**:
  - Un GoTo nuevo propone empezar otro apilado.
  - Mover la montura o pulsar STOP pausa la captura.
  - Al alinear las estrellas de cada foto se mide la deriva, que más adelante se puede usar para **corregir el seguimiento** (un autoguiado sin cámara guía).

---

## 4 · 0. Automático o manual: ruido, enfoque, zoom y exposición
**Decisión: todo en Auto por defecto, con Manual disponible en cada ajuste** (se recuerda por cámara).

| Ajuste | Auto (por defecto) | Manual |
|---|---|---|
| Cámara | Recomendación para EAA (monocromo y RAW si los hay) | Cualquiera de la lista |
| Exposición e ISO | Calculadas con medidas del sensor y del cielo, con explicación | Deslizadores hasta el máximo real |
| Enfoque del móvil | Barrido automático al mejor "infinito" | Ajuste fino |
| Enfoque del telescopio | Asistente que mide y guía | Asistente opcional |
| Filtro de fotos | Conservador | Normal / desactivado |
| Suavizado visual | Según la SNR del apilado | Desactivado / bajo / medio / alto |
| Brillo y contraste | Auto-estirado | Deslizadores |
| Agrupado | Según memoria y temperatura | 1×1 / 2×2 / 3×3 |
| Dithering | Activado con montura | Activado / desactivado |

La limpieza de los datos y el apilado no son configurables: siempre activos. En manual se siguen mostrando las medidas como guía.

**Criterio general:** todo arranca en **Auto** y cada ajuste tiene su interruptor **Auto ⟷ Manual**. Los automáticos se basan en **medidas** de las propias fotos (estrellas, fondo, ruido). Lo elegido se guarda **por cámara**.

### Ruido: dos niveles muy distintos
1. **Ruido de los datos: siempre automático, es el corazón del sistema.** Lo eliminan la limpieza (negro, darks, píxeles calientes), el apilado con rechazo sigma y el dithering. No tiene botones porque siempre conviene. Es **ruido real que desaparece**, no maquillaje.
   - La reducción de ruido del fabricante se **apaga**: borra estrellas débiles y engaña a la alineación.
2. **Suavizado visual (solo en la vista y al exportar; los datos apilados nunca se tocan):**
   - **Auto** (por defecto): la intensidad depende de la SNR medida del apilado. Al principio, con pocas fotos, suaviza más; según se acumulan, cada vez menos hasta casi nada. **Protege las estrellas**: solo actúa en el fondo y en las zonas débiles, con una máscara por nivel de señal.
   - **Manual**: Desactivado / Bajo / Medio / Alto.
   - Técnica: reducción de ruido multiescala (*wavelets* à trous), que suaviza el grano fino sin borrar estrellas ni detalle de las nebulosas.

### Enfoque: hay dos y se tratan por separado
1. **El objetivo del móvil: automático y fijo.** Se bloquea en **infinito** (enfoque manual a distancia 0) y no se vuelve a mover en toda la sesión, porque el autofoco "buscando" de noche lo estropearía todo.
   - Ajuste fino manual opcional: en algunos móviles el "infinito" no es exacto. El asistente lo puede probar midiendo la nitidez.
2. **El enfocador del telescopio**: es la ruedecita que giras tú. Sin un motor de enfoque no puede ser automático (y eso queda abierto para el futuro).
   - **Asistente de enfoque**: fotos cortas (1–2 s) en bucle; la app mide la **nitidez de las estrellas (HFR)** y la muestra **en grande**, con una gráfica en forma de V y la marca del **mejor valor alcanzado**: "¡Mejor! · Peor, vuelve atrás · ✓ En foco".
   - Muestra las 3 estrellas más brillantes ampliadas.
   - Más adelante: análisis automático de la **máscara de Bahtinov**, que es muy barata.
   - El enfoque se vigila durante la sesión: si la nitidez empeora (cambio de temperatura), avisa "revisa el enfoque".

### Zoom: tres cosas distintas
1. **Aumento óptico = el ocular que pongas** (físico). Para cielo profundo, la app **recomienda un ocular de pocos aumentos** (por ejemplo el de 25 mm: ~26×): más luz por píxel, más campo, más estrellas para alinear y menos efecto de la turbulencia.
   - El asistente calcula el **campo de visión y la escala** a partir de las estrellas de la imagen.
2. **Zoom de la cámara del móvil**: en captura **nunca hay zoom digital** (solo recorta y no añade información). Se graba siempre el sensor completo en RAW.
   - Para planetas o Luna se puede **elegir la cámara tele** (zoom óptico real), si la hay, desde el selector de cámaras.
   - El agrupado de píxeles (*binning*) es el ajuste útil en lugar del zoom.
3. **Zoom al mirar en el iPhone**: pellizcar sobre la imagen apilada (solo visualización), con encuadre automático al **círculo del ocular**.

### Exposición e ISO
- **Auto (asistente de exposición)**: 2–3 fotos de prueba. Mide el **nivel del fondo** y si las **estrellas brillantes se saturan**, y propone la exposición e ISO que dan más señal/ruido sin quemar:
  - Fondo hacia el 10–20 % del rango.
  - Por encima del ruido de lectura.
  - Dentro del límite de rotación de campo de la montura altacimutal, calculado según hacia dónde apunta.
  - Explica su elección: "15 s · ISO 1600: el fondo queda al 15 % y la rotación de campo es aceptable".
- **Manual**: deslizadores de exposición (hasta el máximo real de la cámara elegida) e ISO, con las mismas medidas en pantalla como guía.
- Durante la sesión, si el cielo cambia (la luna sale, se aclara), el modo Auto **sugiere** reajustar. No cambia solo a mitad de un apilado, porque mezclaría fotos distintas (y los darks dependen de la exposición).

## 4 · 1. Garantía de que el enfoque y la exposición automáticos aciertan

### Enfoque
**Qué es automático y qué no:**
- **Objetivo del móvil → automático de verdad.** La app lo mueve sola: barrido fino alrededor de "infinito" midiendo la nitidez de las estrellas, y se queda en el mejor punto.
- **Enfocador del telescopio → asistido.** Lo giras tú: la app mide y te guía, porque no hay motor. Lo que garantizamos es que **la medida sea fiable**: si la medida es buena, el enfoque al que te lleva es el bueno.

**La medida (HFR, *Half Flux Radius*):**
- Radio que contiene la mitad de la luz de la estrella. Se elige frente a la FWHM porque funciona también **muy desenfocado**: en tu reflector, con el espejo secundario, las estrellas desenfocadas son "donuts" y la FWHM falla con ellos.
- Se usa la **mediana de muchas estrellas** (no una sola) y la **media de varias fotos**: la turbulencia hace "bailar" la nitidez de una foto a otra.
- Se muestra con su **margen de error**. "Mejor" o "peor" solo se anuncian cuando la diferencia supera ese margen; nada de flechas que bailan sin sentido.
- **Mejor punto**: se ajusta una curva en V (hipérbola) a las medidas mientras giras, y "✓ En foco" aparece cuando estás dentro del 5 % del mínimo **de forma estable**.

**Pruebas obligatorias (sintético con desenfoque conocido, estrellas tipo donut, turbulencia y ruido):**

| Prueba | Criterio |
|---|---|
| La HFR crece siempre al alejarse del foco (incluidos donuts) | Monótona en todo el rango |
| Repetibilidad de la medida en foco con turbulencia | Variación < 3 % |
| Pocas estrellas o mucho ruido | La app dice "medida poco fiable" en vez de dar un número falso |
| Enfoque automático del objetivo del móvil | Elige el mejor punto con un error ≤ 1 paso en ≥ 95 % de los casos |
| Asistente del telescopio (simulando que el usuario gira y se pasa) | La marca "mejor punto" coincide con el foco real dentro de la tolerancia |

**Comprobación real:** en las primeras noches se compara la nitidez final con la que da una máscara de Bahtinov (opcional) y con la nitidez del apilado.

### Exposición e ISO óptimos
**La teoría (lo que define "óptimo"):**
- Para un tiempo total fijo, lo mejor es la exposición **más corta** en la que el **ruido del cielo ya tapa el ruido de lectura del sensor**. A partir de ahí, alargar no mejora el resultado y sí añade riesgos: estrellas quemadas, rotación de campo, golpes, menos fotos para descartar.
- Criterio de partida: que la luz del fondo por píxel sea al menos unas **10 veces el cuadrado del ruido de lectura** (pierde < 5 % frente a una exposición infinita). Es ajustable.
- Para eso hacen falta **la ganancia y el ruido de lectura de TU sensor**. La app los **mide sola**:
  - El ruido de lectura con fotos muy cortas tapadas.
  - La ganancia comparando la varianza y la media del fondo de dos fotos iguales (técnica de transferencia de fotones).
  - Se guardan por cámara e ISO.
- **El ISO óptimo** es el más bajo a partir del cual el ruido de lectura deja de bajar. Por encima solo se pierde rango dinámico. Se mide en la calibración y suele coincidir con la ganancia analógica máxima.

**Los límites (lo que impide alargar más):**
- Máximo real de la cámara.
- **Rotación de campo**: se calcula la exposición en la que el borde de la imagen gira menos de 1 píxel, según hacia dónde apunta el telescopio. Cerca del cénit es mucho menor.
- **Deriva del seguimiento**: medida en las fotos anteriores.
- **Estrellas quemadas**: porcentaje de estrellas saturadas aceptable.
- **Calor**: más exposición, más ruido térmico, comprobado con los darks.

**Decisión:** la exposición mínima "que tapa el ruido de lectura" × un margen, recortada por esos límites. La app **explica cuál de ellos manda** ("limitado por la rotación de campo: 12 s").

**Pruebas obligatorias (simulador de sensor con ganancia, ruido de lectura, corriente oscura y cielo conocidos):**
- Para cada escenario (cielo oscuro, ciudad, luna, objeto débil, estrella brillante cerca, apuntando al cénit) se busca por **fuerza bruta** la exposición e ISO que dan la **mejor señal/ruido por minuto de sesión**.
- **Criterio: la elección automática debe quedar a menos del 10 % de ese óptimo en todos los escenarios.**
- La medida automática de ganancia y ruido de lectura debe acertar dentro del ±10 %.

**Comprobación real: "Optimizar exposición (2 min)".**
- Un botón que prueba en la noche real 3–4 combinaciones alrededor de la elegida.
- Mide con las propias fotos la **señal/ruido por minuto** y la redondez de las estrellas, y se queda con la mejor.
- Así el "óptimo" no depende solo de la teoría, sino de tu cielo, tu móvil y tu telescopio de esa noche.

## 4 bis. Modos de uso: control, cámara o los dos (cambio con un toque)
La versión **Imaging** permite tres formas de uso:

| Modo | Para qué | Qué hace falta |
|---|---|---|
| **Solo control** | Observar por el ocular | El cable al mando (como ahora) |
| **Solo cámara** | Apilar con **cualquier** montura: otra montura, ecuatorial o incluso sin motor | Solo el móvil en el ocular. La alineación por estrellas no necesita la montura |
| **Control + cámara** | Lo completo: GoTo → encuadre → apilado, con la montura y la imagen colaborando | Cable al mando + móvil en el ocular |

**Cambio fácil entre control e imagen (en el iPhone):**
- La barra inferior tiene una pestaña **Imagen** junto a Cielo, Mover, Buscar y Alinear. Se cambia con un toque y el apilado **sigue funcionando en segundo plano** en el Android mientras miras otras pestañas.
- En las pestañas de control aparece una **miniatura del apilado** en vivo (tocarla abre Imagen).
- En la pestaña Imagen hay una **mini-cruceta desplegable** para retocar el encuadre sin salir, con el **STOP** siempre visible.
- Si no hay telescopio conectado, las pestañas de control se ocultan solas y la app queda en "solo cámara". Si no hay cámara activa, la pestaña Imagen se oculta.
- Los dos usos comparten la misma clave y el mismo QR. Una persona puede mover el telescopio mientras otra mira la imagen.

## 4 quater. Interfaz: una sola app que se adapta, bonita y limpia
**Principios:**
1. **Una app, no dos.** No hay que elegir "app de control" o "app de cámara": la interfaz **se adapta a lo que hay conectado** (telescopio, cámara o los dos).
2. **La imagen es la protagonista.** En la pestaña Imagen, la foto ocupa toda la pantalla y los controles flotan encima, discretos.
3. **Divulgación progresiva.** En Auto solo se ve lo esencial; los deslizadores aparecen al pasar a Manual. Nunca más de una acción principal por pantalla.
4. **Coherencia.** El mismo STOP flotante, el mismo modo nocturno rojo, las mismas fichas y hojas inferiores en toda la app.

**Inicio ("¿Qué hacemos hoy?"):** tres tarjetas, Observar (solo control), Fotografiar (solo cámara) y Las dos cosas. Se **elige sola** según lo conectado; esta pantalla solo aparece si hay dudas (la primera vez o con todo conectado). Se puede cambiar en cualquier momento desde Más.

**Barra inferior (máximo 5 pestañas, como iOS):**

| Modo | Pestañas |
|---|---|
| Observar | Cielo · Mover · Buscar · Más |
| Fotografiar | Imagen · Enfoque · Más |
| Las dos cosas | Cielo · Mover · **Imagen** · Buscar · Más |

- **Alinear deja de ser pestaña**: es una tarea de una vez por noche. Aparece como **aviso en Cielo y Mover** ("Sin alinear · Alinear ahora") que abre el asistente a pantalla completa. Cuando ya está alineado, desaparece; sigue accesible desde Más.
- El **apilado sigue en marcha** aunque cambies de pestaña. En Cielo y Mover se ve una **miniatura en vivo** (arriba a la derecha) con el número de fotos; tocarla lleva a Imagen.

**Pestaña Imagen:**
- Imagen de borde a borde sobre negro, con zoom con los dedos. **Un toque oculta o muestra los controles** para verla limpia.
- Arriba, pastillas pequeñas: objeto · nº de fotos · tiempo total · **ganancia en magnitudes**.
- Abajo, una barra flotante con 4 iconos: **Pausa/Reanudar · Ajustes · Encuadre · Guardar**.
  - **Ajustes** abre una hoja inferior: Auto/Manual, exposición, ISO, cámara, filtro, suavizado, brillo y contraste.
  - **Encuadre** despliega una mini-cruceta (solo si hay montura).
- Al deslizar hacia arriba sale una hoja con **gráfica de la sesión**, **comparador 1 foto ↔ apilado**, fotos **descartadas** (motivo + Recuperar) y nitidez.
- Arriba a la izquierda, cuando procede: "Enfoque" o "Darks pendientes" como acceso directo a esos asistentes.

**Estilo visual:**
- Oscuro por defecto, tipografía del sistema (SF), barras translúcidas, esquinas redondeadas y **un solo color de acento**. Sin adornos.
- **Modo nocturno rojo** en todo, incluida la imagen: se muestra en tonos rojos para no perder la adaptación a la oscuridad. Se puede ver en color o gris al exportar.
- Animaciones cortas y suaves: cambios de pestaña, la imagen "aclarándose" al llegar cada foto.
- Estados vacíos que guían: "Apunta a un objeto y pulsa Empezar" en lugar de pantallas en blanco.

**Se prototipa primero en el devserver** (cámara y montura simuladas) y se prueba en el iPhone antes de darlo por bueno.

## 4 ter. Garantía de que el apilado mejora de verdad
No basta con que "parezca" mejor: se **mide**, en los tests y en vivo.

**Qué es físicamente posible (para no prometer de más).** El ruido baja con √N:

| Integración | Mejora de señal/ruido | Estrellas/nebulosas más débiles visibles |
|---|---|---|
| 4 fotos | ×2 | ~+0,75 magnitudes |
| 16 fotos | ×4 | ~+1,5 mag |
| 64 fotos (16 min a 15 s) | ×8 | ~+2,25 mag |
| 256 fotos (≈1 h a 15 s) | ×16 | ~+3 mag |

Lo que manda es el **tiempo total de integración**. La contaminación lumínica, la turbulencia y el seguimiento ponen techo, pero la mejora entre 1 foto y 15–30 minutos es enorme: objetos invisibles en una foto suelta pasan a verse con claridad.

**Pruebas obligatorias sobre cielo sintético (con la "imagen perfecta" conocida):**
1. **El ruido baja como debe**: el error respecto a la imagen perfecta cae como 1/√N (N = 1, 4, 16, 64, 256), con tolerancia de ±10 %. Si se aleja de esa curva, algo está mal (mala alineación, pesos o rechazo).
2. **Aparece lo invisible**: se inyecta una galaxia y una nebulosa débiles con SNR 0,5 por foto (invisibles). Tras 64 fotos deben detectarse con SNR ≥ 4.
3. **No se emborrona**: la FWHM de las estrellas del apilado debe quedar en **≤ 1,1 ×** la mediana de las fotos sueltas. La alineación no puede engordar las estrellas.
4. **Cero artefactos**:
   - Sin estrellas dobles.
   - Sin trazas de satélites.
   - Sin **ruido "en arcos"**: los píxeles calientes, con la rotación de campo, dibujarían arcos si no se eliminan bien.
   - Sin bordes duros por la rotación.
5. **Robustez**: lo mismo con nubes intermitentes, una deriva del seguimiento, un golpe a mitad de sesión y la luna saliendo (gradiente cambiante).
6. **Comparación con software de referencia**: con tus RAW reales de las primeras noches, el resultado se compara con **Siril** (gratuito, el estándar). Objetivo: SNR y FWHM al menos al **90 %** de los de Siril procesando las mismas fotos.

**En vivo, para que lo veas tú (pestaña Imagen):**
- **Comparador "1 foto ↔ apilado"**: deslizas y ves la foto suelta frente al apilado del mismo campo. Es la prueba más honesta de que funciona.
- **Gráfica de la sesión**: SNR estimada subiendo y ruido de fondo bajando foto a foto, más la **ganancia en magnitudes** ("+1,8 mag respecto a una foto").
- Nitidez (FWHM) por foto y del apilado, para ver que no se emborrona.
- Si la mejora se estanca (contaminación lumínica, nubes), la app lo dice en vez de seguir sumando sin sentido.

**Una ventaja que solo tenemos nosotros: dithering con la montura.** Como StarBridge controla el telescopio, cada pocas fotos puede mover el encuadre unos píxeles al azar (*dithering*). Es la técnica profesional para eliminar el ruido fijo del sensor y los arcos de los píxeles calientes. Se activa en las fases I4/I5 y se mide con la prueba 4.

## 5. Cómo se valida (antes de salir de noche)
**Cielo sintético** (módulo `imaging/sim`) con:
- Estrellas con forma realista (PSF de Moffat), magnitudes realistas y ruido de fotones y de lectura.
- Fondo con gradiente y viñeteado de ocular, píxeles calientes fijos en el sensor y rayos cósmicos.
- Satélites, giro y desplazamiento conocidos, y fotos malas (nubes, viento, desenfoque).

**Tests con números:**

| Prueba | Criterio |
|---|---|
| Centroides | Error < 0,1 px con señal/ruido > 20 |
| Registro (con giro de campo y desplazamiento) | Error < 0,1 px en toda la imagen |
| Emparejado ciego tras un "golpe" de 200 px y 10° | Recupera la alineación |
| SNR del apilado | Crece ≈ √N (N = 4, 16, 64) |
| Satélite | Desaparece tras 5+ fotos |
| Píxeles calientes | Desaparecen |
| Fotos con nubes densas, viento, golpe y desenfoque | Se descartan (≥ 95 %), con el motivo correcto |
| Fotos buenas pero débiles o con pocas estrellas, neblina fina | **0 descartes** (entran con su peso) |
| Filtro activado frente a desactivado | SNR y FWHM iguales o mejores en todos los escenarios |
| Rendimiento | Tiempo por foto de 5 MP en el PC, de referencia para el móvil |

Después, **devserver con cámara simulada**: ver el apilado en el navegador del PC.

---

## 6. Fases

| Fase | Contenido | Hecho cuando… |
|---|---|---|
| **I0** | Diagnóstico y descubrimiento de cámaras (todas las físicas y lógicas), en la app actual | Ficha de cada cámara del P20 Pro: exposición máxima real, RAW, monocromo, ISO, pantalla apagada |
| **I1** | `imaging`: cielo sintético, detección, registro y control de calidad, con tests | Todos los criterios de §5 de registro y detección en verde |
| **I2** | Calibración (negro, darks, píxeles calientes, máscara del ocular), apilado con rechazo y revelado | Criterios de SNR, satélite y píxeles calientes en verde, con rendimiento medido |
| **I3** | Pestaña Imagen en el iPhone + devserver con cámara simulada | Apilado en directo visible en el navegador |
| **I4** | Captura real Camera2 RAW en el P20 Pro + asistentes de enfoque y darks + gestión térmica | Primera imagen real apilada en el iPhone |
| **I5** | Color, flats, exportar (PNG 16 bit, FITS, DNG) | Sesión completa guardada |
| **I6** (opc.) | Plate solving + corrección de seguimiento por imagen | Alineación de la montura sin estrellas manuales |

## 7. Riesgos y planes B
| Riesgo | Plan B |
|---|---|
| Camera2 no deja exposiciones largas | Muchas fotos cortas (0,5–1 s) apiladas: el bajo ruido de lectura del móvil lo hace viable, aunque peor |
| No se puede usar la cámara monocromo por separado | La principal en RAW con debayer superpíxel; en luminancia funciona igual |
| No da RAW | YUV sin procesado (reducción de ruido apagada) como último recurso |
| No captura con la pantalla apagada | Pantalla al mínimo y en negro (modo nocturno) con la app en primer plano |
| Demasiado lento | Más agrupado, región de interés (solo el círculo del ocular) y vista previa con menos frecuencia |
| Calor | Pausas entre fotos y aviso; en verano, sesiones más cortas |
