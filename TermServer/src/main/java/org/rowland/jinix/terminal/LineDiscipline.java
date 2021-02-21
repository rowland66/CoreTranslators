package org.rowland.jinix.terminal;

import org.rowland.jinix.proc.ProcessManager;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by rsmith on 12/30/2016.
 */
public class LineDiscipline {

    public static final int CODE_ETX = 3; // ctrl+c
    public static final int CODE_BEL = 7; // Ring the bell
    public static final int CODE_DC1 = 17; // XON
    public static final int CODE_DC3 = 19; // XOFF
    public static final int CODE_SUB = 26; // ctrl+z

    private Terminal parentTerminal;
    private TermBuffer outputBuffer;
    private TermBuffer inputBuffer;
    private int lastChar = -1;
    private byte pushbackByte = 0;
    private boolean raw = false;

    LineDiscipline(Terminal terminal, TermBuffer outputBuffer, TermBuffer inputBuffer, boolean raw) {
        this.parentTerminal = terminal;
        this.outputBuffer = outputBuffer;
        this.inputBuffer = inputBuffer;

        this.raw = raw; // The slave LineDiscipline is always raw.
    }

    // The terminal writing to the master, or the program writing to the slave (raw)
    public void write(int c) throws IOException {

        if (raw) {
            writeRawOutput(c);
            if (!parentTerminal.localModes.contains(LocalMode.ICANON)) {
                this.outputBuffer.flush();
            }
        } else {
            if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VSTOP) && parentTerminal.inputModes.contains(InputMode.IXON)) {
                this.inputBuffer.suspendFlow();
            } else if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VSTART) && parentTerminal.inputModes.contains(InputMode.IXON)) {
                this.inputBuffer.resumeFlow();
            } else if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VINTR) && parentTerminal.localModes.contains(LocalMode.ISIG)) {
                parentTerminal.signalForegroundProcessGroup(ProcessManager.Signal.TERMINATE);
            } else if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VSUSP) && parentTerminal.localModes.contains(LocalMode.ISIG)) {
                parentTerminal.signalForegroundProcessGroup(ProcessManager.Signal.TSTOP);
            } else if (c == '\r') {
                    handleOutputCR();
            } else if (c == '\n') {
                    handleOutputLF();
            } else if (parentTerminal.localModes.contains(LocalMode.ICANON)) {
                if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VERASE)) {
                    if (this.outputBuffer.erase()) {
                        echoCharacter((byte) 0x08); // ctrl-h
                        echoCharacter((byte) 0x1b); //ESC
                        echoCharacter((byte) '[');
                        echoCharacter((byte) 'K');
                    } else {
                        echoCharacter((byte) CODE_BEL);
                    }
                } else if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VKILL)) {
                    int chars = this.outputBuffer.kill();
                    if (chars > 0) {
                        for ( ; chars > 0; chars--) {
                            echoCharacter((byte) 0x08); // ctrl-h
                        }
                        echoCharacter((byte) 0x1b); //ESC
                        echoCharacter((byte) '[');
                        echoCharacter((byte) 'K');
                    } else {
                        echoCharacter((byte) CODE_BEL);
                    }
                } else if (c == parentTerminal.specialCharacters.get(SpecialCharacter.VEOF)) {
                    this.outputBuffer.flush();
                } else {
                    writeRawOutput(c);
                }
            } else {
                writeRawOutput(c);
                this.outputBuffer.flush();
            }
        }
    }

    protected void handleOutputCR() throws IOException {
        if (parentTerminal.inputModes.contains(InputMode.IGNCR)) {
            return;    // Ignore CR on input
        }
        if (parentTerminal.inputModes.contains(InputMode.ICRNL)) {
            writeRawOutput('\n');   // Map CR to NL on input
        } else {
            writeRawOutput('\r');
        }
        this.outputBuffer.flush();
    }

    protected void handleOutputLF() throws IOException {
        if (parentTerminal.inputModes.contains(InputMode.INLCR)) {
            writeRawOutput('\r');   // Map NL into CR on input
        } else {
            writeRawOutput('\n');
        }
        this.outputBuffer.flush();
    }

    protected void writeRawOutput(int c) throws IOException {
        int bytesPut = this.outputBuffer.put((byte) c);
        if (!raw) {
            echoCharacter((byte) c);
        }
    }

    protected void flush() {
        if (raw) {
            this.outputBuffer.flush();
        }
    }

    public int available() {
        if (pushbackByte > 0) {
            return 1;
        }
        return inputBuffer.available();
    }

    public int read() throws IOException {
        int c = readRawInput();

        if (!raw) {
            if (c == '\r') {
                c = handleInputCR();
            } else if (c == '\n') {
                c = handleInputLF();
            }
            lastChar = c;
        }
        return c;
    }

    protected int handleInputCR() throws IOException {
        if (parentTerminal.outputModes.contains(OutputMode.OCRNL)) {
            return '\n';    // Translate carriage return to newline
        } else {
            return '\r';
        }
    }

    protected int handleInputLF() throws IOException {
        // Map NL to CR-NL.
        if ((parentTerminal.outputModes.contains(OutputMode.ONLCR) || parentTerminal.outputModes.contains(OutputMode.ONOCR)) && (lastChar != '\r')) {
            this.pushbackByte = '\n';
            return '\r';
        } else if (parentTerminal.outputModes.contains(OutputMode.ONLRET)) {   // Newline performs a carriage return
            return '\r';
        } else {
            return '\n';
        }
    }

    protected int readRawInput() throws IOException {

        int c;
        if (pushbackByte != 0) {
            c = pushbackByte;
            pushbackByte = 0;
            return c;
        }
        return inputBuffer.get();
    }

    private void echoCharacter(byte c) {
        if (parentTerminal.localModes.contains(LocalMode.ECHO)) {
            if (inputBuffer.putIfPossible(c) > 0) {
                inputBuffer.flush();
            }
        }

    }

}
