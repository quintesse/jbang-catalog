///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.jmdns:jmdns:3.5.7
//DEPS com.konghq:unirest-java:3.14.5
//DEPS org.slf4j:slf4j-nop:1.7.25
//DEPS https://github.com/quintesse/attocli/tree/main#:SNAPSHOT

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.codejive.attocli.Args;
import org.codejive.attocli.ArgsParser;
import org.codejive.attocli.Option;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;

public class shelly {
    private abstract static class BaseListCmd implements Function<List<String>, Integer> {
        private static int timeout = 10;

        private class ShellyServiceListener implements ServiceListener {
            @Override
            public void serviceAdded(ServiceEvent event) {
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                if (event.getInfo().getKey().startsWith("shelly")) {
                    handleShelly(event.getInfo());
                }
            }
        }

        public void handleShelly(ServiceInfo info) {
            if (info.getHostAddresses() != null && info.getHostAddresses().length > 0) {
                String gen = info.getPropertyString("gen");
                if (gen == null) {
                    requestInfoGen1(info);
                } else if ("2".equalsIgnoreCase(gen)) {
                    requestInfoGen2(info);
                } else {
                    System.out.println(String.format("%s %s Unsupported hardware generation: %s",
                        info.getName(),
                        Arrays.toString(info.getHostAddresses())));
                }
            }
        }

        public void requestInfoGen1(ServiceInfo info) {
            String url = "http://" + info.getHostAddresses()[0] + "/settings";
            request(url, obj -> handleShellyGen1Info(info, obj));
        }

        public void requestInfoGen2(ServiceInfo info) {
            String url = "http://" + info.getHostAddresses()[0] + "/rpc/Shelly.GetConfig";
            request(url, obj -> handleShellyGen2Info(info, obj));
        }

        public abstract void handleShellyGen1Info(ServiceInfo info, JSONObject settings);

        public abstract void handleShellyGen2Info(ServiceInfo info, JSONObject settings);

