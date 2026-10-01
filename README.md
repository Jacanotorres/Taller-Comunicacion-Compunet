# Chat Ice — Chat multimedia y voz UDP con ZeroC Ice

Taller Evaluativo Unidad 2 · Computación en Internet I (09810) · Universidad Icesi · 2026-2

Plataforma distribuida de comunicación en tiempo real (tipo Discord/Slack):

- **ZeroC Ice (TCP):** sesión, presencia, chat privado y grupal, transferencia de archivos y señalización de llamadas.
- **UDP (`DatagramSocket`):** transmisión de voz en llamadas 1 a 1 y conferencias grupales.

> **¿Vas a trabajar en el proyecto?** Lee primero [GUIA_EQUIPO.md](GUIA_EQUIPO.md): qué está hecho, qué falta y cómo continuar (también sirve de contexto para asistentes de IA).

## Integrantes

| Nombre | Código |
|---|---|
| | |
| | |
| | |
| | |

## Requisitos

| Herramienta | Versión | Notas |
|---|---|---|
| JDK | 17 o superior (probado con 25) | |
| ZeroC Ice | 3.7.x | Solo se necesita el compilador `slice2java`; la librería Java la descarga Gradle |
| Gradle | No hace falta instalarlo | Se usa el wrapper incluido (`gradlew`) |

### Instalar Ice 3.7 (`slice2java`)

- **macOS:** `brew tap zeroc-ice/tap && brew install zeroc-ice/tap/ice@3.7`
- **Windows:** instalador MSI de Ice 3.7 desde https://zeroc.com/downloads/ice/3.7 (agregar `bin` al `PATH`)
- **Linux (Debian/Ubuntu):** paquete `zeroc-ice-compilers` del repositorio de ZeroC

El build localiza Ice automáticamente buscando `slice2java` en el `PATH`. Si no lo encuentra, se puede indicar con la variable de entorno `ICE_HOME` o con `-PiceHome=<ruta>`.

## Ejecución

```bash
./gradlew build          # compila contratos Slice + servidor + cliente
./gradlew runServer      # inicia el servidor (puerto TCP 10000)
./gradlew runClient      # inicia un cliente contra localhost
./gradlew runClient -Phost=192.168.1.20   # cliente contra otra máquina de la red
```

En Windows se usa `gradlew.bat` en lugar de `./gradlew`.

## Comandos del cliente

| Comando | Descripción |
|---|---|
| `/login <nickname>` | Inicia sesión (nickname único, de 2 a 20 caracteres, sin espacios) |
| `/logout` | Cierra la sesión |
| `/users` | Lista los usuarios conectados |
| `/msg <usuario> <texto>` | Mensaje privado |
| `/create <sala>` | Crea una sala y entra a ella |
| `/rooms` | Lista las salas disponibles |
| `/join <sala>` | Entra a una sala (se puede estar en varias) |
| `/leave <sala>` | Sale de una sala |
| `/members <sala>` | Lista los miembros de una sala |
| `/room <sala> [texto]` | Envía un mensaje a esa sala; sin texto, la deja como sala activa |
| *texto sin `/`* | Se envía a la sala activa (la que muestra el prompt) |
| `/help`, `/quit` | Ayuda y salir |

## Estructura del proyecto

```
├── build.gradle            # configuración común + tareas runServer / runClient
├── settings.gradle         # módulos: common, server, client
├── buildSrc/               # puente de compatibilidad del plugin Ice con Gradle 9 (ver abajo)
├── config/
│   ├── server.config       # endpoints, tamaño máximo de mensaje, pool de hilos, ACM
│   └── client.config       # proxy del servidor, heartbeats
├── common/                 # contratos Slice → stubs Java (paquete co.edu.icesi.chat)
│   └── src/main/slice/
│       ├── Types.ice       # structs y excepciones
│       ├── Callback.ice    # interfaz ClientCallback (la implementa el cliente)
│       └── Service.ice     # interfaces ChatService y Session (las implementa el servidor)
├── server/                 # ChatServer, ChatServiceI, SessionI, ChatHub, UserRegistry, RoomManager
└── client/                 # ChatClient, CommandLoop, ChatCommands, ClientCallbackI, Console
```

## Decisiones de diseño (resumen)

- **Patrón de sesión:** `ChatService` solo expone `login(nickname, callback)`, que devuelve un proxy `Session*` propio de cada usuario. Las demás operaciones se invocan sobre la sesión, así el servidor siempre sabe quién llama y nadie puede suplantar a otro nickname.
- **Callbacks bidireccionales:** el cliente crea un adaptador de objetos sin endpoints y lo asocia a la conexión ya abierta con el servidor (`Connection.setAdapter`). El servidor invoca los callbacks por esa misma conexión TCP, así el cliente no necesita abrir puertos (útil detrás de NAT o firewalls del laboratorio).
- **Concurrencia en el servidor:** el estado compartido vive en `ConcurrentHashMap`. La unicidad del nickname se resuelve con `putIfAbsent` (atómico) y las altas y bajas de una sala con `computeIfPresent`, que bloquea solo esa sala. Las notificaciones a clientes se envían con invocaciones asíncronas (`...Async`), para que un cliente lento no frene a los demás.
- **Detección de clientes caídos:** el cliente envía latidos (ACM heartbeats); si el servidor deja de recibir actividad durante 15 s cierra la conexión, y el *close callback* de esa conexión limpia la sesión y avisa a los demás usuarios.
- **Salas:** una sala se elimina cuando queda sin miembros. Los nombres de usuarios y salas no distinguen mayúsculas.
- **Conferencias de voz:** relay centralizado en el servidor (SFU ligero).
- **Plugin de Ice con Gradle 9:** el taller exige `com.zeroc.gradle.ice-builder.slice` (1.5.2), compilado contra Groovy 3. Gradle 9, necesario para compilar con JDK 25, trae Groovy 4, que movió `groovy.util.XmlSlurper` a `groovy.xml.XmlSlurper`. `buildSrc/` contiene una clase puente de pocas líneas que restaura ese nombre para que el plugin funcione.

## Estado del desarrollo

| Etapa | Contenido | Estado |
|---|---|---|
| 1 | Proyecto Gradle, contratos Slice, arranque de servidor y cliente con CLI | ✅ |
| 2 | Sesión, presencia, chat privado y salas (RF-01, RF-02, RF-03) | ✅ |
| 3 | Transferencia de archivos por chunks (RF-04) | ⏳ |
| 4 | Llamadas de voz 1 a 1 por UDP (RF-05) | ⏳ |
| 5 | Conferencias de voz y robustez (RF-06) | ⏳ |
| 6 | Documentación final, cuestionario y bitácora IAG | ⏳ |
