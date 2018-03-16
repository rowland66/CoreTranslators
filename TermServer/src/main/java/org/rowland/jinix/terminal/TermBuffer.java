package org.rowland.jinix.terminal;

/**
 * Created by rsmith on 11/28/2016.
 */
public class TermBuffer {

    byte[] buffer;
    enum mode_value {read, write};
    mode_value mode;
    int position;
    int limit;
    Terminal parentTerminal;
    private volatile boolean suspended = false;
    private volatile boolean requestSuspend = false;

    TermBuffer(Terminal parent, int capacity) {
        this.parentTerminal = parent;
        this.buffer = new byte[capacity];
        this.mode = mode_value.write;
        this.position = 0;
        this.limit = 0;
    }

    synchronized int putIfPossible(byte b) {

        int bytesAvailable = buffer.length-position;

        if (bytesAvailable > 0) {
            return put(b);
        } else {
            return 0;
        }
    }

    synchronized int put(byte b) {

        int bytesAvailable = buffer.length - position;

        while (mode == mode_value.read || bytesAvailable == 0) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return 0;
            }
            bytesAvailable = buffer.length - position;
        }

        if (parentTerminal.activeWriterThreads.get(Thread.currentThread()) != null) {
            Integer processGroupId = parentTerminal.activeWriterThreads.get(Thread.currentThread());
            if (parentTerminal.localModes.contains(LocalMode.TOSTOP) && processGroupId != parentTerminal.foregroundProcessGroupId) {
                throw new TerminalBlockedOperationException();
            }
        }

        bytesAvailable = buffer.length-position;

        if (bytesAvailable > 0) {
            buffer[position++] = b;
            limit = position;
        } else {
            flip();
            return put(b);
        }

        bytesAvailable = buffer.length-position;
        if (bytesAvailable == 0) {
            flip();
        }

        return 1;
    }

    synchronized int put(byte[] b, int offset, int length) {
        while (mode == mode_value.read) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return 0;
            }

            if (parentTerminal.activeWriterThreads.get(Thread.currentThread()) != null) {
                Integer processGroupId = parentTerminal.activeWriterThreads.get(Thread.currentThread());
                if (processGroupId != parentTerminal.foregroundProcessGroupId) {
                    throw new TerminalBlockedOperationException();
                }
            }
        }

        int bytesToWrite = Math.min(length, buffer.length-position);

        System.arraycopy(b, offset, buffer, position, bytesToWrite);
        position = position + bytesToWrite;
        limit = position;

        return bytesToWrite;
    }

    synchronized boolean erase() {
        if (mode == mode_value.read) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return false;
            }
        }

        if (position > 0) {
            position--;
            return true;
        }

        return false;
    }

    synchronized int kill() {
        if (mode == mode_value.read) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return 0;
            }
        }

        if (position > 0) {
            int rtrnVal = position;
            position = 0;
            return rtrnVal;
        }

        return 0;
    }

    synchronized int get() {

        while (mode == mode_value.write || suspended) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return 0; // This should never happen
            }
        }

        // If this thread is not in the process group that is the foreground, so it should not receive input. get() is
        // call by both master and the slave. Only the slave will populate the map, if the thread is not in the map, it
        // means that the master is calling.
        if (parentTerminal.activeReaderThreads.get(Thread.currentThread()) != null) {
            Integer processGroupId = parentTerminal.activeReaderThreads.get(Thread.currentThread());
            if (processGroupId != parentTerminal.foregroundProcessGroupId) {
                throw new TerminalBlockedOperationException();
            }
        }

        if (this.limit == 0) {
            flip();
            return -1;
        }

        int rtrn = buffer[this.position++];

        if (this.position == this.limit) {
            limit = 0;
            flip();
        }

        return rtrn;
    }

    synchronized int get(byte[] b) {
        while (mode == mode_value.write || suspended) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return 0;
            }

            if (parentTerminal.activeReaderThreads.get(Thread.currentThread()) != null) {
                Integer processGroupId = parentTerminal.activeReaderThreads.get(Thread.currentThread());
                if (processGroupId != parentTerminal.foregroundProcessGroupId) {
                    throw new TerminalBlockedOperationException();
                }
            }
        }

        // If position equals limit and the buffer is in read mode, then the buffer has been reset.
        if (this.position == this.limit) {
            return -1;
        }

        int rtrn = Math.min(b.length, this.limit - this.position);
        System.arraycopy(buffer, this.position, b, 0, rtrn);

        this.position = this.position + rtrn;
        if (this.position == this.limit) {
            limit = 0;
            flip();
        }

        return rtrn;
    }

    private synchronized void flip() {

        // Once we enter suspend state we should be locked in write mode. This will allow echo characters to be added
        // to the buffer, and for the slave OS to continue writing until the buffer is full
        if (suspended) {
            return;
        }

        if (mode == mode_value.read) {
            mode = mode_value.write;
        } else {
            mode = mode_value.read;
        }

        // If we are transitioning back to read, and a suspend has been requested, enter true suspend state.
        if (mode == mode_value.read && requestSuspend == true) {
            mode = mode_value.write;
            requestSuspend = false;
            suspended = true;
            notifyAll();
            return;
        }

        this.position = 0;

        this.notifyAll();
    }

    synchronized void flush() {
        while (mode == mode_value.read) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return ;
            }
        }

        if (position == 0) {
            return;
        }

        flip();
    }

    synchronized int available() {

        if (mode == mode_value.write) {
            return 0;
        }
        return limit - position;
    }

    synchronized public void reset() {
         if (mode == mode_value.read) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                return;
            }
        }

        // Setting the limit to 0 and the mode back to read will cause any waiting getter to receive -1 indicating EOF.
        this.limit = 0;
        flip();
    }

    public synchronized void suspendFlow() {
        if (requestSuspend || suspended) {
            return;
        }

        requestSuspend = true;
    }

    public synchronized void resumeFlow() {
        if (!suspended) {
            requestSuspend = false;
            return;
        }

        requestSuspend = false;
        suspended = false;

        if (mode == mode_value.write) {
            mode = mode_value.read;
            position = 0;
        }

        notifyAll();
    }

    private String bytesToString(byte[] ba) {
        StringBuilder rtrnString = new StringBuilder();
        for (byte b : ba) {
            rtrnString.append(String.format("%02x", b));
        }
        return rtrnString.toString();
    }
}
