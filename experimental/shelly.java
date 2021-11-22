///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.jmdns:jmdns:3.5.7
//DEPS com.konghq:unirest-java:3.11.09
//DEPS https://github.com/quintesse/attocli/tree/main#:SNAPSHOT

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.codejive.attocli.Args;
import org.codejive.attocli.ArgsParser;
import org.codejive.attocli.Option;

import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

public class shelly {
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
        System.err.println(info.getKey() + " " + Arrays.toString(info.getHostAddresses()));
        if (info.getHostAddresses() != null && info.getHostAddresses().length > 0) {
            String url = "http://" + info.getHostAddresses()[0] + "/status";
            Unirest.get(url).asJsonAsync(response -> {
                int code = response.getStatus();
                JsonNode body = response.getBody();
                if (!body.isArray()) {
                    result.put(info.getKey(), body.getObject());
                }
            });
        }
    }

    public static void main(String... args) throws InterruptedException {
        Args res = ArgsParser.create().parse(args);
        for (Option opt : res.options()) {
            switch (opt.name()) {
                case "-t":
                case "--timeout":
                    timeout = Integer.parseInt(opt.values().get(0));
                    break;
                case "-h":
                case "--help":
                    System.err.println("Usage: shelly [-t|--timeout <seconds>]");
                    System.exit(1);
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
        } catch (UnknownHostException e) {
            System.err.println(e.getMessage());
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
        Unirest.shutDown();

        JSONObject obj = (JSONObject)JSONObject.wrap(result);
        System.out.println(obj.toString(2));
    }
}

