///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.jmdns:jmdns:3.5.7
//DEPS com.konghq:unirest-java:3.11.09
//DEPS org.slf4j:slf4j-nop:1.7.25
//DEPS https://github.com/quintesse/attocli/tree/main#:SNAPSHOT

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.codejive.attocli.Args;
import org.codejive.attocli.ArgsParser;
import org.codejive.attocli.Option;

import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;

public class shelly {
    private static class ListCmd implements Function<List<String>, Integer> {
        private static int timeout = 10;

        private static Map<String, JSONObject> result;

        private static class ShellyServiceListener implements ServiceListener {
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

        public static void handleShelly(ServiceInfo info) {
            if (info.getHostAddresses() != null && info.getHostAddresses().length > 0) {
                String url = "http://" + info.getHostAddresses()[0] + "/settings";
                Unirest.get(url).asJsonAsync(response -> {
                    int code = response.getStatus();
                    if (code == 200) {
                        JsonNode body = response.getBody();
                        if (!body.isArray()) {
                            try {
                                printShelly(info, body.getObject());
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            return;
                        }
                    }
                    System.out.println("ERROR: " + code + " - " + info.getName() + " - " + Arrays.toString(info.getHostAddresses()));
                });
            }
        }

        private static synchronized void printShelly(ServiceInfo info, JSONObject settings) {
            String type = get(settings, "device").getString("type");
            String mode = settings.optString("mode");
            String name = settings.optString("name");
            System.out.print(String.format("%s %s %s",
                info.getName(),
                Arrays.toString(info.getHostAddresses()),
                type));
            String[] typeparts = type.split("-", 2);
            switch (typeparts[0]) {
                case "SHSW":
                case "SHPLG":
                    System.out.print(String.format(" SWITCH %s", mode));
                    if (mode.isEmpty() || "relay".equals(mode)) {
                        JSONArray relays = settings.getJSONArray("relays");
                        if (relays.length() > 1 && name != null && !name.isEmpty()) {
                            System.out.print(String.format(" '%s'", name));
                        }
                        System.out.println();
                        for (int i=0; i<relays.length(); i++) {
                            JSONObject relay = relays.getJSONObject(i);
                            String rname = relay.optString("name");
                            System.out.println(String.format("   RELAY #%d %s '%s'",
                                i,
                                relay.getBoolean("ison") ? "ON" : "OFF",
                                rname != null && !rname.isEmpty() ? rname : name));
                        }
                    } else {
                        System.out.println(" ...");
                    }
                    break;
                case "SHCB":
                case "SHBLB":
                case "SHRGBW2":
                case "SHDM":
                    System.out.print(String.format(" LIGHT %s", mode));
                    JSONArray lights = settings.getJSONArray("lights");
                    if (lights.length() > 1 && name != null) {
                        System.out.print(String.format(" '%s'", name));
                    }
                    System.out.println();
                    for (int i=0; i<lights.length(); i++) {
                        JSONObject relay = lights.getJSONObject(i);
                        String rname = relay.optString("name");
                        System.out.println(String.format("   LIGHT #%d %s '%s'",
                            i,
                            relay.getBoolean("ison") ? "ON" : "OFF",
                            rname != null && !rname.isEmpty() ? rname : name));
                    }
                break;
                default:
                    System.out.println(" (Unknown hardware type)");
                    break;
            }
        }

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
    
            result = new ConcurrentHashMap<>();
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

    private static abstract class InfoCmd implements Function<List<String>, Integer> {
        protected abstract String url(String ip);

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
            String ip = res.params().get(0).value();
            String url = "http://" + ip + "/settings";
            Unirest.get(url).asJsonAsync(response -> {
                int code = response.getStatus();
                if (code == 200) {
                    JsonNode body = response.getBody();
                    System.out.println(body.toPrettyString());
                } else {
                    System.err.println("ERROR: " + code);
                }
        }).join();
            return 0;
        }

        protected abstract void help();
    }

    private static class SettingsCmd extends InfoCmd {
        @Override
        protected String url(String ip) {
            return "http://" + ip + "/settings";
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly settings <ip>");
        }
    }

    private static class StatusCmd extends InfoCmd {
        @Override
        protected String url(String ip) {
            return "http://" + ip + "/status";
        }

        @Override
        protected void help() {
            System.err.println("Usage: shelly status <ip>");
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
        if (!res.options().isEmpty()) {
            System.err.println("Unknown option " + res.options().get(0));
            System.exit(1);
        }
        String cmd = res.params().get(0).value();
        switch (cmd) {
            case "list":
                System.exit(new ListCmd().apply(res.rest()));
                break;
            case "settings":
                System.exit(new SettingsCmd().apply(res.rest()));
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
        System.err.println("Usage: shelly list|ip|info ...");
    }
}
