package com.local.arwserver;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Vector;

/**
 * SSDP 应答器：让相机在网络上宣告自己是一台 UPnP MediaServer。
 * 客户端（PlayMemories / Windows / 电视）发 M-SEARCH，我们回 200 OK + LOCATION。
 */
public class UpnpServer extends Thread {

    public static final String UUID = "uuid:2f4f4b10-9d1e-4a60-b000-0a6000000600";
    private static final String SERVER_ST = "urn:schemas-upnp-org:device:MediaServer:1";
    private static final String SERVICE_ST = "urn:schemas-upnp-org:service:ContentDirectory:1";
    private static final int SSDP_PORT = 1900;
    private static final InetAddress SSDP_ADDR;

    static {
        InetAddress a = null;
        try {
            a = InetAddress.getByName("239.255.255.250");
        } catch (Throwable ignored) {
        }
        SSDP_ADDR = a;
    }

    private volatile boolean done = false;
    private MulticastSocket msock;

    /** 枚举本机非回环 IPv4 地址（用于 LOCATION 和 NOTIFY） */
    public static Vector<String> localIps() {
        Vector<String> v = new Vector<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                            String s = a.getHostAddress();
                            if (!v.contains(s)) v.add(s);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    public void run() {
        try {
            msock = new MulticastSocket(SSDP_PORT);
            msock.setSoTimeout(1000);
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (ni.isUp() && !ni.isLoopback()) {
                        msock.joinGroup(new java.net.InetSocketAddress(SSDP_ADDR, SSDP_PORT), ni);
                    }
                } catch (Throwable ignored) {
                }
            }
            Log.i("ARWSRV", "ssdp up on :1900");
            sendAlive();
            long lastAlive = System.currentTimeMillis();

            byte[] buf = new byte[2048];
            while (!done) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    msock.receive(p);
                } catch (java.io.InterruptedIOException t) {
                    // 每 10 分钟重发一次 alive
                    if (System.currentTimeMillis() - lastAlive > 600000) {
                        sendAlive();
                        lastAlive = System.currentTimeMillis();
                    }
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (msg.startsWith("M-SEARCH")) {
                    handleSearch(p);
                }
            }
        } catch (Throwable t) {
            Log.w("ARWSRV", "ssdp error: " + t);
        }
        Log.i("ARWSRV", "ssdp exit");
    }

    public void shutdown() {
        done = true;
        try {
            sendByebye();
        } catch (Throwable ignored) {
        }
        try {
            if (msock != null) msock.close();
        } catch (Throwable ignored) {
        }
    }

    private void handleSearch(DatagramPacket p) {
        String st = findHeader(msgOf(p), "ST");
        if (st == null) return;
        st = st.trim();
        InetAddress to = p.getAddress();
        int port = p.getPort();

        if ("ssdp:all".equals(st)) {
            respond(to, port, "upnp:rootdevice", "upnp:rootdevice");
            respond(to, port, UUID, UUID);
            respond(to, port, SERVER_ST, SERVER_ST);
            respond(to, port, SERVICE_ST, SERVICE_ST);
        } else if ("upnp:rootdevice".equals(st)) {
            respond(to, port, "upnp:rootdevice", "upnp:rootdevice");
        } else if (UUID.equals(st)) {
            respond(to, port, UUID, UUID);
        } else if (SERVER_ST.equals(st)) {
            respond(to, port, SERVER_ST, SERVER_ST);
        } else if (SERVICE_ST.equals(st)) {
            respond(to, port, SERVICE_ST, SERVICE_ST);
        }
    }

    private static String msgOf(DatagramPacket p) {
        return new String(p.getData(), 0, p.getLength());
    }

    private static String findHeader(String msg, String name) {
        String[] lines = msg.split("\r\n");
        for (int i = 0; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(c + 1);
            }
        }
        return null;
    }

    private void respond(InetAddress to, int port, String st, String usnTail) {
        Vector<String> ips = localIps();
        for (int i = 0; i < ips.size(); i++) {
            String ip = ips.get(i);
            String body = "HTTP/1.1 200 OK\r\n"
                    + "CACHE-CONTROL: max-age=1800\r\n"
                    + "LOCATION: http://" + ip + ":8080/desc.xml\r\n"
                    + "SERVER: Linux/2.6 UPnP/1.0 ArwServer/3.0\r\n"
                    + "ST: " + st + "\r\n"
                    + "USN: " + UUID + "::" + usnTail + "\r\n"
                    + "CONTENT-LENGTH: 0\r\n\r\n";
            send(body, to, port);
        }
    }

    private void sendAlive() {
        Vector<String> ips = localIps();
        for (int i = 0; i < ips.size(); i++) {
            String ip = ips.get(i);
            String loc = "http://" + ip + ":8080/desc.xml";
            notify(ips.get(i), "upnp:rootdevice", "upnp:rootdevice", loc);
            notify(ips.get(i), UUID, UUID, loc);
            notify(ips.get(i), SERVER_ST, SERVER_ST, loc);
            notify(ips.get(i), SERVICE_ST, SERVICE_ST, loc);
        }
    }

    private void sendByebye() {
        notify(null, "upnp:rootdevice", "upnp:rootdevice", null);
        notify(null, UUID, UUID, null);
    }

    private void notify(String ip, String nt, String usnTail, String loc) {
        StringBuffer sb = new StringBuffer();
        sb.append("NOTIFY * HTTP/1.1\r\n");
        sb.append("NT: ").append(nt).append("\r\n");
        sb.append("USN: ").append(UUID).append("::").append(usnTail).append("\r\n");
        if (loc != null) {
            sb.append("NTS: ssdp:alive\r\n");
            sb.append("LOCATION: ").append(loc).append("\r\n");
            sb.append("CACHE-CONTROL: max-age=1800\r\n");
        } else {
            sb.append("NTS: ssdp:byebye\r\n");
        }
        sb.append("SERVER: Linux/2.6 UPnP/1.0 ArwServer/3.0\r\n");
        sb.append("CONTENT-LENGTH: 0\r\n\r\n");
        try {
            InetAddress to = SSDP_ADDR;
            int port = SSDP_PORT;
            DatagramPacket p = new DatagramPacket(sb.toString().getBytes("UTF-8"),
                    sb.length(), to, port);
            if (msock != null && !msock.isClosed()) {
                msock.send(p);
            } else {
                DatagramSocket s = new DatagramSocket();
                s.send(p);
                s.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private void send(String body, InetAddress to, int port) {
        try {
            byte[] b = body.getBytes("UTF-8");
            DatagramPacket p = new DatagramPacket(b, b.length, to, port);
            if (msock != null && !msock.isClosed()) {
                msock.send(p);
            } else {
                DatagramSocket s = new DatagramSocket();
                s.send(p);
                s.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
