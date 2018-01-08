package org.rowland.jinix.terminal;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.io.JinixFile;
import org.rowland.jinix.io.JinixFileDescriptor;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.naming.FileNameSpace;
import org.rowland.jinix.naming.NameSpace;
import org.rowland.jinix.naming.RemoteFileAccessor;
import org.rowland.jinix.proc.EventData;
import org.rowland.jinix.proc.EventNotificationHandler;
import org.rowland.jinix.proc.ProcessManager;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by rsmith on 11/28/2016.
 */
class TermServerServer extends JinixKernelUnicastRemoteObject implements TermServer {

    static TermServerServer server;
    private static Thread mainThread;

    private Terminal[] term;
    private NameSpace rootNamespace;
    ProcessManager processManager;

    Map<PtyMode, Integer> defaultModes = new HashMap<>();

    private TermServerServer() throws RemoteException {

        super();
        rootNamespace = JinixRuntime.getRuntime().getRootNamespace();

        while (true) {
            try {
                processManager = (ProcessManager) rootNamespace.lookup(ProcessManager.SERVER_NAME).remote;
            } catch (RemoteException e) {
                System.err.println("Term: Failed to locate process manager at " + ProcessManager.SERVER_NAME);
            }
            if (processManager == null) {
                try {
                    Thread.sleep(500);
                    continue;
                } catch (InterruptedException e) {
                    System.exit(0);
                }
            }
            break;
        }

        term = new Terminal[256];

        processManager.registerGlobalEventNotificationHandler(ProcessManager.EventName.RESUME,
                new ResumeEventNotificationHandler());


        setupDefaultPtyModes();
    }

    private void setupDefaultPtyModes() {
        defaultModes.put(PtyMode.VINTR, 0x03);
        defaultModes.put(PtyMode.VQUIT, 0x1c);
        defaultModes.put(PtyMode.VERASE, 0x7F);
        defaultModes.put(PtyMode.VKILL, 0x15);
        defaultModes.put(PtyMode.VEOF, 0x04);
        defaultModes.put(PtyMode.VSTART, 0x11);
        defaultModes.put(PtyMode.VSTOP, 0x13);
        defaultModes.put(PtyMode.VSUSP, 0x1a);
        defaultModes.put(PtyMode.VREPRINT, 0x12);
        defaultModes.put(PtyMode.VWERASE, 0x17);
        defaultModes.put(PtyMode.VLNEXT, 0x16);
        defaultModes.put(PtyMode.VFLUSH, 0x0f);
    }

    @Override
    public short createTerminal() throws RemoteException {
        return createTerminal(Collections.emptyMap());
    }

    @Override
    public short createTerminal(Map<PtyMode, Integer> ttyOptions) throws RemoteException {
        for (short i = 0; i < 255; i++) {
            if(term[i]==null) {
                term[i] = new Terminal(server, i, ttyOptions);
                return i;
            }
        }

        throw new RuntimeException("TermServer: Unable to allocate a new Terminal");
    }

    @Override
    public void linkProcessToTerminal(short termId, int pid) throws RemoteException {

        if (term[termId] == null) throw new RuntimeException("TermServer: Attempt to link a process to an unallocated terminal");
        term[termId].setLinkedProcess(pid);
        processManager.registerEventNotificationHandler(pid, ProcessManager.EventName.DEREGISTER,
                new DeRegisterEventNotificationHandler());

    }

    @Override
    public RemoteFileAccessor getTerminalMaster(short termId) throws RemoteException {
        return term[termId].getMasterTerminalFileDescriptor();
    }

    @Override
    public RemoteFileAccessor getTerminalSlave(short termId) throws RemoteException {
        return term[termId].getSlaveTerminalFileDescriptor();
    }

    @Override
    public void setTerminalForegroundProcessGroup(short termId, int processGroupId) throws RemoteException {
        term[termId].setForegroundProcessGroupId(processGroupId);
    }

    private void shutdown() {
        for (short i = 0; i < 255; i++) {
            if(term[i] != null) {
                term[i].close();
            }
        }
        unexport();
    }

    public class DeRegisterEventNotificationHandler extends UnicastRemoteObject implements EventNotificationHandler {

        private DeRegisterEventNotificationHandler() throws RemoteException {
            super(0, RMISocketFactory.getSocketFactory(), RMISocketFactory.getSocketFactory());
        }

        @Override
        public void handleEventNotification(ProcessManager.EventName event, Object eventData) throws RemoteException {

            if (!event.equals(ProcessManager.EventName.DEREGISTER)) {
                return; // This should never happen as we have only registered for DEREGISTER events
            }

            // Check if the process is a session leader and is assigned to a terminal
            if (((EventData) eventData).pid == ((EventData) eventData).sessionId && ((EventData) eventData).terminalId != -1) {
                Terminal t = term[((EventData) eventData).terminalId];
                if (t != null) {
                    t.close();
                    term[((EventData) eventData).terminalId] = null;
                }
            }
        }
    }

    public class ResumeEventNotificationHandler extends UnicastRemoteObject implements EventNotificationHandler {

        private ResumeEventNotificationHandler() throws RemoteException {
            super(0, RMISocketFactory.getSocketFactory(), RMISocketFactory.getSocketFactory());
        }

        @Override
        public void handleEventNotification(ProcessManager.EventName event, Object eventData) throws RemoteException {

            if (!event.equals(ProcessManager.EventName.RESUME)) {
                return; // This should never happen as we have only registered for RESUME events
            }

            Terminal t = term[((EventData) eventData).terminalId];
            int pgid = ((EventData) eventData).pgid;

            if (pgid != t.foregroundProcessGroupId && t.isActiveReader(pgid)) {
                processManager.sendSignalProcessGroup(pgid, ProcessManager.Signal.TERMINAL_INPUT);
            }

            if (pgid != t.foregroundProcessGroupId && t.isActiveWriter(pgid)) {
                processManager.sendSignalProcessGroup(pgid, ProcessManager.Signal.TERMINAL_OUTPUT);
            }
        }
    }

    // FileNameSpace implementation starts here

    public static void main(String[] args) {

            JinixFileDescriptor fd = JinixRuntime.getRuntime().getTranslatorFile();

            if (fd == null) {
                System.err.println("Translator must be started with settrans");
                return;
            }
            try {
                server = new TermServerServer();
            } catch (RemoteException e) {
                throw new RuntimeException("Translator failed initialization",e);
            }

            JinixRuntime.getRuntime().bindTranslator(server);

            mainThread = Thread.currentThread();

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

            server.shutdown();

            System.out.println("TermServer shutdown complete");
    }
}
