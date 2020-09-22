///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS com.konghq:unirest-java:3.6.00

import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;

public class jget {
    public static void main(String[] args) {
        String url = null;
        boolean getJson = false;
        for (int i=0; i<args.length;) {
            String arg = args[i++];
            switch (arg) {
                case "--json":
                    getJson = true;
                    break;
                default:
                    url = arg;
                    break;
            }
        }
        if (url == null) {
            System.out.println("Usage: jget [--json] <url>");
            System.exit(-1);
        }
        int status;
        GetRequest req = Unirest.get(url);
        if (getJson) {
            HttpResponse<JsonNode> response = req.asJson();
            System.out.println(response.getBody().toPrettyString());
            status = response.getStatus();
        } else {
            HttpResponse<String> response = req.asString();
            System.out.println(response.getBody());
            status = response.getStatus();
        }
        System.exit(status == 200 ? 0 : 1);
    }
}
