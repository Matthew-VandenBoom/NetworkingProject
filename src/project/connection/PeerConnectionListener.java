package project.connection;

import project.LocalPeerManager;
import project.message.packet.Packet;
import project.message.packet.packets.*;
import project.utils.Logger;
import project.utils.Tag;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class PeerConnectionListener extends PeerConnection {

    // private final BlockingQueue<Packet> messageQueue;
    private final PeerConnectionManager manager;

    private InputStream in;

    public PeerConnectionListener(Socket connection, LocalPeerManager localPeerManager,
                                  ConnectionState state, PeerConnectionManager manager) {
        super(connection, localPeerManager, state);

        // this.messageQueue = incomingMessageQueue;
        this.manager = manager;
    }

    public void run() {
        try {
            this.in = this.connection.getInputStream();

            // Listen to the first incoming message (handshake)
            byte[] message = this.readBytes(HandshakePacket.HANDSHAKE_LENGTH);
            // this.messageQueue.put(new HandshakePacket(message));
            this.manager.SendRecievedPacket(new HandshakePacket(message));

            // Wait for the handshake to be finished
            this.state.waitForHandshake();
            this.state.unlockHandshake();

            // Start listening to incoming messages until the connection is closed
            while (this.state.isConnectionActive()) {
                // this.messageQueue.put(this.listenToMessage());
                this.manager.SendRecievedPacket(this.listenToMessage());
            }
        } catch (IOException exception) {
            System.err.println("An error occurred when listening to incoming packets with peer " +
                    this.state.getRemotePeerId());
        } finally {
            try {
                Logger.print(Tag.LISTENER, "Closing input stream with peer " + this.state.getRemotePeerId());

                this.in.close();
            } catch(IOException ioException){
                System.err.println("An error occurred when closing the input stream with peer " +
                        this.state.getRemotePeerId());
            }
        }
    }


    /**
     * Listen to a single incoming message.
     * This is done by reading 4 bytes, which is the predefined number of bytes for the length header,
     * and then reading ${lengthHeader} bytes where length is the 4-byte value read.
     *
     * @return   Receive, parsed packet. Unknown if an unknown packet was received.
     */
    private Packet listenToMessage() {
        // Read the length of the incoming packet
        byte[] lengthHeaderBytes = this.readBytes(4);
        int lengthHeader = lengthHeaderBytes == null ? 0 : ByteBuffer.wrap(lengthHeaderBytes).getInt();

        if(lengthHeader < 1) {
            return new UnknownPacket();
        }

        // Read 'lengthHeader' bytes, which is the content of the packet
        byte[] payload = this.readBytes(lengthHeader);

        // Create packet from the read payload
        Packet packet = Packet.PacketFromBytes(payload);

        Logger.print(Tag.LISTENER, "Parsed packet of type " + packet.getTypeString() + " from peer " +
                this.state.getRemotePeerId() + ". Data: " + packet.dataString());

        return packet;
    }

    /**
     * Reads ${length} amount of bytes from the input stream
     *
     * @param length   Number of bytes to read
     * @return         Read byte array
     */
    private byte[] readBytes(int length) {
        int nIters = 0;
        int totalread = 0;
        int n;
        try {
            if(length > 0) {
                byte[] message = new byte[length];
                while(totalread < length)
                {
                    n = this.in.read(message, totalread, message.length - totalread);
                    if (n < 0)
                    {
                        Logger.print(Tag.LISTENER, "readBytes in " + this.state.getRemotePeerId() + " read nothing (" + nIters + "): " + totalread + " instead of " + length);
                    }
                    else if ((totalread += n) != length) {
                        Logger.print(Tag.LISTENER, "readBytes in " + this.state.getRemotePeerId()
                                + " read less than expected (" + nIters + "): " + totalread + " instead of " + length);
                    }
                    nIters++;
                }
                return message;
            }
        } catch (IOException exception) {
            // this exception should only happen when the connetion is closed, in which case we should be exiting anyways
            // System.err.println("IO Exception In listener " +this.state.getRemotePeerId()+ " \n" +  exception);
            // exception.printStackTrace(System.err);
        }

        return null;
    }
}
