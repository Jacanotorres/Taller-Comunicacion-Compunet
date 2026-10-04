# Guía del equipo — Chat Ice

Documento de traspaso para los integrantes del equipo **y para sus asistentes de IA**.
Explica qué está hecho, cómo funciona, qué falta y las reglas para seguir trabajando.

> **Si eres un asistente de IA:** lee este archivo completo antes de proponer o escribir código,
> y respeta la sección [Instrucciones para asistentes de IA](#9-instrucciones-para-asistentes-de-ia).

Última actualización: 2026-10-03 · Estado: **Etapas 1, 2 y 3 terminadas** (de 6).

---

## 1. Qué estamos construyendo

Taller Evaluativo Unidad 2 de Computación en Internet I (Icesi, 2026-2): una plataforma de chat tipo Discord con **dos canales**:

| Canal | Tecnología | Qué transporta |
|---|---|---|
| Control | ZeroC Ice 3.7 sobre TCP | Sesión, presencia, chat privado y grupal, archivos, señalización de llamadas |
| Voz | `DatagramSocket` UDP puro | Audio de llamadas 1 a 1 y conferencias |

**Reglas del taller que no se pueden romper** (si se rompen, se pierde el módulo completo de la rúbrica):

1. El audio va **solo por UDP**. Prohibido enviarlo por Ice o por TCP.
2. Los archivos van **solo por Ice** con `sequence<byte>` y en fragmentos (chunks). Prohibido usar sockets TCP propios.
3. El estado del servidor debe ser **thread-safe** (`ConcurrentHashMap`, `CopyOnWriteArrayList` o sincronización explícita).
4. Proyecto Gradle multimódulo con el plugin `com.zeroc.gradle.ice-builder.slice`; deben funcionar `./gradlew runServer` y `./gradlew runClient`.
5. Repositorio privado, con **commits de todos los integrantes**.
6. Bitácora de uso de IA obligatoria (ver sección 8).

## 2. Estado actual

| Etapa | Contenido | Requerimiento | Estado |
|---|---|---|---|
| 1 | Proyecto Gradle, contratos Slice, arranque de servidor y cliente | — | ✅ Hecha |
| 2 | Sesión, presencia, chat privado, salas | RF-01, RF-02, RF-03 | ✅ Hecha |
| 3 | Transferencia de archivos por chunks | RF-04 | ✅ Hecha |
| 4 | Llamadas de voz 1 a 1 por UDP | RF-05 | ⏳ Pendiente |
| 5 | Conferencias de voz en salas + robustez | RF-06 | ⏳ Pendiente |
| 6 | README final, cuestionario, bitácora de IA | Entrega | ⏳ Pendiente |

La **etapa 4** no depende de nada pendiente; la 5 depende de la 4.

Peso en la nota de lo que falta: voz 1.2 + parte de concurrencia/documentación/sustentación 0.6.

## 3. Cómo ponerlo a andar

### Requisitos

- **JDK 17 o superior** (probado con JDK 25).
- **ZeroC Ice 3.7** — solo se necesita el compilador `slice2java` en el `PATH`:
  - macOS: `brew tap zeroc-ice/tap && brew install zeroc-ice/tap/ice@3.7` (si Homebrew pide confianza: `brew trust --formula zeroc-ice/tap/ice@3.7`)
  - Windows: instalador MSI de Ice 3.7 desde https://zeroc.com/ice/downloads/3.7/java (sección *Windows Installer* → *Download for Windows*) y agregar su carpeta `bin` al `PATH`
  - Linux: paquete `zeroc-ice-compilers` del repositorio de ZeroC
  - Si el build no lo encuentra: variable de entorno `ICE_HOME` o `./gradlew build -PiceHome=<ruta>`. En Windows, por ejemplo: `.\gradlew.bat build -PiceHome="C:\Program Files\ZeroC\Ice-3.7.11"`
- Gradle **no** hay que instalarlo: se usa el wrapper (`./gradlew`, o `gradlew.bat` en Windows).

### Compilar y ejecutar

```bash
./gradlew build                          # compila todo
./gradlew runServer                      # terminal 1: servidor en el puerto TCP 10000
./gradlew runClient                      # terminal 2, 3, ...: clientes contra localhost
./gradlew runClient -Phost=192.168.1.20  # cliente contra otra máquina
```

Prueba mínima para confirmar que todo sirve: dos clientes, `/login ana` en uno y `/login juan` en el otro, `/msg juan hola` desde ana.

Para lanzar clientes más rápido (sin pasar por Gradle cada vez):

```bash
./gradlew installDist
client/build/install/client/bin/client --Ice.Config=config/client.config
```

### Comandos del cliente que ya existen

`/login <nick>`, `/logout`, `/users`, `/msg <usuario> <texto>`, `/create <sala>`, `/rooms`, `/join <sala>`, `/leave <sala>`, `/members <sala>`, `/room <sala> [texto]`, `/sendfile <usuario|#sala> <ruta>`, `/help`, `/quit`. El texto sin `/` se envía a la sala activa (la que muestra el prompt, ej. `ana #redes>`).

## 4. Arquitectura

```
        CLIENTE A                         SERVIDOR                        CLIENTE B
  ┌──────────────────┐            ┌────────────────────┐           ┌──────────────────┐
  │ CommandLoop (CLI)│──Session──▶│ ChatServiceI       │           │                  │
  │ ChatCommands     │   (Ice/TCP)│ SessionI (1 x user)│           │                  │
  │                  │            │ ChatHub            │──callback─▶│ ClientCallbackI │
  │ ClientCallbackI  │◀─callback──│  ├ UserRegistry    │  (Ice/TCP) │ Console          │
  │ Console          │            │  └ RoomManager     │           │                  │
  └──────────────────┘            └────────────────────┘           └──────────────────┘
        una sola conexión TCP por cliente; los callbacks viajan por esa misma conexión

  (Etapas 4 y 5, pendiente)   audio UDP directo A ⇄ B en llamadas 1 a 1
                              audio UDP A → relay del servidor → demás, en conferencias
```

### Decisiones ya tomadas (no cambiarlas sin hablarlo con el equipo)

- **Patrón de sesión.** `ChatService` solo tiene `login(nickname, callback)`, que devuelve un proxy `Session*` propio del usuario. Todo lo demás se invoca sobre la sesión. Por eso las operaciones **no reciben el nickname del emisor**: el servidor ya lo sabe (`me` en `SessionI`).
- **Callbacks bidireccionales.** El cliente crea un adaptador sin endpoints y lo asocia a su conexión con el servidor (`ClientContext.bindConnection`). El servidor fija el proxy de callback a esa conexión con `callback.ice_fixed(connection)` (`ChatHub.login`). El cliente no abre puertos TCP.
- **Notificaciones asíncronas.** El servidor nunca llama a un cliente de forma síncrona: usa `ChatHub.notify(...)` / `ChatHub.broadcast(...)`, que invocan `...Async`. Así un cliente lento no bloquea un hilo del servidor.
- **Concurrencia.** Estado compartido solo en `UserRegistry` y `RoomManager` (ambos con `ConcurrentHashMap`). Nickname único con `putIfAbsent`; altas y bajas de salas dentro de `computeIfPresent`. `SessionI` y `ChatHub` no guardan estado mutable propio.
- **Detección de caídas.** El cliente envía latidos (ACM). Si el servidor no ve actividad en 15 s, cierra la conexión; el *close callback* (`ChatHub.connectionClosed`) llama a `ChatHub.disconnect`, que es **idempotente**. Todo recurso nuevo que se asocie a un usuario (llamadas, voz) debe liberarse en `ChatHub.disconnect`.
- **Nombres.** Nicknames y salas: 2 a 20 caracteres, sin espacios, sin distinguir mayúsculas (`Names`). Una sala se borra al quedar vacía.
- **Conferencias de voz:** relay centralizado en el servidor (opción *a* del taller), no malla P2P.
- **Interfaz:** CLI (opción A del taller), no JavaFX.

## 5. Mapa del código

### `common/src/main/slice/` — contratos (generan el paquete Java `co.edu.icesi.chat`)

| Archivo | Contenido |
|---|---|
| `Types.ice` | Structs (`ChatMessage`, `RoomInfo`, `FileMeta`, `FileChunk`, `AudioEndpoint`) y excepciones (todas heredan de `ChatException`, que trae `reason`) |
| `Callback.ice` | Interfaz `ClientCallback`: la implementa el **cliente** |
| `Service.ice` | Interfaces `ChatService` y `Session`: las implementa el **servidor** |

Los contratos **ya incluyen** las operaciones de archivos, llamadas y conferencias. Para las etapas 4 y 5 normalmente no hay que tocarlos; si hace falta, avisar al equipo porque afecta a servidor y cliente.

### `common/src/main/java/co/edu/icesi/chat/` — reglas compartidas (escritas a mano, no generadas)

| Clase | Responsabilidad |
|---|---|
| `FileTransferLimits` | Tamaño de chunk (64 KB), tamaño máximo de archivo (256 MB) y `check(chunk)`, que valida un fragmento. Lo usan servidor y cliente |

### `server/src/main/java/co/edu/icesi/chat/server/`

| Clase | Responsabilidad |
|---|---|
| `ChatServer` | `main`: crea el communicator, el adaptador `ChatAdapter` y publica `ChatService` |
| `ChatServiceI` | Servant de `ChatService`; delega el login en `ChatHub` |
| `SessionI` | Servant de `Session`, uno por usuario. **Aquí están los métodos pendientes de las etapas 4 y 5** (lanzan `pending(...)`) |
| `ChatHub` | Núcleo: login, `disconnect`, `notify` y `broadcast` |
| `UserRegistry` | Usuarios conectados (thread-safe) |
| `RoomManager`, `Room` | Salas y sus miembros (thread-safe) |
| `ClientSession` | Datos inmutables de un usuario: nickname, callback, conexión |
| `Names`, `Log` | Validación de nombres y log por consola |

### `client/src/main/java/co/edu/icesi/chat/client/`

| Clase | Responsabilidad |
|---|---|
| `ChatClient` | `main`: conecta, crea el adaptador de callbacks, arranca la CLI |
| `CommandLoop` | Lee el teclado (hilo principal), despacha comandos y traduce excepciones a mensajes |
| `ChatCommands` | Comandos de sesión, mensajes y salas. **Modelo a seguir para los comandos nuevos** |
| `ClientCallbackI` | Servant de `ClientCallback`. Corre en hilos de Ice. **Tiene `TODO` para la etapa 4** |
| `ClientContext` | Estado del cliente: proxies, sesión, nickname, sala activa |
| `Console` | Salida sincronizada: `event()` para lo que llega por callback, `echo()`/`info()`/`error()` para la CLI |
| `FileCommands` | Comando `/sendfile`: valida ruta, tamaño y destino |
| `FileSender` | Lee el archivo en bloques de 64 KB y los envía en un hilo aparte (`file-sender`) |
| `FileAssembler` | Recibe chunks y arma el archivo en `downloads/`. Todo su estado vive en un solo hilo (`file-assembler`) |
| `FileFormat` | Tamaños legibles y SHA-256 |

### Otros

- `config/server.config`, `config/client.config`: propiedades de Ice (endpoints, `Ice.MessageSizeMax`, hilos, ACM).
- `buildSrc/`: clase puente para que el plugin de Ice funcione con Gradle 9. **No tocar** (ver sección 7).

## 6. Detalle por etapa

### Etapa 3 — Transferencia de archivos (RF-04) · ✅ Hecha

`/sendfile <usuario|#sala> <ruta>`. Decisiones tomadas:

- Chunks de **64 KB fijos** (`FileTransferLimits.CHUNK_SIZE`); todos miden eso salvo el último. Así la posición de un chunk es siempre `index * 64 KB` y se escribe bien aunque lleguen desordenados. Máximo 256 MB por archivo. Un archivo vacío viaja como un solo chunk sin datos.
- El servidor valida cada chunk (`FileTransferLimits.check`) y solo reenvía; no guarda nada. Rechaza auto-envíos.
- El emisor envía en un hilo aparte, de a un chunk (síncrono): la CLI no se congela y no se acumulan chunks en memoria.
- El receptor encola los chunks y los escribe un único hilo (`file-assembler`), así los hilos de Ice no tocan disco. Escribe en `downloads/.recibiendo-*.part` y renombra solo al completar con el tamaño correcto. Nunca sobrescribe (`archivo (1).ext`) y limpia el nombre recibido para que nadie escriba fuera de `downloads/`.
- Integridad: tamaño final igual a `totalSize`, y ambos lados imprimen el SHA-256 para comparar. No se agregó hash a `FileMeta` para no tocar los contratos.

Limitaciones conocidas: "Archivo enviado" significa que el servidor recibió todo, no que el receptor terminó (el contrato no tiene confirmación). Si el emisor se cae a mitad, el receptor descarta el archivo incompleto a los 2 minutos o al salir.

### Etapa 4 — Llamadas de voz 1 a 1 (RF-05) · parte de 1.2 puntos

**Objetivo:** `/call <usuario>`, `/accept`, `/reject`, `/hangup`, con audio por UDP directo entre los dos clientes.

Servidor:
- Clase nueva `CallManager` (thread-safe) con el estado de las llamadas: quién llama a quién, timbrando o en curso.
- `SessionI.startCall(to, myAudio)`: validar que ninguno esté ya en llamada (`CallException`), registrar y notificar `incomingCall` al destino.
- `SessionI.answerCall(caller, accept, myAudio)`: notificar `callAccepted` (con el endpoint UDP del que acepta) o `callRejected`.
- `SessionI.hangup()`: terminar la llamada y notificar `callEnded` al otro.
- **Agregar la limpieza en `ChatHub.disconnect`**: si el usuario se cae en medio de una llamada, el otro debe recibir `callEnded`.
- El cliente puede no conocer bien su propia IP. El servidor sí la ve: `((com.zeroc.Ice.IPConnectionInfo) current.con.getInfo()).remoteAddress`. Sirve para completar `AudioEndpoint.host`.

Cliente:
- `AudioSender`: hilo que captura del micrófono (`TargetDataLine`) y envía `DatagramPacket`.
- `AudioReceiver`: hilo dedicado que recibe datagramas y reproduce (`SourceDataLine`).
- `CallCommands` con los cuatro comandos; completar los `TODO` de `ClientCallbackI` (`incomingCall`, `callAccepted`, `callEnded`).
- Formato de audio sugerido: PCM 16 bits, mono, 16 kHz, paquetes de 20 a 40 ms (640 a 1280 bytes). Paquetes pequeños dan baja latencia y caben en un datagrama sin fragmentarse.
- Al colgar: detener los hilos, cerrar el `DatagramSocket` y liberar las líneas de audio (`stop()`, `close()`).

Puntos delicados: en macOS hay que dar permiso de micrófono a la terminal o al IDE; para probar en una sola máquina usar audífonos (si no, hay eco y acople); cada cliente debe usar un puerto UDP distinto (pedir puerto `0` para que el sistema asigne uno libre).

### Etapa 5 — Conferencias de voz y robustez (RF-06) · resto de 1.2 + 0.6 puntos

- Servidor: clase `AudioRelay` con un `DatagramSocket` UDP que recibe el audio de cada participante y lo reenvía a los demás de la misma sala. **No devolverle a nadie su propio audio** (eco).
- `SessionI.joinVoice(room, myAudio)` devuelve el endpoint UDP del relay; `leaveVoice(room)` saca al participante sin cortar a los demás. Notificar `voiceParticipantJoined` / `voiceParticipantLeft`.
- `RoomInfo.voiceActive` hoy está fijo en `false` en `RoomManager.list()`: conectarlo al estado real.
- Cliente: `/voice <sala>`, `/leavevoice`, `/mute`, `/unmute`. Mute = dejar de enviar paquetes, sin cerrar la llamada.
- Limpieza en `ChatHub.disconnect` y al salir de la sala (`leaveRoom`).
- Revisión final de concurrencia y manejo de errores en todo el servidor.
- Probar con 3 o más participantes en máquinas distintas.

### Etapa 6 — Documentación y entrega

- `README.md`: diagrama de arquitectura con el flujo Ice y el flujo UDP, manual de usuario completo, respuestas a las 4 preguntas del cuestionario (sección 4 del enunciado).
- `BITACORA_IA.md`: prompts principales, código generado que se adaptó, lecciones de depuración.
- Completar la tabla de integrantes del README.
- Verificar: repositorio privado y profesor con acceso de lectura.

## 7. Problemas ya resueltos (para no repetirlos)

| Síntoma | Causa | Solución aplicada |
|---|---|---|
| `compileSlice` falla con `groovy/util/XmlSlurper` | El plugin de Ice es para Groovy 3 y Gradle 9 trae Groovy 4 | Clase puente en `buildSrc/`. No borrarla. Gradle 8 no sirve con JDK 25 |
| `slice2java not found ... /usr/local` | El plugin busca Ice en una ruta fija | `common/build.gradle` detecta Ice por `ICE_HOME` o el `PATH` |
| Las excepciones Slice llegan como `UnknownUserException` | Los stubs usan el paquete `co.edu.icesi` | `Ice.Package.chat=co.edu.icesi` en ambos `.config`. No quitarla |
| `-Phost` no cambiaba el servidor | `Util.initialize` solo lee de la línea de comandos las propiedades `Ice.*` | `parseCommandLineOptions("ChatService", ...)` en `ChatClient` |
| El servidor no detectaba un cliente congelado | Sus propios latidos contaban como actividad | Solo el cliente envía latidos (`Ice.ACM.Server.Heartbeat=0`, `Close=4`) |
| `slice2java (\bin\slice2java.exe) not found` en Windows | No está instalado el compilador de Ice | Instalar el MSI de Ice 3.7 (página de descarga de **Java**) y compilar con `-PiceHome="C:\Program Files\ZeroC\Ice-3.7.11"` |

## 8. Reglas de trabajo del equipo

**Git**
- Antes de empezar: `git pull`. Trabajar en una rama por etapa (`feature/etapa-3-archivos`) y unirla a `main` cuando compile y esté probada.
- Cada quien hace commit **desde su propio equipo y con su cuenta**: el profesor revisa el historial por integrante.
- Un commit = algo que funciona. Mensajes con el formato ya usado: `feat(server): ...`, `feat(client): ...`, `fix: ...`, `docs: ...`.
- Nunca subir a `main` algo que no pase `./gradlew build`.

**Código**
- Comentarios y mensajes al usuario en español; nombres de clases, métodos y variables en inglés (como está el código actual).
- Comentar el *porqué* de las decisiones (concurrencia, red), no lo obvio.
- Probar siempre con al menos 2 clientes antes de hacer commit; para voz y conferencias, con 3.

**Bitácora de IA (obligatoria, Nivel 3 del curso)**
- Cada integrante anota mientras trabaja: el prompt principal, qué código generó la IA, qué se cambió y por qué, y qué error costó depurar.
- Todavía no existe `BITACORA_IA.md`; quien empiece la Etapa 3 puede crearla. Las etapas 1 y 2 se hicieron con Claude Code y la sección 7 de esta guía resume sus lecciones de depuración.
- En la sustentación **cada integrante debe poder explicar el código**, incluido el que generó una IA.

## 9. Instrucciones para asistentes de IA

Contexto: proyecto académico en Java con ZeroC Ice 3.7 y Gradle multimódulo. Lee las secciones 4, 5 y 6 antes de escribir código.

**Obligatorio**
- Mantener la arquitectura existente: patrón de sesión, callbacks bidireccionales, notificaciones con `ChatHub.notify` / `broadcast`.
- Audio solo por UDP (`java.net.DatagramSocket`). Archivos solo por Ice con `sequence<byte>` en chunks. No proponer WebSockets, HTTP, sockets TCP propios ni librerías de audio o red externas.
- Todo estado nuevo en el servidor debe ser thread-safe y liberarse en `ChatHub.disconnect`.
- No hacer invocaciones Ice síncronas desde el servidor hacia un cliente.
- No bloquear los métodos de `ClientCallbackI` (corren en hilos de Ice): nada de leer teclado, esperas largas ni E/S pesada ahí.
- Toda salida por consola en el cliente pasa por `Console` (`event` desde callbacks; `info`, `error`, `echo` desde la CLI). No usar `System.out` directamente.
- Los comandos nuevos van en una clase propia (`FileCommands`, `CallCommands`, ...) con un método `registerIn(CommandLoop)`, igual que `ChatCommands`.
- Errores de aplicación: lanzar las excepciones Slice existentes con un `reason` en español entendible por el usuario.
- Verificar con `./gradlew build` y probar con varios clientes antes de dar algo por terminado. Decir con claridad qué se probó y qué no.

**Prohibido sin consultar al equipo**
- Cambiar los contratos `.ice` (afecta a servidor y cliente a la vez).
- Tocar `buildSrc/`, la versión de Gradle, la versión de Ice o el plugin de Ice.
- Quitar `Ice.Package.chat` o cambiar las propiedades `Ice.ACM.*` de los `.config`.
- Cambiar la interfaz a JavaFX o la estrategia de conferencias a malla P2P.
- Reescribir código de las etapas ya terminadas sin una razón concreta.

**Estilo**
- Java 17 (se compila con `--release 17`): se pueden usar `record`, `var`, `switch` con flechas y `Stream.toList()`.
- Seguir el estilo del código existente: clases pequeñas con una responsabilidad, Javadoc breve en español en cada clase.
- No agregar dependencias a los `build.gradle`.

**Recetas**

Agregar un comando al cliente:
1. Crear o abrir la clase de comandos (`XxxCommands`) y escribir el método `private void comando(String args) throws ChatException`.
2. Registrarlo en `registerIn`: `loop.register("/cmd", "/cmd <args>", "Descripción", this::comando);`
3. Si la clase es nueva, instanciarla en `CommandLoop.registerCommands()`.
4. Las excepciones Slice y de red ya las captura `CommandLoop.execute`; no hace falta `try/catch` para mostrarlas.

Implementar una operación pendiente del servidor:
1. Abrir `SessionI` y reemplazar el `throw pending(...)` del método.
2. Usar `me` (quien invoca), `hub.users()`, `hub.rooms()` y `hub.notify` / `hub.broadcast`.
3. Si se necesita estado nuevo, crear un gestor thread-safe (como `RoomManager`), exponerlo desde `ChatHub` y limpiarlo en `ChatHub.disconnect`.
4. Registrar la acción con `Log.info(...)`.

## 10. Contacto y reparto

| Etapa | Responsable |
|---|---|
| 1 y 2 | Jacanotorres (hechas) |
| 3 — Archivos | JUANFIX1 (hecha) |
| 4 — Llamadas 1 a 1 | *por asignar* |
| 5 — Conferencias y robustez | *por asignar* |
| 6 — Documentación | Todos |

Mantener este archivo al día: al terminar una etapa, actualizar las secciones 2, 5 y 6.
