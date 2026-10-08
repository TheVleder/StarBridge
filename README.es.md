# StarBridge

**Controla un telescopio Celestron NexStar desde cualquier móvil, tablet u ordenador. Sin la app de Celestron, sin adaptador WiFi y sin ordenador: basta un cable y un Android viejo.**

*[Read in English](README.md)*

Un Android viejo (o un PC con Windows) se enchufa al mando del telescopio con un cable USB y se convierte en su **«cerebro»**. Sirve una web por tu WiFi local: ábrela en un iPhone, un Android o cualquier navegador y podrás mover el telescopio, hacer GoTo a miles de objetos, alinearlo con una o dos estrellas, compensar la holgura de los engranajes e incluso **apilar fotos en directo (EAA)** con el móvil en el ocular. En el campo no hace falta internet.

> ⚠️ StarBridge es un proyecto aficionado probado en un telescopio (NexStar 130 SLT con el mando clásico). Vigila el telescopio mientras se mueve y **no lo apuntes nunca al Sol** sin un filtro solar adecuado.

---

## Por qué StarBridge

**Funciona por sí solo.** De Celestron no necesitas nada más que el telescopio: ni el módulo WiFi SkyPortal, ni su app, ni una cuenta. Tampoco hay que montar un portátil, una Raspberry Pi o una conexión a internet. Solo el cable del mando y un móvil que seguramente ya tienes en un cajón.

**Un Android como cerebro del telescopio.** Hasta donde hemos podido comprobar, nadie más lo ofrece. Hoy un Android solo puede participar como parte de una cadena:

- **Una app puente USB→red más una app de planetario.** Por ejemplo, *BT/USB/TCP Bridge Pro* con Stellarium Mobile PLUS o SkySafari. Son dos apps (de pago) y el móvil solo reenvía bytes.
- **Un cliente INDI** (como Telescope.Touch). Necesita un ordenador o una Raspberry Pi junto al telescopio con el servidor INDI.
- **SkySafari por sí solo.** Sus desarrolladores han dicho que no controla el telescopio por el USB de Android.

StarBridge convierte el propio móvil en el sistema completo:
- lee el GPS (lugar y hora) y sus sensores;
- alinea el telescopio con su propio modelo de puntería, sin alinear con el mando y sin nivelar el trípode;
- calcula el GoTo y el seguimiento, y compensa la holgura;
- sirve el mando a **cualquier** dispositivo como una web, así que el iPhone no necesita ninguna app;
- puede apilar fotos en directo con ese mismo móvil en el ocular.

Es gratis y de código disponible. Si conoces algo parecido, abre un *issue* y lo corregimos encantados.

## Telescopios compatibles

StarBridge habla el **protocolo del mando NexStar**, el que Celestron usa en todas sus monturas computerizadas desde la NexStar GPS (2001). Se conecta por el puerto serie/USB del mando, no por el puerto AUX de la montura. Por ahora solo se ha probado en real en la **NexStar 130 SLT** (mando clásico, firmware 5.x). Se agradecen informes de otros modelos.

| Soporte | Monturas | Cómo |
|---|---|---|
| ✅ **Completo** (altacimutales) | NexStar **SLT** (102, 114, 127, 130 SLT…), NexStar **SE** (4SE, 5SE, 6SE, 8SE), NexStar **Evolution**, **CPC** / CPC Deluxe, NexStar **GT**, **i-Series** (5i, 8i), NexStar **GPS**, **SkyProdigy**, **LCM**, **Cosmos**, **StarSeeker** | StarBridge se alinea solo (1–2 estrellas, sensores, base inclinada), hace el GoTo y el seguimiento y recoge la holgura. También puedes usar la alineación del propio mando. |
| 🟡 **Modo mando** (ecuatoriales) | **Advanced VX** (AVX), **Advanced GT** (CG-5), **CGEM** / CGEM II / CGEM DX, **CGE** / CGE Pro, **CGX** / CGX-L | Alinea con el mando como siempre; StarBridge hace el GoTo con esa alineación, sigue en ecuatorial y te da todo el control remoto. La alineación propia de StarBridge es solo altacimutal. |
| ❌ **No compatibles** | La NexStar 5/8 original (1999) y la Ultima 2000 (un protocolo más antiguo); telescopios sin mando NexStar (p. ej. StarSense Explorer, que no tiene motores); monturas de otras marcas | El apilado en directo funciona igual con cualquier montura: pon el móvil en el ocular. |

