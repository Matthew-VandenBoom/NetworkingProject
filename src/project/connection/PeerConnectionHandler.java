package project.connection;

import project.LocalPeerManager;
import project.connection.piece.Piece;
import project.connection.piece.PieceStatus;
import project.packet.Packet;
import project.packet.packets.*;
import project.utils.Logger;
import project.utils.Tag;

import java.util.BitSet;

public class PeerConnectionHandler {

    private final PeerConnectionManager peerConnectionManager;

    private final LocalPeerManager localPeerManager;
    private final ConnectionState state;

    public PeerConnectionHandler(PeerConnectionManager peerConnectionManager) {
        this.peerConnectionManager = peerConnectionManager;

        this.localPeerManager = peerConnectionManager.getLocalPeerManager();
        this.state = peerConnectionManager.getConnectionState();
    }

    /**
     * Handles a single received packet by parsing it, updating local state and preparing a reply packet
     *
     * @param packet   Packet to handle
     */
    public void handle(Packet packet) {
        Logger.print(Tag.HANDLER, "Received a " + packet.getTypeString() + " from peer " +
                this.state.getRemotePeerId());

        switch (packet.getType()) {
            case CHOKE -> handleChoke((ChokePacket) packet);
            case UNCHOKE -> handleUnchoke((UnchokePacket) packet);

            case INTERESTED -> handleInterested((InterestedPacket) packet);
            case NOT_INTERESTED -> handleNotInterested((NotInterestedPacket) packet);

            case BITFIELD -> handleBitfield((BitFieldPacket) packet);

            case HAVE -> handleHave((HavePacket) packet);

            case REQUEST -> handleRequest((RequestPacket) packet);
            case PIECE -> handlePiece((PiecePacket) packet);

            default -> {}
        }
    }

    /**
     * handles receiving a Choke packet.
     * According to the protocol, when local peer is being choked by remote peer, the local peer
     * is not able to request pieces from remote peer.
     * Thus, when receiving an Unchoke packet, we update the remote choke value
     *
     * @param packet   The Unchoke Packet
     */
    private void handleChoke(ChokePacket packet) {
        this.state.setRemoteChoke(true);
    }

    /**
     * handles receiving an Unchoke packet.
     * According to the protocol, when local peer is being unchoked by remote peer, the local peer
     * is able to request pieces from remote peer.
     * Thus, when receiving an Unchoke packet, we update the remote choke value and the local peer
     * sends a Request packet back.
     *
     * @param packet   The Unchoke Packet
     */
    private void handleUnchoke(UnchokePacket packet) {
        this.state.setRemoteChoke(false);

        int pieceIndex = this.localPeerManager.choosePieceToRequest(this.state.getPieces());

        if(pieceIndex != -1 && !this.state.isRemoteChoked()) {
            this.sendRequest(pieceIndex);
            return;
        }
    }

    /**
     * handles receiving an Interested packet.
     * According to the protocol, when local peer is getting an Interested packet from the remote peer, the local peer
     * knows it has pieces the remote peer wants.
     * Thus, when receiving an Interested packet, local peer updates the connection state to Interested=true
     *
     * @param packet   The Interested Packet
     */
    private void handleInterested(InterestedPacket packet) {
        this.state.setInterested(true);
    }

    /**
     * handles receiving a NotInterested packet.
     * According to the protocol, when local peer is getting a NotInterested packet from the remote peer, the local peer
     * knows it has no pieces the remote peer wants.
     * Thus, when receiving a NotInterested packet, local peer updates the connection state to Interested=false
     *
     * @param packet   The NotInterested Packet
     */
    private void handleNotInterested(NotInterestedPacket packet) {
        this.state.setInterested(false);
    }

    /**
     * handles receiving a Bitfield packet.
     * According to the protocol, when local peer is getting a Bitfield packet from the remote peer, the local peer
     * knows the remote peer's bitfield status.
     * Thus, when receiving a Bitfield packet, local peer updates the connection state with the remote peer's pieces.
     * In addition, if the local peer has any interest in the remote peer's pieces, it sends an Interested
     * packet, and otherwise, sends a NotInterested packet.
     *
     * @param packet   The Bitfield Packet
     */
    private void handleBitfield(BitFieldPacket packet) {
        PieceStatus[] pieces = PieceStatus.bitsetToPiecesStatus(packet.getBitfield(),
                this.localPeerManager.getConfig().getNumberOfPieces());

        this.state.setPieces(pieces);

        if(this.hasInterest()) {
            this.sendInterested();
        } else {
            this.sendNotInterested();
        }
    }

    /**
     * handles receiving a Have packet.
     * According to the protocol, when local peer is getting a Have packet from the remote peer, the local peer
     * knows the remote peer's status for a specific piece.
     * Thus, when receiving a Have packet, local peer updates the connection state with the remote peer's piece status.
     * In addition, if the local peer has any interest in the updated remote peer's pieces, it sends an Interested
     * packet, and otherwise, sends a NotInterested packet.
     *
     * @param packet   The Have Packet
     */
    private void handleHave(HavePacket packet) {
        int pieceIndex = packet.getPieceIndex();

        this.state.updatePiece(pieceIndex);

        if(this.hasInterest()) {
            this.sendInterested();
        } else {
            this.sendNotInterested();
        }

        // Check if the connection needs to be terminated (both local & remote peers have all pieces)
        this.localPeerManager.attemptTerminate(this.peerConnectionManager);
    }

