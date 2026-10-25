package com.lite.xray;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Turns a share link (vless/vmess/trojan/ss) or a raw Xray JSON into a full Xray config. */
final class ConfigBuilder {
    static final int SOCKS_PORT = 10808;

    private ConfigBuilder() {}

    static String build(String input) throws Exception {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) throw new Exception("empty config");

        JSONObject root;
        if (s.startsWith("{")) {
            root = new JSONObject(s);
        } else {
            s = s.split("\\r?\\n")[0].trim();
            root = new JSONObject();
            root.put("log", new JSONObject().put("loglevel", "warning"));
            root.put("dns", new JSONObject().put("servers", new JSONArray().put("1.1.1.1").put("8.8.8.8")));
            JSONArray outs = new JSONArray();
            outs.put(parseLink(s));
            outs.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
            root.put("outbounds", outs);
        }
        root.put("inbounds", new JSONArray().put(socksInbound()));
        return root.toString(2);
    }

    private static JSONObject socksInbound() throws Exception {
        return new JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("udp", true).put("auth", "noauth"))
                .put("sniffing", new JSONObject()
                        .put("enabled", true)
                        .put("destOverride", new JSONArray().put("http").put("tls")));
    }

    private static JSONObject parseLink(String s) throws Exception {
        if (s.startsWith("vless://")) return vless(s);
        if (s.startsWith("vmess://")) return vmess(s);
        if (s.startsWith("trojan://")) return trojan(s);
        if (s.startsWith("ss://")) return shadowsocks(s);
        throw new Exception("unsupported link");
    }

    // ---------- protocols ----------

    private static JSONObject vless(String s) throws Exception {
        Uri u = Uri.parse(s);
        Map<String, String> p = params(u);
        String host = u.getHost();
        JSONObject user = new JSONObject()
                .put("id", u.getUserInfo())
                .put("encryption", p.getOrDefault("encryption", "none"));
        String flow = p.get("flow");
        if (flow != null && !flow.isEmpty()) user.put("flow", flow);
        JSONObject settings = new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                .put("address", host)
                .put("port", u.getPort())
                .put("users", new JSONArray().put(user))));
        return outbound("vless", settings).put("streamSettings", stream(p, host));
    }

    private static JSONObject vmess(String s) throws Exception {
        JSONObject j = new JSONObject(b64(s.substring("vmess://".length()).trim()));
        String host = j.getString("add");
        Map<String, String> p = new HashMap<>();
        p.put("type", j.optString("net", "tcp"));
        p.put("headerType", j.optString("type", "none"));
        p.put("host", j.optString("host", ""));
        p.put("path", j.optString("path", "/"));
        p.put("sni", j.optString("sni", ""));
        p.put("fp", j.optString("fp", ""));
        p.put("alpn", j.optString("alpn", ""));
        p.put("security", "tls".equals(j.optString("tls")) ? "tls" : "none");

        JSONObject user = new JSONObject()
                .put("id", j.getString("id"))
                .put("alterId", j.optInt("aid", 0))
                .put("security", j.optString("scy", "auto"));
        JSONObject settings = new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                .put("address", host)
                .put("port", j.optInt("port", 443))
                .put("users", new JSONArray().put(user))));
        return outbound("vmess", settings).put("streamSettings", stream(p, host));
    }

    private static JSONObject trojan(String s) throws Exception {
        Uri u = Uri.parse(s);
        Map<String, String> p = params(u);
        if (!p.containsKey("security")) p.put("security", "tls");
        String host = u.getHost();
        JSONObject settings = new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                .put("address", host)
                .put("port", u.getPort())
                .put("password", u.getUserInfo())));
        return outbound("trojan", settings).put("streamSettings", stream(p, host));
    }

    private static JSONObject shadowsocks(String s) throws Exception {
        String body = s.substring("ss://".length());
        int h = body.indexOf('#');
        if (h >= 0) body = body.substring(0, h);
        int q = body.indexOf('?');
        if (q >= 0) body = body.substring(0, q);
        while (body.endsWith("/")) body = body.substring(0, body.length() - 1);

        String cred, hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            String ui = body.substring(0, at);
            hostPort = body.substring(at + 1);
            String dec = tryB64(ui);
            cred = (dec != null && dec.contains(":")) ? dec : Uri.decode(ui);
        } else {
            String dec = b64(body);
            int a = dec.lastIndexOf('@');
            cred = dec.substring(0, a);
            hostPort = dec.substring(a + 1);
        }
        int c = cred.indexOf(':');
        int pc = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, pc).replace("[", "").replace("]", "");
        int port = Integer.parseInt(hostPort.substring(pc + 1));

        JSONObject settings = new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                .put("address", host)
                .put("port", port)
                .put("method", cred.substring(0, c))
                .put("password", cred.substring(c + 1))));
        return outbound("shadowsocks", settings);
    }

    // ---------- helpers ----------

    private static JSONObject outbound(String proto, JSONObject settings) throws Exception {
        return new JSONObject().put("tag", "proxy").put("protocol", proto).put("settings", settings);
    }

    private static Map<String, String> params(Uri u) {
        Map<String, String> p = new HashMap<>();
        for (String n : u.getQueryParameterNames()) p.put(n, u.getQueryParameter(n));
        return p;
    }

    private static JSONObject stream(Map<String, String> p, String addr) throws Exception {
        String net = p.getOrDefault("type", "tcp");
        if (net.equals("splithttp")) net = "xhttp";
        String path = p.getOrDefault("path", "/");
        String host = p.getOrDefault("host", "");
        String sec = p.getOrDefault("security", "none");

        JSONObject ss = new JSONObject().put("network", net);
        switch (net) {
            case "ws": {
                JSONObject w = new JSONObject().put("path", path);
                if (!host.isEmpty()) w.put("headers", new JSONObject().put("Host", host));
                ss.put("wsSettings", w);
                break;
            }
            case "grpc": {
                String svc = p.containsKey("serviceName") ? p.get("serviceName")
                        : ("/".equals(path) ? "" : path);
                ss.put("grpcSettings", new JSONObject()
                        .put("serviceName", svc)
                        .put("multiMode", "multi".equals(p.get("mode"))));
                break;
            }
            case "httpupgrade": {
                JSONObject w = new JSONObject().put("path", path);
                if (!host.isEmpty()) w.put("host", host);
                ss.put("httpupgradeSettings", w);
                break;
            }
            case "xhttp": {
                JSONObject w = new JSONObject().put("path", path);
                if (!host.isEmpty()) w.put("host", host);
                if (p.containsKey("mode")) w.put("mode", p.get("mode"));
                ss.put("xhttpSettings", w);
                break;
            }
            case "h2":
            case "http": {
                ss.put("network", "http");
                JSONObject w = new JSONObject().put("path", path);
                if (!host.isEmpty()) w.put("host", new JSONArray().put(host));
                ss.put("httpSettings", w);
                break;
            }
            case "tcp": {
                if ("http".equals(p.get("headerType"))) {
                    JSONObject req = new JSONObject().put("path", new JSONArray().put(path));
                    if (!host.isEmpty()) {
                        req.put("headers", new JSONObject().put("Host", new JSONArray().put(host)));
                    }
                    ss.put("tcpSettings", new JSONObject().put("header",
                            new JSONObject().put("type", "http").put("request", req)));
                }
                break;
            }
            default:
                break;
        }

        String sni = nonEmpty(p.get("sni")) ? p.get("sni") : (host.isEmpty() ? addr : host);
        if (sec.equals("tls")) {
            ss.put("security", "tls");
            JSONObject t = new JSONObject().put("serverName", sni);
            if (nonEmpty(p.get("fp"))) t.put("fingerprint", p.get("fp"));
            if (nonEmpty(p.get("alpn"))) {
                JSONArray a = new JSONArray();
                for (String x : p.get("alpn").split(",")) a.put(x.trim());
                t.put("alpn", a);
            }
            if ("1".equals(p.get("allowInsecure"))) t.put("allowInsecure", true);
            ss.put("tlsSettings", t);
        } else if (sec.equals("reality")) {
            ss.put("security", "reality");
            ss.put("realitySettings", new JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", nonEmpty(p.get("fp")) ? p.get("fp") : "chrome")
                    .put("publicKey", p.getOrDefault("pbk", ""))
                    .put("shortId", p.getOrDefault("sid", ""))
                    .put("spiderX", p.getOrDefault("spx", "")));
        }
        return ss;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String b64(String s) {
        s = s.replace('-', '+').replace('_', '/').trim();
        while (s.length() % 4 != 0) s += "=";
        return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static String tryB64(String s) {
        try {
            return b64(s);
        } catch (Exception e) {
            return null;
        }
    }
}
