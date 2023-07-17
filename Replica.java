import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.UnknownHostException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.rmi.registry.LocateRegistry;

public class Replica extends UnicastRemoteObject implements Storage {

    private static String masterIP;
    private static int masterPort;
    private static int tcpPort;
    private static String replicaIP;
    private static int replicaPort;
    public Storage storage;
    private ConcurrentHashMap<String, byte[]> isolatedStorage = new ConcurrentHashMap<String, byte[]>();
    private ConcurrentHashMap<String, byte[]> fileContentStorage = new ConcurrentHashMap<String, byte[]>();
    private static ServerSocket ssock;
    private static List<String> filesCounter = new ArrayList<>();
    // private final static ScheduledExecutorService executorService =
    // Executors.newSingleThreadScheduledExecutor();
    // private static boolean shutValue = false;

    // Constructor -> No args
    public Replica() throws RemoteException {
        // super();
        // TODO Auto-generated constructor stub
    }

    // Constructor -> Single arg
    public Replica(File root) throws RemoteException {
    }

    // Constructor -> arg array
    public Replica(String[] args) throws Exception {

        replicaIP = args[0]; // IP
        replicaPort = Integer.parseInt(args[1]);
        masterIP = args[2];
        masterPort = Integer.parseInt(args[3]);
        tcpPort = Integer.parseInt(args[4]);
        // isolatedStorage = new ConcurrentHashMap<String, byte[]>();
        // fileContentStorage = new ConcurrentHashMap<String, byte[]>();

        ssock = new ServerSocket(tcpPort);

        createServer(replicaIP, replicaPort);
        registerMaster(masterIP, masterPort);
        getfiles();
    }

    // Registring with RMI
    private void createServer(String replicaIP, int replicaPort) throws Exception, RemoteException {
        System.setProperty("java.rmi.server.hostname", replicaIP);

        Registry registry = LocateRegistry.createRegistry(replicaPort);
        registry.rebind("Service_" + replicaIP, new Replica());

        Registry storageserver = LocateRegistry.getRegistry("localhost", replicaPort);
        storage = (Storage) storageserver.lookup("Service_" + replicaIP);

    }

    // Getting Master RMI ref
    private void registerMaster(String masterIP, int masterPort) throws Exception {
        Registry masterServer = LocateRegistry.getRegistry(masterIP, masterPort);
        Storage reg_stub = (Storage) masterServer.lookup("Master");
        reg_stub.register(replicaIP, tcpPort, storage);
        System.out.println("\nConnected to Master successfully");
    }

    private void getfiles() throws Exception {
        File curDir = new File(".");
        File[] filesList = curDir.listFiles();
        for (File f : filesList)
            filesCounter.add(f.getName());
    }

    // Renaming dir/file
    public boolean rename(String newFile, String oldFile) throws RemoteException, IOException {
        File oldDir = new File(oldFile);
        File newDir = new File(newFile);

        if (newDir.isDirectory() || newDir.isFile()) {
            System.err.println("The new path: " + newFile + " already exists");
            return false;
        }

        if (oldDir.isDirectory() || oldDir.isFile()) {
            System.out.println("the file: " + oldFile + " renamed to " + newFile + " successfully..");
            return oldDir.renameTo(newDir);
        } else {
            System.err.println("the old path: " + oldFile + " doesnt exist");
            return false;
        }
    }

    // Creating dir/file
    public boolean create(String en_path, String operation, String clientIP) throws RemoteException, IOException {
        File dir = new File(en_path);

        if (dir.isDirectory() || dir.isFile()) {
            System.err.println("The path: " + en_path + " already exists");
            return false;
        }

        if (operation.equals("mkdir")) {
            System.out.println("The folder: " + en_path + " created..");
            return dir.mkdir();
        } else {
            System.out.println("The file: " + en_path + " created..");
            return dir.createNewFile();
        }
    }

    // removing dir/file
    public boolean remove(String en_path, String clientIP) throws RemoteException, IOException {

        File dir = new File(en_path);
        System.out.println("\nTrying to remove filepath: " + en_path);
        if (dir.isDirectory() && dir.list().length != 0) {
            System.err.println("The folder can't be deleted because it has the files/subdir in it");
            return false;
        }
        read(en_path, clientIP); // before deleting it store the contents
        boolean res = dir.delete();
        if (res)
            System.out.println("The file/folder: " + en_path + " successfully deleted..");
        else
            System.out.println("Issue in deleting file/folder: " + en_path);

        return res;
    }

