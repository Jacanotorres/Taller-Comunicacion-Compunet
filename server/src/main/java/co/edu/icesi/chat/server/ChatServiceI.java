package co.edu.icesi.chat.server;

import co.edu.icesi.chat.ChatService;
import co.edu.icesi.chat.ClientCallbackPrx;
import co.edu.icesi.chat.InvalidNameException;
import co.edu.icesi.chat.NicknameInUseException;
import co.edu.icesi.chat.SessionPrx;
import com.zeroc.Ice.Current;

/**
 * Servant del objeto "ChatService": único punto de entrada público.
 * Autentica al usuario y le entrega un proxy a su Session.
 */
public class ChatServiceI implements ChatService {

    private final ChatHub hub;

    public ChatServiceI(ChatHub hub) {
        this.hub = hub;
    }

    @Override
    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current)
            throws InvalidNameException, NicknameInUseException {
        return hub.login(nickname, callback, current.con);
    }
}
