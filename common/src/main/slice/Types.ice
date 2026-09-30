// Tipos de datos y excepciones compartidos entre servidor y cliente.
#pragma once

[["java:package:co.edu.icesi"]]
module chat
{
    sequence<byte> ByteSeq;
    sequence<string> StringSeq;

    // ----------------------------------------------------------------
    // Mensajería
    // ----------------------------------------------------------------

    /** Mensaje de texto. room vacío = mensaje privado 1 a 1. */
    struct ChatMessage
    {
        string sender;
        string room;
        string text;
        long timestamp;     // epoch en milisegundos, asignado por el servidor
    }

    /** Resumen de una sala para el catálogo (/rooms). */
    struct RoomInfo
    {
        string name;
        string owner;
        int memberCount;
        bool voiceActive;   // hay una conferencia de voz en curso
    }
    sequence<RoomInfo> RoomInfoSeq;

    // ----------------------------------------------------------------
    // Transferencia de archivos (chunking sobre Ice)
    // ----------------------------------------------------------------

    /** Metadatos de una transferencia; viajan en cada chunk. */
    struct FileMeta
    {
        string transferId;  // UUID generado por el emisor
        string fileName;
        long totalSize;     // bytes
        int totalChunks;
    }

    /** Un bloque del archivo (32 a 64 KB). */
    struct FileChunk
    {
        FileMeta meta;
        int index;          // 0 .. totalChunks-1
        ByteSeq data;
    }

    // ----------------------------------------------------------------
    // Voz sobre UDP (Ice solo hace la señalización)
    // ----------------------------------------------------------------

    /** Dirección UDP donde un participante recibe audio. */
    struct AudioEndpoint
    {
        string host;
        int port;
    }

    // ----------------------------------------------------------------
    // Excepciones
    // ----------------------------------------------------------------

    /** Base de todas las excepciones de la aplicación. */
    exception ChatException
    {
        string reason;
    }

    exception InvalidNameException extends ChatException {}
    exception NicknameInUseException extends ChatException {}
    exception UserNotFoundException extends ChatException {}

    exception RoomAlreadyExistsException extends ChatException {}
    exception RoomNotFoundException extends ChatException {}
    exception NotRoomMemberException extends ChatException {}

    exception FileTransferException extends ChatException {}
    exception CallException extends ChatException {}
}