### Lista completa de telescopios Celestron compatibles

**Altacimutales (soporte completo: StarBridge se alinea, apunta y sigue él solo)**
- **Celestron NexStar SLT:** NexStar 90SLT, NexStar 102SLT, NexStar 114SLT, NexStar 127SLT, NexStar 130SLT
- **Celestron NexStar SE:** NexStar 4SE, NexStar 5SE, NexStar 6SE, NexStar 8SE
- **Celestron NexStar Evolution:** NexStar Evolution 6, NexStar Evolution 8, NexStar Evolution 8 HD (EdgeHD), NexStar Evolution 9.25
- **Celestron CPC y CPC Deluxe:** CPC 800, CPC 925, CPC 1100, CPC Deluxe 800 HD, CPC Deluxe 925 HD, CPC Deluxe 1100 HD
- **Celestron NexStar GPS:** NexStar 8 GPS, NexStar 11 GPS
- **Celestron NexStar i-Series:** NexStar 5i, NexStar 8i
- **Celestron NexStar GT:** NexStar 60GT, NexStar 80GT, NexStar 80GTL, NexStar 102GT, NexStar 114GT (los mandos con firmware anterior a 2.2 tienen menos funciones)
- **Celestron SkyProdigy:** SkyProdigy 70, SkyProdigy 90, SkyProdigy 102, SkyProdigy 130
- **Celestron LCM:** LCM 60, LCM 80, LCM 114
- **Celestron Cosmos:** Cosmos 90GT WiFi

