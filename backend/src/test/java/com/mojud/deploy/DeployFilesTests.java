package com.mojud.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The server setup in deploy/ keeps up with the app (implementation-plan.md 13b): a setting the
 * app reads from the environment but compose.yml never passes would silently fall back to its
 * local default on a server.
 */
class DeployFilesTests {

    static final Path DEPLOY = Path.of("../deploy");
    static final Path RESOURCES = Path.of("src/main/resources");
    static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

    /** Read by the app, but never set on a server: the role names keep their defaults; the image sets APP_VERSION. */
    static final Set<String> NOT_PASSED = Set.of("DB_APP_USER", "DB_OWNER_USER", "DB_SYSTEM_USER", "APP_VERSION");

    @Test
    void theDatabaseRolesAreTheOnesTheTestsUse() throws IOException {
        assertThat(Files.readString(DEPLOY.resolve("db/roles.sql")))
                .isEqualTo(Files.readString(RESOURCES.resolve("db/bootstrap/roles.sql")));
    }

    @Test
    void composePassesEverySettingTheAppReads() throws IOException {
        Set<String> read = new TreeSet<>();
        for (String file : new String[] {"application.yml", "application-prod.yml"}) {
            read.addAll(variables(Files.readString(RESOURCES.resolve(file))));
        }
        read.removeAll(NOT_PASSED);

        assertThat(appEnvironment().keySet()).containsAll(read);
    }

    @Test
    void theTemplateListsEverySettingComposeUses() throws IOException {
        Set<String> used = variables(Files.readString(DEPLOY.resolve("compose.yml")));
        Set<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(DEPLOY.resolve(".env.example"))) {
            if (line.matches("[A-Z][A-Z0-9_]*=.*")) {
                listed.add(line.substring(0, line.indexOf('=')));
            }
        }
        assertThat(listed).containsAll(used);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appEnvironment() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(DEPLOY.resolve("compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return (Map<String, Object>) ((Map<String, Object>) services.get("app")).get("environment");
    }

    private static Set<String> variables(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
