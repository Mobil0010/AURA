package dev.aura.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in LAN controller. Token is kept in URL fragment, never in an HTTP request URL. */
public final class RemoteServer implements AutoCloseable {
    public interface Endpoint {
        String status() throws Exception;

        void command(String command);
    }

    private final Endpoint endpoint;
    private final String token;
    private final java.util.concurrent.Executor main;
    private volatile boolean running;
    private ServerSocket server;
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    public RemoteServer(Endpoint endpoint, java.util.concurrent.Executor main) {
        this.endpoint = endpoint;
        this.main = main;
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        token = Hex.encode(b);
    }

    public void start() throws IOException {
        running = true;
        server = new ServerSocket();
        server.bind(new InetSocketAddress(0), 8);
        io.execute(
                () -> {
                    while (running && !server.isClosed())
                        try {
                            Socket socket = server.accept();
                            socket.setSoTimeout(2500);
                            handle(socket);
                        } catch (IOException ignored) {
                        }
                });
    }

    public String link() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || !n.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return linkForHost(a.getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return "Wi-Fi 연결 후 다시 열어 주세요";
    }

    String linkForHost(String host) {
        return "http://" + host + ":" + server.getLocalPort() + "/#" + token;
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String request = line(reader);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length != 3) return;
            String auth = "", origin = "";
            int length = 0;
            for (int i = 0; i < 32; i++) {
                String h = line(reader);
                if (h == null) return;
                if (h.isEmpty()) break;
                int pos = h.indexOf(':');
                if (pos < 0) continue;
                String k = h.substring(0, pos).toLowerCase(Locale.ROOT),
                        v = h.substring(pos + 1).trim();
                if (k.equals("authorization")) auth = v;
                if (k.equals("origin")) origin = v;
                if (k.equals("content-length")) length = Integer.parseInt(v);
                if (i == 31) return;
            }
            if (!running) return;
            String path = parts[1];
            if (parts[0].equals("GET") && path.equals("/")) {
                reply(socket, 200, "text/html; charset=utf-8", PAGE);
                return;
            }
            if (!MessageDigest.isEqual(
                    auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                reply(socket, 403, "text/plain", "인증이 필요합니다");
                return;
            }
            // No cross-origin access, no CORS. Authenticated commands are POST-only.
            if (!origin.isEmpty()
                    && !origin.equals(
                            "http://"
                                    + socket.getLocalAddress().getHostAddress()
                                    + ":"
                                    + server.getLocalPort())) {
                reply(socket, 403, "text/plain", "잘못된 출처");
                return;
            }
            if (parts[0].equals("GET") && path.equals("/status")) {
                reply(socket, 200, "application/json", onMain(endpoint::status));
                return;
            }
            if (!parts[0].equals("POST") || !path.startsWith("/command/") || length > 0) {
                reply(socket, 400, "text/plain", "잘못된 요청");
                return;
            }
            String command = path.substring(9);
            if (command.matches("seek/[0-9]{1,9}")) {
                long position = Long.parseLong(command.substring(5));
                if (position > 604800000) {
                    reply(socket, 400, "text/plain", "invalid seek");
                    return;
                }
                command = "seek:" + position;
            }
            if (!command.startsWith("seek:")
                    && !List.of("play", "pause", "next", "previous").contains(command)) {
                reply(socket, 404, "text/plain", "없는 명령");
                return;
            }
            String validatedCommand = command;
            onMain(
                    () -> {
                        if (!running) return "";
                        endpoint.command(validatedCommand);
                        return "";
                    });
            reply(socket, 200, "application/json", "{}");
        } catch (Exception ignored) {
        }
    }

    private String line(Reader r) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') return b.toString().replace("\r", "");
            if (b.length() >= 4096) throw new IOException("request too long");
            b.append((char) c);
        }
        return null;
    }

    private String onMain(Callable<String> c) throws Exception {
        FutureTask<String> f = new FutureTask<>(c);
        main.execute(f);
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(false);
            throw e;
        }
    }

    private void reply(Socket s, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(
                ("HTTP/1.1 "
                                + code
                                + " Response\r\nContent-Type: "
                                + type
                                + "\r\nContent-Length: "
                                + bytes.length
                                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        io.shutdownNow();
    }

    private static final String PAGE =
            """
        <!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AURA Remote</title><style>body{background:#11151e;color:#f2f4f8;font:16px system-ui;max-width:420px;margin:50px auto;padding:24px}small{color:#b8e6cc}button{background:#b8e6cc;color:#152a1d;border:0;border-radius:12px;padding:16px;margin:6px;font:inherit}h1{font-weight:500}#error{color:#f8bbab}</style><small>AURA · 같은 Wi-Fi 원격 제어</small><h1 id="title">연결 중…</h1><p id="artist"></p><p id="time"></p><input id="seek" type="range" min="0" max="1" value="0" aria-label="재생 위치" style="width:100%;accent-color:#b8e6cc"><button data-c="previous">이전</button><button data-c="play">재생</button><button data-c="pause">일시정지</button><button data-c="next">다음</button><p id="error"></p><script>const token=location.hash.slice(1)||sessionStorage.getItem('auraToken')||'';sessionStorage.setItem('auraToken',token);let dragging=false;const seek=document.getElementById('seek');seek.onpointerdown=()=>dragging=true;seek.onchange=async()=>{try{await fetch('/command/seek/'+Math.round(Number(seek.value)),{method:'POST',headers});}finally{dragging=false;}};history.replaceState(null,'',location.pathname);const headers={Authorization:'Bearer '+token};async function status(){try{const r=await fetch('/status',{headers});if(!r.ok)throw Error('연결 또는 인증을 확인해 주세요');const s=await r.json();document.getElementById('title').textContent=s.title;document.getElementById('artist').textContent=s.artist;document.getElementById('time').textContent=(s.playing?'재생 중 · ':'일시정지 · ')+Math.floor(s.position/1000)+'초';if(!dragging){seek.max=Math.max(1,s.duration);seek.value=s.position;}document.getElementById('error').textContent=s.error||'';}catch(e){document.getElementById('error').textContent=e.message;}}document.querySelectorAll('button').forEach(b=>b.onclick=async()=>{try{const r=await fetch('/command/'+b.dataset.c,{method:'POST',headers});if(!r.ok)throw Error('명령 전송 실패');status();}catch(e){document.getElementById('error').textContent=e.message;}});status();setInterval(status,1500);</script></html>
        """;
}
