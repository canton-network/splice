// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Triggers a heap dump on a remote JVM through JMX. The dump is written to the
 * filesystem of the remote JVM.
 *
 * <p>Usage: java JmxHeapDump.java host:port remote-file.hprof [live]
 */
public class JmxHeapDump {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      System.err.println("Usage: java JmxHeapDump.java <host:port> <remote-file.hprof> [live]");
      System.exit(2);
    }
    String hostPort = args[0];
    String file = args[1];
    boolean live = args.length == 3 && Boolean.parseBoolean(args[2]);
    JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + hostPort + "/jmxrmi");
    try (JMXConnector connector = JMXConnectorFactory.connect(url)) {
      MBeanServerConnection connection = connector.getMBeanServerConnection();
      connection.invoke(
          new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
          "dumpHeap",
          new Object[] {file, live},
          new String[] {String.class.getName(), boolean.class.getName()});
    }
    System.out.println("Heap dump written to " + file);
  }
}
