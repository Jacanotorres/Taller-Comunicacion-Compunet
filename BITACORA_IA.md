# Bitácora de uso ético de IA generativa

Taller Evaluativo Unidad 2 · Computación en Internet I · Política IAG **Nivel 3: colaboración asistida**.

Esta bitácora resume, por etapa, qué herramienta de IA se usó, los prompts principales, qué código generó la IA, qué se cambió y por qué, y qué errores costó depurar. Cada integrante registra lo que hizo en su etapa. Todos los integrantes deben poder explicar en la sustentación cualquier parte del código, incluida la generada con IA.

> **Para completar:** las secciones marcadas con *(completar: ...)* las llena el integrante responsable de esa etapa, con sus propios prompts y su propia revisión. No deben quedar vacías al entregar.

## Herramientas usadas

| Herramienta | Etapas | Para qué |
|---|---|---|
| Claude Code (Anthropic) | 1, 2 | Estructura del proyecto Gradle multimódulo, contratos Slice, sesión, presencia, chat y salas |
| *(completar: JUANFIX1)* | 3, 4 | Transferencia de archivos y llamadas 1 a 1 |
| Claude Code (Anthropic, modelo Claude Opus 5.5), app de escritorio | 5, 6 | Conferencias de voz, pruebas automáticas, documentación y cuestionario |

Para que la IA trabajara con el contexto correcto, el equipo mantiene [GUIA_EQUIPO.md](GUIA_EQUIPO.md). Ahí están la arquitectura, las decisiones ya tomadas y las reglas obligatorias para asistentes de IA (sección 9). Los archivos `CLAUDE.md` y `AGENTS.md` obligan a leerla antes de escribir código.

---

## Etapas 1 y 2 — Proyecto base, sesión, chat y salas

**Responsable:** Jacanotorres · **Herramienta:** Claude Code

- **Prompts principales:** *(completar: Jacanotorres)*
- **Código generado y adaptado:** *(completar: qué clases generó la IA y qué se cambió tras revisarlas)*

**Errores y lecciones de depuración** (registrados en la sección 7 de la guía):

| Problema | Causa | Cómo se resolvió |
|---|---|---|
| `compileSlice` fallaba con `groovy/util/XmlSlurper` | El plugin de Ice está compilado para Groovy 3 y Gradle 9 trae Groovy 4 | Clase puente en `buildSrc/` (Gradle 8 no sirve con JDK 25) |
| `slice2java not found ... /usr/local` | El plugin busca Ice en una ruta fija | `common/build.gradle` detecta Ice por `ICE_HOME`, `-PiceHome` o el `PATH` |
| Las excepciones Slice llegaban como `UnknownUserException` | Los stubs se generan en el paquete `co.edu.icesi` | `Ice.Package.chat=co.edu.icesi` en ambos `.config` |
| `-Phost` no cambiaba el servidor | `Util.initialize` solo lee de la línea de comandos las propiedades `Ice.*` | `parseCommandLineOptions("ChatService", ...)` en `ChatClient` |
| El servidor no detectaba un cliente congelado | Los latidos del propio servidor contaban como actividad | Solo el cliente envía latidos (`Ice.ACM.Server.Heartbeat=0`, `Close=4`) |

## Etapas 3 y 4 — Archivos y llamadas 1 a 1

**Responsable:** JUANFIX1 · **Herramienta:** *(completar)*

- **Prompts principales:** *(completar: JUANFIX1)*
- **Código generado y adaptado:** *(completar)*
- **Errores y lecciones de depuración:** *(completar)*

---

## Etapa 5 — Conferencias de voz (RF-06)

**Responsable:** Elias Saldarriaga · **Herramienta:** Claude Code (Claude Opus 5.5), con acceso a la carpeta del proyecto

### Prompts principales

