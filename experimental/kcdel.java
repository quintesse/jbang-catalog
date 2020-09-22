///usr/bin/env jbang "$0" "$@" ; exit $?

/*
 * Simplistic and probably very dangerous script to delete all users from a KeyCloak instance.
 * Pass it the server part of the URL of the KeyCloak server (so no paths and no trailing slash),
 * the name of a realm and the refresh token. The latter you can find in your browser's developer
 * console by looking at a request for "token". Extract the "refresh_token" you find in the
 * request and pass it to the script.
 */

//DEPS com.konghq:unirest-java:3.6.00

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;

public class kcdel {
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: kcdel <server_url> <realm> <refresh_token>");
            System.exit(-1);
        }
        String serverUrl = args[0];
        String realm = args[1];
        String refreshToken = args[2];
        String token = getAccessToken(serverUrl, refreshToken);

        String url = serverUrl + "/auth/admin/realms/" + realm + "/users";

        int count = 0;
        do {
            HttpResponse<String> countres = Unirest
                .get(url + "/count")
                .header("Authorization", "Bearer " + token)
                .asString();
            if (countres.getStatus() != 200) {
                System.err.println("Error counting users. Aborting. " + countres.getStatus());
                System.exit(4);
            }
            count = Integer.parseInt(countres.getBody());
            System.out.println("Number of users = " + count);

            HttpResponse<JsonNode> res = Unirest
                .get(url + "?max=300")
                .header("Authorization", "Bearer " + token)
                .asJson();
            if (res.getStatus() != 200) {
                System.err.println("Error retrieving list of users. Aborting. " + res.getStatus());
                System.exit(1);
            }
            JSONArray users = res.getBody().getArray();
            for (Object u : users) {
                JSONObject user = (JSONObject)u;
                String id = user.getString("id");
                String name = user.getString("username");

                boolean retry = true;
                while (retry) {
                    System.out.print(id + " = " + name + " = ");
                    HttpResponse delres = Unirest
                        .delete(url + "/" + id)
                        .header("Authorization", "Bearer " + token)
                        .asEmpty();
                    System.out.println(delres.getStatus());
                    if (delres.getStatus() == 401) {
                        if (retry) {
                            // Probably our access token expired, let's get a new one
                            token = getAccessToken(serverUrl, refreshToken);
                            System.out.println("Got new access token, let's retry...");
                        } else {
                            System.err.println("We don't seem to be auhorized. Aborting.");
                            System.exit(2);
                        }
                    } else {
                        retry = false;
                    }
                }
            }
        } while (count > 0);
    }

    private static String getAccessToken(String serverUrl, String refreshToken) {
        String url = serverUrl + "/auth/realms/master/protocol/openid-connect/token";
        HttpResponse<JsonNode> res = Unirest
            .post(url)
            .field("grant_type", "refresh_token")
            .field("client_id", "security-admin-console")
            .field("refresh_token", refreshToken)
            .asJson();
        if (res.getStatus() != 200) {
            System.err.println("Failed to get access token. Aborting. " + res.getStatus());
            System.exit(3);
        }
        System.out.println("Retrieved access token...");
        return res
            .getBody()
            .getObject()
            .getString("access_token");
    }
}
