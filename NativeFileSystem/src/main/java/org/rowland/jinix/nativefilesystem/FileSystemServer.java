package org.rowland.jinix.nativefilesystem;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.RootFileSystem;
import org.rowland.jinix.io.BaseRemoteFileHandleImpl;
import org.rowland.jinix.io.JinixFile;
import org.rowland.jinix.io.JinixFileDescriptor;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.naming.*;
import org.rowland.jinix.proc.ProcessManager;

import javax.naming.NamingException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.rmi.RemoteException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Server for FileSystem files. This server overlays an existing file system providing access
 * to its files through the Jinix FileNameSpace interface.
 */
public class FileSystemServer extends JinixKernelUnicastRemoteObject implements FileNameSpace, RootFileSystem {

    static Logger logger = Logger.getLogger("jinix.nativefilesystem");
    private static FileSystemServer server;
    private static Thread mainThread;

    private Path f;
    private FileNameSpace parent;
    String attachPointPathWithinParent;

    private Map<Integer, List<FileAccessorStatistics>> openFileMap = Collections.synchronizedMap(
            new HashMap<Integer, List<FileAccessorStatistics>>());
    private List<FileAccessorStatistics> kernelOpenFileList = Collections.synchronizedList(
            new LinkedList<FileAccessorStatistics>());

    FileSystemServer(Path file) throws RemoteException {
        super();
        this.f = file;
        this.parent = null;
        this.attachPointPathWithinParent = "";
    }

    FileSystemServer(Path file, FileNameSpace parent, String attachPointPathWithinParent) throws RemoteException {
        this(file);
        this.parent = parent;
        this.attachPointPathWithinParent = attachPointPathWithinParent;
    }

    @Override
    public URI getURI() throws RemoteException {
        try {
            String parentURIPath = "";
            if (parent != null) {
                parentURIPath = parent.getURI().getPath();
            }
            return new URI("file", null, parentURIPath+"/"+getPathWithinParent(), null);
        } catch (URISyntaxException e) {
            throw new RemoteException("Unexpected failure creating FileNameSpace URI", e);
        }
    }

    @Override
    public DirectoryFileData getFileAttributes(String filePathName) throws NoSuchFileException, RemoteException {
        try {
            DirectoryFileData dfd = new DirectoryFileData();
            Path absoluteFilePath = resolveAbsolutePath(filePathName);
            BasicFileAttributes fa = Files.readAttributes(absoluteFilePath, BasicFileAttributes.class);
            dfd.name = absoluteFilePath.getFileName().toString();
            dfd.length = fa.size();
            dfd.type = (fa.isDirectory() ? DirectoryFileData.FileType.DIRECTORY : DirectoryFileData.FileType.FILE);
            dfd.lastModified = fa.lastModifiedTime().toMillis();
            return dfd;
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(filePathName);
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException reading file attributes", e);
        }
    }

    @Override
    public void setFileAttributes(String filePathName, DirectoryFileData attributes) throws NoSuchFileException, RemoteException {

        long lastModified = attributes.lastModified;

        // We only support setting the lastModified time
        if (lastModified <= 0) {
            return;
        }

        try {
            BasicFileAttributeView attrsView = Files.getFileAttributeView(resolveAbsolutePath(filePathName), BasicFileAttributeView.class);
            attrsView.setTimes(FileTime.fromMillis(lastModified), null, null);
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(filePathName);
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException setting file attributes");
        }
    }

    @Override
    public String[] list(String directoryPathName) throws RemoteException {
        try (Stream<Path> dirList = Files.list(resolveAbsolutePath(directoryPathName))) {
            return dirList.map(Path::getFileName).map(Path::toString).toArray(size -> new String[size]);
        } catch (InvalidPathException e) {
            return new String[0];
        } catch (NotDirectoryException e) {
            return null;
        } catch (IOException e) {
            throw new RemoteException("DirectoryServer: IOException getting directory list for directory: "+directoryPathName);
        }
    }

    @Override
    public boolean createFileAtomically(String directoryPathName, String fileName) throws FileAlreadyExistsException, RemoteException {
        try {
            Files.createFile(resolveAbsolutePath(directoryPathName+"/"+fileName));
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (InvalidPathException e) {
            return false;
        } catch (IOException e) {
            throw new RemoteException("IOException creating file: "+directoryPathName+"/"+fileName);
        }
        return true;
    }

    @Override
    public boolean createDirectory(String parentDirectory, String directoryName) throws FileAlreadyExistsException, RemoteException {
        try {
            Files.createDirectory(resolveAbsolutePath(parentDirectory+"/"+directoryName));
        } catch (InvalidPathException e) {
            return false;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new RemoteException("IOException creating directory: "+parentDirectory+"/"+directoryName);
        }
        return true;
    }

    @Override
    public void delete(String filePathName) throws NoSuchFileException, DirectoryNotEmptyException, RemoteException {
        try {
            Files.delete(resolveAbsolutePath(filePathName));
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(filePathName);
        } catch (NoSuchFileException | DirectoryNotEmptyException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException deleting file: "+filePathName, e);
        }
    }

    @Override
    public void copy(RemoteFileHandle sourceFile, RemoteFileHandle destinationDirectory, String fileName, CopyOption... options)
            throws NoSuchFileException, FileAlreadyExistsException, UnsupportedOperationException, RemoteException {

        if (destinationDirectory.getParent().getURI().equals(this.getURI())) {
            try {
                Files.copy(resolveAbsolutePath(sourceFile.getPath()), resolveAbsolutePath(destinationDirectory.getPath()).resolve(fileName), options);
            } catch (NoSuchFileException | FileAlreadyExistsException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteException("IOException copying file " + sourceFile + " to " + destinationDirectory + "/" + fileName);
            }
        } else {
            RemoteFileAccessor sourceFileAccessor = getRemoteFileAccessor(0, sourceFile.getPath(), EnumSet.of(StandardOpenOption.READ));
            try {
                RemoteFileAccessor destinationFileAccessor = destinationDirectory.getParent().getRemoteFileAccessor(0,
                        destinationDirectory.getPath() + "/" + fileName,
                        EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW));
                try {
                    byte[] buffer = sourceFileAccessor.read(0, 2048);
                    while (buffer != null) {
                        destinationFileAccessor.write(0, buffer);
                        buffer = sourceFileAccessor.read(0, 2048);
                    }
                } finally {
                    destinationFileAccessor.close();
                }
            } finally {
                sourceFileAccessor.close();
            }
        }
    }