1. Se le dio acceso a la carpeta del proyecto y el enunciado del taller en PDF (*Taller Evaluativo Unidad 2*), con la instrucción de continuar con la etapa pendiente.
2. *"Exacto, así lo vamos a hacer"*: aprobación del plan que propuso la IA tras leer la guía y el enunciado. El plan era implementar RF-06 con un relay centralizado, en la rama `etapa-5`, sin tocar los contratos `.ice`.
3. *"Ya instalé el Ice"*: tras instalar ZeroC Ice 3.7.11, que faltaba en el equipo, se le pidió compilar y verificar.

Primero se le pasó por error el PDF del taller guiado de la sesión 18 (chat con *polling*). La IA detectó que no coincidía con el repositorio (callbacks, voz UDP, RF-01 a RF-06) y pidió confirmar cuál era el enunciado antes de escribir código.

### Código generado por la IA

| Archivo | Contenido |
|---|---|
| `server/.../AudioRelay.java` | Socket UDP en el puerto 10001 y su hilo: reenvía la voz a los demás participantes de la sala, nunca al emisor, y antepone 2 bytes con el id de quien habla |
| `server/.../ConferenceManager.java` | Estado de las conferencias, avisos `voiceParticipantJoined/Left`, tabla de rutas del relay |
| `server/.../AudioAddresses.java` | Lógica de direcciones UDP que estaba dentro de `CallManager`, extraída para reutilizarla |
| `client/.../AudioMixer.java` | Mezcla en el cliente de las voces que llegan del relay |
| `client/.../ConferenceSession.java`, `VoiceCommands.java` | Comandos `/voice`, `/leavevoice`, `/mute`, `/unmute` |
| Cambios en `CallManager`, `ChatHub`, `SessionI`, `RoomManager`, `ChatServer`, `VoiceChannel`, `AudioSender`, `CallSession` | Integración, limpieza al salir de la sala o desconectarse, `voiceActive` en `/rooms`, mute |

### Decisiones que se revisaron y ajustaron durante el trabajo

- **Mezcla de audio.** La guía proponía reutilizar `AudioReceiver` tal cual, con el relay como *peer*. Al revisarlo apareció un problema: con 3 o más participantes llegan N-1 flujos al mismo altavoz, que se llenaría y descartaría paquetes. Se oiría entrecortado justo en el caso que penaliza la rúbrica ("bloqueos al ingresar más de 2 participantes").
  - Se agregó un id del emisor en cada paquete del relay y un `AudioMixer` en el cliente, que suma un paquete de cada participante cada 20 ms.
  - Se descartó mezclar en el servidor, porque el taller define el relay como un servidor que *reenvía* los paquetes.
- **Exclusión llamada 1 a 1 / conferencia.** Comprobarla con dos locks separados dejaba una carrera: alguien podía terminar en ambas a la vez. `ConferenceManager` usa el mismo monitor que `CallManager`, así la comprobación y el registro son atómicos.
- **Carreras al entrar.** Si alguien entra a la voz mientras sale de la sala o se desconecta, la limpieza puede correr antes del registro. Se aplicó el mismo patrón que ya usaban `joinRoom` y `CallManager.start`: registrar, volver a verificar y revertir si hace falta.

### Revisión del equipo

*(completar: Elias, qué partes revisaste línea por línea, qué preguntaste o cambiaste, y el resultado de las pruebas de audio reales con 3 participantes: en cuántas máquinas, si se oyó bien cuando dos hablaban a la vez, si mute y leavevoice funcionaron)*

### Errores que cometió la IA y cómo se detectaron

