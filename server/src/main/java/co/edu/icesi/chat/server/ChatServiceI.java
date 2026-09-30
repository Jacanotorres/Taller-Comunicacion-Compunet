package co.edu.icesi.chat.server;

import co.edu.icesi.chat.ChatService;
import co.edu.icesi.chat.ClientCallbackPrx;
import co.edu.icesi.chat.InvalidNameException;
import co.edu.icesi.chat.NicknameInUseException;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.ObjectAdapter;

/**
 * Servant del objeto "ChatService": único punto de entrada público.
 * Autentica al usuario y le entrega un proxy a su Session.
 */
public class ChatServiceI implements ChatService {

    private final ObjectAdapter adapter;

    public ChatServiceI(ObjectAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current)
            throws InvalidNameException, NicknameInUseException {
        Log.info("Solicitud de login de '" + nickname + "' desde " + current.con);

        // TODO Etapa 2 (RF-01):
        //  1. Validar el nickname (InvalidNameException) y que no esté en uso (NicknameInUseException).
        //  2. Asociar el callback a la conexión bidireccional:
        //     callback.ice_fixed(current.con) para que el servidor invoque por la misma conexión.
        //  3. Crear SessionI, registrarla en el adaptador y guardar el usuario en un mapa concurrente.
        //  4. Registrar current.con.setCloseCallback(...) para limpiar si el cliente se cae.
        //  5. Notificar userConnected(...) al resto de usuarios.
        throw new UnsupportedOperationException("login pendiente de implementar (Etapa 2)");
    }
}