        @Override
        public Integer apply(List<String> args) {
            Args res = ArgsParser.create().needsValue("--timeout", "-t").parse(args);
            for (Option opt : res.options()) {
                switch (opt.name()) {
                    case "-t":
                    case "--timeout":
                        timeout = Integer.parseInt(opt.values().get(0));
                        break;
                    case "-h":
                    case "--help":
                        System.err.println("Usage: shelly list [-t|--timeout <seconds>]");
                        return 1;
                }
            }
    
            try (JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost())) {
                jmdns.addServiceListener("_http._tcp.local.", new ShellyServiceListener());
                // Wait for timeout or user interrupt
                if (timeout > 0) {
                    Thread.sleep(timeout * 1000);
                } else {
                    Thread.currentThread().join();
                }
            } catch (IOException e) {
                System.err.println(e.getMessage());
            } catch (InterruptedException e) {
                System.err.println(e.getMessage());
            }
            Unirest.shutDown();
            return 0;
        }
    }

    private static class ListCmd extends BaseListCmd {
        @Override
        public synchronized void handleShellyGen1Info(ServiceInfo info, JSONObject settings) {
            String type = get(settings, "device").getString("type");
            String mode = settings.optString("mode");
            String name = settings.optString("name");
            StringBuilder str = new StringBuilder();
            str.append(String.format("%s %s %s",
                info.getName(),
                Arrays.toString(info.getHostAddresses()),
                type));
            String[] typeparts = type.split("-", 2);
            switch (typeparts[0]) {
                case "SHSW":
                case "SHPLG":
                    str.append(String.format(" SWITCH %s", mode));
                    if (mode.isEmpty() || "relay".equals(mode)) {
                        JSONArray relays = settings.getJSONArray("relays");
                        if (relays.length() > 1 && name != null && !name.isEmpty()) {
                            str.append(String.format(" '%s'", name));
                        }
                        for (int i=0; i<relays.length(); i++) {
                            JSONObject relay = relays.getJSONObject(i);
                            String rname = relay.optString("name");
                            System.out.println(String.format("%s / RELAY #%d %s '%s'",
                                str.toString(),
                                i,
                                relay.getBoolean("ison") ? "ON" : "OFF",
                                rname != null && !rname.isEmpty() ? rname : name));
                        }
                    } else {
                        System.out.println(String.format("%s ...", str.toString()));
                    }
                    break;
                case "SHCB":
                case "SHBLB":
                case "SHRGBW2":
                case "SHDM":
                    str.append(String.format(" LIGHT %s", mode));
                    JSONArray lights = settings.getJSONArray("lights");
                    if (lights.length() > 1 && name != null) {
                        str.append(String.format(" '%s'", name));
                    }
                    for (int i=0; i<lights.length(); i++) {
                        JSONObject relay = lights.getJSONObject(i);
                        String rname = relay.optString("name");
                        System.out.println(String.format("%s / LIGHT #%d %s '%s'",
                            str.toString(),
                            i,
                            relay.getBoolean("ison") ? "ON" : "OFF",
                            rname != null && !rname.isEmpty() ? rname : name));
                    }
                    break;
                case "SHHT":
                    str.append(" SENSOR");
                    if (name != null && !name.isEmpty()) {
                        str.append(String.format(" '%s'", name));
                    }
                    System.out.println(str.toString());
                    break;
                default:
                    System.out.println(String.format("%s (Unknown hardware type)", str.toString()));
                    break;
            }
        }

        @Override
        public synchronized void handleShellyGen2Info(ServiceInfo info, JSONObject settings) {
            StringBuilder str = new StringBuilder();
            str.append(String.format("%s %s",
                info.getName(),
                Arrays.toString(info.getHostAddresses())));
            System.out.println(str.toString());
        }

        @Override
        public Integer apply(List<String> args) {
            return super.apply(args);
        }
    }

    private static abstract class InfoCmd implements Function<List<String>, Integer> {
        protected String ip;

        private int gen = -1;

        protected int gen() {
            if (gen == -1) {
                String url = "http://" + ip + "/shelly";
                request(url, obj -> {
                    if (obj.has("gen")) {
                        gen = obj.getInt("gen");
                    } else {
                        gen = 1;
                    }
                }).join();
            }
            return gen;
        }

        protected abstract String url();

        @Override
        public Integer apply(List<String> args) {
            Args res = ArgsParser.create().showHelp(this::help).parse(args);
            if (res.showHelp()) {
                return 1;
            }
            if (res.params().size() < 1) {
                System.err.println("Missing IP address");
                help();
            }
            if (res.optionsMap().containsKey("-g")) {
                parseInt(res.optionsMap().get("-g").values().get(0), num -> gen = num);
            }
            ip = res.params().get(0).value();
            request(url(), obj -> System.out.println(obj.toString(1))).join();
            return 0;
        }

        protected abstract void help();
    }

    private static class ConfigCmd extends InfoCmd {
        @Override
        protected String url() {
            switch (gen()) {
                case 1:
                    return "http://" + ip + "/settings";
                case 2:
                    return "http://" + ip + "/rpc/Shelly.GetConfig";
                default:
                    throw new NoSuchElementException();
            }
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly config <ip>");
        }
    }

    private static class StatusCmd extends InfoCmd {
        @Override
        protected String url() {
            switch (gen()) {
                case 1:
                    return "http://" + ip + "/status";
                case 2:
                    return "http://" + ip + "/rpc/Shelly.GetStatus";
                default:
                    throw new NoSuchElementException();
            }
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly status <ip>");
        }
    }

    private static class DumpCmd extends BaseListCmd {
        private JSONArray result = new JSONArray();

        @Override
        public synchronized void handleShellyGen1Info(ServiceInfo info, JSONObject settings) {
            String name = info.getName();
            System.err.println("Found Gen 1 device: " + name);
            result.put(settings);
        }

        @Override
        public synchronized void handleShellyGen2Info(ServiceInfo info, JSONObject settings) {
            String name = info.getName();
            System.err.println("Found Gen 2 device: " + name);
            result.put(settings);
        }

        @Override
        public Integer apply(List<String> args) {
            int res = super.apply(args);
            if (res == 0) {
                System.out.println(result.toString(1));
            }
            return res;
        }
    }

    private static abstract class ActionCmd implements Function<List<String>, Integer> {

        protected abstract String url(String ip, int index);

        @Override
        public Integer apply(List<String> args) {
            Args res = ArgsParser.create().showHelp(this::help).parse(args);
            if (res.showHelp()) {
                return 1;
            }
            if (res.params().size() < 1) {
                System.err.println("Missing IP address");
                help();
                return 1;
            }
            int idx = 0;
            String ip = res.params().get(idx++).value();
            int relay = 0;
            if (idx < res.params().size() && res.params().get(idx).value().matches("[\\d]+")) {
                relay = Integer.valueOf(res.params().get(idx++).value());
            }
            String url = url(ip, relay);
            if (idx < res.params().size()) {
                String turn = res.params().get(idx++).value();
                if (!Arrays.asList("on", "off", "toggle").contains(turn)) {
                    System.err.println("Invalid status: " + turn);
                    help();
                    return 1;
                }
                url += "?turn=" + turn;
                Unirest.post(url).asJsonAsync(response -> {
                    int code = response.getStatus();
                    if (code == 200) {
                        System.err.println("OK");
                    } else {
                        System.err.println("ERROR: " + code);
                    }
                }).join();
            } else {
                Unirest.get(url).asJsonAsync(response -> {
                    int code = response.getStatus();
                    if (code == 200) {
                        JsonNode body = response.getBody();
                        System.out.println(body.toPrettyString());
                    } else {
                        System.err.println("ERROR: " + code);
                    }
                }).join();
            }
        return 0;
        }

        protected abstract void help();
    }

    private static class SwitchCmd extends ActionCmd {

        @Override
        protected String url(String ip, int index) {
            return "http://" + ip + "/relay/" + index;
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly switch <ip> <index> [on|off|toggle]");
        }
    }

    private static class LightCmd extends ActionCmd {

        @Override
        protected String url(String ip, int index) {
            return "http://" + ip + "/light/" + index;
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly light <ip> <index> [on|off|toggle]");
        }
    }

    private static JSONObject get(JSONObject obj, String key) {
        String parent = "";
        int p = key.lastIndexOf('.');
        if (p >= 0) {
            parent = key.substring(0, p);
            key = key.substring(p + 1);
        }
        if (!parent.isEmpty()) {
            obj = get(obj, parent);
        }
        if (obj.has(key)) {
            return obj.getJSONObject(key);
        } else {
            return new JSONObject();
        }
    }

    public static void main(String... args) throws InterruptedException {
        Args res = ArgsParser.create().showHelp(shelly::help).commandMode().parse(args);
        if (res.showHelp()) {
            System.exit(1);
        }
        if (res.params().isEmpty()) {
            System.err.println("Missing command");
            help();
            System.exit(1);
        }
        if (!res.options().isEmpty()) {
            System.err.println("Unknown option " + res.options().get(0));
            System.exit(1);
        }
        String cmd = res.params().get(0).value();
        switch (cmd) {
            case "list":
                System.exit(new ListCmd().apply(res.rest()));
                break;
            case "config":
                System.exit(new ConfigCmd().apply(res.rest()));
                break;
            case "dump":
                System.exit(new DumpCmd().apply(res.rest()));
                break;
            case "status":
                System.exit(new StatusCmd().apply(res.rest()));
                break;
            case "switch":
                System.exit(new SwitchCmd().apply(res.rest()));
                break;
            case "light":
                System.exit(new LightCmd().apply(res.rest()));
                break;
            default:
                System.err.println("Unknown command " + cmd);
                System.exit(1);
        }
    }

    private static void help() {
        System.err.println("Usage: shelly list|config|dump|status|switch|light ...");
    }

    private static CompletableFuture<HttpResponse<JsonNode>> request(String url, Consumer<JSONObject> func) {
        return Unirest.get(url).asJsonAsync(response -> {
            int code = response.getStatus();
            if (code == 200) {
                JsonNode body = response.getBody();
                if (!body.isArray()) {
                    try {
                        func.accept(body.getObject());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    return;
                }
            }
            System.out.println("ERROR: " + code + " - " + url);
        });
    }

    private static void parseInt(String num, Consumer<Integer> func) {
        parseInt(num, func, e -> {});
    }

    private static void parseInt(String num, Consumer<Integer> func, Consumer<NumberFormatException> err) {
        try {
            func.accept(Integer.parseInt(num));
        } catch (NumberFormatException e) {
            err.accept(e);
        }
    }
}
