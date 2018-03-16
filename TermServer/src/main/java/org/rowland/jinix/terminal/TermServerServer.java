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

import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

/**
 * Created by rsmith on 11/28/2016.
 */
class TermServerServer extends JinixKernelUnicastRemoteObject implements TermServer {

    static TermServerServer server;
    private static Thread mainThread;

    private Terminal[] term;
    private NameSpace rootNamespace;
    ProcessManager processManager;
    private ResumeEventNotificationHandler processManagerEventHandler;

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

        processManagerEventHandler = new ResumeEventNotificationHandler();
        processManager.registerGlobalEventNotificationHandler(ProcessManager.EventName.RESUME,
                processManagerEventHandler);
    }


    @Override
    public short createTerminal() throws RemoteException {
        return createTerminal(null, null, null, null);
    }

    @Override
    public short createTerminal(Set<InputMode> im, Set<OutputMode> om, Set<LocalMode> lm,
                                Map<SpecialCharacter, Byte> cc)
            throws RemoteException {

        for (short i = 0; i < 255; i++) {
            if(term[i]==null) {
                term[i] = new Terminal(server, i, im, om, lm, cc);
                return i;
            }
        }

        throw new RuntimeException("TermServer: Unable to allocate a new Terminal");
    }

    @Override
    public void linkProcessToTerminal(short termId, int pid) throws RemoteException {

        if (term[termId] == null) throw new RuntimeException("TermServer: Attempt to link a process to an unallocated terminal");
        term[termId].setLinkedProcess(pid);
        term[termId].deRegisterEventNotificationHandler = new DeRegisterEventNotificationHandler();
        processManager.registerEventNotificationHandler(pid, ProcessManager.EventName.DEREGISTER,
                term[termId].deRegisterEventNotificationHandler);
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

    @Override
    public TerminalAttributes getTerminalAttributes(short termId) throws RemoteException {
        if (term[termId] == null) {
            throw new IllegalArgumentException("Unassigned terminal ID: "+termId);
        }

        TerminalAttributes rtrn = new TerminalAttributes();
        rtrn.inputModes = term[termId].getInputModes();
        rtrn.outputModes = term[termId].getOutputModes();
        rtrn.localModes = term[termId].getLocalModes();
        rtrn.specialCharacterMap = term[termId].getSpecialCharacters();
        return rtrn;
    }

    @Override
    public void setTerminalAttributes(short termId, TerminalAttributes terminalAttributes) {
        if (terminalAttributes == null) {
            return;
        }

        if (term[termId] == null) {
            throw new IllegalArgumentException("Unassigned terminal ID: "+termId);
        }

        term[termId].setInputModes(terminalAttributes.inputModes);
        term[termId].setOutputModes(terminalAttributes.outputModes);
        term[termId].setLocalModes(terminalAttributes.localModes);
        term[termId].setSpecialCharacters(terminalAttributes.specialCharacterMap);
    }

    private void shutdown() {
        for (short i = 0; i < 255; i++) {
            if(term[i] != null) {
                term[i].close();
            }
        }

        processManagerEventHandler.unexport();
        unexport();
    }

    public class DeRegisterEventNotificationHandler extends JinixKernelUnicastRemoteObject implements EventNotificationHandler {

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

    public class ResumeEventNotificationHandler extends JinixKernelUnicastRemoteObject implements EventNotificationHandler {

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
