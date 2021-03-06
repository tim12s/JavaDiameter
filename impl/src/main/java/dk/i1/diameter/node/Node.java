package dk.i1.diameter.node;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import dk.i1.diameter.AVP;
import dk.i1.diameter.AVP_Address;
import dk.i1.diameter.AVP_Grouped;
import dk.i1.diameter.AVP_UTF8String;
import dk.i1.diameter.AVP_Unsigned32;
import dk.i1.diameter.InvalidAVPLengthException;
import dk.i1.diameter.Message;
import dk.i1.diameter.ProtocolConstants;
import dk.i1.diameter.Utils;
import dk.i1.diameter.VendorIDs;
import lombok.extern.slf4j.Slf4j;

/**
 * A Diameter node.
 * The Node class manages diameter transport connections and peers. It handles
 * the low-level messages itself (CER/CEA/DPR/DPA/DWR/DWA). The rest is sent to
 * the MessageDispatcher. When connections are established or closed the
 * ConnectionListener is notified. Message can be sent and received through the
 * node but no state is maintained per message.
 * <p>
 * Node is quite low-level. You probably want to use {@link NodeManager} instead.
 * <p>
 * Node instances logs with the name "dk.i1.diameter.node", so you can
 * get detailed logging (including hex-dumps of incoming and outgoing packets)
 * by putting "dk.i1.diameter.node.level = ALL" into your log.properties
 * file (or equivalent)
 *
 * <h3>Enabling TCP and/or SCTP transport protocols</h3>
 * <p>
 * The Node instance uses two properties when deciding which transport-protocols to support:
 * <ul>
 * <li><tt>dk.i1.diameter.node.use_tcp=</tt> [<tt><em>true</em></tt>|<tt><em>false</em></tt>|<tt><em>maybe</em></tt>]
 * (default:true)</li>
 * <li><tt>dk.i1.diameter.node.use_sctp=</tt> [<tt><em>true</em></tt>|<tt><em>false</em></tt>|<tt><em>maybe</em></tt>]
 * (default:maybe)</li>
 * </ul>
 * If a setting is set to true and the support class could not be loaded, then start operation fails.
 * If a setting is false, then no attempt will be made to use that transport-protocol.
 * If a setting is 'maybe' then the stack will try to initialize and use that trasnport-protocol, but failure to do so
 * will not cause the stack initialization to fail.
 * You can override the properties by changing the setting with {@link NodeSettings#setUseTCP} and
 * {@link NodeSettings#setUseSCTP}.
 *
 * <h3>DW jitter, system RNG and stalls on first connection</h3>
 * The node applies jitter to the DW intervals as required by RFC3588->RFC3539->RFC1750, by using
 * <tt>java.security.SecureRandom.getInstance("SHA1PRNG")</tt> when initializing. This
 * can however cause the stack to stall when the <em>first</em> connection is established
 * if the system RNG does not have enough entropy (seen on on a machine with
 * little activity and no hardware RNG). To circumvent this you can set the property:
 * <p>
 * <tt>dk.i1.diameter.node.jitter_prng=</tt><em>your favorite PRNG</em>
 * </p>
 * The default value is <tt>SHA1PRNG</tt>.
 * If set to <tt>bogus</tt> then the stack simply uses Random() instead.
 * Doing so technically violates RFC3588->RFC3539->RFC1750
 *
 * @see NodeManager
 */
@Slf4j
public final class Node {

  private final MessageDispatcher message_dispatcher;
  private final ConnectionListener connection_listener;
  private final NodeSettings settings;
  private final NodeValidator node_validator;
  private final NodeState node_state;
  private Thread reconnect_thread;
  private boolean please_stop;
  private long shutdown_deadline;
  private Map<ConnectionKey, Connection> map_key_conn;
  private Set<Peer> persistent_peers;
  private final Object obj_conn_wait;
  private NodeImplementation tcp_node;
  private NodeImplementation sctp_node;

  /**
   * Constructor for Node.
   * Constructs a Node instance with the specified parameters.
   * The node is not automatically started.
   * Implemented as <tt>this(message_dispatcher,connection_listener,settings,null);</tt>
   *
   * @param message_dispatcher A message dispatcher. If null, a default dispatcher is used you. You probably dont want
   *        that one.
   * @param connection_listener A connection observer. Can be null.
   * @param settings The node settings.
   */
  public Node(final MessageDispatcher message_dispatcher,
          final ConnectionListener connection_listener,
          final NodeSettings settings) {
    this(message_dispatcher, connection_listener, settings, null);
  }

  /**
   * Constructor for Node.
   * Constructs a Node instance with the specified parameters.
   * The node is not automatically started.
   *
   * @param message_dispatcher A message dispatcher. If null, a default dispatcher is used you. You probably dont want
   *        that one.
   * @param connection_listener A connection observer. Can be null.
   * @param settings The node settings.
   * @param node_validator a custom NodeValidator. If null then a {@link DefaultNodeValidator} is used.
   * @since 0.9.4
   */
  public Node(final MessageDispatcher message_dispatcher,
          final ConnectionListener connection_listener,
          final NodeSettings settings,
          final NodeValidator node_validator) {
    this.message_dispatcher = (message_dispatcher == null) ? new DefaultMessageDispatcher() : message_dispatcher;
    this.connection_listener = (connection_listener == null) ? new DefaultConnectionListener() : connection_listener;
    this.settings = settings;
    this.node_validator = (node_validator == null) ? new DefaultNodeValidator() : node_validator;
    this.node_state = new NodeState();
    this.obj_conn_wait = new Object();
    this.tcp_node = null;
    this.sctp_node = null;
  }

  /**
   * Start the node.
   * The node is started. If the port to listen on is already used by
   * another application or some other initial network error occurs a java.io.IOException is thrown.
   *
   * @throws java.io.IOException Usually when a priviledge port is specified, system out of resoruces, etc.
   * @throws UnsupportedTransportProtocolException If a transport-protocol has been specified as mandatory but could not
   *         be initialised.
   */
  public void start() throws java.io.IOException, UnsupportedTransportProtocolException {
    if (tcp_node != null || sctp_node != null) {
      throw new java.io.IOException("Diameter stack is already running");
    }
    log.info("Starting Diameter node");
    please_stop = false;
    prepare();
    if (tcp_node != null) {
      tcp_node.start();
    }
    if (sctp_node != null) {
      sctp_node.start();
    }
    reconnect_thread = new ReconnectThread();
    reconnect_thread.setDaemon(true);
    reconnect_thread.start();
    log.info("Diameter node started");
  }

  /**
   * Stop the node.
   * Implemented as stop(0)
   */
  public void stop() {
    stop(0);
  }