    @Override
    public void move(RemoteFileHandle sourceFile, RemoteFileHandle destinationDirectory, String fileName, CopyOption... options)
            throws DirectoryNotEmptyException, NoSuchFileException, FileAlreadyExistsException, UnsupportedOperationException, RemoteException {

        if (destinationDirectory.getParent().getURI().equals(this.getURI())) {
            try {
                Files.move(resolveAbsolutePath(sourceFile.getPath()), resolveAbsolutePath(destinationDirectory.getPath()).resolve(fileName), options);
            } catch (NoSuchFileException | FileAlreadyExistsException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteException("IOException moving file " + sourceFile + " to " + destinationDirectory + "/" + fileName);
            }
        } else {
            throw new UnsupportedOperationException("Moving between filesystems is not supported");
        }
    }

    @Override
    public RemoteFileAccessor getRemoteFileAccessor(int pid, String name, Set<? extends OpenOption> options)
            throws FileAlreadyExistsException, NoSuchFileException, RemoteException {

        try {

            FileSystemChannelServer s;
            if (name.endsWith(".jar")) {
                s = new JarFileSystemChannelServer(this, pid, name, resolveAbsolutePath(name), options);
            } else {
                s = new FileSystemChannelServer(this, pid, name, resolveAbsolutePath(name), options);
            }

            // Only the Jinix Kernel passes pid -1 when it gets the init jar or when starting a translator
            if (pid == -1) {
                kernelOpenFileList.add(s);
                return s;
            }

            List<FileAccessorStatistics> l = openFileMap.get(pid);
            if (l == null) {
                l = new LinkedList<FileAccessorStatistics>();
                openFileMap.put(pid, l);
            }
            l.add(s);
            return s;
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        }
    }

    @Override
    public RemoteFileAccessor getRemoteFileAccessor(int pid, RemoteFileHandle remoteFileHandle, Set<? extends OpenOption> options) throws FileAlreadyExistsException, NoSuchFileException, RemoteException {
        return getRemoteFileAccessor(pid, remoteFileHandle.getPath(), options);
    }

    @Override
    public Object lookup(int pid, String path) {
        if (Paths.get(path).normalize().startsWith(Paths.get(".."))) {
            return null;
        }
        if (Files.exists(f.resolve(path.substring(1)), LinkOption.NOFOLLOW_LINKS)) {
            return new BaseRemoteFileHandleImpl(this, path);
        }
        return null;
    }

    @Override
    public FileNameSpace getParent() throws RemoteException {
        return parent;
    }

    @Override
    public String getPathWithinParent() throws RemoteException {
        return attachPointPathWithinParent;
    }

    @Override
    public List<FileAccessorStatistics> getOpenFiles(int pid) throws RemoteException {
        return this.openFileMap.get(pid);
    }

    void removeFileSystemChannelServer(int pid, FileSystemChannelServer s) {
        if (pid == -1) {
            kernelOpenFileList.remove(s);
            return;
        }
        List<FileAccessorStatistics> l = openFileMap.get(pid);
        if (l != null) { // In rare cases where the kernel has opened the file, the list will be null.
            l.remove(s);
        }
    }

    /**
     * Take the name parameter and resolve it against the FileSystemServer root to
     * obtain an absolute path that can used to access file in the underlying file
     * system
     *
     * @param name
     * @return
     */
    private Path resolveAbsolutePath(String name) throws InvalidPathException {
        if (!name.startsWith("/")) {
            throw new RuntimeException("All paths must start with '/': " + name);
        }
        name = name.substring(1); //remove the leading '/'
        return f.resolve(name);
    }

    public static void main(String[] args) {

        JinixFile translatorFile = JinixRuntime.getRuntime().getTranslatorFile();

        if (translatorFile == null) {
            System.err.println("Translator must be started with settrans");
            return;
        }

        String rootPath;
        if (args == null || args.length == 0 || args[0].isEmpty()) {
            System.err.println("FileSystemServer translator requires rootPath argument");
            return;
        } else {
            rootPath = args[0];
        }

        try {
            RemoteFileHandle file = (RemoteFileHandle) (new JinixContext()).lookup(translatorFile.getAbsolutePath());
            server = new FileSystemServer(Paths.get(rootPath), file.getParent(), file.getPath());
        } catch (NamingException e) {
            throw new RuntimeException("Internal error", e);
        } catch (RemoteException e) {
            throw new RuntimeException("Internal error", e);
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

        System.out.println("FileSystemServer shutdown complete");

    }

    @Override
    public void shutdown() {

    }

    @Override
    public void sync() {

    }

    public static FileNameSpace runAsRootFileSystem(String[] args) {
        String rootPath;
        if (args == null || args.length == 0 || args[0].isEmpty()) {
            rootPath = "root";
        } else {
            rootPath = args[0];
        }

        try {
            return new FileSystemServer(Paths.get(rootPath));
        } catch (RemoteException e) {
            throw new RuntimeException("Internal error", e);
        }
    }


}