    // reading block used before deleting file
    public void read(String en_path, String clientIp) throws RemoteException {
        new Thread(new Runnable() {
            public void run() {
                try {
                    File file = new File(en_path);
                    if (file.isFile()) {
                        FileInputStream fis = new FileInputStream(file);
                        BufferedInputStream bis = new BufferedInputStream(fis);
                        byte[] contents;
                        long fileLength = file.length();
                        long current = 0;
                        while (current != fileLength) {
                            int size = 10000;
                            if (fileLength - current >= size)
                                current += size;
                            else {
                                size = (int) (fileLength - current);
                                current = fileLength;
                            }
                            contents = new byte[size];
                            bis.read(contents, 0, size);
                            fileContentStorage.put(en_path, contents); // Assuming max file size is 10kb
                        }
                        bis.close();
                    } else {
                        return;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // Writing file if deleting file failed
    public boolean write(String en_path) throws UnknownHostException, IOException {
        new Thread(new Runnable() {
            public void run() {
                System.out.println("File: " + en_path);
                try {
                    FileOutputStream fos = new FileOutputStream(en_path);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    bos.write(fileContentStorage.get(en_path));
                    bos.flush();
                    bos.close();
                    fos.flush();
                    fos.close();
                    fileContentStorage.remove(en_path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        return true;
    }

    // Write : 2pc (phase1)
    public boolean writePhaseone(String IP, String PORT, String path, String client)
            throws UnknownHostException, IOException {
        new Thread(new Runnable() {
            public void run() {
                System.out.println("\nWriting FilePath: " + path + " at replicas");
                try {
                    Socket socket = ssock.accept();
                    InputStream is = socket.getInputStream();
                    byte[] ar = is.readAllBytes();

                    isolatedStorage.put(client, ar);
                    is.close();
                    socket.close();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }).start();
        return true;
    }

    // Write : 2pc (phase2)
    public boolean writePhaseTwo(String IP, String path) throws UnknownHostException, IOException {
        new Thread(new Runnable() {
            public void run() {

                try {
                    FileOutputStream fos = new FileOutputStream(path);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    bos.write(isolatedStorage.get(IP));
                    bos.flush();
                    bos.close();
                    fos.flush();
                    fos.close();
                    filesCounter.add(path);
                    System.out.println("File saved successfully! at replica...");
                    isolatedStorage.remove(IP);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        return true;
    }

    // Abort Write 2PC
    public boolean writeAbort(String IP) throws UnknownHostException, IOException {
        new Thread(new Runnable() {
            public void run() {
                try {
                    isolatedStorage.remove(IP);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        return true;
    }

    public void shutSignal() throws Exception {
        System.out.println("\nShutting down the Replica");
        System.exit(1);
    }

    public boolean maliciousCheckReplica() throws Exception {
        File curDir = new File(".");
        File[] filesList = curDir.listFiles();
        List<String> fileset = new ArrayList<String>();
        for (File f : filesList)
            fileset.add(f.getName());

        if (filesCounter.size() != fileset.size()) {
            System.out.println(
                    "\n+---------------------------------------------------------------------------------------------------+");
            System.out.println("malicious Activity detected at " + new java.util.Date());
            System.out.println(
                    "+---------------------------------------------------------------------------------------------------+");
            return true;
        }
        return false;
    }

    public static void main(String args[]) throws RemoteException, NotBoundException, UnknownHostException, Exception {
        // java Replica
        // args[0] -> replica ip
        // args[1] -> replica port
        // args[2] -> master ip
        // args[3] -> master port
        // args[4] -> tcp port

        if (args[0].equalsIgnoreCase("--help")) {
            System.out.println(
                    "\n+---------------------------------------------------------------------------------------------------+");
            System.out.println(
                    "|\n| ___ Command: java Replica ReplicaIP Replicaport masterIP masterport tcpPort(unique)");
            System.out.println(
                    "+---------------------------------------------------------------------------------------------------+");
            System.exit(1);
        }

        if (args.length < 5) {
            System.err.println(
                    "Incorrect arguments. plz use command: java Replica --help");
            System.exit(1);
        }

        new Replica(args);
    }

    @Override
    public String[] register(String IP_STORAGE_SERVER, int PORT_STORAGE_SERVER, Storage command_stub)
            throws RemoteException, NotBoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<String> list() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, List<String>> getFileMap() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void authShare(List<String> iplist, String path, String operation) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean getAccess(String clientIP, String secretkey) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean write(String clientIP, String PORT, String en_path, String path, String fileStatus)
            throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Map<String, String> getRegisteredClients() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean getValidate(String clientIP, String secretKey) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Map<String, String> getFileKeys() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean directoryimpl(String clientIP, String PORT, String path, String en_path, String operation,
            String fileStatus) throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

}
