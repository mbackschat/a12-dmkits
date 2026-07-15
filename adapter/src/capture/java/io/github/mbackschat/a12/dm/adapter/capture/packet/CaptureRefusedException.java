package io.github.mbackschat.a12.dm.adapter.capture.packet;

/**
 * A capture/verify run refused BEFORE mutating anything — dirty source in default mode, a non-empty or
 * corpus-tree output directory, a leak in generated metadata. The message is the corrective diagnostic.
 */
public class CaptureRefusedException extends RuntimeException {

    public CaptureRefusedException(String message) {
        super(message);
    }
}
