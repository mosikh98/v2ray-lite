package com.lite.xray;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Turns a share link (vless/vmess/trojan/ss) or a raw Xray JSON into a full Xray config. */
final class ConfigBuilder {
    static final int SOCKS_PORT = 10808;

    private ConfigBuilder() {}


    static boolean isIp(String s) {
        return s.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || s.contains(":");
    }

    /**
     * Plain Xray on Android has no working system resolver, so the server domain is resolved here
     * (Android resolver) and written into the config as an IP. SNI / Host keep the original domain.
     * Must NOT be called on the main thread.
     */
    static String resolveServers(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray outs = root.optJSONArray("outbounds");
        if (outs == null) return json;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.getJSONObject(i);
            JSONObject st = o.optJSONObject("settings");
            if (st == null) continue;
            JSONObject target = null;
            if (st.has("address") && st.has("port")) {
                target = st;
            } else {
                JSONArray a = st.optJSONArray("vnext");
                if (a == null) a = st.optJSONArray("servers");
                if (a != null && a.length() > 0) target = a.getJSONObject(0);
            }
            if (target == null) continue;
            String host = target.optString("address", "");
            if (host.isEmpty() || isIp(host)) continue;

            InetAddress[] all = InetAddress.getAllByName(host);
            String ip = null;
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) {
                    ip = a.getHostAddress();
                    break;
                }
            }
            if (ip == null && all.length > 0) ip = all[0].getHostAddress();
            if (ip == null) throw new Exception("cannot resolve " + host);
            target.put("address", ip);

            JSONObject ss = o.optJSONObject("streamSettings");
            if (ss != null) {
                for (String k : new String[]{"tlsSettings", "realitySettings"}) {
                    JSONObject t = ss.optJSONObject(k);
                    if (t != null && t.optString("serverName", "").isEmpty()) t.put("serverName", host);
                }
            }
        }
        return root.toString(2);
    }

    static boolean isLink(String s) {
        return s.startsWith("vless://") || s.startsWith("vmess://")
                || s.startsWith("trojan://") || s.startsWith("ss://");
    }

    static String build(String input) throws Exception {
        return build(input, SOCKS_PORT, new String[]{"8.8.8.8", "8.8.4.4"}, true);
    }

    static String build(String input, int port, String[] dns, boolean sniff) throws Exception {
        return build(input, port, dns, sniff, null);
    }

    static String build(String input, int port, String[] dns, boolean sniff, Settings.Frag frag)
            throws Exception {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) throw new Exception("empty config");

        JSONObject root;
        if (s.startsWith("{")) {
            root = new JSONObject(s);
        } else {
            s = s.split("\\r?\\n")[0].trim();
            root = new JSONObject();
            root.put("log", new JSONObject().put("loglevel", "warning").put("access", "none"));
            JSONArray ds = new JSONArray();
            for (String d : dns) ds.put(d);
            root.put("dns", new JSONObject().put("servers", ds));
            JSONArray outs = new JSONArray();
            outs.put(parseLink(s));
            outs.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
            root.put("outbounds", outs);
        }
        applyFragment(root, frag);
        root.put("inbounds", new JSONArray().put(socksInbound(port, sniff)));
        return root.toString(2);
    }

    /**
     * Fragment = a "freedom" outbound with fragment settings used as dialerProxy of the proxy outbound.
     * Fragment-in-fragment = two such outbounds chained: proxy -> frag2 (outer) -> frag1 (inner).
     */
    private static void applyFragment(JSONObject root, Settings.Frag f) throws Exception {
        if (f == null || f.mode == 0) return;
        JSONArray outs = root.optJSONArray("outbounds");
        if (outs == null || outs.length() == 0) return;
        JSONObject proxy = null;
        for (int i = 0; i < outs.length(); i++) {
            if ("proxy".equals(outs.getJSONObject(i).optString("tag"))) proxy = outs.getJSONObject(i);
        }
        if (proxy == null) proxy = outs.getJSONObject(0);

        JSONObject ss = proxy.optJSONObject("streamSettings");
        if (ss == null) ss = new JSONObject();
        JSONObject sock = ss.optJSONObject("sockopt");
        if (sock == null) sock = new JSONObject();
        sock.put("dialerProxy", f.mode == 2 ? "frag2" : "frag1");
        ss.put("sockopt", sock);
        proxy.put("streamSettings", ss);

        if (f.mode == 2) outs.put(fragOutbound("frag2", f.l2, "frag1"));
        outs.put(fragOutbound("frag1", f.l1, null));
    }

    private static JSONObject fragOutbound(String tag, String[] v, String dialer) throws Exception {
        JSONObject o = new JSONObject()
                .put("tag", tag)
                .put("protocol", "freedom")
                .put("settings", new JSONObject().put("fragment", new JSONObject()
                        .put("packets", v[0]).put("length", v[1]).put("interval", v[2])));
        if (dialer != null) {
            o.put("streamSettings", new JSONObject()
                    .put("sockopt", new JSONObject().put("dialerProxy", dialer)));
        }
        return o;
    }

    /** Display name of a link / JSON config. */
    static String name(String link) {
        try {
            String s = link.trim();
            if (s.startsWith("{")) return new JSONObject(s).optString("remarks", "JSON");
            if (s.startsWith("vmess://")) {
                String ps = new JSONObject(b64(s.substring(8).trim())).optString("ps", "");
                if (!ps.isEmpty()) return ps;
            } else {
                String f = Uri.parse(s).getFragment();
                if (f != null && !f.isEmpty()) return f;
            }
            String[] ep = endpoint(s);
            if (ep != null) return ep[0] + ":" + ep[1];
        } catch (Exception ignored) {
        }
        return "server";
    }

    /** {host, port} of the first proxy outbound, or null. */
    static String[] endpoint(String link) throws Exception {
        JSONArray outs = new JSONObject(build(link)).optJSONArray("outbounds");
        if (outs == null) return null;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject st = outs.getJSONObject(i).optJSONObject("settings");
            if (st == null) continue;
            if (st.has("address") && st.has("port")) {
                return new String[]{st.getString("address"), String.valueOf(st.getInt("port"))};
            }
            JSONArray a = st.optJSONArray("vnext");
            if (a == null) a = st.optJSONArray("servers");
            if (a != null && a.length() > 0) {
                JSONObject e = a.getJSONObject(0);
                return new String[]{e.getString("address"), String.valueOf(e.getInt("port"))};
            }
        }
        return null;
    }

    private static JSONObject socksInbound(int port, boolean sniff) throws Exception {
        JSONObject in = new JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", port)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("udp", true).put("auth", "noauth"));
        if (sniff) {
            in.put("sniffing", new JSONObject()
                    .put("enabled", true)
                    .put("destOverride", new JSONArray().put("http").put("tls")));
        }
        return in;
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
        // the server address is replaced by its IP before launch, so keep the domain as Host/SNI
        if (host.isEmpty() && !isIp(addr)) host = addr;
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
                String mode = p.get("mode");
                if (nonEmpty(mode)) w.put("mode", "packet".equals(mode) ? "packet-up" : mode);
                // share links carry the client-side tuning (padding, headers, xmux ...) as URL-encoded JSON;
                // it must match the server or the server answers 400
                String ex = p.get("extra");
                if (nonEmpty(ex)) {
                    try {
                        if (ex.startsWith("%")) ex = Uri.decode(ex);
                        w.put("extra", new JSONObject(ex));
                    } catch (Exception ignored) {
                    }
                }
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
                if ("http".equals(p.get("headerType")) && !"reality".equals(sec)) {
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
            JSONObject rs = new JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", nonEmpty(p.get("fp")) ? p.get("fp") : "chrome")
                    .put("publicKey", p.getOrDefault("pbk", ""))
                    .put("shortId", p.getOrDefault("sid", ""))
                    .put("spiderX", p.getOrDefault("spx", ""));
            if (nonEmpty(p.get("pqv"))) rs.put("mldsa65Verify", p.get("pqv"));
            ss.put("realitySettings", rs);
        }
        return ss;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    static String b64(String s) {
        s = s.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
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
