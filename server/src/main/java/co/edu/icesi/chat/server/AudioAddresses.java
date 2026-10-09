package co.edu.icesi.chat.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import co.edu.icesi.chat.AudioEndpoint;
import co.edu.icesi.chat.CallException;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.IPConnectionInfo;
import com.zeroc.Ice.LocalException;

/**
 * Direcciones UDP de audio, deducidas de la conexión TCP (Ice) de cada usuario. Las usan las
 * llamadas 1 a 1 (CallManager) y las conferencias (ConferenceManager).
 */
final class AudioAddresses {

    private AudioAddresses() {
    }

    /**
     * Valida el puerto que declara el cliente y completa el host con la dirección que el
     * servidor ve en su conexión TCP. El cliente suele no conocer bien su propia IP (varias
     * tarjetas, VPN), y no se confía en el host que declare: así nadie puede pedir que el audio
     * de otro usuario se envíe a una máquina ajena.
     */
    static AudioEndpoint observed(ClientSession me, AudioEndpoint requested) throws CallException {
        if (requested == null || requested.port < 1 || requested.port > 65535) {
            throw new CallException("El puerto UDP de audio no es válido.");
        }
        return new AudioEndpoint(ipInfo(me).remoteAddress, requested.port);
    }

    /**
     * Dirección del servidor tal como la ve este cliente (la IP a la que se conectó por TCP).
     * Es la que se le entrega para enviar audio al relay: seguro que la alcanza.
     */
    static String localHost(ClientSession me) throws CallException {
        return ipInfo(me).localAddress;
    }

    static InetSocketAddress toSocketAddress(AudioEndpoint endpoint) throws CallException {
        try {
            // El host es una IP literal (viene de la conexión), así que no hay consulta DNS.
            return new InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port);
        } catch (UnknownHostException e) {
            throw new CallException("No se pudo interpretar tu dirección de red.");
        }
    }

    private static IPConnectionInfo ipInfo(ClientSession me) throws CallException {
        try {
            ConnectionInfo info = me.connection().getInfo();
            while (info != null) {
                if (info instanceof IPConnectionInfo ip) {
                    return ip;
                }
                info = info.underlying;
            }
        } catch (LocalException e) {
            // la conexión ya se cerró
        }
        throw new CallException("No se pudo determinar tu dirección de red.");
    }
}
