package org.rowland.jinix.nativefilesystem;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.naming.FileAccessorStatistics;
import org.rowland.jinix.naming.RemoteFileAccessor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.*;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ServerCloneException;
import java.util.Set;

/**
 * A FileChannel server that serves up files from an underlying file system
 */
public class FileSystemChannelServer extends JinixKernelUnicastRemoteObject
        implements RemoteFileAccessor, FileAccessorStatistics {

    protected FileSystemServer server;
    protected int pid;
    protected String jinixPath; // the absolute pathname of the Jinix file
    protected Path filePath; // the absolute pathname of the file in the underlying OS
    protected Set<? extends OpenOption> openOptions;
    protected java.nio.channels.FileChannel fc;
    private int openCount;

    protected FileSystemChannelServer(FileSystemServer server,
                                      int pid,
                                      String fullPath,
                                      Path path,
                                      Set<? extends OpenOption> options)
            throws FileAlreadyExistsException, NoSuchFileException, RemoteException {
        super();
        try {
            this.server = server;
            this.pid = pid;
            this.jinixPath = fullPath;
            this.filePath = path;
            this.openOptions = options;
            fc = java.nio.channels.FileChannel.open(path, options);
            this.openCount = 1;
            FileSystemServer.logger.fine("Opening FSCS: " + this.toString());
        } catch (FileAlreadyExistsException | NoSuchFileException e) {
            unexport();
            throw e;
        } catch (IOException e) {
            unexport();
            throw new RemoteException("Internal error", e);
        }
    }

    public String toString() {
        return filePath.toString() + (fc != null ? ":" + fc.hashCode() : "");
    }

    @Override
    public byte[] read(int pid, int len) throws RemoteException {
        try {
            byte[] b = new byte[len];
            int r = fc.read(ByteBuffer.wrap(b));
            if (r == -1) {
                return null;
            }

            if (r < len) {
                byte[] rb = new byte[r];
                System.arraycopy(b, 0, rb, 0, r);
                return rb;
            } else {
                return b;
            }
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public int write(int pid, byte[] b) throws NonWritableChannelException, RemoteException {
        try {
            return fc.write(ByteBuffer.wrap(b));
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public long skip(long n) throws RemoteException {
        try {
            return fc.position(fc.position()+n).position();
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public int available() throws RemoteException {
        try {
            return (int) Math.min(fc.size()-fc.position()-1,Integer.MAX_VALUE);
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public long getFilePointer() throws RemoteException {
        try {
            return fc.position();
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public void seek(long l) throws RemoteException {
        try {
            fc.position(l);
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public long length() throws RemoteException {
        try {
            return fc.size();
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public void setLength(long l) throws RemoteException {
        try {
            fc.position(l);
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    public synchronized void close() throws RemoteException {
        try {
            if (openCount > 0) {
                openCount--;
                if (openCount == 0) {
                    FileSystemServer.logger.fine("Closing FSCS: " + this.toString());
                    fc.close();
                    if (!unexport()) {
                        FileSystemServer.logger.severe("FSCS unexport failed: "+this.toString());
                    }
                }
            }
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        } finally {
            if (openCount == 0) {
                server.removeFileSystemChannelServer(pid, this);
            }
        }
    }

    /*
    public FileChannel clone() throws CloneNotSupportedException {
        FileSystemChannelServer cloned = (FileSystemChannelServer) super.clone();
        try {
            cloned.filePath = this.filePath;
            cloned.fc = java.nio.channels.FileChannel.open(this.filePath, this.openOptions);
            return cloned;
        } catch (IOException e) {
            throw new ServerCloneException("Clone failed", e);
        }
    }
    */

    @Override
    public synchronized void duplicate() throws RemoteException {
        FileSystemServer.logger.fine("Duplicate: "+toString());
        openCount++;
    }

    @Override
    public void force(boolean metaData) throws RemoteException {
        try {
            fc.force(metaData);
        } catch (IOException e) {
            throw new RemoteException("Internal error", e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    @Override
    public String getAbsolutePathName() throws RemoteException {
        return jinixPath;
    }

    @Override
    public void flush() throws RemoteException {
        // Noop for files.
    }
}
