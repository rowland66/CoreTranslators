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

    public static final Set<PtyMode> OUTPUT_OPTIONS =
            Collections.unmodifiableSet(EnumSet.of(PtyMode.ECHO, PtyMode.INLCR, PtyMode.ICRNL, PtyMode.IGNCR));
    public static final Set<PtyMode> INPUT_OPTIONS =
            Collections.unmodifiableSet(EnumSet.of(PtyMode.ONLCR, PtyMode.OCRNL, PtyMode.ONLRET, PtyMode.ONOCR));

    private Terminal parentTerminal;
    private TermBuffer outputBuffer;
    private TermBuffer inputBuffer;
    private Set<PtyMode> outputTtyOptions = null;
    private Set<PtyMode> inputTtyOptions = null;
    private int lastChar = -1;
    private byte pushbackByte = 0;
    private boolean raw = false;
    private byte verase, vquit, vintr, vkill, veof, vsusp, vstop, vstart = 0;

    LineDiscipline(Terminal terminal, TermBuffer outputBuffer, TermBuffer inputBuffer, Map<PtyMode, Integer> modes) {
        this.parentTerminal = terminal;
        this.outputBuffer = outputBuffer;
        this.inputBuffer = inputBuffer;

        if (modes == null) {
            raw = true; // No modes means we are in raw mode. The slave LineDiscipline is always raw.
        } else {
            this.outputTtyOptions = PtyMode.resolveEnabledOptions(modes, OUTPUT_OPTIONS);
            this.inputTtyOptions = PtyMode.resolveEnabledOptions(modes, INPUT_OPTIONS);

            setModeBytes(modes);
        }
    }

    private void setModeBytes(Map<PtyMode, Integer> modes) {

        vintr = TermServerServer.server.defaultModes.get(PtyMode.VINTR).byteValue();
        if (modes.containsKey(PtyMode.VINTR)) {
            vintr = modes.get(PtyMode.VINTR).byteValue();
        }

        vquit = TermServerServer.server.defaultModes.get(PtyMode.VQUIT).byteValue();
        if (modes.containsKey(PtyMode.VQUIT)) {
            vquit = modes.get(PtyMode.VQUIT).byteValue();
        }

        verase = TermServerServer.server.defaultModes.get(PtyMode.VERASE).byteValue();
        if (modes.containsKey(PtyMode.VERASE)) {
            verase = modes.get(PtyMode.VERASE).byteValue();
        }

        vkill = TermServerServer.server.defaultModes.get(PtyMode.VKILL).byteValue();
        if (modes.containsKey(PtyMode.VKILL)) {
            vkill = modes.get(PtyMode.VKILL).byteValue();
        }

        veof = TermServerServer.server.defaultModes.get(PtyMode.VEOF).byteValue();
        if (modes.containsKey(PtyMode.VEOF)) {
            veof = modes.get(PtyMode.VEOF).byteValue();
        }

        vsusp = TermServerServer.server.defaultModes.get(PtyMode.VSUSP).byteValue();
        if (modes.containsKey(PtyMode.VSUSP)) {
            vsusp = modes.get(PtyMode.VSUSP).byteValue();
        }

        vstop = TermServerServer.server.defaultModes.get(PtyMode.VSTOP).byteValue();
        if (modes.containsKey(PtyMode.VSTOP)) {
            vstop = modes.get(PtyMode.VSTOP).byteValue();
        }

        vstart = TermServerServer.server.defaultModes.get(PtyMode.VSTART).byteValue();
        if (modes.containsKey(PtyMode.VSTART)) {
            vstart = modes.get(PtyMode.VSTART).byteValue();
        }
    }

    public void write(int c) throws IOException {

        if (raw) {
            writeRawOutput(c);
        } else {
            if (c == '\r') {
                handleOutputCR();
            } else if (c == '\n') {
                handleOutputLF();
            } else if (c == verase) {
                if (this.outputBuffer.erase()) {
                    echoCharacter((byte) c);
                } else {
                    echoCharacter((byte) CODE_BEL);
                }
            } else if (c == vstop) {
                this.inputBuffer.suspendFlow();
            } else if (c == vstart) {
                this.inputBuffer.resumeFlow();
            } else if (c == vintr) {
                parentTerminal.signalForegroundProcessGroup(ProcessManager.Signal.TERMINATE);
                parentTerminal.setForegroundProcessGroupId(-1);
            } else if (c == vsusp) {
                parentTerminal.signalForegroundProcessGroup(ProcessManager.Signal.TSTOP);
                parentTerminal.setForegroundProcessGroupId(-1);
            } else {
                writeRawOutput(c);
            }
        }
    }

    protected void handleOutputCR() throws IOException {
        if (outputTtyOptions.contains(PtyMode.ICRNL)) {
            writeRawOutput('\n');   // Map CR to NL on input
            this.outputBuffer.flip();
        } else if (outputTtyOptions.contains(PtyMode.IGNCR)) {
            return;    // Ignore CR on input
        } else {
            writeRawOutput('\r');
        }
    }

    protected void handleOutputLF() throws IOException {
        if (outputTtyOptions.contains(PtyMode.INLCR)) {
            writeRawOutput('\r');   // Map NL into CR on input
        } else {
            writeRawOutput('\n');
        }

        if (!raw) {
            this.outputBuffer.flip();
        }
    }

    protected void writeRawOutput(int c) throws IOException {
        int bytesPut = this.outputBuffer.put((byte) c);
        if (!raw) {
            echoCharacter((byte) c);
        }
    }

    protected void flush() {
        if (raw) {
            this.outputBuffer.flip();
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
        if (inputTtyOptions.contains(PtyMode.OCRNL)) {
            return '\n';    // Translate carriage return to newline
        } else {
            return '\r';
        }
    }

    protected int handleInputLF() throws IOException {
        // Map NL to CR-NL.
        if ((inputTtyOptions.contains(PtyMode.ONLCR) || inputTtyOptions.contains(PtyMode.ONOCR)) && (lastChar != '\r')) {
            this.pushbackByte = '\n';
            return '\r';
        } else if (inputTtyOptions.contains(PtyMode.ONLRET)) {   // Newline performs a carriage return
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
        if (outputTtyOptions.contains(PtyMode.ECHO)) {
            if (inputBuffer.putIfPossible(c) > 0) {
                inputBuffer.flip();
            }
        }

    }

}
