package org.rowland.jinix.terminal;

import org.rowland.jinix.naming.RemoteFileAccessor;
import org.rowland.jinix.proc.ProcessManager;

import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Terminal provides a mechanism for interaction between a Jinix process and a
 * virtual terminal. A Terminal provides a MasterTerminalFileDescriptor and a
 * SlaveTerminalFileDescriptor. The master provides input and output streams for
 * the virtual terminal. The slave provides the same for the process. Data written
 * to the output stream of the master is presented on the input stream of the slave.
 * Data written to the output stream of the slave is presented on the input stream
 * of the master.
 */
public class Terminal {

    TermServerServer server;
    short id;
    int linkedProcess;
    int sessionLeaderProcessGroup;
    // Some synchronization is needed here, but it is hard to do.
    volatile int foregroundProcessGroupId;
    Map<Thread, Integer> activeReaderThreads;
    Map<Thread, Integer> activeWriterThreads;
    private TermBuffer inputTermBuffer;
    private TermBuffer outputTermBuffer;
    private Map<PtyMode, Integer> modes;

    /**
     * Constructor used by the TermServerServer to create a new Terminal
     *
     * @param terminalId the ID that identifies the terminal
     * @param overridePtyModes a map of PtyModes that will override the terminal defaults
     */
    Terminal(TermServerServer termServer, short terminalId, Map<PtyMode, Integer> overridePtyModes) {
        server = termServer;
        id = terminalId;
        inputTermBuffer = new TermBuffer(this,1024); // output from the running process
        outputTermBuffer = new TermBuffer(this,1024); // input for the running process
        activeReaderThreads = new HashMap<>(16);
        activeWriterThreads = new HashMap<>(16);
        this.modes = overridePtyModes;
        foregroundProcessGroupId = -1; // Always start with the foreground process group set to -1 to indicate that the terminal is suspended.
        inputTermBuffer.suspendFlow();

        System.out.println("Terminal opened:"+id);
    }

    /**
     * Get the RemoteFileAccessor for the terminal master. The master is used by the terminal (ie ssh) to write typed
     * characters to the shell, and to read output from the shell.
     *
     * @return
     * @throws RemoteException
     */
    RemoteFileAccessor getMasterTerminalFileDescriptor() throws RemoteException {
        return new TerminalFileChannel(this, "Master",
                new LineDiscipline(this, inputTermBuffer, outputTermBuffer, modes));
    }

    /**
     * Get the RemoteFileAccessor for the terminal slave. The slave is used by the shell (ie jsh) to write program output
     * and read program input.
     *
     * @return
     * @throws RemoteException
     */
    RemoteFileAccessor getSlaveTerminalFileDescriptor() throws RemoteException {
        return new TerminalFileChannel(this, "Slave",
                new LineDiscipline(this, outputTermBuffer, inputTermBuffer, null));
    }

    void close() {
        System.out.println("Terminal closed:"+id);
        outputTermBuffer.reset();
    }

    void setLinkedProcess(int pid) {
        linkedProcess = pid;
    }

    int getLinkedProcess() {
        return linkedProcess;
    }

    int getForegroundProcessGroupId() {
        return foregroundProcessGroupId;
    }

    /**
     * Set the foreground process group. Also looks for any threads from the new foreground process in the active thread
     * maps, and wakes them up so that they begin waiting for data. Synchronized to make sure that changes for the
     * foreground process group and the active thread held happen atomically.
     *
     * @param foregroundProcessGroupId
     */
    void setForegroundProcessGroupId(int foregroundProcessGroupId) {
        this.foregroundProcessGroupId = foregroundProcessGroupId;

        if (foregroundProcessGroupId == -1) {
            inputTermBuffer.suspendFlow();
            return;
        }

        inputTermBuffer.resumeFlow();

        for (Integer processGroupId : activeReaderThreads.values()) {
            if (processGroupId == foregroundProcessGroupId) {
                synchronized (processGroupId) {
                    processGroupId.notify();
                }
            }
        }

        for (Integer processGroupId : activeWriterThreads.values()) {
            if (processGroupId == foregroundProcessGroupId) {
                synchronized (processGroupId) {
                    processGroupId.notify();
                }
            }
        }

        // The shell is the first foreground process group set for a terminal. It is remembered as the session leader
        // and will have special privileges.
        if (sessionLeaderProcessGroup == 0) {
            sessionLeaderProcessGroup = foregroundProcessGroupId;
        }
    }

    short getId() {
        return id;
    }

    void signalForegroundProcessGroup(ProcessManager.Signal signal) {
        try {
            server.processManager.sendSignalProcessGroup(foregroundProcessGroupId, signal);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    void signalProcessGroup(int processGroupId, ProcessManager.Signal signal) {
        try {
            server.processManager.sendSignalProcessGroup(processGroupId, signal);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    boolean isActiveReader(int pgid) {
        for (Integer processGroupId : activeReaderThreads.values()) {
            if (pgid == processGroupId) {
                return true;
            }
        }
        return false;
    }

    boolean isActiveWriter(int pgid) {
        for (Integer processGroupId : activeWriterThreads.values()) {
            if (pgid == processGroupId) {
                return true;
            }
        }
        return false;
    }

    private Map<PtyMode, Integer> convertRawTtyOptions(Map<Byte, Integer> inputMap) {
        Map<PtyMode, Integer> outputMap = new HashMap<PtyMode, Integer>(inputMap.size());
        for(Map.Entry<Byte, Integer> entry : inputMap.entrySet()) {
            outputMap.put(PtyMode.fromInt(entry.getKey()), entry.getValue());
        }
        return outputMap;
    }
}
