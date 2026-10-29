package dev.aura.player;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;

public final class Dlna {
    public record Device(String name, String control, String service) {}

    public static List<Device> discover() throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(800);
            String query =
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
            byte[] bytes = query.getBytes(StandardCharsets.US_ASCII);
            socket.send(
                    new DatagramPacket(
                            bytes, bytes.length, InetAddress.getByName("239.255.255.250"), 1900));
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                try {
                    byte[] packet = new byte[16384];
                    DatagramPacket received = new DatagramPacket(packet, packet.length);
                    socket.receive(received);
                    String response =
                            new String(packet, 0, received.getLength(), StandardCharsets.UTF_8);
                    for (String line : response.split("\r?\n"))
                        if (line.toLowerCase(Locale.ROOT).startsWith("location:"))
                            urls.add(line.substring(9).trim());
                } catch (SocketTimeoutException ignored) {
                }
            }
        }
        List<Device> devices = new ArrayList<>();
        for (String location : urls)
            try {
                URI base = new URI(location);
                if (!local(base)) continue;
                Document doc = xml(request(location, null, null));
                String name = tag(doc.getDocumentElement(), "friendlyName");
                NodeList services = doc.getElementsByTagName("service");
                for (int i = 0; i < services.getLength(); i++) {
                    Element service = (Element) services.item(i);
                    String type = tag(service, "serviceType");
                    if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
                        String control = base.resolve(tag(service, "controlURL")).toString();
                        if (local(new URI(control))) devices.add(new Device(name, control, type));
                    }
                }
            } catch (Exception ignored) {
            }
        return devices;
    }

    private static boolean local(URI uri) throws Exception {
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null)
            return false;
        InetAddress a = InetAddress.getByName(uri.getHost());
        return a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    public static String command(Device d, String action, Map<String, String> args)
            throws Exception {
        StringBuilder body =
                new StringBuilder(
                        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
                                + action
                                + " xmlns:u=\""
                                + d.service()
                                + "\"><InstanceID>0</InstanceID>");
        args.forEach(
                (k, v) ->
                        body.append('<')
                                .append(k)
                                .append('>')
                                .append(escape(v))
                                .append("</")
                                .append(k)
                                .append('>'));
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        return request(d.control(), d.service() + "#" + action, body.toString());
    }

    public static void load(Device d, String url) throws Exception {
        load(d, url, "audio/wav", "AURA");
    }

    public static void load(Device d, String url, String mime, String title) throws Exception {
        String metadata =
                "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>"
                        + escape(title)
                        + "</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"http-get:*:"
                        + escape(mime)
                        + ":*\">"
                        + escape(url)
                        + "</res></item></DIDL-Lite>";
        command(d, "SetAVTransportURI", Map.of("CurrentURI", url, "CurrentURIMetaData", metadata));
        command(d, "Play", Map.of("Speed", "1"));
    }

    public static Document xml(String text) throws Exception {
        if (java.util.regex.Pattern.compile(
                        "<!\\s*(DOCTYPE|ENTITY)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .find()) throw new IOException("XML DTD/entity declarations are not allowed");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String tag(Element element, String name) {
        NodeList found = element.getElementsByTagName(name);
        return found.getLength() == 0 ? "" : found.item(0).getTextContent();
    }

    public static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String request(String url, String action, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setInstanceFollowRedirects(false);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            c.setRequestProperty("SOAPACTION", "\"" + action + "\"");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            if (c.getResponseCode() != 200) throw new IOException("DLNA 응답 " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() > 256000) throw new IOException("DLNA response too large");
                    out.write(b, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
