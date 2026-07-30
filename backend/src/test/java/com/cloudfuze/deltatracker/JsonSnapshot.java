package com.cloudfuze.deltatracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.nio.file.Files;
import java.nio.file.Path;

// Golden-file JSON snapshot. First run for a name writes the baseline (from current behavior) and
// fails loudly so it's never a silent pass; every subsequent run STRICT-compares the endpoint's
// JSON against that committed baseline. This is the characterization gate: after a refactor, the
// same seed must produce byte-for-byte-equivalent JSON (STRICT = exact values + array order,
// key order ignored). Snapshots live in src/test/resources/snapshots and are committed.
final class JsonSnapshot {

    private static final Path DIR = Path.of("src", "test", "resources", "snapshots");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSnapshot() {
    }

    static void match(String name, String actualJson) throws Exception {
        Files.createDirectories(DIR);
        Path file = DIR.resolve(name + ".json");
        if (!Files.exists(file)) {
            Object tree = MAPPER.readValue(actualJson, Object.class);
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
            Assertions.fail("Snapshot '" + name + "' did not exist -- wrote baseline to " + file
                    + ". Review it and re-run to lock it in.");
        }
        String expected = Files.readString(file);
        JSONAssert.assertEquals(expected, actualJson, JSONCompareMode.STRICT);
    }
}
