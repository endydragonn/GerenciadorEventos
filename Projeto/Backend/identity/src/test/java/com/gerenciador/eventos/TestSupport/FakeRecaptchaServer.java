package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * Google reCAPTCHA falso (JDK {@code HttpServer}, uma instância por JVM de teste): lê o formulário {@code secret}/{@code response} e
 * responde pelo valor de {@code response}: {@code alto} = success/score 0.9/action {@code login}; {@code baixo} = score 0.3;
 * {@code acao-errada} = action {@code outra}; {@code falha} = success false; {@code http500} = 500; {@code vazio} = corpo
 * {@code null}; {@code lento} = dorme além do timeout de leitura (5 s). Guarda o último {@code secret} recebido: o teste confere
 * que o identity manda a chave dele (e que a real, vinda do {@code .env}, nunca sai da máquina: aqui é só loopback).
 */
public final class FakeRecaptchaServer {

    private static volatile FakeRecaptchaServer instancia;

    private final HttpServer server;
    private final AtomicReference<String> ultimoSecret = new AtomicReference<>();

    private FakeRecaptchaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/recaptcha/api/siteverify", exchange -> {
            Map<String, String> form = new HashMap<>();
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String par : corpo.split("&")) {
                String[] kv = par.split("=", 2);
                if (kv.length == 2) {
                    form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
            ultimoSecret.set(form.get("secret"));
            String resposta = form.getOrDefault("response", "");
            int status = 200;
            String json;
            switch (resposta) {
                case "alto" -> json = "{\"success\":true,\"score\":0.9,\"action\":\"login\"}";
                case "baixo" -> json = "{\"success\":true,\"score\":0.3,\"action\":\"login\"}";
                case "acao-errada" -> json = "{\"success\":true,\"score\":0.9,\"action\":\"outra\"}";
                case "falha" -> json = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
                case "vazio" -> json = "null";
                case "http500" -> {
                    status = 500;
                    json = "{}";
                }
                case "lento" -> {
                    try {
                        Thread.sleep(6500);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    json = "{\"success\":true,\"score\":0.9,\"action\":\"login\"}";
                }
                default -> json = "{\"success\":false}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ex) {
                // o cliente desistiu (timeout do caso "lento")
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-recaptcha");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public static synchronized FakeRecaptchaServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeRecaptchaServer();
        }
        return instancia;
    }

    public static FakeRecaptchaServer get() {
        return instancia;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/recaptcha/api/siteverify";
    }

    public String ultimoSecret() {
        return ultimoSecret.get();
    }
}
