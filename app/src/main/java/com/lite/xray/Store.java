package com.lite.xray;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Servers + subscriptions persisted as JSON in SharedPreferences. */
final class Store {
    private Store() {}

    static final class Server {
        String id, name, link, group = "";
        /** ms; -1 = untested, -2 = failed, -3 = testing */
        volatile long tcp = -1, icmp = -1, real = -1;
    }

    static final class Sub {
        String id, name, url;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("store", Context.MODE_PRIVATE);
    }

    static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static List<Server> servers(Context c) {
        List<Server> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("servers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Server s = new Server();
                s.id = o.getString("id");
                s.name = o.optString("name", "server");
                s.link = o.getString("link");
                s.group = o.optString("group", "");
                l.add(s);
            }
        } catch (JSONException ignored) {
        }
        return l;
    }

    static void saveServers(Context c, List<Server> l) {
        try {
            JSONArray a = new JSONArray();
            for (Server s : l) {
                a.put(new JSONObject()
                        .put("id", s.id).put("name", s.name)
                        .put("link", s.link).put("group", s.group));
            }
            sp(c).edit().putString("servers", a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static List<Sub> subs(Context c) {
        List<Sub> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("subs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sub s = new Sub();
                s.id = o.getString("id");
                s.name = o.optString("name", "sub");
                s.url = o.getString("url");
                l.add(s);
            }
        } catch (JSONException ignored) {
        }
        return l;
    }

    static void saveSubs(Context c, List<Sub> l) {
        try {
            JSONArray a = new JSONArray();
            for (Sub s : l) {
                a.put(new JSONObject().put("id", s.id).put("name", s.name).put("url", s.url));
            }
            sp(c).edit().putString("subs", a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static String selected(Context c) {
        return sp(c).getString("selected", "");
    }

    static void setSelected(Context c, String id) {
        sp(c).edit().putString("selected", id).apply();
    }
}