**Ecuatoriales alemanas (modo mando: alinea con el mando y StarBridge hace el resto)**
- **Celestron Advanced VX (AVX):** la montura sola y sus kits (AVX 6" y 8" SCT, AVX 8" EdgeHD, AVX 6" refractor, AVX 8" Newton…)
- **Celestron Advanced GT (CG-5 GT):** la montura y sus kits (C6-SGT, C8-SGT, C9.25-SGT, C11-SGT…)
- **Celestron CGEM, CGEM II y CGEM DX:** las monturas y sus kits (CGEM 800, 925, 1100, CGEM II 800/925/1100, EdgeHD…)
- **Celestron CGE y CGE Pro**
- **Celestron CGX y CGX-L**

Si tu telescopio tiene un mando Celestron NexStar o NexStar+ y no está en la lista, es muy probable que también funcione. Pruébalo y cuéntanoslo.

StarBridge es una alternativa gratuita al **módulo WiFi Celestron SkyPortal**, a SkyFi, a StarFi y a otros adaptadores WiFi. Te permite controlar un telescopio Celestron desde un móvil Android, un iPhone o un PC solo con un cable USB, sin hardware extra.

**Mando:** se recomienda firmware **2.2 o superior**; aparece en el mando al encenderlo. Las versiones anteriores no tienen las órdenes de posición precisa. El mando **NexStar+** también vale, por su puerto mini-USB.

## Qué necesitas

| | |
|---|---|
| **Telescopio** | Una montura GoTo Celestron NexStar con su mando: mira [Telescopios compatibles](#telescopios-compatibles). |
| **Cable** | Cable USB → serie para el mando: un cable **RJ9/RJ22 4P4C** al puerto de la base del mando clásico (p. ej. chip PL2303GT). También valen los chips FTDI, CP210x y CH34x. Los mandos NexStar+ tienen su propio puerto mini-USB. |
| **Cerebro** — opción A | Un móvil **Android 10 o superior** con **USB OTG** (USB‑C o un adaptador OTG USB‑A→USB‑C/micro‑USB). |
| **Cerebro** — opción B | Un PC con **Windows 10/11 (64 bits)**. No hay que instalar nada más: Java va incluido. |
| **Mando** | Cualquier móvil, tablet u ordenador con un navegador moderno (Safari de iPhone 13.1+, Chrome de Android…) en la misma WiFi. |

Solo el que tiene el cable es el cerebro; todos los demás son mandos. Si un Android y un PC están en la misma WiFi, se encuentran solos.

## Descargar

Descarga los ficheros de la última versión en **[Releases](../../releases/latest)**:

- `StarBridge-<versión>.apk` — la app de Android.
- `StarBridge-PC-<versión>-windows.zip` — el programa de Windows (portátil, sin instalador).

## Instalar en Android

1. Descarga el `.apk` en el móvil y ábrelo. Android pedirá **permitir instalar apps de este origen** (el navegador o el gestor de archivos): permítelo. Es lo normal con apps que no vienen de Google Play.
2. Abre **StarBridge**. La primera vez pide el **idioma** (Español / English) y enseña una lista corta de lo que hace falta.
3. Enchufa el cable al móvil (con el adaptador OTG si hace falta) y enciende el telescopio. **La app se abre sola**: acepta el permiso USB («usar siempre») y el de ubicación (el GPS da la hora y tu posición).
4. Pulsa **«Permitir la excepción de batería»** (menú ⋮ › Batería). Huawei, Xiaomi, Samsung… cierran las apps en segundo plano si no.

> **NO hace falta la depuración USB, ni las opciones de desarrollador, ni root.** La depuración USB solo la usan los programadores para instalar la app desde un ordenador. Para usarla basta con instalar el APK.

## Instalar en Windows

1. Descomprime `StarBridge-PC-<versión>-windows.zip` donde quieras (p. ej. Documentos) y haz doble clic en **`StarBridge.exe`**.
   - Windows SmartScreen puede avisar de un editor desconocido (el programa no está firmado): pulsa **Más información → Ejecutar de todas formas**.
2. La primera vez pide el **idioma** y explica lo que hace falta.
3. **Driver del cable:** los cables PL2303 necesitan el [driver de Prolific](https://www.prolific.com.tw/) (Windows Update suele instalarlo al enchufar el cable). Los FTDI, CP210x y CH34x suelen instalarse solos. Si hay varios puertos COM, elige el del cable en **Ajustes**.
4. Cuando el Firewall de Windows pregunte, **permite el acceso en redes privadas** para que los móviles puedan conectarse.
5. StarBridge sigue funcionando como un **icono junto al reloj**: desde ahí vuelves a abrir el panel, enseñas el QR para el móvil o sales (al salir se para el telescopio).

¿Prefieres un instalador de verdad? Mira [Compilar](#compilar) (`packaging/windows/StarBridge.iss`, Inno Setup).

## La primera noche

1. Enciende el telescopio. **No hace falta alinear con el mando.**
2. Pon el cerebro y el mando en la **misma WiFi**. En el campo, sin router, crea un **punto de acceso en el iPhone** y conecta a él el Android o el PC (sin SIM ni internet).
3. **Escanea el QR** que enseña el cerebro (pantalla del Android, o en el PC: Ajustes › Conectar el iPhone / icono del reloj) con la cámara del móvil. El enlace lleva un **código de acceso**: sin él nadie más en la red puede manejar el telescopio. Añádelo a la pantalla de inicio para la próxima vez.
4. Pulsa **Alinear**: StarBridge propone una estrella brillante, hace el GoTo, la centras con las flechas y pulsas **«Está centrada»**. Con una segunda estrella, precisión completa (el trípode no tiene que estar nivelado).
5. Busca (`M31`, `31`, `andromeda`, `saturno`…) y pulsa **Ir**. El botón **STOP** está siempre a la vista y, si el mando pierde la WiFi, el telescopio **se para solo en menos de un segundo**.
6. Con el Android como cerebro ya puedes apagar su pantalla o usar **Pantalla negra** (menú ⋮).

## Qué hace

- **Mapa del cielo** en tiempo real con la posición del telescopio; toca un objeto para ir a él.
- **Buscar** en todo el NGC y el IC (~12.100 objetos de cielo profundo, con Messier, Caldwell, Barnard…), más de 41.000 estrellas hasta magnitud 8, la Luna y los planetas, sin internet. Vale cualquier designación: `M31`, `31`, `NGC 224`, `PGC 2557`, `α CMa`, `61 Cyg`, `HIP 32349`, `HD 48915`; se perdonan las erratas. Las listas enseñan lo que alcanza **tu** telescopio (eliges su abertura) y el mapa enseña estrellas y galaxias más débiles al hacer zoom.
- **Modelo de puntería propio**: alineación con 1–N estrellas, base inclinada, GoTo en dos tramos y seguimiento por velocidad variable. También funciona con la alineación del mando.
- **Compensación de la holgura**, medida con el giroscopio del móvil o centrando una estrella dos veces.
- **Apilado en directo (EAA)** con el Android en el ocular: exposiciones largas en RAW, calibración, registro, control de calidad y apilado automáticos; guarda FITS/TIFF (lineales) + JPEG.
- **Panel del PC**: mapa grande con constelaciones, control con teclado, GoTo a coordenadas, gráficas del seguimiento, consola, zonas de cielo visibles, varias ventanas.
- **Modo nocturno rojo**, en español e inglés.
- **Diagnóstico**: qué ha contestado el mando, para cuando algo no se mueve.

## Preguntas frecuentes

**¿Cómo controlo mi telescopio Celestron con el móvil?**
Instala StarBridge en un móvil Android y conéctalo al mando del telescopio con un cable USB. Después escanea el QR que enseña con cualquier otro móvil (también un iPhone) y tendrás el mando en el navegador. También puedes usar el propio Android como mando.

**¿Puedo usar mi Celestron NexStar sin el módulo WiFi SkyPortal?**
Sí. StarBridge sustituye al módulo WiFi SkyPortal (y a SkyFi, StarFi y adaptadores parecidos). Solo necesitas un cable USB para el mando.

**¿Hay una alternativa gratis al adaptador WiFi de Celestron?**
StarBridge es gratis para uso personal y cualquier otro uso no comercial. Si ya tienes un Android viejo, lo único que hay que comprar es el cable (unos 10–20 €).

**¿Puedo manejar un telescopio Celestron con un Android por USB?**
Sí, es justo lo que hace StarBridge. Vale cualquier Android 10 o superior con USB OTG; casi todos los móviles de los últimos años lo tienen. Puede que necesites un pequeño adaptador OTG de USB‑A a USB‑C.

**¿Puedo controlar mi Celestron NexStar con un iPhone?**
Sí, como mando. El iPhone no puede manejar el cable, así que al telescopio tiene que ir enchufado un Android o un PC con Windows. El iPhone abre el mando en Safari, sin instalar ninguna app.

**¿Cómo conecto mi Celestron NexStar al portátil o a un PC con Windows?**
Enchufa el cable USB al mando y al PC, instala el driver del cable si Windows lo pide y abre StarBridge para Windows. Encuentra el puerto solo y abre el panel de control.

**¿Qué cable necesito para conectar el mando NexStar al móvil o al PC?**
Un cable USB → serie con conector RJ9/RJ22 (4P4C, como el del auricular de un teléfono fijo antiguo) para el puerto de la base del mando. Valen los que llevan chip Prolific PL2303, FTDI, CP210x o CH340. Los mandos NexStar+ tienen un puerto mini-USB, así que basta un cable USB normal. Va enchufado al mando, **no** al puerto AUX de la montura.

**¿Tengo que alinear el telescopio con el mando primero?**
No. Solo enciende el telescopio. StarBridge te propone una estrella brillante, apunta a ella y tú la centras con las flechas. Con una estrella ya funciona y con dos tienes precisión completa, aunque el trípode no esté nivelado. (Las monturas ecuatoriales se alinean con el mando, como siempre.)

**¿Funciona sin internet, en el campo?**
Sí. Todo funciona sin conexión. Sin router, activa el punto de acceso del iPhone y conecta a él el Android o el PC; no se gastan datos ni hace falta SIM.

**¿Con qué telescopios Celestron funciona?**
Con todos los NexStar GoTo con mando: SLT, SE, Evolution, CPC, GT, GPS y más. Las monturas ecuatoriales (AVX, CGEM, CGX…) funcionan en modo mando. Mira [Telescopios compatibles](#telescopios-compatibles).

**Soy principiante y no me llevo bien con la tecnología. ¿Es difícil?**
Está pensada para eso. La app te pregunta el idioma, te enseña una lista corta de lo que hace falta y se abre sola al enchufar el cable. No hace falta la depuración USB, ni las opciones de desarrollador, ni root, ni un ordenador.

**El telescopio no se mueve o el mando no contesta. ¿Qué miro?**
- Que el telescopio esté encendido y el cable vaya al **mando** (puerto de abajo), no al puerto AUX de la montura.
- En Android: acepta el permiso USB. Algunos móviles (Xiaomi, OnePlus, Honor…) tienen un interruptor **OTG** en Ajustes que hay que activar.
- En Windows: instala el driver del cable y, si hay varios puertos COM, elige el bueno en Ajustes.
- *Más → Diagnóstico* enseña exactamente qué ha contestado el mando.

**¿Puedo hacer fotos de galaxias y nebulosas con el móvil a través del telescopio?**
Sí. Pon el Android en el ocular con un adaptador de móvil y pulsa *Empezar* en la pestaña *Imagen*. StarBridge hace muchas fotos y las apila en directo, y los objetos débiles van apareciendo poco a poco en la pantalla (EAA).

**¿Es seguro? ¿Alguien podría manejar mi telescopio?**
Solo se conectan los dispositivos que tienen el código de acceso del enlace del QR, y puedes cambiar el código cuando quieras. Además, el telescopio se para solo si el mando pierde la conexión.

**¿Es gratis? ¿Puedo venderla?**
Es gratis para usarla, compartirla y modificarla con fines no comerciales. Venderla o usarla comercialmente requiere permiso del autor ([licencia](LICENSE.md)).

## Compilar

Requisitos: **JDK 17**; para la app de Android, el Android SDK (`local.properties` con `sdk.dir`).

```bash
./gradlew :imaging:test :core:test :server:test :desktop:test   # tests
./gradlew :app:assembleRelease          # APK → app/build/outputs/apk/release/
./gradlew :desktop:packageWindows       # zip de Windows → build/release/ (en Windows)
./gradlew :devserver:run                # la web con un telescopio simulado → http://localhost:8099
```

- **Firma de la versión:** pon tu propio almacén de claves en `~/.starbridge-release/` (`keystore.properties` con `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) o define `STARBRIDGE_STORE_FILE`, `STARBRIDGE_STORE_PASSWORD`, `STARBRIDGE_KEY_ALIAS` y `STARBRIDGE_KEY_PASSWORD`. Sin ellos, el APK de release se firma con la clave de debug. No subas nunca un almacén de claves al repositorio.
- **Instalador de Windows:** después de `packageWindows`, compila `packaging/windows/StarBridge.iss` con [Inno Setup 6](https://jrsoftware.org/isinfo.php) (`iscc packaging\windows\StarBridge.iss`).
- **Traducciones:** el texto original de la interfaz está en español; `python tools/i18n/build.py` regenera `app/src/main/assets/web/i18n-en.js` a partir de `tools/i18n/en_*.py`.
- CI: `codemagic.yaml` (Codemagic).

La estructura de los módulos está en el [README en inglés](README.md#project-layout) y los detalles técnicos, en `docs/`.

## Licencia

StarBridge es **de código disponible** con la **[PolyForm Noncommercial License 1.0.0](LICENSE.md)**: puedes usarlo, estudiarlo, modificarlo y compartirlo para cualquier fin **no comercial** (uso personal, agrupaciones astronómicas, colegios, investigación…). **No se permite venderlo ni usarlo con fines comerciales** sin permiso del autor. Ver [NOTICE.md](NOTICE.md).

## Créditos

- Catálogo de cielo profundo: [OpenNGC](https://github.com/mattiaverga/OpenNGC), de Mattia Verga — CC BY-SA 4.0.
- Estrellas: [HYG Database v4](https://codeberg.org/astronexus/hyg), de David Nash — CC BY-SA 4.0.
- Figuras de las constelaciones: [d3-celestial](https://github.com/ofrohn/d3-celestial), © 2015 Olaf Frohn — BSD-3-Clause.
- Bibliotecas: Ktor, kotlinx.coroutines/serialization, usb-serial-for-android, jSerialComm (ver [NOTICE.md](NOTICE.md)).

Celestron y NexStar son marcas de Celestron, LLC. StarBridge no está afiliado a Celestron ni cuenta con su respaldo.
