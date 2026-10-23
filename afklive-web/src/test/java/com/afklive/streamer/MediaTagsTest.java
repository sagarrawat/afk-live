package com.afklive.streamer;

import com.afklive.streamer.model.MediaAsset;
import com.afklive.streamer.model.MediaStatus;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.MediaAssetRepository;
import com.afklive.streamer.repository.MediaTagRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.MediaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ML-8: tags on library files: create, rename, delete, tag/untag in bulk, filter, and ownership. */
@SpringBootTest
class MediaTagsTest extends PostgresIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired MediaAssetRepository assets;
    @Autowired MediaTagRepository tags;
    @Autowired UserRepository users;
    @Autowired MediaService media;

    MockMvc mvc;
    String email;
    String other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = newUser();
        other = newUser();
    }

    private String newUser() {
        String e = "tags-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        User u = new User(e);
        u.setPlanType(PlanType.ESSENTIALS);
        users.save(u);
        return e;
    }

    private long file(String owner, String title) {
        MediaAsset a = new MediaAsset(users.findByEmail(owner).orElseThrow(), title, "media/" + UUID.randomUUID() + ".mp4", 1000L,
                MediaAsset.ContentType.VIDEO);
        a.setStatus(MediaStatus.READY);
        return assets.save(a).getId();
    }

    private ResultActions createTag(String owner, String name) throws Exception {
        return mvc.perform(post("/api/media/tags").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", name))));
    }

    private long tag(String owner, String name) throws Exception {
        String body = createTag(owner, name).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private ResultActions tagFiles(String owner, long tagId, List<Long> ids) throws Exception {
        return mvc.perform(post("/api/media/tags/" + tagId + "/files").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("ids", ids))));
    }

    private ResultActions untagFiles(String owner, long tagId, List<Long> ids) throws Exception {
        return mvc.perform(post("/api/media/tags/" + tagId + "/files/remove").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("ids", ids))));
    }

    private JsonNode library(String owner, String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/media" + query).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    @Test
    void createRenameAndDeleteTags() throws Exception {
        long chill = tag(email, "  Chill   nights ");
        tag(email, "Bhajans");

        mvc.perform(get("/api/media/tags").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Bhajans")) // by name
                .andExpect(jsonPath("$[1].name").value("Chill nights")) // spaces tidied
                .andExpect(jsonPath("$[1].fileCount").value(0));

        // names are unique per user, ignoring case
        createTag(email, "chill NIGHTS").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have a tag called \"chill NIGHTS\"."));
        createTag(email, "   ").andExpect(status().isBadRequest());
        createTag(email, "x".repeat(41)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Keep tag names to 40 characters or fewer."));
        createTag(email, "x".repeat(40)).andExpect(status().isCreated());
        // ...but another user can use the same name
        createTag(other, "Chill nights").andExpect(status().isCreated());

        mvc.perform(patch("/api/media/tags/" + chill).with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rainy\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Rainy"));
        // renaming to its own name in another case is fine; to another tag's name is not
        mvc.perform(patch("/api/media/tags/" + chill).with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RAINY\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/media/tags/" + chill).with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bhajans\"}"))
                .andExpect(status().isConflict());

        long f = file(email, "storm.mp4");
        tagFiles(email, chill, List.of(f)).andExpect(status().isOk());
        mvc.perform(delete("/api/media/tags/" + chill).with(user(email)).with(csrf())).andExpect(status().isNoContent());
        // the file stays, just without the tag
        assertThat(assets.findById(f)).isPresent();
        assertThat(library(email, "").get("content").get(0).get("tags")).isEmpty();
        mvc.perform(get("/api/media/tags").with(user(email))).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aUserHasAtMostFiftyTags() throws Exception {
        for (int i = 0; i < 50; i++) {
            tag(email, "Tag " + i);
        }
        createTag(email, "One more").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can have up to 50 tags. Delete one you no longer use first."));
    }

    @Test
    void tagManyFilesAtOnceAndFilterTheLibraryByTag() throws Exception {
        long rain = file(email, "rain.mp4");
        long lofi = file(email, "lofi.mp3");
        long fire = file(email, "fireplace.mp4");
        long chill = tag(email, "Chill");
        long night = tag(email, "Night");

        tagFiles(email, chill, List.of(rain, lofi, rain)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(2));
        tagFiles(email, chill, List.of(rain)).andExpect(jsonPath("$.changed").value(0)); // already there: harmless
        tagFiles(email, night, List.of(rain)).andExpect(status().isOk());

        assertThat(titles(library(email, "?tag=" + chill))).containsExactlyInAnyOrder("rain.mp4", "lofi.mp3");
        assertThat(titles(library(email, "?tag=" + night))).containsExactly("rain.mp4");
        assertThat(titles(library(email, "?tag=none"))).containsExactly("fireplace.mp4");
        assertThat(titles(library(email, "?tag=" + chill + "&q=lofi"))).containsExactly("lofi.mp3"); // combines with search

        // each file shows its tags, by name
        mvc.perform(get("/api/media/" + rain).with(user(email)))
                .andExpect(jsonPath("$.tags[*].name", containsInAnyOrder("Chill", "Night")))
                .andExpect(jsonPath("$.tags[0].name").value("Chill"));
        mvc.perform(get("/api/media/tags").with(user(email)))
                .andExpect(jsonPath("$[0].fileCount").value(2))
                .andExpect(jsonPath("$[1].fileCount").value(1));

        untagFiles(email, chill, List.of(rain, lofi)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(2));
        assertThat(titles(library(email, "?tag=" + chill))).isEmpty();
        assertThat(titles(library(email, "?tag=none"))).containsExactlyInAnyOrder("lofi.mp3", "fireplace.mp4");

        // deleting a file takes its tags with it
        media.delete(email, List.of(rain));
        mvc.perform(get("/api/media/tags").with(user(email))).andExpect(jsonPath("$[1].fileCount").value(0));

        mvc.perform(get("/api/media?tag=abc").with(user(email))).andExpect(status().isBadRequest());
        tagFiles(email, chill, List.of()).andExpect(status().isBadRequest());
    }

    @Test
    void tagsAndFilesNeverCrossAccounts() throws Exception {
        long mine = file(email, "mine.mp4");
        long theirs = file(other, "theirs.mp4");
        long myTag = tag(email, "Shows");
        long theirTag = tag(other, "Secret");
        tagFiles(other, theirTag, List.of(theirs)).andExpect(status().isOk());

        // the list is only your own tags
        mvc.perform(get("/api/media/tags").with(user(email)))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("Shows"));

        // someone else's tag can't be renamed, deleted, used as a filter or put on your files
        mvc.perform(patch("/api/media/tags/" + theirTag).with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mine now\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/media/tags/" + theirTag).with(user(email)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/media?tag=" + theirTag).with(user(email))).andExpect(status().isNotFound());
        tagFiles(email, theirTag, List.of(mine)).andExpect(status().isNotFound());
        untagFiles(email, theirTag, List.of(theirs)).andExpect(status().isNotFound());

        // your tag can't go on someone else's file, even mixed in with yours (nothing is changed)
        tagFiles(email, myTag, List.of(mine, theirs)).andExpect(status().isNotFound());
        assertThat(titles(library(email, "?tag=" + myTag))).isEmpty();
        untagFiles(email, myTag, List.of(theirs)).andExpect(status().isNotFound());

        assertThat(tags.findById(theirTag).orElseThrow().getName()).isEqualTo("Secret");
        assertThat(titles(library(other, "?tag=" + theirTag))).containsExactly("theirs.mp4");
        assertThat(library(email, "").get("content").get(0).get("tags")).isEmpty();

        // the ownership check is also in the statement itself
        long uid = users.findByEmail(email).orElseThrow().getId();
        assertThat(tags.addLink(uid, myTag, theirs)).isZero();
        assertThat(tags.addLink(uid, theirTag, mine)).isZero();

        // signed out: nothing
        mvc.perform(get("/api/media/tags")).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(302, 401, 403));
    }
}
