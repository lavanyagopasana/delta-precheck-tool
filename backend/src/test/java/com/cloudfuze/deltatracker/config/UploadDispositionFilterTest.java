package com.cloudfuze.deltatracker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * {@code /uploads/**} is same-origin and {@code permitAll()}, so any uploaded file a browser renders
 * as markup would execute in the application's origin — stored XSS against every reviewer who opens
 * the attachment. {@link UploadDispositionFilter} forces those types to download instead.
 *
 * <p>These assert on the header rather than on a stored file, because the header must be set whether
 * or not the file resolves: the filter runs before the resource handler, and a 404 that still renders
 * inline would be just as exploitable if the path were later populated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:/application-test.properties")
class UploadDispositionFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "evidence.svg", "evidence.svgz", "capture.html", "capture.htm", "page.xhtml",
            "doc.xml", "sheet.xsl", "script.js", "mod.mjs", "blob.wasm", "archive.mhtml",
    })
    void scriptableTypesAreForcedToDownload(String fileName) throws Exception {
        mockMvc.perform(get("/uploads/" + fileName))
                .andExpect(header().string("Content-Disposition", "attachment"))
                .andExpect(header().string("X-Content-Type-Options", equalToIgnoringCase("nosniff")));
    }

    // Asserts "not attachment" rather than "header absent": Spring's own ResourceHttpRequestHandler
    // already sets `Content-Disposition: inline` for some media types (PDF among them) as reflected-
    // file-download hardening. `inline` is exactly what we want for a previewable type, so demanding
    // the header be absent would fail on behaviour that is already correct. The property that matters
    // is that this filter didn't downgrade a previewable attachment into a forced download.
    @ParameterizedTest
    @ValueSource(strings = {"shot.png", "shot.jpg", "proof.pdf", "recording.mp4", "notes.txt", "pairs.csv"})
    void previewableTypesAreNotForcedToDownload(String fileName) throws Exception {
        mockMvc.perform(get("/uploads/" + fileName))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader("Content-Disposition");
                    if (disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
                        throw new AssertionError(
                                "Previewable type " + fileName + " was forced to download: " + disposition);
                    }
                });
    }

    // Case is attacker-controlled in the URL, so the extension match must not be case-sensitive.
    @ParameterizedTest
    @ValueSource(strings = {"evidence.SVG", "capture.HtMl", "script.JS"})
    void extensionMatchIsCaseInsensitive(String fileName) throws Exception {
        mockMvc.perform(get("/uploads/" + fileName))
                .andExpect(header().string("Content-Disposition", "attachment"));
    }

    // Stored names are UUID-based so this is the "unknown blob" case, not a bypass: with no extension
    // nothing tells the browser to parse it as markup.
    @Test
    void extensionlessPathIsNotForcedToDownload() throws Exception {
        mockMvc.perform(get("/uploads/9f1c2d3e-no-extension"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    // A dot in a directory segment must not be mistaken for the file's extension.
    @Test
    void dotInEarlierPathSegmentIsIgnored() throws Exception {
        mockMvc.perform(get("/uploads/v1.2/shot"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void filterOnlyAppliesToTheUploadsPath() throws Exception {
        mockMvc.perform(get("/some-other-place/evidence.svg"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }
}