  /**
   * Stop the node.
   * All connections are closed. A DPR is sent to the each connected peer
   * unless the transport connection's buffers are full.
   * Threads waiting in {@link #waitForConnection} are woken.
   * Graceful connection close is not guaranteed in all cases.
   *
   * @param grace_time Maximum time (milliseconds) to wait for connections to close gracefully.
   * @since grace_time parameter introduced in 0.9.3
   */
  public void stop(final long grace_time) {
    log.info("Stopping Diameter node");
    shutdown_deadline = System.currentTimeMillis() + grace_time;
    if (tcp_node != null) {
      tcp_node.initiateStop(shutdown_deadline);
    }
    if (sctp_node != null) {
      sctp_node.initiateStop(shutdown_deadline);
    }
    if (map_key_conn == null) {
      log.info("Cannot stop node: It appears to not be running. (This is the fault of the caller)");
      return;
    }
    synchronized (map_key_conn) {
      please_stop = true;
      //Close all the non-ready connections, initiate close on ready ones.
      for (final Iterator<Map.Entry<ConnectionKey, Connection>> it = map_key_conn.entrySet().iterator(); it
              .hasNext();) {
        final Map.Entry<ConnectionKey, Connection> e = it.next();
        final Connection conn = e.getValue();
        switch (conn.state) {
          case connecting:
          case connected_in:
          case connected_out:
            if (log.isTraceEnabled()) {
              log.trace("Closing connection to " + conn.host_id + " because we are shutting down");
            }
            it.remove();
            conn.node_impl.closeConnection(conn);
            break;
          case tls:
            break; //don't know what to do here yet.
          case ready:
            initiateConnectionClose(conn, ProtocolConstants.DI_DISCONNECT_CAUSE_REBOOTING);
            break;
          case closing:
            break; //nothing to do
          case closed:
            break; //nothing to do
        }
      }
    }
    if (tcp_node != null) {
      tcp_node.wakeup();
    }
    if (sctp_node != null) {
      sctp_node.wakeup();
    }
    synchronized (map_key_conn) {
      map_key_conn.notify();
    }
    try {
      if (tcp_node != null) {
        tcp_node.join();
      }
      if (sctp_node != null) {
        sctp_node.join();
      }
      reconnect_thread.join();
    } catch (final InterruptedException ex) {
    }
    reconnect_thread = null;
    //close all connections not already closed
    //(todo) if a connection's out-buffer is non-empty we should wait for it to empty.
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        closeConnection(conn);
      }
    }
    //other cleanup
    synchronized (obj_conn_wait) {
      obj_conn_wait.notifyAll();
    }
    map_key_conn = null;
    persistent_peers = null;
    if (tcp_node != null) {
      tcp_node.closeIO();
      tcp_node = null;
    }
    if (sctp_node != null) {
      sctp_node.closeIO();
      sctp_node = null;
    }
    log.info("Diameter node stopped");
  }

  private boolean anyReadyConnection() {
    if (map_key_conn == null) {
      return false;
    }
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        if (conn.state == Connection.State.ready) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Wait until at least one connection has been established to a peer
   * and capability-exchange has finished.
   *
   * @since 0.9.1
   */
  public void waitForConnection() throws InterruptedException {
    synchronized (obj_conn_wait) {
      while (!anyReadyConnection()) {
        obj_conn_wait.wait();
      }
    }
  }

  /**
   * Wait until at least one connection has been established or until the timeout expires.
   * Waits until at least one connection to a peer has been established
   * and capability-exchange has finished, or the specified timeout has expired.
   *
   * @param timeout The maximum time to wait in milliseconds.
   * @since 0.9.1
   */
  public void waitForConnection(final long timeout) throws InterruptedException {
    final long wait_end = System.currentTimeMillis() + timeout;
    synchronized (obj_conn_wait) {
      long now = System.currentTimeMillis();
      while (!anyReadyConnection() && now < wait_end) {
        final long t = wait_end - now;
        obj_conn_wait.wait(t);
        now = System.currentTimeMillis();
      }
    }
  }

  /**
   * Wait until at least one connection has been established or until the timeout expires.
   * Waits until at least one connection to a peer has been established
   * and capability-exchange has finished, or the specified timeout has expired.
   * If the timeout expires then a ConnectionTimeoutException is thrown.
   *
   * @param timeout The maximum time to wait in milliseconds.
   * @since 0.9.6.5
   * @throws ConnectionTimeoutException If the timeout expires without any connection established.
   */
  public void waitForConnectionTimeout(final long timeout) throws InterruptedException, ConnectionTimeoutException {
    waitForConnection(timeout);
    if (!anyReadyConnection()) {
      throw new ConnectionTimeoutException(
              "No connection was established within timeout (" + timeout + " milliseconds)");
    }
  }

  /**
   * Returns the connection key for a peer.
   * Behaviour change since 0.9.6: Connections that are not in the "Open"
   * state (rfc3588 section 5.6) will not be returned.
   *
   * @return The connection key. Null if there is no connection to the peer.
   */
  public ConnectionKey findConnection(final Peer peer) {
    if (log.isTraceEnabled()) {
      log.trace("Finding '" + peer.host() + "'");
    }
    if (map_key_conn == null) {
      if (log.isTraceEnabled()) {
        log.trace(peer.host() + " NOT found (node is not ready)");
      }
      return null;
    }
    synchronized (map_key_conn) {
      //System.out.println("Node.findConnection: size=" + map_key_conn.size());
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        //System.out.println("Node.findConnection(): examing " + ((conn.peer!=null)?conn.peer.host():"?"));
        if (conn.state != Connection.State.ready) {
          continue;
        }
        if (conn.peer != null
                && conn.peer.equals(peer)) {
          //System.out.println("Node.findConnection(): found");
          return conn.key;
        }
      }
      if (log.isTraceEnabled()) {
        log.trace(peer.host() + " NOT found");
      }
      return null;
    }
  }

  /**
   * Returns if the connection is still valid.
   * This method is usually only of interest to programs that do lengthy
   * processing of requests nad are located in a poor network. It is
   * usually much easier to just call sendMessage() and catch the
   * exception if the connection has gone stale.
   */
  public boolean isConnectionKeyValid(final ConnectionKey connkey) {
    if (map_key_conn == null) {
      return false;
    }
    synchronized (map_key_conn) {
      return map_key_conn.get(connkey) != null;
    }
  }

  /**
   * Returns the Peer on a connection.
   */
  public Peer connectionKey2Peer(final ConnectionKey connkey) {
    if (map_key_conn == null) {
      return null;
    }
    synchronized (map_key_conn) {
      final Connection conn = map_key_conn.get(connkey);
      if (conn != null) {
        return conn.peer;
      } else {
        return null;
      }
    }
  }

  /**
   * Returns the IP-address of the remote end of a connection.
   * Note: for connections using the SCTP transport protocol the returned
   * IP-address will be one of the peer's IP-addresses but it is
   * unspecified which one. In this case it is better to use
   * connectionKey2Peer()
   */
  public InetAddress connectionKey2InetAddress(final ConnectionKey connkey) {
    if (map_key_conn == null) {
      return null;
    }
    synchronized (map_key_conn) {
      final Connection conn = map_key_conn.get(connkey);
      if (conn != null) {
        return conn.toInetAddress();
      } else {
        return null;
      }
    }
  }

  /**
   * Returns the next hop-by-hop identifier for a connection
   */
  public int nextHopByHopIdentifier(final ConnectionKey connkey) throws StaleConnectionException {
    if (map_key_conn == null) {
      throw new StaleConnectionException();
    }
    synchronized (map_key_conn) {
      final Connection conn = map_key_conn.get(connkey);
      if (conn == null) {
        throw new StaleConnectionException();
      }
      return conn.nextHopByHopIdentifier();
    }
  }

  /**
   * Send a message.
   * Send the specified message on the specified connection.
   *
   * @param msg The message to be sent
   * @param connkey The connection to use. If the connection has been closed in the meantime StaleConnectionException is
   *        thrown.
   */
  public void sendMessage(final Message msg, final ConnectionKey connkey) throws StaleConnectionException {
    if (map_key_conn == null) {
      throw new StaleConnectionException();
    }
    synchronized (map_key_conn) {
      final Connection conn = map_key_conn.get(connkey);
      if (conn == null) {
        throw new StaleConnectionException();
      }
      if (conn.state != Connection.State.ready) {
        throw new StaleConnectionException();
      }
      sendMessage(msg, conn);
    }
  }

  private void sendMessage(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("command=" + msg.hdr.command_code + ", to=" + (conn.peer != null ? conn.peer.toString() : conn.host_id));
    }
    final byte[] raw = msg.encode();

    if (log.isTraceEnabled()) {
      log.trace(hexDump("Raw packet encoded", raw, 0, raw.length));
    }

    conn.sendMessage(raw);
  }

  /**
   * Initiate a connection to a peer.
   * A connection (if not already present) will be initiated to the peer.
   * On return, the connection is probably not established and it may
   * take a few seconds before it is. It is safe to call multiple times.
   * If <code>persistent</code> true then the peer is added to a list of
   * persistent peers and if the connection is lost it will automatically
   * be re-established. There is no way to change a peer from persistent
   * to non-persistent.
   * <p>
   * If/when the connection has been established and capability-exchange
   * has finished threads waiting in {@link #waitForConnection} are woken.
   * <p>
   * You cannot initiate connections before the node has been started.
   * Connection to peers specifying an unsupported transport-protocl are simply ignored.
   *
   * @param peer The peer that the node should try to establish a connection to.
   * @param persistent If true the Node wil try to keep a connection open to the peer.
   */
  public void initiateConnection(final Peer peer, final boolean persistent) {
    if (persistent) {
      synchronized (persistent_peers) {
        persistent_peers.add(new Peer(peer));
      }
    }
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        if (conn.peer != null
                && conn.peer.equals(peer)) {
          return; //already has a connection to that peer
          //what if we are connecting and the host_id matches?
        }
      }
      if (log.isInfoEnabled()) {
        log.info("Initiating connection to '" + peer.host() + "' port " + peer.port());
      }
      NodeImplementation node_impl = null;
      switch (peer.transportProtocol()) {
        case tcp:
          node_impl = tcp_node;
          break;
        case sctp:
          node_impl = sctp_node;
          break;
      }
      if (node_impl != null) {
        final Connection conn = node_impl.newConnection(settings.watchdogInterval(), settings.idleTimeout());
        conn.host_id = peer.host();
        conn.peer = peer;
        if (node_impl.initiateConnection(conn, peer)) {
          map_key_conn.put(conn.key, conn);
          if (log.isTraceEnabled()) {
            log.trace("Initiated connection to [" + peer.toString() + "]");
          }
        }
      } else {
        if (log.isInfoEnabled()) {
          log.info(
                  "Transport connection to '" + peer.host() + "' cannot be established because the transport protocol ("
                  + peer.transportProtocol() + ") is not supported");
        }
      }
    }
  }

  private class ReconnectThread extends Thread {

    public ReconnectThread() {
      super("Diameter node reconnect thread");
    }

    @Override
    public void run() {
      for (;;) {
        synchronized (map_key_conn) {
          if (please_stop) {
            return;
          }
          try {
            map_key_conn.wait(30000);
          } catch (final InterruptedException ex) {
          }
          if (please_stop) {
            return;
          }
        }
        synchronized (persistent_peers) {
          for (final Peer peer : persistent_peers) {
            initiateConnection(peer, false);
          }
        }
      }
    }
  }

  private static Boolean getUseOption(final Boolean setting, final String property_name,
          final Boolean default_setting) {
    if (setting != null) {
      return setting;
    }
    final String v = System.getProperty(property_name);
    if (v != null && v.equals("true")) {
      return true;
    }
    if (v != null && v.equals("false")) {
      return false;
    }
    if (v != null && v.equals("maybe")) {
      return null;
    }
    return default_setting;
  }

  @SuppressWarnings("unchecked")
  private NodeImplementation instantiateNodeImplementation(final String class_name) {
    final Class our_cls = this.getClass();
    ClassLoader cls_ldr = our_cls.getClassLoader();
    if (cls_ldr == null) {
      cls_ldr = ClassLoader.getSystemClassLoader();
    }
    try {
      final Class cls = cls_ldr.loadClass(class_name);
      Constructor<NodeImplementation> ctor;
      try {
        ctor = cls.getConstructor(this.getClass(),
                settings.getClass());
      } catch (final NoSuchMethodException ex) {
        log.warn("Could not find constructor for " + class_name, ex);
        return null;
      } catch (final NoClassDefFoundError ex) {
        log.warn("Could not find constructor for " + class_name, ex);
        return null;
      } catch (final UnsatisfiedLinkError ex) {
        log.warn("Could not find constructor for " + class_name, ex);
        return null;
      }
      if (ctor == null) {
        return null;
      }
      try {
        final NodeImplementation instance = ctor.newInstance(this, settings);
        return instance;
      } catch (final InstantiationException ex) {
        return null;
      } catch (final IllegalAccessException ex) {
        return null;
      } catch (final java.lang.reflect.InvocationTargetException ex) {
        return null;
      } catch (final UnsatisfiedLinkError ex) {
        log.warn("Could not construct a " + class_name, ex);
        return null;
      } catch (final NoClassDefFoundError ex) {
        //this exception was seen with ibm-java-ppc-60 JDK
        return null;
      }
    } catch (final ClassNotFoundException ex) {
      log.warn("class " + class_name + " not found/loaded", ex);
      return null;
    }
  }

  private NodeImplementation loadTransportProtocol(final Boolean setting, final String setting_name,
          final Boolean default_setting,
          final String class_name, final String short_name)
          throws java.io.IOException, UnsupportedTransportProtocolException {
    Boolean b;
    b = getUseOption(setting, setting_name, default_setting);
    NodeImplementation node_impl = null;
    if (b == null || b) {
      node_impl = instantiateNodeImplementation(class_name);
      if (node_impl != null) {
        node_impl.openIO();
      } else if (b != null) {
        throw new UnsupportedTransportProtocolException(short_name + " support could not be loaded");
      }
    }
    if (log.isInfoEnabled()) {
      log.info(short_name + " support was " + (node_impl != null ? "loaded" : "not loaded"));
    }
    return node_impl;
  }

  private void prepare() throws java.io.IOException, UnsupportedTransportProtocolException {
    tcp_node = loadTransportProtocol(settings.useTCP(), "dk.i1.diameter.node.use_tcp", true,
            "dk.i1.diameter.node.TCPNode", "TCP");
    sctp_node = loadTransportProtocol(settings.useSCTP(), "dk.i1.diameter.node.use_sctp", null,
            "dk.i1.diameter.node.SCTPNode", "SCTP");
    if (tcp_node == null && sctp_node == null) {
      log.warn("No transport protocol classes could be loaded. The stack is running but without have any connectivity");
    }

    map_key_conn = new HashMap<ConnectionKey, Connection>();
    persistent_peers = new HashSet<Peer>();
  }

  /**
   * Calculate next timeout for a node implementation.
   * Located here because the calculation involves examining each open connection.
   */
  long calcNextTimeout(final NodeImplementation node_impl) {
    long timeout = -1;
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        if (conn.node_impl != node_impl) {
          continue;
        }
        final boolean ready = conn.state == Connection.State.ready;
        final long conn_timeout = conn.timers.calcNextTimeout(ready);
        if (timeout == -1 || conn_timeout < timeout) {
          timeout = conn_timeout;
        }
      }
    }
    if (please_stop && shutdown_deadline < timeout) {
      timeout = shutdown_deadline;
    }
    return timeout;
  }

  /**
   * Run timers on the connections for a node implementation.
   * Located here because it involves examining each open connection.
   */
  void runTimers(final NodeImplementation node_impl) {
    synchronized (map_key_conn) {
      for (final Iterator<Map.Entry<ConnectionKey, Connection>> it = map_key_conn.entrySet().iterator(); it
              .hasNext();) {
        final Map.Entry<ConnectionKey, Connection> e = it.next();
        final Connection conn = e.getValue();
        if (conn.node_impl != node_impl) {
          continue;
        }
        final boolean ready = conn.state == Connection.State.ready;
        switch (conn.timers.calcAction(ready)) {
          case none:
            break;
          case disconnect_no_cer:
            log.warn("Disconnecting due to no CER/CEA");
            it.remove();
            closeConnection(conn);
            break;
          case disconnect_idle:
            log.warn("Disconnecting due to idle");
            //busy is the closest thing to "no traffic for a long time. No point in keeping the connection"
            it.remove();
            initiateConnectionClose(conn, ProtocolConstants.DI_DISCONNECT_CAUSE_BUSY);
            break;
          case disconnect_no_dw:
            log.warn("Disconnecting due to no DWA");
            it.remove();
            closeConnection(conn);
            break;
          case dwr:
            sendDWR(conn);
            break;
        }
      }
    }
  }

  /** Logs a correctly decoded message */
  void logRawDecodedPacket(final byte[] raw, final int offset, final int msg_size) {
    log.trace(hexDump("Raw packet decoded", raw, offset, msg_size));
  }

  /** Logs an incorrectly decoded (non-diameter-)message. */
  void logGarbagePacket(final Connection conn, final byte[] raw, final int offset, final int msg_size) {
    log.warn(hexDump("Garbage from " + conn.host_id, raw, offset, msg_size));
  }

  private String hexDump(final String msg, final byte buf[], final int offset, int bytes) {
    //For some reason this method is grotesquely slow, so we limit the raw dump to 1K
    if (bytes > 1024) {
      bytes = 1024;
    }
    final StringBuffer sb = new StringBuffer(msg.length() + 1 + bytes * 3 + (bytes / 16 + 1) * (6 + 3 + 5 + 1));
    sb.append(msg + "\n");
    for (int i = 0; i < bytes; i += 16) {
      sb.append(String.format("%04X ", Integer.valueOf(i)));
      for (int j = i; j < i + 16; j++) {
        if ((j % 4) == 0) {
          sb.append(' ');
        }
        if (j < bytes) {
          final byte b = buf[offset + j];
          sb.append(String.format("%02X", b));
        } else {
          sb.append("  ");
        }
      }
      sb.append("     ");
      for (int j = i; j < i + 16 && j < bytes; j++) {
        final byte b = buf[offset + j];
        if (b >= 32 && b < 127) {
          sb.append((char) b);
        } else {
          sb.append('.');
        }
      }
      sb.append('\n');
    }
    if (bytes > 1024) {
      sb.append("...\n"); //Maybe the string "(truncated)" would be a more direct hint
    }
    return sb.toString();
  }

  void closeConnection(final Connection conn) {
    closeConnection(conn, false);
  }

  void closeConnection(final Connection conn, final boolean reset) {
    if (conn.state == Connection.State.closed) {
      return;
    }
    if (log.isInfoEnabled()) {
      log.info("Closing connection to " + (conn.peer != null ? conn.peer.toString() : conn.host_id));
    }
    synchronized (map_key_conn) {
      conn.node_impl.close(conn, reset);
      map_key_conn.remove(conn.key);
      conn.state = Connection.State.closed;
    }
    connection_listener.handle(conn.key, conn.peer, false);
  }

  //Send a DPR with the specified disconnect-cause, want change the state to 'closing'
  private void initiateConnectionClose(final Connection conn, final int why) {
    if (conn.state != Connection.State.ready) {
      return; //should probably never happen
    }
    conn.state = Connection.State.closing;
    sendDPR(conn, why);
  }

  boolean handleMessage(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("command_code=" + msg.hdr.command_code + " application_id=" + msg.hdr.application_id
              + " connection_state=" + conn.state);
    }
    conn.timers.markActivity();
    if (conn.state == Connection.State.connected_in) {
      //only CER allowed
      if (!msg.hdr.isRequest()
              || msg.hdr.command_code != ProtocolConstants.DIAMETER_COMMAND_CAPABILITIES_EXCHANGE
              || msg.hdr.application_id != ProtocolConstants.DIAMETER_APPLICATION_COMMON) {
        log.warn("Got something that wasn't a CER");
        return false;
      }
      conn.timers.markRealActivity();
      return handleCER(msg, conn);
    } else if (conn.state == Connection.State.connected_out) {
      //only CEA allowed
      if (msg.hdr.isRequest()
              || msg.hdr.command_code != ProtocolConstants.DIAMETER_COMMAND_CAPABILITIES_EXCHANGE
              || msg.hdr.application_id != ProtocolConstants.DIAMETER_APPLICATION_COMMON) {
        log.warn("Got something that wasn't a CEA");
        return false;
      }
      conn.timers.markRealActivity();
      return handleCEA(msg, conn);
    } else {
      switch (msg.hdr.command_code) {
        case ProtocolConstants.DIAMETER_COMMAND_CAPABILITIES_EXCHANGE:
          log.warn("Got CER from " + conn.host_id + " after initial capability-exchange");
          //not allowed in this state
          return false;
        case ProtocolConstants.DIAMETER_COMMAND_DEVICE_WATCHDOG:
          if (msg.hdr.isRequest()) {
            return handleDWR(msg, conn);
          } else {
            return handleDWA(msg, conn);
          }
        case ProtocolConstants.DIAMETER_COMMAND_DISCONNECT_PEER:
          if (msg.hdr.isRequest()) {
            return handleDPR(msg, conn);
          } else {
            return handleDPA(msg, conn);
          }
        default:
          conn.timers.markRealActivity();
          if (msg.hdr.isRequest()) {
            if (isLoopedMessage(msg)) {
              rejectLoopedRequest(msg, conn);
              return true;
            }
            if (!isAllowedApplication(msg, conn.peer)) {
              rejectDisallowedRequest(msg, conn);
              return true;
            }
            //We could also reject requests if we ar shutting down, but there are no result-code for this.
          }
          if (!message_dispatcher.handle(msg, conn.key, conn.peer)) {
            if (msg.hdr.isRequest()) {
              return handleUnknownRequest(msg, conn);
            } else {
              return true; //unusual, but not impossible
            }
          } else {
            return true;
          }
      }
    }
  }

  private boolean isLoopedMessage(final Message msg) {
    //6.1.3
    for (final AVP a : msg.subset(ProtocolConstants.DI_ROUTE_RECORD)) {
      final AVP_UTF8String avp = new AVP_UTF8String(a);
      if (avp.queryValue().equals(settings.hostId())) {
        return true;
      }
    }
    return false;
  }

  private void rejectLoopedRequest(final Message msg, final Connection conn) {
    log.warn("Rejecting looped request from " + conn.peer.host() + " (command=" + msg.hdr.command_code + ").");
    rejectRequest(msg, conn, ProtocolConstants.DIAMETER_RESULT_LOOP_DETECTED);
  }

  /**
   * A small class to ease parsing of vendor-specific-app
   */
  private static class AVP_VendorSpecificApplicationId extends AVP_Grouped {

    public AVP_VendorSpecificApplicationId(final AVP a) throws InvalidAVPLengthException, InvalidAVPValueException {
      super(a);
      final AVP g[] = queryAVPs();
      if (g.length < 2) {
        throw new InvalidAVPValueException(a);
      }
      boolean found_vendor_id = false;
      boolean found_app_id = false;
      for (final AVP e : g) {
        if (e.code == ProtocolConstants.DI_VENDOR_ID) {
          found_vendor_id = true;
        } else if (e.code == ProtocolConstants.DI_AUTH_APPLICATION_ID) {
          found_app_id = true;
        } else if (e.code == ProtocolConstants.DI_ACCT_APPLICATION_ID) {
          found_app_id = true;
          //else: something non-compliant, but we are tolerant
        }
      }
      if (!found_vendor_id || !found_app_id) {
        throw new InvalidAVPValueException(a);
      }
    }

    public AVP_VendorSpecificApplicationId(final int vendor_id, final int auth_app_id, final int acct_app_id) {
      super(ProtocolConstants.DI_VENDOR_SPECIFIC_APPLICATION_ID);
      AVP_Unsigned32 app_id_avp;
      if (auth_app_id != 0) {
        app_id_avp = new AVP_Unsigned32(ProtocolConstants.DI_AUTH_APPLICATION_ID, auth_app_id);
      } else {
        app_id_avp = new AVP_Unsigned32(ProtocolConstants.DI_ACCT_APPLICATION_ID, acct_app_id);
      }
      setAVPs(new AVP[]{new AVP_Unsigned32(ProtocolConstants.DI_VENDOR_ID, vendor_id),
        app_id_avp
      });
    }

    public int vendorId() throws InvalidAVPLengthException, InvalidAVPValueException {
      for (final AVP a : queryAVPs()) {
        if (a.code == ProtocolConstants.DI_VENDOR_ID) {
          return new AVP_Unsigned32(a).queryValue();
        }
      }
      throw new InvalidAVPValueException(this);
    }

    public Integer authAppId() throws InvalidAVPLengthException {
      for (final AVP a : queryAVPs()) {
        if (a.code == ProtocolConstants.DI_AUTH_APPLICATION_ID) {
          return new AVP_Unsigned32(a).queryValue();
        }
      }
      return null;
    }

    public Integer acctAppId() throws InvalidAVPLengthException {
      for (final AVP a : queryAVPs()) {
        if (a.code == ProtocolConstants.DI_ACCT_APPLICATION_ID) {
          return new AVP_Unsigned32(a).queryValue();
        }
      }
      return null;
    }
  }

  /**
   * Determine if a message is supported by a peer.
   * The auth-application-id, acct-application-id or
   * vendor-specific-application AVP is extracted and tested against the
   * peer's capabilities.
   *
   * @param msg The message
   * @param peer The peer
   * @return True if the peer should be able to handle the message.
   */
  public boolean isAllowedApplication(final Message msg, final Peer peer) {
    try {
      AVP avp;
      avp = msg.find(ProtocolConstants.DI_AUTH_APPLICATION_ID);
      if (avp != null) {
        final int app = new AVP_Unsigned32(avp).queryValue();
        if (log.isTraceEnabled()) {
          log.trace("auth-application-id=" + app);
        }
        if (peer.capabilities.isAllowedAuthApp(app)) {
          return true;
        }
        //special wrinkle for 3GPP IMS applications where CER/CEA uses
        //vendor-specific-application but the actual messages uses plain
        //auth-application-id
        if (peer.capabilities.isAllowedAuthApp(VendorIDs.Vendor_3GPP, app)) {
          return true;
        }
        return false;
      }
      avp = msg.find(ProtocolConstants.DI_ACCT_APPLICATION_ID);
      if (avp != null) {
        final int app = new AVP_Unsigned32(avp).queryValue();
        if (log.isTraceEnabled()) {
          log.trace("acct-application-id=" + app);
        }
        return peer.capabilities.isAllowedAcctApp(app);
      }
      avp = msg.find(ProtocolConstants.DI_VENDOR_SPECIFIC_APPLICATION_ID);
      if (avp != null) {
        final AVP_VendorSpecificApplicationId vsai = new AVP_VendorSpecificApplicationId(avp);
        final int vendor_id = vsai.vendorId();
        if (log.isTraceEnabled()) {
          if (vsai.authAppId() != null) {
            log.trace("vendor-id=" + vendor_id + ", auth_app=" + vsai.authAppId());
          }
          if (vsai.acctAppId() != null) {
            log.trace("vendor-id=" + vendor_id + ", acct_app=" + vsai.acctAppId());
          }
        }
        if (vsai.authAppId() != null) {
          return peer.capabilities.isAllowedAuthApp(vendor_id, vsai.authAppId());
        }
        if (vsai.acctAppId() != null) {
          return peer.capabilities.isAllowedAcctApp(vendor_id, vsai.acctAppId());
        }
        return false;
      }
      log.warn("No auth-app-id, acct-app-id nor vendor-app in packet");
    } catch (final InvalidAVPLengthException ex) {
      log.info("Encountered invalid AVP length while looking at application-id", ex);
    } catch (final InvalidAVPValueException ex) {
      log.info("Encountered invalid AVP value while looking at application-id", ex);
    }
    return false;
  }

  private void rejectDisallowedRequest(final Message msg, final Connection conn) {
    log.warn("Rejecting request  from " + conn.peer.host() + " (command=" + msg.hdr.command_code
            + ") because it is not allowed.");
    rejectRequest(msg, conn, ProtocolConstants.DIAMETER_RESULT_APPLICATION_UNSUPPORTED);
  }

  private void rejectRequest(final Message msg, final Connection conn, final int result_code) {
    final Message response = new Message();
    response.prepareResponse(msg);
    if (result_code >= 3000 && result_code <= 3999) {
      response.hdr.setError(true);
    }
    response.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, result_code));
    addOurHostAndRealm(response);
    Utils.copyProxyInfo(msg, response);
    Utils.setMandatory_RFC3588(response);
    sendMessage(response, conn);
  }

  /**
   * Add origin-host and origin-realm to a message.
   * The configured host and realm is added to the message as origin-host
   * and origin-realm AVPs
   */
  public void addOurHostAndRealm(final Message msg) {
    msg.add(new AVP_UTF8String(ProtocolConstants.DI_ORIGIN_HOST, settings.hostId()));
    msg.add(new AVP_UTF8String(ProtocolConstants.DI_ORIGIN_REALM, settings.realm()));
  }

  /**
   * Returns an end-to-end identifier that is unique.
   * The initial value is generated as described in RFC 3588 section 3 page 34.
   */
  public int nextEndToEndIdentifier() {
    return node_state.nextEndToEndIdentifier();
  }

  /**
   * Generate a new session-id.
   * Implemented as makeNewSessionId(null)
   *
   * @since 0.9.2
   */
  public String makeNewSessionId() {
    return makeNewSessionId(null);
  }

  /**
   * Generate a new session-id.
   * A Session-Id consists of a mandatory part and an optional part.
   * The mandatory part consists of the host-id and two sequencers.
   * The optional part can be anything. The caller provide some
   * information that will be helpful in debugging in production
   * environments, such as user-name or calling-station-id.
   *
   * @since 0.9.2
   */
  public String makeNewSessionId(final String optional_part) {
    final String mandatory_part = settings.hostId() + ";" + node_state.nextSessionId_second_part();
    if (optional_part == null) {
      return mandatory_part;
    } else {
      return mandatory_part + ";" + optional_part;
    }
  }

  /**
   * Returns the node's state-id.
   *
   * @since 0.9.2
   */
  public int stateId() {
    return node_state.stateId();
  }

  private boolean doElection(final String cer_host_id) {
    final int cmp = settings.hostId().compareTo(cer_host_id);
    if (cmp == 0) {
      log.warn("Got CER with host-id=" + cer_host_id + ". Suspecting this is a connection from ourselves.");
      //this is a misconfigured peer or ourselves.
      return false;
    }
    final boolean close_other_connection = cmp > 0;
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        if (conn.host_id != null && conn.host_id.equals(cer_host_id)
                && conn.state == Connection.State.ready //TODO: what about TLS?
                ) {
          if (log.isInfoEnabled()) {
            log.info("New connection to a peer we already have a connection to (" + cer_host_id + ")");
          }
          if (close_other_connection) {
            closeConnection(conn);
            return true;
          } else {
            return false; //close this one
          }
        }
      }
    }
    return true;
  }

  private boolean handleCER(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("CER received from " + conn.host_id);
    }
    //Handle election
    String host_id;
    {
      final AVP avp = msg.find(ProtocolConstants.DI_ORIGIN_HOST);
      if (avp == null) {
        //Origin-Host-Id is missing
        if (log.isTraceEnabled()) {
          log.trace("CER from " + conn.host_id + " is missing the Origin-Host_id AVP. Rejecting.");
        }
        final Message error_response = new Message();
        error_response.prepareResponse(msg);
        error_response
                .add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_MISSING_AVP));
        addOurHostAndRealm(error_response);
        error_response.add(new AVP_FailedAVP(new AVP_UTF8String(ProtocolConstants.DI_ORIGIN_HOST, "")));
        Utils.setMandatory_RFC3588(error_response);
        sendMessage(error_response, conn);
        return false;
      }
      host_id = new AVP_UTF8String(avp).queryValue();
      if (log.isTraceEnabled()) {
        log.trace("Peer's origin-host-id is " + host_id);
      }

      //We must authenticate the host before doing election.
      //Otherwise a rogue node could trick us into
      //disconnecting legitimate peers.
      final NodeValidator.AuthenticationResult ar
              = node_validator.authenticateNode(host_id, conn.getRelevantNodeAuthInfo());
      if (ar == null || !ar.known) {
        if (log.isTraceEnabled()) {
          log.trace("We do not know " + conn.host_id + " Rejecting.");
        }
        final Message error_response = new Message();
        error_response.prepareResponse(msg);
        if (ar != null && ar.result_code != null) {
          error_response.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ar.result_code));
        } else {
          error_response.add(
                  new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_UNKNOWN_PEER));
        }
        addOurHostAndRealm(error_response);
        if (ar != null && ar.error_message != null) {
          error_response.add(new AVP_UTF8String(ProtocolConstants.DI_ERROR_MESSAGE, ar.error_message));
        }
        Utils.setMandatory_RFC3588(error_response);
        sendMessage(error_response, conn);
        return false;

      }

      if (!doElection(host_id)) {
        if (log.isTraceEnabled()) {
          log.trace("CER from " + conn.host_id + " lost the election. Rejecting.");
        }
        final Message error_response = new Message();
        error_response.prepareResponse(msg);
        error_response.add(
                new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_ELECTION_LOST));
        addOurHostAndRealm(error_response);
        Utils.setMandatory_RFC3588(error_response);
        sendMessage(error_response, conn);
        return false;
      }
    }

    conn.peer = conn.toPeer();
    conn.peer.host(host_id);
    conn.host_id = host_id;

    if (handleCEx(msg, conn)) {
      //todo: check inband-security
      final Message cea = new Message();
      cea.prepareResponse(msg);
      //Result-Code
      cea.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_SUCCESS));
      addCEStuff(cea, conn.peer.capabilities, conn);

      if (log.isInfoEnabled()) {
        log.info("Connection to " + conn.peer.toString() + " is now ready");
      }
      Utils.setMandatory_RFC3588(cea);
      sendMessage(cea, conn);
      conn.state = Connection.State.ready;
      connection_listener.handle(conn.key, conn.peer, true);
      synchronized (obj_conn_wait) {
        obj_conn_wait.notifyAll();
      }
      return true;
    } else {
      return false;
    }
  }

  private boolean handleCEA(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("CEA received from " + conn.host_id);
    }
    AVP avp = msg.find(ProtocolConstants.DI_RESULT_CODE);
    if (avp == null) {
      log.warn("CEA from " + conn.host_id
              + " did not contain a Result-Code AVP (violation of RFC3588 section 5.3.2 page 61). Dropping connection");
      return false;
    }
    int result_code;
    try {
      result_code = new AVP_Unsigned32(avp).queryValue();
    } catch (final InvalidAVPLengthException ex) {
      if (log.isInfoEnabled()) {
        log.info("CEA from " + conn.host_id + " contained an ill-formed Result-Code. Dropping connection");
      }
      return false;
    }
    if (result_code != ProtocolConstants.DIAMETER_RESULT_SUCCESS) {
      if (log.isInfoEnabled()) {
        log.info("CEA from " + conn.host_id + " was rejected with Result-Code " + result_code + ". Dropping connection");
      }
      return false;
    }
    avp = msg.find(ProtocolConstants.DI_ORIGIN_HOST);
    if (avp == null) {
      log.warn("Peer did not include origin-host-id in CEA (violation of RFC3588 section 5.3.2 page 61). Dropping connection");
      return false;
    }
    final String host_id = new AVP_UTF8String(avp).queryValue();
    if (log.isTraceEnabled()) {
      log.trace("Node:Peer's origin-host-id is '" + host_id + "'. Expected: '" + conn.host_id + "'");
    }

    conn.peer = conn.toPeer();
    conn.peer.host(host_id);
    conn.host_id = host_id;
    final boolean rc = handleCEx(msg, conn);
    if (rc) {
      conn.state = Connection.State.ready;
      if (log.isInfoEnabled()) {
        log.info("Connection to " + conn.peer.toString() + " is now ready");
      }
      connection_listener.handle(conn.key, conn.peer, true);
      synchronized (obj_conn_wait) {
        obj_conn_wait.notifyAll();
      }
      return true;
    } else {
      return false;
    }
  }

  private boolean handleCEx(final Message msg, final Connection conn) {
    log.trace("Processing CER/CEA");
    //calculate capabilities and allowed applications
    try {
      final Capability reported_capabilities = new Capability();
      for (final AVP a : msg.subset(ProtocolConstants.DI_SUPPORTED_VENDOR_ID)) {
        final int vendor_id = new AVP_Unsigned32(a).queryValue();
        if (log.isTraceEnabled()) {
          log.trace("peer supports vendor " + vendor_id);
        }
        reported_capabilities.addSupportedVendor(vendor_id);
      }
      for (final AVP a : msg.subset(ProtocolConstants.DI_AUTH_APPLICATION_ID)) {
        final int app = new AVP_Unsigned32(a).queryValue();
        if (log.isTraceEnabled()) {
          log.trace("peer supports auth-app " + app);
        }
        if (app != ProtocolConstants.DIAMETER_APPLICATION_COMMON) {
          reported_capabilities.addAuthApp(app);
        }
      }
      for (final AVP a : msg.subset(ProtocolConstants.DI_ACCT_APPLICATION_ID)) {
        final int app = new AVP_Unsigned32(a).queryValue();
        if (log.isTraceEnabled()) {
          log.trace("peer supports acct-app " + app);
        }
        if (app != ProtocolConstants.DIAMETER_APPLICATION_COMMON) {
          reported_capabilities.addAcctApp(app);
        }
      }
      for (final AVP a : msg.subset(ProtocolConstants.DI_VENDOR_SPECIFIC_APPLICATION_ID)) {
        final AVP_VendorSpecificApplicationId vsai = new AVP_VendorSpecificApplicationId(a);
        final int vendor_id = vsai.vendorId();
        if (vsai.authAppId() != null) {
          reported_capabilities.addVendorAuthApp(vendor_id, vsai.authAppId());
        }
        if (vsai.acctAppId() != null) {
          reported_capabilities.addVendorAcctApp(vendor_id, vsai.acctAppId());
        }
      }

      final Capability result_capabilities
              = node_validator.authorizeNode(conn.host_id, settings, reported_capabilities);
      if (log.isTraceEnabled()) {
        String s = "";
        for (final Integer i : result_capabilities.supported_vendor) {
          s = s + "  supported_vendor " + i + "\n";
        }
        for (final Integer i : result_capabilities.auth_app) {
          s = s + "  auth_app " + i + "\n";
        }
        for (final Integer i : result_capabilities.acct_app) {
          s = s + "  acct_app " + i + "\n";
        }
        for (final Capability.VendorApplication va : result_capabilities.auth_vendor) {
          s = s + "  vendor_auth_app: vendor " + va.vendor_id + ", application " + va.application_id + "\n";
        }
        for (final Capability.VendorApplication va : result_capabilities.acct_vendor) {
          s = s + "  vendor_acct_app: vendor " + va.vendor_id + ", application " + va.application_id + "\n";
        }
        log.trace("Resulting capabilities:\n" + s);
      }
      if (result_capabilities.isEmpty()) {
        log.warn("No application in common with " + conn.host_id);
        if (msg.hdr.isRequest()) {
          final Message error_response = new Message();
          error_response.prepareResponse(msg);
          error_response.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE,
                  ProtocolConstants.DIAMETER_RESULT_NO_COMMON_APPLICATION));
          addOurHostAndRealm(error_response);
          Utils.setMandatory_RFC3588(error_response);
          sendMessage(error_response, conn);
        }
        return false;
      }

      conn.peer.capabilities = result_capabilities;
    } catch (final InvalidAVPLengthException ex) {
      log.warn("Invalid AVP in CER/CEA", ex);
      if (msg.hdr.isRequest()) {
        final Message error_response = new Message();
        error_response.prepareResponse(msg);
        error_response.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE,
                ProtocolConstants.DIAMETER_RESULT_INVALID_AVP_LENGTH));
        addOurHostAndRealm(error_response);
        error_response.add(new AVP_FailedAVP(ex.avp));
        Utils.setMandatory_RFC3588(error_response);
        sendMessage(error_response, conn);
      }
      return false;
    } catch (final InvalidAVPValueException ex) {
      log.warn("Invalid AVP in CER/CEA", ex);
      if (msg.hdr.isRequest()) {
        final Message error_response = new Message();
        error_response.prepareResponse(msg);
        error_response.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE,
                ProtocolConstants.DIAMETER_RESULT_INVALID_AVP_VALUE));
        addOurHostAndRealm(error_response);
        error_response.add(new AVP_FailedAVP(ex.avp));
        Utils.setMandatory_RFC3588(error_response);
        sendMessage(error_response, conn);
      }
      return false;
    }
    return true;
  }

  void initiateCER(final Connection conn) {
    sendCER(conn);
  }

  private void sendCER(final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("Sending CER to " + conn.host_id);
    }
    final Message cer = new Message();
    cer.hdr.setRequest(true);
    cer.hdr.command_code = ProtocolConstants.DIAMETER_COMMAND_CAPABILITIES_EXCHANGE;
    cer.hdr.application_id = ProtocolConstants.DIAMETER_APPLICATION_COMMON;
    cer.hdr.hop_by_hop_identifier = conn.nextHopByHopIdentifier();
    cer.hdr.end_to_end_identifier = node_state.nextEndToEndIdentifier();
    addCEStuff(cer, settings.capabilities(), conn);
    Utils.setMandatory_RFC3588(cer);

    sendMessage(cer, conn);
  }

  private void addCEStuff(final Message msg, final Capability capabilities, final Connection conn) {
    //Origin-Host, Origin-Realm
    addOurHostAndRealm(msg);
    //Host-IP-Address
    final Collection<InetAddress> local_addresses = conn.getLocalAddresses();
    for (final InetAddress ia : local_addresses) {
      msg.add(new AVP_Address(ProtocolConstants.DI_HOST_IP_ADDRESS, ia));
    }
    //Vendor-Id
    msg.add(new AVP_Unsigned32(ProtocolConstants.DI_VENDOR_ID, settings.vendorId()));
    //Product-Name
    msg.add(new AVP_UTF8String(ProtocolConstants.DI_PRODUCT_NAME, settings.productName()));
    //Origin-State-Id
    msg.add(new AVP_Unsigned32(ProtocolConstants.DI_ORIGIN_STATE_ID, node_state.stateId()));
    //Error-Message, Failed-AVP: not in success
    //Supported-Vendor-Id
    for (final Integer i : capabilities.supported_vendor) {
      msg.add(new AVP_Unsigned32(ProtocolConstants.DI_SUPPORTED_VENDOR_ID, i));
    }
    //Auth-Application-Id
    for (final Integer i : capabilities.auth_app) {
      msg.add(new AVP_Unsigned32(ProtocolConstants.DI_AUTH_APPLICATION_ID, i));
    }
    //Inband-Security-Id
    //  todo
    //Acct-Application-Id
    for (final Integer i : capabilities.acct_app) {
      msg.add(new AVP_Unsigned32(ProtocolConstants.DI_ACCT_APPLICATION_ID, i));
    }
    //Vendor-Specific-Application-Id
    for (final Capability.VendorApplication va : capabilities.auth_vendor) {
      msg.add(new AVP_VendorSpecificApplicationId(va.vendor_id, va.application_id, 0));
    }
    for (final Capability.VendorApplication va : capabilities.acct_vendor) {
      msg.add(new AVP_VendorSpecificApplicationId(va.vendor_id, 0, va.application_id));
    }
    //Firmware-Revision
    if (settings.firmwareRevision() != 0) {
      msg.add(new AVP_Unsigned32(ProtocolConstants.DI_FIRMWARE_REVISION, settings.firmwareRevision()));
    }
  }

  private boolean handleDWR(final Message msg, final Connection conn) {
    if (log.isInfoEnabled()) {
      log.info("DWR received from " + conn.host_id);
    }
    conn.timers.markDWR();
    final Message dwa = new Message();
    dwa.prepareResponse(msg);
    dwa.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_SUCCESS));
    addOurHostAndRealm(dwa);
    dwa.add(new AVP_Unsigned32(ProtocolConstants.DI_ORIGIN_STATE_ID, node_state.stateId()));
    Utils.setMandatory_RFC3588(dwa);

    sendMessage(dwa, conn);
    return true;
  }

  private boolean handleDWA(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("DWA received from " + conn.host_id);
    }
    conn.timers.markDWA();
    return true;
  }

  private boolean handleDPR(final Message msg, final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("DPR received from " + conn.host_id);
    }
    final Message dpa = new Message();
    dpa.prepareResponse(msg);
    dpa.add(new AVP_Unsigned32(ProtocolConstants.DI_RESULT_CODE, ProtocolConstants.DIAMETER_RESULT_SUCCESS));
    addOurHostAndRealm(dpa);
    Utils.setMandatory_RFC3588(dpa);

    sendMessage(dpa, conn);
    return false;
  }

  private boolean handleDPA(final Message msg, final Connection conn) {
    if (conn.state == Connection.State.closing) {
      if (log.isInfoEnabled()) {
        log.info("Got a DPA from " + conn.host_id);
      }
    } else {
      log.warn("Got a DPA. This is not expected (state=" + conn.state + ")");
    }
    return false; //in any case close the connection
  }

  private boolean handleUnknownRequest(final Message msg, final Connection conn) {
    if (log.isInfoEnabled()) {
      log.info("Unknown request received from " + conn.host_id);
    }
    rejectRequest(msg, conn, ProtocolConstants.DIAMETER_RESULT_UNABLE_TO_DELIVER);
    return true;
  }

  private void sendDWR(final Connection conn) {
    if (log.isTraceEnabled()) {
      log.trace("Sending DWR to " + conn.host_id);
    }
    final Message dwr = new Message();
    dwr.hdr.setRequest(true);
    dwr.hdr.command_code = ProtocolConstants.DIAMETER_COMMAND_DEVICE_WATCHDOG;
    dwr.hdr.application_id = ProtocolConstants.DIAMETER_APPLICATION_COMMON;
    dwr.hdr.hop_by_hop_identifier = conn.nextHopByHopIdentifier();
    dwr.hdr.end_to_end_identifier = node_state.nextEndToEndIdentifier();
    addOurHostAndRealm(dwr);
    dwr.add(new AVP_Unsigned32(ProtocolConstants.DI_ORIGIN_STATE_ID, node_state.stateId()));
    Utils.setMandatory_RFC3588(dwr);

    sendMessage(dwr, conn);

    conn.timers.markDWR_out();
  }

  private void sendDPR(final Connection conn, final int why) {
    if (log.isTraceEnabled()) {
      log.trace("Sending DPR to " + conn.host_id);
    }
    final Message dpr = new Message();
    dpr.hdr.setRequest(true);
    dpr.hdr.command_code = ProtocolConstants.DIAMETER_COMMAND_DISCONNECT_PEER;
    dpr.hdr.application_id = ProtocolConstants.DIAMETER_APPLICATION_COMMON;
    dpr.hdr.hop_by_hop_identifier = conn.nextHopByHopIdentifier();
    dpr.hdr.end_to_end_identifier = node_state.nextEndToEndIdentifier();
    addOurHostAndRealm(dpr);
    dpr.add(new AVP_Unsigned32(ProtocolConstants.DI_DISCONNECT_CAUSE, why));
    Utils.setMandatory_RFC3588(dpr);

    sendMessage(dpr, conn);
  }

  boolean anyOpenConnections(final NodeImplementation node_impl) {
    synchronized (map_key_conn) {
      for (final Map.Entry<ConnectionKey, Connection> e : map_key_conn.entrySet()) {
        final Connection conn = e.getValue();
        if (conn.node_impl == node_impl) {
          return true;
        }
      }
    }
    return false;
  }

  void registerInboundConnection(final Connection conn) {
    synchronized (map_key_conn) {
      map_key_conn.put(conn.key, conn);
    }
  }

  void unregisterConnection(final Connection conn) {
    synchronized (map_key_conn) {
      map_key_conn.remove(conn.key);
    }
  }

  Object getLockObject() {
    return map_key_conn;
  }
}