    /**
     * handles receiving a Request packet.
     * According to the protocol, when local peer is getting a Request packet from the remote peer, the local peer
     * knows the remote peer wants a specific piece it has.
     * Thus, when receiving a Request packet, the local peer makes sure that the remote peer is not choked
     * locally (meaning that the local peer choked remote peer <=> local peer refuses sending data to remote), and
     * sends a Piece packet back.
     *
     * @param packet   The Request Packet
     */
    private void handleRequest(RequestPacket packet) {
        int pieceIndex = packet.getPieceIndex();

        // If the remote peer is not locally choked, it means the local peer can send pieces to it.
        if(!this.state.isLocalChoked()) {
            this.sendPiece(pieceIndex);
        }
    }

    /**
     * handles receiving a Piece packet.
     * According to the protocol, when local peer is getting a Piece packet from the remote peer, the local peer
     * now has a specific piece content.
     * Thus, when receiving a Piece packet, the local peer updates the piece's content locally and then checks
     * if there are any other pieces it wants from the remote peer. If there are, a Request packet is being sent,
     * and otherwise, checks if all pieces were received, in which case, the file is being dumped.
     *
     * @param packet   The Piece Packet
     */
    private void handlePiece(PiecePacket packet) {
        this.localPeerManager.setLocalPiece(packet.getPieceIndex(), PieceStatus.HAVE, packet.getPieceContent(), true);
        this.state.increaseDownloadSpeed();

        // If there's more pieces to request, request one.
        int pieceIndex = this.localPeerManager.choosePieceToRequest(this.state.getPieces());

        if(pieceIndex != -1 && !this.state.isRemoteChoked()) {
            this.sendRequest(pieceIndex);
            return;
        }

        // Check if the connection needs to be terminated (both local & remote peers have all pieces)
        this.localPeerManager.attemptTerminate(this.peerConnectionManager);
    }


    public void sendBitfield() {
        Logger.print(Tag.HANDLER, "Preparing a Bitfield packet to send to peer " +
                this.state.getRemotePeerId());

        BitFieldPacket packet = new BitFieldPacket();
        BitSet bitset = PieceStatus.piecesToBitset(this.localPeerManager.getLocalPieces());

        packet.setData(bitset);

        try {
            this.peerConnectionManager.preparePacket(packet);

            this.state.setSentBitfield(true);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send a Bitfield packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    public void sendInterested() {
        Logger.print(Tag.HANDLER, "Preparing an Interested packet to send to peer " +
                this.state.getRemotePeerId());

        Packet packet = new InterestedPacket();

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send an Interested packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    public void sendNotInterested() {
        Logger.print(Tag.HANDLER, "Preparing a NotInterested packet to send to peer " +
                this.state.getRemotePeerId());

        Packet packet = new NotInterestedPacket();

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send a NotInterested packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    public void sendPiece(int pieceIndex) {
        Logger.print(Tag.HANDLER, "Preparing a Piece packet to send to peer " +
                this.state.getRemotePeerId());

        if(this.localPeerManager.getLocalPieces()[pieceIndex].getContent() == null) {
            System.err.println("Tried to send a PIECE packet with a piece local peer doesn't have to peer " +
                    this.state.getRemotePeerId());
            return;
        }

        PiecePacket packet = new PiecePacket();
        packet.setData(pieceIndex, this.localPeerManager.getLocalPieces()[pieceIndex].getContent());

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send a Piece packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    public void sendHave(int pieceIndex) {
        Logger.print(Tag.HANDLER, "Preparing a Have packet to send to peer " +
                this.state.getRemotePeerId());

        if (!this.state.hasSentBitfield()) {
            System.err.println("Tried to send a HAVE packet before a BITFIELD packet has been sent");
            return;
        }

        HavePacket packet = new HavePacket();
        packet.setData(pieceIndex);

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send a Piece packet to peer " +
                    this.state.getRemotePeerId());
        }

        // Check if this connection should be terminated and terminate it if so
//        this.localPeerManager.checkTerminateConnection(this);
    }

    public void sendChoke() {
        Logger.print(Tag.HANDLER, "Preparing a Choke packet to send to peer " +
                this.state.getRemotePeerId());

        ChokePacket packet = new ChokePacket();

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send a Choke packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    public void sendUnchoke() {
        Logger.print(Tag.HANDLER, "Preparing an Unchoke packet to send to peer " +
                this.state.getRemotePeerId());

        UnchokePacket packet = new UnchokePacket();

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send an Unchoke packet to peer " +
                    this.state.getRemotePeerId());
        }
    }

    private void sendRequest(int pieceIndex) {
        Logger.print(Tag.HANDLER, "Preparing a Request packet to send to peer " +
                this.state.getRemotePeerId());

        if(pieceIndex == -1) {
            System.err.println("Tried to send a REQUEST packet with an invalid piece index");
            return;
        }

        RequestPacket packet = new RequestPacket();
        packet.setData(pieceIndex);

        try {
            this.peerConnectionManager.preparePacket(packet);
        } catch (InterruptedException exception) {
            System.err.println("An error occurred when trying to send an Request packet to peer " +
                    this.state.getRemotePeerId());
        }
    }


    /**
     * Checks if the local peer has any interest in the remote peer
     *
     * @return   Whether the remote peer has any pieces the local peer wants
     */
    private boolean hasInterest() {
        Piece[] local = this.localPeerManager.getLocalPieces();
        PieceStatus[] remote = this.state.getPieces();

        for(int pieceId = 0; pieceId < Math.min(local.length, remote.length); pieceId++) {
            if(local[pieceId].getStatus() == PieceStatus.NOT_HAVE && remote[pieceId] == PieceStatus.HAVE) {
                return true;
            }
        }

        return false;
    }
}