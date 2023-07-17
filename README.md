distributed-file-system
===============

`distributed-file-system` It will allow multiple clients to work on files both independently and collabartively effectively by the file permissions feature. The file system is immune to malicious activity implicitly and explicitly. Such as firstly the total content including the filenames, directories, file data everything is in encrypted format. And every client has theirs own secretkey for encryption, if not default secretkey will be used. `Master server` has the sole responsibility to accept/decline the every new user connection. No activity couldnt go unnoticed, a [periodic_thread](https://docs.oracle.com/javase/6/docs/api/java/util/concurrent/ScheduledExecutorService.html) part of a multithreading runs for every 4 seconds. If and all malicious activity is detected, the system goes shutdown, displaying the issue. The `file-system` follows the `two-phase commit` protocol ensuring `ACID` property across all the `replicas`. 

Installing
----------

You must have the [Java Runtime Environment](http://java.com/en/download/manual.jsp) version 7 or above. 
The Distributed-file-system is built on using Java RMI, multithreading and sockets.

Usage
-----

`distributed-file-system` can be run from the command line. It has three important files:

`Client.java`: To act as a client, which will perform all `CRUD` file operations. Below are the commands:

```
To send: java Client put filepath masterIP masterport tcpPort yourIp
To Read: java Client read filepath masterIP masterport tcpPort yourIp
To share access: java Client share filepath masterIP masterport tcpPort yourIp ip1,ip2,...
```

`Master.java`: This will act as a main server which hosts the file system. Below is the command:
```
java Master masterIP masterport tcpPort
```

`Replica.java`: As name suggests it will replicates the master server file system. Below is the command:
```
java Replica ReplicaIP Replicaport masterIP masterport tcpPort(unique)
```

The above three files should be run in the nodes which all are in the same `LAN`. At first `Master` file should be up and running and then if one need replica instances, you can run the `Replica.java` files in extra systems. Then periodically users can use `Client.java` to perform the desired CRUD operations. For any command help, one can use belwow commands.
  
All together:
```
java Client --help
java Master --help
java Replica --help
```

You may see the warning message on the master server while it started, which is safe to ignore.
