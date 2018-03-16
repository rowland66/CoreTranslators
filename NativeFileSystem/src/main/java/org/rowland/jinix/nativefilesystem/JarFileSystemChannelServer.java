package org.rowland.jinix.nativefilesystem;

import org.rowland.jinix.naming.JarManifest;
import org.rowland.jinix.naming.RemoteJarFileAccessor;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A special FileSystemChannelServer for jar files that provides extra functionality to improve classloading performance.
 * This class is used by the ExecClassLoader to load classes from Jinix jar files. Jinix jar files may contain stored
 * uncompressed jar files. When opened, this class scans the jar file and builds an index of class names that references
 * the starting locates of the named classes byte code. This indexing will include any jar files in the /lib directory.
 * Once the index is built, class byte code and resources can be accessed quickly by positioning the channel underlying
 * the FileOutputStream at the start of the byte code, and using an InflaterInputStream to uncompress the class byte code.
 */

//TODO: Add FileSystem level caching of jar file indexes. As many jars do not change often, and many processes will be
    // loading classes from the same jar files. This will reduce the time spent indexing jars, and reduce the memroy
    // required to store the jar file indexes.
//TODO: Improve the efficiency of the jar file indexes. Many names repeat package text.
public class JarFileSystemChannelServer extends FileSystemChannelServer
    implements RemoteJarFileAccessor {

    JarFile jarFile;
    InputStream inflaterInputStream;
    boolean entryEOF;
    FileInputStream fis;
    FileChannel ch;

    // The JarFileSystemChannelServer is primarily used by the ExecClassLoader. The ExecClassLoader will read multiple
    // entries and then close the server an extra time. However, the JarFileSystemChannelServer also provide entries when
    // resources are being loaded from the jar file. This is outside of the control
    boolean closeAfterSingleEntryRead = true; // By default we

    Map<String, ClassEntry> classBytesMap; // A map from fully qualified class names to the bytes that define their class
    Set<String> packageSet;

    long position; // The position in the jar as it is being scanned.

    protected JarFileSystemChannelServer(FileSystemServer server,
                                      int pid,
                                      String fullPath,
                                      Path path,
                                      Set<? extends OpenOption> options)
            throws FileAlreadyExistsException, NoSuchFileException, RemoteException {
        super(server, pid, fullPath, path, options);

        jarFile = null;
        classBytesMap = null;
        packageSet = null;
        inflaterInputStream = null;
    }

    @Override
    public JarManifest getManifest() throws RemoteException {
        try {
            if (classBytesMap == null) {
                buildClassBytesMap();
            }
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            return new JarManifest(manifest.getMainAttributes(), manifest.getEntries());
        } catch (IOException e) {
            throw new RemoteException("Internal Error", e);
        }
    }

    @Override
    public String[] getPackages() throws RemoteException {
        try {
            if (classBytesMap == null) {
                buildClassBytesMap();
            }
            String[] rtrn = new String[packageSet.size()];
            rtrn = packageSet.toArray(rtrn);
            Arrays.sort(rtrn);
            return rtrn;
        } catch (IOException e) {
            throw new RemoteException("Internal Error", e);
        }
    }

    @Override
    public long findEntry(String name) throws RemoteException{
        try {
            if (classBytesMap == null) {
                buildClassBytesMap();
            }

            ClassEntry classEntry = classBytesMap.get(name);
            if (classEntry == null) {
                return -1;
            }
            inflaterInputStream = new InflaterInputStream(fis, new Inflater(true), 512);
            ch.position(classEntry.position);
            entryEOF = false;

            return classEntry.size;
        } catch (IOException e) {
            throw new RemoteException("Internal Error", e);
        }
    }

    @Override
    public byte[] read(int pid, int len) throws RemoteException {
        if (inflaterInputStream == null) {
            return super.read(pid, len);
        }

        if (entryEOF) {
            return null;
        }

        try {
            byte[] b = new byte[len];
            int bytesRead = 0;
            while (bytesRead < len) {
                int r = inflaterInputStream.read(b, bytesRead, len - bytesRead);
                if (r == -1) {
                    entryEOF = true;
                    if (bytesRead > 0) {
                        if (bytesRead < len) {
                            byte[] rb = new byte[bytesRead];
                            System.arraycopy(b, 0, rb, 0, bytesRead);
                            return rb;
                        } else {
                            return b;
                        }
                    }
                    return null;
                }
                bytesRead += r;
            }
            return b;
        } catch (IOException e) {
            throw new RemoteException("Internal Error", e);
        }
    }

    @Override
    public synchronized void close() throws RemoteException {
        try {
            if (inflaterInputStream != null) {
                inflaterInputStream = null;
                return;
            }
            if (jarFile != null) {
                jarFile.close();
                fis.close();
            }
            super.close();
        } catch (IOException e) {
            throw new RemoteException("Internal Error", e);
        }
    }


    private void buildClassBytesMap() throws IOException {
        this. jarFile = new JarFile(filePath.toFile());
        this.fis = new FileInputStream(filePath.toFile());
        ch = fis.getChannel();

        classBytesMap = new HashMap<>(512);
        packageSet = new HashSet<>();
        ZipInputStream zie = new ZipInputStream(new BufferedInputStream(fis));
        scanZip(zie);
    }

    private long scanZip(ZipInputStream zis) throws IOException {
        boolean dataDescriptor;
        long bytesProcessed = 0;
        long startingPosition = position;

        ZipEntry ze = zis.getNextEntry();
        if (ze.getSize() == -1) {
            dataDescriptor = true;
        } else {
            dataDescriptor = false;
        }
        while (ze != null) {
            ZipEntry nextEntry = zis.getNextEntry();
            if (bytesProcessed == 0) {
                position += (30 + ze.getName().length() + (ze.getExtra() != null ? ze.getExtra().length : 0));

                if (ze.getSize() > 0) {
                    classBytesMap.put(ze.getName(), new ClassEntry(position, ze.getSize()));
                    String pkg;
                    if (ze.getName().indexOf("/") == -1) {
                        pkg = "/";
                    } else {
                        pkg = ze.getName().substring(0, ze.getName().lastIndexOf("/"));
                    }
                    if (!packageSet.contains(pkg)) {
                        packageSet.add(pkg);
                    }
                }

                position += (ze.getCompressedSize() + (dataDescriptor ? 16 : 0));
            } else {
                if (bytesProcessed < ze.getSize()) {
                    position += (ze.getSize() - bytesProcessed);
                }
                bytesProcessed = 0;
            }

            ze = nextEntry;

            if (ze != null) {
                if (ze.getSize() == -1) {
                    dataDescriptor = true;
                } else {
                    dataDescriptor = false;
                }
                if (ze.getName().startsWith("lib/") && ze.getName().endsWith(".jar")) {
                    position += (30 + ze.getName().length() + (ze.getExtra() != null ? ze.getExtra().length : 0));
                    bytesProcessed = scanZip(new ZipInputStream(zis));
                }
            }
        }
        return (position - startingPosition);
    }

    private static class ClassEntry {
        long position;
        long size;

        private ClassEntry(long p, long s) {
            position = p;
            size = s;
        }
    }
}