| Error | Cómo se detectó | Corrección |
|---|---|---|
| En el programa de prueba usó `throws Exception` con `import com.zeroc.Ice.*`, y `Exception` quedó ambiguo (`com.zeroc.Ice.Exception` vs `java.lang.Exception`) | Error de compilación de `javac` | Calificar `java.lang.Exception` |
| Olvidó declarar `InvalidNameException` (excepción Slice *checked*) en un método auxiliar de prueba | Error de compilación | `throws ChatException` |
| Ejecutó `gradlew.bat` desde Git Bash con la ruta del proyecto, que tiene espacios (`Semestre 5`), y el `.bat` falló | Error de `cmd`: "no se reconoce como un comando" | Ejecutarlo desde PowerShell |
| Hipótesis inicial (no verificada) de que el emisor de un mensaje demasiado grande recibiría `MemoryLimitException` | El experimento de la pregunta 2 del cuestionario mostró otra cosa | El emisor recibe `SocketException` y la `MemoryLimitException` solo aparece en el servidor; se documentó lo medido |

### Estrategia de validación

1. **Compilación:** `gradlew build` sin errores.
2. **Pruebas automáticas sin micrófono.** Un programa externo al repositorio se conecta con 5 usuarios y usa sockets UDP propios en lugar de micrófono. **26 verificaciones, todas correctas**, entre ellas:
   - avisos de entrada y salida;
   - el relay reenvía a los demás y no al emisor (sin eco);
   - cada emisor llega con un id distinto;
   - se descartan los paquetes de direcciones no registradas;
   - exclusión entre llamada 1 a 1 y conferencia en ambos sentidos;
   - salida por `/leavevoice`, por `/leave` de la sala y por desconexión abrupta;
   - `voiceActive` en `/rooms`;
   - el `AudioMixer` suma, recorta a 16 bits y mantiene el ritmo de 20 ms.
3. **Prueba real de audio:** con personas y micrófonos, pendiente de documentar en *Revisión del equipo*. La IA no puede hablar ni escuchar, así que esta verificación es necesariamente humana.

---

## Etapa 6 — Documentación y cuestionario

**Responsable:** Elias Saldarriaga · **Herramienta:** Claude Code (Claude Opus 5.5)

- **Prompt principal:** *"Vamos entonces con la etapa 6"*. Pidió diagrama de arquitectura, manual de usuario, cuestionario y bitácora, según la sección de entrega del enunciado.
- **Qué generó la IA:** el README nuevo (diagramas Mermaid de los flujos Ice y UDP, manual de usuario, problemas frecuentes y las 4 respuestas del cuestionario) y la estructura de esta bitácora.
- **Validación de las respuestas:**
  - **Pregunta 2:** no se respondió de memoria. Se ejecutó un experimento contra nuestro servidor, enviando 80 MB y 3 MB en una sola invocación, y se documentó lo observado. La `MemoryLimitException` solo aparece en el log del servidor con `Ice.Warn.Connections=1`; el emisor ve una `SocketException` y pierde la sesión.
  - **Pregunta 3:** las cifras se calcularon con el formato real del audio (640 bytes cada 20 ms, más 2 bytes de id y 28 de cabeceras UDP/IP).
  - **Manual de usuario:** se contrastó con el código (por ejemplo, que `/sendfile` acepta rutas con espacios, con o sin comillas).
- **Revisión del equipo:** *(completar: quién revisó las respuestas del cuestionario y qué se corrigió)*

## Lecciones aprendidas

- **Darle contexto escrito a la IA funciona.** Con `GUIA_EQUIPO.md` (arquitectura, decisiones tomadas y reglas), la IA respetó las restricciones del taller: audio solo por UDP, no cambiar los contratos, estado thread-safe. Sin esa guía habría propuesto rediseñar lo que ya funcionaba.
- **La IA puede verificar la lógica, pero no la experiencia.** Las pruebas automáticas confirman que los paquetes llegan a quien deben. Si el audio se entiende, si hay eco o si la latencia molesta, solo lo comprueba una persona con audífonos.
- **Medir antes de afirmar.** La respuesta "obvia" sobre `MemoryLimitException` era incompleta; el experimento mostró qué ve realmente cada lado.
- **Las rutas con espacios y la consola de Windows** fueron la fuente más frecuente de errores operativos (scripts `.bat`, rutas largas): conviene usar PowerShell en Windows.
