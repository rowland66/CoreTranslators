package org.rowland.jinix.fifo;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.io.JinixFile;
import org.rowland.jinix.io.JinixFileDescriptor;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.naming.NameSpace;
import org.rowland.jinix.proc.ProcessManager;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.rmi.RemoteException;

/**
 * Server that provides Fifo pipes. A Fifo pipe can be used to connect two processes.
 */
public class FifoServerServer extends JinixKernelUnicastRemoteObject implements FifoServer {

    private static FifoServerServer server;
    private static Thread mainThread;

    private FifoServerServer() throws RemoteException {
        super();
    }

    @Override
    public FileChannelPair openPipe() throws RemoteException {
        try {
            PipedOutputStream os = new PipedOutputStream();
            PipedInputStream is = new PipedInputStream(os);

            FileChannelPair rtrn = new FileChannelPair(
                    new PipedInputStreamFileChannelServer(is),
                    new PipedOutputStreamFileChannelServer(os)
            );

            return rtrn;
        } catch (IOException e) {
            throw new RemoteException("FifoServer: failed to create pipe", e);
        }
    }

    public static void main(String[] args) {

        JinixFile translatorFile = JinixRuntime.getRuntime().getTranslatorFile();

        if (translatorFile == null) {
            System.err.println("Translator must be started with settrans");
            return;
        }

        try {
            server = new FifoServerServer();
        } catch (RemoteException e) {
            throw new RuntimeException("FifoServer: failed initialization",e);
        }

        JinixRuntime.getRuntime().bindTranslator(server);

        mainThread = Thread.currentThread();

        System.out.println("FifoServer: server started and bound to root namespace at "+FifoServer.SERVER_NAME);

        JinixRuntime.getRuntime().registerSignalHandler(new ProcessSignalHandler() {
            @Override
            public boolean handleSignal(ProcessManager.Signal signal) {
                if (signal == ProcessManager.Signal.TERMINATE) {
                    mainThread.interrupt();
                    return true;
                }
                return false;
            }
        });

        try {
            Thread.sleep(Integer.MAX_VALUE);
        } catch (InterruptedException e) {
            // Interrupted shutting down
        }

        server.unexport();
        System.out.println("FifoServer: server shutdown complete");
    }
}
