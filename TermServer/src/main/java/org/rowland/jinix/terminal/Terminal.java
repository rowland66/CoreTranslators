package org.rowland.jinix.terminal;

import org.rowland.jinix.naming.RemoteFileAccessor;
import org.rowland.jinix.proc.ProcessManager;

import java.rmi.RemoteException;
import java.util.*;

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

    private static final Set<InputMode> DEFAULT_INPUT_MODES =
            Collections.unmodifiableSet(EnumSet.of(InputMode.ICRNL, InputMode.IXON));
    private static final Set<OutputMode> DEFAULT_OUTPUT_MODES =
            Collections.unmodifiableSet(EnumSet.of(OutputMode.ONLCR));
    private static final Set<LocalMode> DEFAULT_LOCAL_MODES =
            Collections.unmodifiableSet(EnumSet.of(LocalMode.ECHO, LocalMode.ISIG, LocalMode.ICANON, LocalMode.IEXTEN, LocalMode.TOSTOP));

    private static final EnumMap<SpecialCharacter, Byte> DEFAULT_SPECIAL_CHARACTERS;

    static {
        Map<SpecialCharacter, Byte> defaultMap = new EnumMap<SpecialCharacter, Byte>(SpecialCharacter.class);
        defaultMap.put(SpecialCharacter.VINTR, (byte) 0x03);
        defaultMap.put(SpecialCharacter.VQUIT, (byte) 0x1c);
        defaultMap.put(SpecialCharacter.VERASE, (byte) 0x7f);
        defaultMap.put(SpecialCharacter.VKILL, (byte) 0x15);
        defaultMap.put(SpecialCharacter.VEOF, (byte) 0x04);
        defaultMap.put(SpecialCharacter.VEOL, (byte) 0x00);
        defaultMap.put(SpecialCharacter.VSUSP, (byte) 0x1a);
        defaultMap.put(SpecialCharacter.VSTOP, (byte) 0x13);
        defaultMap.put(SpecialCharacter.VSTART, (byte) 0x11);

        DEFAULT_SPECIAL_CHARACTERS = new EnumMap<SpecialCharacter, Byte>(defaultMap);
    }

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
    TermServerServer.DeRegisterEventNotificationHandler deRegisterEventNotificationHandler;

    // termios structure data
    EnumSet<InputMode> inputModes;
    EnumSet<OutputMode> outputModes;
    EnumSet<LocalMode> localModes;
    EnumMap<SpecialCharacter, Byte> specialCharacters;

    /**
     * Constructor used by the TermServerServer to create a new Terminal
     *
     * @param terminalId the ID that identifies the terminal
     * @param overridePtyModes a map of PtyModes that will override the terminal defaults
     */
    Terminal(TermServerServer termServer, short terminalId,
             Set<InputMode> im, Set<OutputMode> om, Set<LocalMode> lm,
             Map<SpecialCharacter, Byte> cc) {
        server = termServer;
        id = terminalId;
        inputTermBuffer = new TermBuffer(this,1024); // output from the running process
        outputTermBuffer = new TermBuffer(this,1024); // input for the running process
        activeReaderThreads = new HashMap<>(16);
        activeWriterThreads = new HashMap<>(16);

        inputModes = EnumSet.copyOf(im != null ? im : DEFAULT_INPUT_MODES);
        outputModes = EnumSet.copyOf(om != null ? om : DEFAULT_OUTPUT_MODES);
        localModes = EnumSet.copyOf(lm != null ? lm : DEFAULT_LOCAL_MODES);
        specialCharacters = new EnumMap<SpecialCharacter, Byte>(DEFAULT_SPECIAL_CHARACTERS);
        applySpecialCharacterOverrides(cc);

        foregroundProcessGroupId = -1; // Always start with the foreground process group set to -1 to indicate that the terminal is suspended.
        inputTermBuffer.suspendFlow();

        System.out.println("Terminal opened:"+id);
    }

    private void applySpecialCharacterOverrides(Map<SpecialCharacter, Byte> cc) {
        if (cc == null) return;
        for (Map.Entry<SpecialCharacter, Byte> entry : cc.entrySet()) {
            specialCharacters.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Get the RemoteFileAccessor for the terminal master. The master is used by the terminal (ie ssh) to write typed
     * characters to the shell, and to read output from the shell.
     *
     * @return
     * @throws RemoteException
     */
    RemoteFileAccessor getMasterTerminalFileDescriptor() throws RemoteException {
        return new TerminalFileChannel(this, TerminalFileChannel.TerminalType.MASTER,
                new LineDiscipline(this, inputTermBuffer, outputTermBuffer, false));
    }

    /**
     * Get the RemoteFileAccessor for the terminal slave. The slave is used by the shell (ie jsh) to write program output
     * and read program input.
     *
     * @return
     * @throws RemoteException
     */
    RemoteFileAccessor getSlaveTerminalFileDescriptor() throws RemoteException {
        return new TerminalFileChannel(this, TerminalFileChannel.TerminalType.SLAVE,
                new LineDiscipline(this, outputTermBuffer, inputTermBuffer, true));
    }

    void close() {
        System.out.println("Terminal closed:"+id);
        outputTermBuffer.reset();
        inputTermBuffer.reset();
        if (deRegisterEventNotificationHandler != null) {
            deRegisterEventNotificationHandler.unexport();
            deRegisterEventNotificationHandler = null;
        }
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

    Set<InputMode> getInputModes() {
        return EnumSet.copyOf(inputModes);
    }

    void setInputModes(Set<InputMode> im) {
        inputModes = EnumSet.copyOf(im);
    }

    Set<OutputMode> getOutputModes() {
        return EnumSet.copyOf(outputModes);
    }

    void setOutputModes(Set<OutputMode> om) {
        outputModes = EnumSet.copyOf(om);
    }

    Set<LocalMode> getLocalModes() {
        return EnumSet.copyOf(localModes);
    }

    void setLocalModes(Set<LocalMode> lm) {
        localModes = EnumSet.copyOf(lm);
    }

    Map<SpecialCharacter, Byte> getSpecialCharacters() {
        return new EnumMap<SpecialCharacter, Byte>(specialCharacters);
    }

    void setSpecialCharacters(Map<SpecialCharacter, Byte> cc) {
        for (Map.Entry<SpecialCharacter, Byte> ccEntry : cc.entrySet()) {
            specialCharacters.put(ccEntry.getKey(), ccEntry.getValue());
        }
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


}
