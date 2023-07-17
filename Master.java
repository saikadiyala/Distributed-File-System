import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Master extends UnicastRemoteObject implements Storage {

    private static Map<String, List<String>> fileLocation; // <filenames,List<clientips>>
    private static Map<Storage, List<String>> replicaDetails;
    private static Set<Storage> replicaInstances;
    private ConcurrentHashMap<String, byte[]> fileContentStorage; // <filepath, filecontent(bytes)>
    private static Map<String, String> registeredClients; // <clientIP, secretKey>
    private static Map<String, String> fileKeyMap; // <Filename, secretKey>

    private static ServerSocket ssock;
    private static Socket socket;

    // periodic thread
    private final static ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    // Constructor
    public Master() throws RemoteException {
        super();
        fileLocation = new HashMap<String, List<String>>();
        replicaDetails = new HashMap<Storage, List<String>>();
        replicaInstances = new HashSet<Storage>();
        fileContentStorage = new ConcurrentHashMap<String, byte[]>();
        registeredClients = new HashMap<String, String>();
        fileKeyMap = new HashMap<String, String>();

        // executorService = Executors.newSingleThreadScheduledExecutor();

        try {
            getFiles();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error in getting file logs in master constructor");
            System.exit(1);
        }
    }

    private static boolean maliciousCheck() throws Exception {
        // ScheduledExecutorService exec =
        // Executors.newSingleThreadScheduledExecutor();
        // exec.scheduleAtFixedRate(new Runnable() {
        // public void run() {

        // }
        // }, 0, 5, TimeUnit.SECONDS);
        executorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    File curDir = new File(".");
                    File[] filesList = curDir.listFiles();
                    List<String> fileset = new ArrayList<String>();
                    for (File f : filesList) {
                        fileset.add(f.getName());
                    }

                    if (fileLocation.size() != fileset.size()) {
                        System.out.println(
                                "\n+---------------------------------------------------------------------------------------------------+");
                        System.out.println("malicious Activity detected at master server " + new java.util.Date());
                        System.out.println("\nShutting down the master server");
                        System.exit(1);
                        System.out.println(
                                "+---------------------------------------------------------------------------------------------------+");
                    } else {
                        for (Storage stub : replicaInstances)
                            if (stub.maliciousCheckReplica()) {
                                System.out.println(
                                        "Malicious Activity at one of the replica instance. Ordered for Replica Shutdown");
                                stub.shutSignal();
                            }
                    }
                } catch (Exception e) {
                    // TODO: handle exception
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
        return true;
    }

    // new user permission
    public boolean getValidate(String clientIP, String secretKey) throws Exception {

        System.out.println("\nClient: " + clientIP + " requesting access.. ");
        System.out.print("Type y/n: ");

        Scanner in = new Scanner(System.in);
        String s = in.nextLine();

        if (s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes")) {
            registeredClients.put(clientIP, secretKey);
            System.out.println("\nGranted access to the client: " + clientIP);
            return true;
        } else if (s.equalsIgnoreCase("n") || s.equalsIgnoreCase("no")) {
            System.out.println("\nBlocked access to the client: " + clientIP);
            return false;
        }
        return false;
    }

    // relicas use this method to get registered of its instance
    public String[] register(String IP_STORAGE_SERVER, int PORT_STORAGE_SERVER, Storage command_stub)
            throws RemoteException, NotBoundException {

        replicaInstances.add(command_stub); // check if server is active
        System.out.println("Replica: " + IP_STORAGE_SERVER + " got connected to Master");

        if (replicaDetails.get(command_stub) == null) {
            List<String> temp = new ArrayList<String>();
            temp.add(new String(IP_STORAGE_SERVER)); // replica Ip
            temp.add(new String(PORT_STORAGE_SERVER + "")); // replica tcp port
            replicaDetails.put(command_stub, temp);
        }
        return new String[2];
    }

    // get all files/folders names
    private void getFiles() throws Exception {
        // String path = Paths.get("").toAbsolutePath().toString();
        File curDir = new File("."); // returns all files from the cur dir
        File[] filesList = curDir.listFiles(); // lists all files and sub dir
        for (File f : filesList)
            fileLocation.put(f.getName(), new ArrayList<String>()); // [key, []]
    }

    // binding remote object with given name in registry
    public synchronized void start(String port, String tcp) throws NumberFormatException, IOException {
        // Creates and exports a Registry instance on the local host that accepts
        // requests on the specified port.
        Registry registry = LocateRegistry.createRegistry(Integer.parseInt(port));

        /**
         * Replaces the binding for the specified <code>name</code> in this registry
         * with the supplied remote reference. If there is an existing binding for the
         * specified <code>name</code>, it is discarded.
         **/
        registry.rebind("Master", new Master());

        ssock = new ServerSocket(Integer.parseInt(tcp));
    }

    // writing into master server and replicas
    public boolean write(String clientIP, String PORT, String en_path, String path, String fileStatus)
            throws UnknownHostException, IOException {

        new Thread(new Runnable() {
            public void run() {
                System.out.println("\nGiven Filepath: " + en_path + " from client");

                try {

                    socket = ssock.accept();
                    FileOutputStream fos = new FileOutputStream(en_path);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    InputStream is = socket.getInputStream();

                    // Writing into Master
                    int bytesRead = 0;
                    byte[] contents = new byte[10000];
                    while ((bytesRead = is.read(contents)) != -1) {
                        // bos.write(contents); //file size: contents size 10000
                        bos.write(contents, 0, bytesRead); // size: actual size of file
                    }

                    // Writing into the replicas
                    System.out.println("Writing into the replicas has started.. ");
                    List<Storage> failedList = new ArrayList<>(); // to store failed replicas
                    for (Storage stub : replicaInstances) {
                        List<String> s = replicaDetails.get(stub);

                        Socket socketre = new Socket(InetAddress.getByName(s.get(0)), Integer.parseInt(s.get(1)));
                        OutputStream os = socketre.getOutputStream();
                        os.write(contents);

                        // replicaip, replica tcpport, file path and clientip
                        if (!stub.writePhaseone(s.get(0), s.get(1), en_path, clientIP))
                            failedList.add(stub);

                        os.flush();
                        os.close();
                        socketre.close();
                    }

                    bos.flush();
                    bos.close();
                    fos.flush();
                    fos.close();
                    socket.close();

                    if (failedList.size() == 0) {
                        for (Storage stub : replicaInstances)
                            stub.writePhaseTwo(clientIP, en_path); // clientip and file path

                        // giving authorization block
                        if (fileStatus.equalsIgnoreCase("new")) {
                            List<String> lis = new ArrayList<>();
                            lis.add(clientIP);
                            fileLocation.put(path, lis);
                            fileKeyMap.put(path, registeredClients.get(clientIP));
                            System.out.println("Client: " + clientIP + " has the access to Filepath: " + en_path);
                        }
                    } else {
                        File f = new File(en_path);
                        f.delete();
                        for (Storage stub : replicaInstances)
                            if (!failedList.contains(stub))
                                stub.writeAbort(clientIP);
                        System.err.println("File: " + en_path + " failed to write.");
                    }

                    System.out.println("Filepath saved successfully! at Primary server");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }).start();
        return true;
    }

    // Reading from file
    public void read(String en_path, String clientIp) throws IOException, RemoteException {
        System.out.println("\nClient: " + clientIp + " started reading the file: " + en_path);
        new Thread(new Runnable() {
            public void run() {
                try {
                    socket = ssock.accept();
                    OutputStream os = socket.getOutputStream();
                    File file = new File(en_path);
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
                        os.write(contents);
                        fileContentStorage.put(en_path, contents); // Assuming max file size is 10kb
                    }
                    bis.close();
                    socket.close();
                    System.out.println(clientIp + " has read the file: " + en_path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // Deleting file/folder with 2pc
    public boolean remove(String en_path, String clientIP) throws RemoteException, IOException {
        try {
            List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // PHASE 1
            for (Storage stub : replicaInstances) {
                if (!stub.remove(en_path, clientIP)) {
                    failedList.add(stub);
                }
            }

            // PHASE 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances)
                    if (!failedList.contains(stub))
                        stub.write(en_path);
                System.err.println("Removing Folder/File: " + en_path + " is failed..");
                return false;
            } else {
                System.out.println("Folder/File: " + en_path + "created successfully");
                return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }

    // create file/folder with 2pc
    public boolean create(String en_path, String operation, String clientIP) throws RemoteException, IOException {
        try {
            List<Storage> failedList = new ArrayList<>(); // to store failed replicas
            // PHASE 1
            for (Storage stub : replicaInstances)
                if (!stub.create(en_path, operation, clientIP)) // file path, mkdir, new/existing
                    failedList.add(stub);

            // phase 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances)
                    if (!failedList.contains(stub))
                        stub.remove(en_path, clientIP);
                System.err.println("Folder/File: " + en_path + " is failed..");
                return false;
            } else {
                System.out.println("Folder/File: " + en_path + "created successfully");
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // Renaming File/dir
    public boolean rename(String newFile, String oldFile) throws RemoteException, IOException {
        // boolean res;

        // new Thread(new Runnable() {
        // public void run() { } }).start();
        // int counter = -1;
        // new Thread(new Runnable() {
        // public void run() {
        try {
            List<Storage> failedList = new ArrayList<>(); // to store failed replicas
            // PHASE 1
            for (Storage stub : replicaInstances)
                if (!stub.rename(newFile, oldFile))
                    failedList.add(stub);

            // phase 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances)
                    if (!failedList.contains(stub))
                        stub.rename(oldFile, newFile);
                System.err.println("Folder/File: " + oldFile + " renaming failed... ");
                // res = false;
                return false;
                // counter = 0;
            } else {
                System.out.println("Folder/File: " + oldFile + " renamed to " + newFile + " successfully");
                // res = true;
                return true;
                // counter = 1;
            }

        } catch (Exception e) {
            e.printStackTrace();
            // res = false;
            return false;
            // counter = 0;
        }

        // } }).start();
        // return res;
    }

    public boolean directoryimpl(String clientIP, String PORT, String p, String en_path, String operation,
            String fileStatus)
            throws UnknownHostException, IOException, SecurityException {

        new Thread(new Runnable() {
            public void run() {
                System.out.println("\nWorking on File Path: " + en_path);
                try {
                    File serverpathdir = new File(en_path);
                    // Creating directory
                    if (operation.equals("mkdir")) {
                        if (!serverpathdir.isDirectory()) { // not a directory or doesnt exist
                            // mkdir = true: if new, false: if already exists
                            if (create(en_path, operation, clientIP)) { // if folder got created at server
                                serverpathdir.mkdir();
                                List<String> temp = new ArrayList<>();
                                temp.add(clientIP);
                                fileLocation.put(p, temp);
                                System.out.println("Folder: " + en_path + " successfully created.. ");
                            } else {
                                System.err.println("Failed to create folder " + en_path);
                            }
                        } else {
                            System.err.println("directory given by " + clientIP + " already exists in master server");
                        }
                    }

                    // Creating File
                    else if (operation.equals("create")) {
                        if (!serverpathdir.isFile()) {
                            if (create(en_path, operation, clientIP)) {
                                serverpathdir.createNewFile();
                                List<String> temp = new ArrayList<>();
                                temp.add(clientIP);
                                fileLocation.put(p, temp);
                                System.out.println("File: " + en_path + " successfully created.. ");
                            } else {
                                System.err.println("Failed to create file " + en_path);
                            }
                        } else {
                            System.err.println("file given already exists in master server");
                        }

                    }

                    // deleting directory
                    else if (operation.equals("rmdir")) {
                        if (serverpathdir.isDirectory()) {
                            if (serverpathdir.list().length != 0) {
                                System.err.println(
                                        "This folder in master server cant be deleted because it has contents in it");
                            } else {
                                if (remove(en_path, clientIP)) {
                                    serverpathdir.delete();
                                    fileLocation.remove(p);
                                    System.out.println("Folder: " + en_path + " successfully deleted.. ");
                                } else {
                                    System.err.println("Failed to delete the folder: " + en_path);
                                }
                            }
                        } else {
                            System.err.println("No such directory exists at master server");
                        }
                    }

                    // deleting file
                    else if (operation.equals("remove")) {
                        if (serverpathdir.isFile()) {
                            if (remove(en_path, clientIP)) {
                                serverpathdir.delete();
                                fileLocation.remove(p);
                                System.out.println("file: " + en_path + " successfully deleted.. ");
                            } else {
                                System.err.println("Failed to delete the file: " + en_path);
                            }
                        } else {
                            System.err.println("No such file exists at master server");
                        }
                    }

                    // Renaming file/dir
                    else if (operation.equals("rename")) {
                        if (en_path.contains(",")) {
                            String newFile = en_path.split(",")[0];
                            String oldFile = en_path.split(",")[1];
                            File n = new File(newFile);
                            File o = new File(oldFile);
                            if (n.isDirectory() || n.isFile()) {
                                System.err.println("the path: " + newFile + " already exists");
                            } else {
                                if (o.isDirectory() || o.isFile()) {
                                    if (rename(newFile, oldFile)) {
                                        o.renameTo(n);
                                        fileLocation.put(newFile, fileLocation.get(oldFile));
                                        fileLocation.remove(oldFile);
                                    } else {
                                        System.err.println("Failed to rename the file/dir");
                                    }
                                } else {
                                    System.err.println("No such file/folder: " + oldFile + " exists");
                                }
                            }
                        } else {
                            System.err.println("Plz provide the new file,old file");
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }).start();
        return true;

    }

    public void authShare(List<String> iplist, String path, String operation) throws Exception {
        new Thread(new Runnable() {
            public void run() {
                List<String> list = fileLocation.get(path);
                if (operation.equals("share"))
                    list.addAll(iplist);
                else
                    list.removeAll(iplist);

                fileLocation.put(path, list);
                System.out.println("Filepath: " + path + " authorization share attempt by client is successful");
            }
        }).start();
    }

    public List<String> list() throws Exception {
        return new ArrayList<>(fileLocation.keySet());
    }

    /**
     * @author Haravind rajula
     * @param none
     * @return Map<String, List<String>>
     */
    public Map<String, List<String>> getFileMap() throws Exception {
        return fileLocation;
    }

    public Map<String, String> getFileKeys() throws Exception {
        return fileKeyMap;
    }

    /**
     * @author Haravind rajula
     * @param none
     * @return Map<String, String>
     */
    public Map<String, String> getRegisteredClients() throws Exception {
        return registeredClients;
    }

    // main function
    public static void main(String args[]) throws Exception {

        // Checking the INPUTS...
        /**
         * java Master
         * args[0] -> master ip
         * args[1] -> master port
         * args[2] -> tcp
         */

        if (args[0].equalsIgnoreCase("--help")) {
            System.out.println(
                    "\n+---------------------------------------------------------------------------------------------------+");
            System.out.println(
                    "|\n| ___ Command: java Master masterIP masterport tcpPort");
            System.out.println(
                    "+---------------------------------------------------------------------------------------------------+");
            System.exit(1);
        }

        if (args.length < 3) {
            System.err.println("Incorrect arguments length.. Please give IP address, port number and tcp");
            System.exit(1);
        }

        System.setProperty("java.rmi.server.hostname", args[0]);
        String value = System.getProperty("java.rmi.server.hostname");
        if (value == null) {
            System.err.println("error in configuring RMI");
            System.exit(1);
        }
        if (System.getSecurityManager() != null) {
            System.out.println("Security manager error");
        }

        new Master().start(args[1], args[2]); // port & tcp port
        maliciousCheck();
        System.out.println("\nMaster/Primary Server is listening on port = " + args[1]); // port number
        System.out.println("Socket listening on port : " + args[2]); // tcp port

    }

    @Override
    public boolean write(String path) throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean writePhaseTwo(String IP, String path) throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean writePhaseone(String IP, String PORT, String path, String client)
            throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean writeAbort(String IP) throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean maliciousCheckReplica() throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void shutSignal() throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean getAccess(String clientIP, String secretkey) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

}
