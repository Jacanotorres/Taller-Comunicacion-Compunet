# Chat Ice — Chat multimedia y voz UDP con ZeroC Ice

Taller Evaluativo Unidad 2 · Computación en Internet I (09810) · Universidad Icesi · 2026-2

Plataforma distribuida de comunicación en tiempo real (tipo Discord/Slack):

- **ZeroC Ice (TCP):** sesión, presencia, chat privado y grupal, transferencia de archivos y señalización de llamadas.
- **UDP (`DatagramSocket`):** transmisión de voz en llamadas 1 a 1 y conferencias grupales.

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
├── server/                 # ChatServer, ChatServiceI, SessionI
└── client/                 # ChatClient, CLI, ClientCallbackI
```

## Decisiones de diseño (resumen)

- **Patrón de sesión:** `ChatService` solo expone `login(nickname, callback)`, que devuelve un proxy `Session*` propio de cada usuario. Las demás operaciones se invocan sobre la sesión, así el servidor siempre sabe quién llama y nadie puede suplantar a otro nickname.
- **Callbacks bidireccionales:** el cliente crea un adaptador de objetos sin endpoints y lo asocia a la conexión ya abierta con el servidor (`Connection.setAdapter`). El servidor invoca los callbacks por esa misma conexión TCP, así el cliente no necesita abrir puertos (útil detrás de NAT o firewalls del laboratorio).
- **Conferencias de voz:** relay centralizado en el servidor (SFU ligero).
- **Plugin de Ice con Gradle 9:** el taller exige `com.zeroc.gradle.ice-builder.slice` (1.5.2), compilado contra Groovy 3. Gradle 9, necesario para compilar con JDK 25, trae Groovy 4, que movió `groovy.util.XmlSlurper` a `groovy.xml.XmlSlurper`. `buildSrc/` contiene una clase puente de pocas líneas que restaura ese nombre para que el plugin funcione.

## Estado del desarrollo

| Etapa | Contenido | Estado |
|---|---|---|
| 1 | Proyecto Gradle, contratos Slice, arranque de servidor y cliente con CLI | ✅ |
| 2 | Sesión, presencia, chat privado y salas (RF-01, RF-02, RF-03) | ⏳ |
| 3 | Transferencia de archivos por chunks (RF-04) | ⏳ |
| 4 | Llamadas de voz 1 a 1 por UDP (RF-05) | ⏳ |
| 5 | Conferencias de voz y robustez (RF-06) | ⏳ |
| 6 | Documentación final, cuestionario y bitácora IAG | ⏳ |
