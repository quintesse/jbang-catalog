//usr/bin/env jbang "$0" "$@" ; exit $?

/*
 * Simplistic and probably very dangerous script to delete all users from a KeyCloak instance.
 */

//DEPS org.keycloak:keycloak-admin-client:9.0.0
//DEPS org.jboss.resteasy:resteasy-client:4.5.2.Final

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.*;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.*;

public class kcusers {
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: kcusers <server_url> <realm> <user> <password>");
            System.exit(-1);
        }
        String serverUrl = args[0];
        String realmName = args[1];
        String userName = args[2];
        String pwd = args[3];
        var kc = KeycloakBuilder.builder()
            .serverUrl(serverUrl)
            .realm(realmName)
            .grantType(OAuth2Constants.PASSWORD)
            .clientId("admin-cli")
            .username(userName)
            .password(pwd)
            .build();
        RealmsResource rs = kc.realms();
        System.out.println(rs);
        RealmResource realm = kc.realm(realmName);
        for (UserRepresentation user : realm.users().list()) {
            System.out.println(user);
        }
    }
}
