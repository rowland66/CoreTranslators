package org.rowland.jinix.nativefilesystem;

import org.rowland.jinix.JinixKernelUnicastRemoteObject;
import org.rowland.jinix.RootFileSystem;
import org.rowland.jinix.io.JinixFileDescriptor;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.naming.*;
import org.rowland.jinix.proc.ProcessManager;

import java.io.IOException;
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
    private NameSpace rootNameSpace;

    private Map<Integer, List<FileAccessorStatistics>> openFileMap = Collections.synchronizedMap(
            new HashMap<Integer, List<FileAccessorStatistics>>());
    private List<FileAccessorStatistics> kernelOpenFileList = Collections.synchronizedList(
            new LinkedList<FileAccessorStatistics>());

    FileSystemServer(Path file) throws RemoteException {
        super();
        this.f = file;
    }

    @Override
    public void setRootNameSpace(NameSpace nameSpace) {
        this.rootNameSpace = nameSpace;
    }

    @Override
    public URI getURI() throws RemoteException {
        try {
            String path = this.f.toAbsolutePath().toString().replace('\\', '/');
            if (!path.startsWith("/")) {
                path = path.substring(path.indexOf('/'),path.length());
            }
            return new URI("file", null, path, null);
        } catch (URISyntaxException e) {
            throw new RemoteException("Unexpected failure creating FileNameSpace URI", e);
        }
    }

    @Override
    public DirectoryFileData getFileAttributes(String name) throws NoSuchFileException, RemoteException {
        try {
            DirectoryFileData dfd = new DirectoryFileData();
            Path absoluteFilePath = resolveAbsolutePath(name);
            BasicFileAttributes fa = Files.readAttributes(absoluteFilePath, BasicFileAttributes.class);
            dfd.name = absoluteFilePath.getFileName().toString();
            dfd.length = fa.size();
            dfd.type = (fa.isDirectory() ? DirectoryFileData.FileType.DIRECTORY : DirectoryFileData.FileType.FILE);
            dfd.lastModified = fa.lastModifiedTime().toMillis();
            return dfd;
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException reading file attributes", e);
        }
    }

    @Override
    public void setFileAttributes(String name, DirectoryFileData attributes) throws NoSuchFileException, RemoteException {
        long lastModified = attributes.lastModified;

        // We only support setting the lastModified time
        if (lastModified <= 0) {
            return;
        }

        try {
            BasicFileAttributeView attrsView = Files.getFileAttributeView(resolveAbsolutePath(name), BasicFileAttributeView.class);
            attrsView.setTimes(FileTime.fromMillis(lastModified), null, null);
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException setting file attributes");
        }
    }

    @Override
    public boolean exists(String name) throws RemoteException {
        try {
            return Files.exists(resolveAbsolutePath(name));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @Override
    public String[] list(String name) throws RemoteException {
        try (Stream<Path> dirList = Files.list(resolveAbsolutePath(name))) {
            return dirList.map(Path::getFileName).map(Path::toString).toArray(size -> new String[size]);
        } catch (InvalidPathException e) {
            return new String[0];
        } catch (IOException e) {
            throw new RemoteException("DirectoryServer: IOException getting directory list for directory: "+name);
        }
    }

    @Override
    public DirectoryFileData[] listDirectoryFileData(String name) throws RemoteException {

        try (Stream<Path> dirList = Files.list(resolveAbsolutePath(name))) {
            int i = 0;
            List<DirectoryFileData> dirFileDataList = new ArrayList<DirectoryFileData>();
            for (Path p : dirList.collect(Collectors.toCollection(LinkedList::new))) {
                DirectoryFileData dfd = new DirectoryFileData();
                BasicFileAttributes fa = Files.readAttributes(p, BasicFileAttributes.class);
                dfd.name = p.getFileName().toString();
                dfd.length = fa.size();
                dfd.type = (fa.isDirectory() ? DirectoryFileData.FileType.DIRECTORY : DirectoryFileData.FileType.FILE);
                dfd.lastModified = fa.lastModifiedTime().toMillis();
                dirFileDataList.add(dfd);
            }
            return dirFileDataList.toArray(new DirectoryFileData[dirFileDataList.size()]);
        } catch (InvalidPathException e) {
            return new DirectoryFileData[0];
        } catch (IOException e) {
            throw new RemoteException("DirectoryServer: IOException getting directory list for directory: "+name);
        }
    }

    @Override
    public boolean createFileAtomically(String name) throws FileAlreadyExistsException, RemoteException {
        try {
            Files.createFile(resolveAbsolutePath(name));
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (InvalidPathException e) {
            return false;
        } catch (IOException e) {
            throw new RemoteException("IOException creating file: "+name);
        }
        return true;
    }

    @Override
    public boolean createDirectory(String name) throws FileAlreadyExistsException, RemoteException {
        try {
            Files.createDirectory(resolveAbsolutePath(name));
        } catch (InvalidPathException e) {
            return false;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new RemoteException("IOException creating directory: "+name);
        }
        return true;
    }

    @Override
    public boolean createDirectories(String name) throws FileAlreadyExistsException, RemoteException {
        try {
            Files.createDirectories(resolveAbsolutePath(name));
        } catch (InvalidPathException e) {
            return false;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new RemoteException("IOException creating directories: "+name);
        }
        return true;
    }

    @Override
    public void delete(String name) throws NoSuchFileException, DirectoryNotEmptyException, RemoteException {
        try {
            Files.delete(resolveAbsolutePath(name));
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        } catch (NoSuchFileException | DirectoryNotEmptyException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException deleting file: "+name, e);
        }
    }

    @Override
    public void copy(String name, String pathNameTo, CopyOption... options)
            throws NoSuchFileException, FileAlreadyExistsException, UnsupportedOperationException, RemoteException {

        if (!pathNameTo.startsWith("/")) {
            throw new IllegalArgumentException("Copy pathNameTo must begin with slash: "+pathNameTo);
        }

        LookupResult result = rootNameSpace.lookup(pathNameTo);
        if (!((FileNameSpace) result.remote).getURI().equals(getURI())) {
            throw new UnsupportedOperationException();
        }

        try {
            if (exists(pathNameTo) &&
                    getFileAttributes(name).type == DirectoryFileData.FileType.DIRECTORY) {
                throw new FileAlreadyExistsException(pathNameTo);
            }

            Path dest;
            if (pathNameTo.equals("/")) {
                dest = Paths.get(".");
            } else {
                pathNameTo = pathNameTo.substring(1);
                dest = f.resolve(pathNameTo);
            }

            Files.copy(resolveAbsolutePath(name), dest, options);
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        } catch (NoSuchFileException | FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException copying file "+name+" to "+pathNameTo);
        }
    }

    @Override
    public void move(String name, String pathNameTo, CopyOption... options)
            throws NoSuchFileException, FileAlreadyExistsException, UnsupportedOperationException, RemoteException {

        if (!pathNameTo.startsWith("/")) {
            throw new IllegalArgumentException("Move pathNameTo must begin with slash: "+pathNameTo);
        }

        LookupResult result = rootNameSpace.lookup(pathNameTo);
        if (!((FileNameSpace) result.remote).getURI().equals(getURI())) {
            throw new UnsupportedOperationException();
        }

        try {
            if (exists(pathNameTo) &&
                    getFileAttributes(name).type == DirectoryFileData.FileType.DIRECTORY) {
                throw new FileAlreadyExistsException(pathNameTo);
            }

            Path dest;
            if (pathNameTo.equals("/")) {
                dest = Paths.get(".");
            } else {
                pathNameTo = pathNameTo.substring(1);
                dest = f.resolve(pathNameTo);
            }

            Files.move(resolveAbsolutePath(name), dest, options);
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(name);
        } catch (NoSuchFileException | FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException("IOException moving file "+name+" to "+pathNameTo);
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

            // Only the Jinix Kernel passes pid -1 when it gets the init
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
    public Object getKey(String name) throws RemoteException {
        return name;
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

        JinixFileDescriptor fd = JinixRuntime.getRuntime().getTranslatorFile();

        if (fd == null) {
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
            server = new FileSystemServer(Paths.get(rootPath));
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
