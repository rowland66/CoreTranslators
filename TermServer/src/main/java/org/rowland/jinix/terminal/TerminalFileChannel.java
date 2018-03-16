package org.rowland.jinix.terminal;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.naming.*;
import org.rowland.jinix.proc.ProcessManager;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.rmi.RemoteException;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

/**
 * Created by rsmith on 11/30/2016.
 */
public class TerminalFileChannel extends JinixKernelUnicastRemoteObject implements RemoteFileAccessor {

    private Terminal parentTerminal;
    private String type;
    LineDiscipline lineDiscipline;
    private volatile int openCount;

    TerminalFileChannel(Terminal terminal, String type, LineDiscipline lineDisipline) throws RemoteException {
        super();
        this.parentTerminal = terminal;
        this.type = type;
        this.lineDiscipline = lineDisipline;
        openCount = 1;
        System.out.println("Opening TFCS("+parentTerminal.getId()+"): open count="+openCount+": "+type);
    }

    @Override
    public byte[] read(int processGroupId, int len) throws RemoteException {

        if (openCount == 0) throw new RemoteException("Illegal attempt to read from a closed TermBuferIS");

        if (type.equals("Slave")) {
            parentTerminal.activeReaderThreads.put(Thread.currentThread(), processGroupId);
        }
        try {
            byte[] b = new byte[len];
            int i =0;
            do {
                while(true) {
                    try {
                        int s = lineDiscipline.read();
                        if (s == -1) {
                            return null;
                        }
                        b[i++] = (byte) s;
                        break;
                    } catch (TerminalBlockedOperationException e) {
                        Integer lockObject = parentTerminal.activeReaderThreads.get(Thread.currentThread());
                        if (parentTerminal.foregroundProcessGroupId != -1) {
                            parentTerminal.signalProcessGroup(lockObject.intValue(), ProcessManager.Signal.TERMINAL_INPUT);
                        }
                        synchronized (lockObject) {
                            try {
                                lockObject.wait();
                            } catch (InterruptedException e1) {
                                return null; // this should never happen
                            }
                        }
                        continue;
                    }
                }
            } while (lineDiscipline.available() > 0);

            if (i < len) {
                byte[] rb = new byte[i];
                System.arraycopy(b, 0, rb, 0, i);
                b = rb;
            }
            return b;
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        } finally {
            if (type.equals("Slave")) {
                parentTerminal.activeReaderThreads.remove(Thread.currentThread());
            }
        }
    }

    @Override
    public int write(int processGroupId, byte[] bs) throws RemoteException {
        if (openCount == 0) throw new RemoteException("Illegal attempt to write to a closed TermBuferOS");

        if (type.equals("Slave")) {
            parentTerminal.activeWriterThreads.put(Thread.currentThread(), processGroupId);
        }

        try {
            for (byte b : bs) {
                while (true) {
                    try {
                        lineDiscipline.write(b);
                        break;
                    } catch (TerminalBlockedOperationException e) {
                        Integer lockObject = parentTerminal.activeWriterThreads.get(Thread.currentThread());
                        if (parentTerminal.foregroundProcessGroupId != -1) {
                            parentTerminal.signalProcessGroup(lockObject.intValue(), ProcessManager.Signal.TERMINAL_OUTPUT);
                        }
                        synchronized (lockObject) {
                            try {
                                lockObject.wait();
                            } catch (InterruptedException e1) {
                                return 0; // this should never happen
                            }
                        }
                        continue;
                    }
                }
            }
            return bs.length;
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        } finally {
            if (type.equals("Slave")) {
                parentTerminal.activeWriterThreads.remove(Thread.currentThread());
            }
        }
    }

    @Override
    public long skip(long n) throws RemoteException {
        return 0;
    }

    @Override
    public int available() throws RemoteException {
        return lineDiscipline.available();
    }

    @Override
    public long getFilePointer() throws RemoteException {
        return 0;
    }

    @Override
    public void seek(long l) throws RemoteException {

    }

    @Override
    public long length() throws RemoteException {
        return 0;
    }

    @Override
    public void setLength(long l) throws RemoteException {

    }

    @Override
    public synchronized void close() throws RemoteException {
        if (openCount > 0) {
            System.out.println("Closing TFCS("+parentTerminal.getId()+"): open count="+openCount+": "+type);
            openCount--;
            if (openCount == 0) {
                System.out.println("Closing TFCS("+parentTerminal.getId()+"): "+type);
                unexport();
            }
        }
    }

    @Override
    public synchronized void duplicate() throws RemoteException {
        openCount++;
        System.out.println("Duplicating TFCS("+parentTerminal.getId()+"): open count="+openCount+": "+type);
    }

    @Override
    public void force(boolean b) throws RemoteException {

    }

    @Override
    public void flush() throws RemoteException {
        lineDiscipline.flush();
    }
}
