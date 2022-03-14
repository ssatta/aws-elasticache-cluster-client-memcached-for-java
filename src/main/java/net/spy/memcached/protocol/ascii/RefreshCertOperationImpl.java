package net.spy.memcached.protocol.ascii;

import net.spy.memcached.ops.OperationCallback;
import net.spy.memcached.ops.OperationState;
import net.spy.memcached.ops.OperationStatus;
import net.spy.memcached.ops.StatusCode;

import java.nio.ByteBuffer;

public class RefreshCertOperationImpl extends OperationImpl {

    private static final String CMD = "refresh_certs\r\n";
    private static final OperationStatus OK = new OperationStatus(true,"OK", StatusCode.SUCCESS);

    public RefreshCertOperationImpl(OperationCallback cb) {
        super(cb);
    }

    @Override
    public void handleLine(String line) {
        assert getState() == OperationState.READING : "Read ``" + line
                + "'' when in " + getState() + " state";
        getCallback().receivedStatus(matchStatus(line, OK));
        transitionState(OperationState.COMPLETE);
    }

    @Override
    public void initialize() {
        ByteBuffer bb = ByteBuffer.allocate(CMD.length());
        bb.put(CMD.getBytes());
        bb.flip();
        setBuffer(bb);
    }

    @Override
    public String toString() {
        return "Cmd: " + CMD;
    }
}
