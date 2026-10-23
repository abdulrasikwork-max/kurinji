package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13B: Perspectives: kinds per post type, one level of replies, the author's comment setting, Only me takes none,
 * hiding is transparent to the writer only (Q5), "This helped" by the asker only, and the order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;

    private SocialClient social;
    private Person asha;     // author
    private Person bala;     // connected to Asha
    private Person chitra;   // stranger
    private Person devi;     // stranger

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        devi = social.person("Devi");
        social.connect(asha, bala);
    }

    @Test
    void kindsFitThePostType_andRepliesAreOneLevel() throws Exception {
        String experience = social.experience(asha, "EVERYONE");
        String ask = ask("EVERYONE");
        social.comment(chitra, experience, "ANSWER", "x", null).andExpect(status().isBadRequest());
        social.comment(chitra, ask, "BEEN_HERE", "x", null).andExpect(status().isBadRequest());
        social.comment(chitra, experience, "REPLY", "no parent", null).andExpect(status().isBadRequest());
        social.comment(chitra, experience, "LIKE", "x", null).andExpect(status().isBadRequest());
        social.comment(chitra, experience, "QUESTION", " ", null).andExpect(status().isBadRequest());
        social.comment(chitra, experience, "QUESTION", "x".repeat(1001), null).andExpect(status().isBadRequest());
        String been = social.commentId(chitra, experience, "BEEN_HERE", "Went last week, same.", null);
        String reply = social.commentId(asha, experience, "REPLY", "Glad you liked it!", been);
        social.comment(bala, experience, "REPLY", "Reply to a reply", reply).andExpect(status().isNotFound());
        social.comment(bala, experience, "QUESTION", "Not a reply kind", been).andExpect(status().isBadRequest());
        social.comment(bala, ask, "REPLY", "Wrong post", been).andExpect(status().isNotFound());
        social.commentId(chitra, ask, "ANSWER", "Yes, autos are easy to find.", null);
    }

    @Test
    void theAuthorsCommentSetting_decidesWhoMayWrite() throws Exception {
        String everyone = post("EVERYONE", "EVERYONE");
        String connections = post("EVERYONE", "CONNECTIONS");
        String nobody = post("EVERYONE", "NOBODY");
        String onlyMe = post("ONLY_ME", "NOBODY");
        social.comment(chitra, everyone, "QUESTION", "Open on Sundays?", null).andExpect(status().isCreated());
        social.comment(chitra, connections, "QUESTION", "Open on Sundays?", null).andExpect(status().isNotFound());
        social.comment(bala, connections, "QUESTION", "Open on Sundays?", null).andExpect(status().isCreated());
        social.comment(bala, nobody, "QUESTION", "Open on Sundays?", null).andExpect(status().isNotFound());
        social.comment(asha, nobody, "QUESTION", "Note to self", null).andExpect(status().isCreated()); // the author
        social.comment(asha, onlyMe, "QUESTION", "Note to self", null).andExpect(status().isNotFound());
        social.comment(bala, onlyMe, "QUESTION", "?", null).andExpect(status().isNotFound());
        assertThat(social.json(social.getPost(chitra, connections)).get("canComment").asBoolean()).isFalse();
        assertThat(social.json(social.getPost(bala, connections)).get("canComment").asBoolean()).isTrue();
    }

    @Test
    void hiding_isTransparentToTheWriterOnly() throws Exception {
        String id = social.experience(asha, "EVERYONE");
        String rude = social.commentId(chitra, id, "QUESTION", "Why would anyone go there?", null);
        String replyToRude = social.commentId(devi, id, "REPLY", "Because it is nice.", rude);
        String kind = social.commentId(devi, id, "BEEN_HERE", "Lovely spot.", null);
        social.commentAction(bala, rude, "hide").andExpect(status().isNotFound());   // only the post's author
        social.commentAction(asha, rude, "hide").andExpect(status().isNoContent());

        JsonNode forWriter = social.json(social.comments(chitra, id));
        assertThat(find(forWriter, rude).get("hidden").asBoolean()).isTrue();
        JsonNode forAuthor = social.json(social.comments(asha, id));
        assertThat(find(forAuthor, rude).get("hidden").asBoolean()).isTrue();
        assertThat(find(forAuthor, rude).get("canHide").asBoolean()).isTrue();
        for (Person other : new Person[] {bala, devi}) {
            List<String> ids = social.ids(social.comments(other, id));
            assertThat(ids).contains(kind).doesNotContain(rude, replyToRude);
        }
        social.commentAction(asha, rude, "unhide").andExpect(status().isNoContent());
        assertThat(social.ids(social.comments(bala, id))).contains(rude, replyToRude);
    }

    @Test
    void helped_byTheAskerOnly_onAnswers_andAnswersMarkedFirst() throws Exception {
        String ask = ask("EVERYONE");
        String first = social.commentId(chitra, ask, "ANSWER", "Take the 23C bus.", null);
        String question = social.commentId(devi, ask, "QUESTION", "From where?", null);
        String second = social.commentId(devi, ask, "ANSWER", "Autos wait at the gate.", null);
        social.commentAction(chitra, second, "helped").andExpect(status().isNotFound());   // not the asker
        social.commentAction(asha, question, "helped").andExpect(status().isNotFound());   // not an answer
        social.commentAction(asha, second, "helped").andExpect(status().isNoContent());
        JsonNode list = social.json(social.comments(chitra, ask));
        assertThat(list.get(0).get("publicId").asText()).isEqualTo(second);
        assertThat(list.get(0).get("helped").asBoolean()).isTrue();
        assertThat(social.ids(social.comments(chitra, ask))).containsExactly(second, first, question);
        social.unmarkHelped(asha, second).andExpect(status().isNoContent());
        assertThat(social.ids(social.comments(chitra, ask))).containsExactly(first, question, second);
        String experience = social.experience(asha, "EVERYONE");
        String been = social.commentId(chitra, experience, "BEEN_HERE", "Me too.", null);
        social.commentAction(asha, been, "helped").andExpect(status().isNotFound());       // not an Ask
    }

    @Test
    void order_beenHereFirst_repliesUnderTheirParent_andBandsNotTimes() throws Exception {
        String id = social.experience(asha, "EVERYONE");
        String question = social.commentId(chitra, id, "QUESTION", "Parking?", null);
        String been = social.commentId(devi, id, "BEEN_HERE", "Went in June.", null);
        String reply = social.commentId(asha, id, "REPLY", "Street parking only.", question);
        JsonNode list = social.json(social.comments(bala, id));
        List<String> ids = new ArrayList<>();
        list.forEach(c -> ids.add(c.get("publicId").asText()));
        assertThat(ids).containsExactly(been, question, reply);
        JsonNode r = find(list, reply);
        assertThat(r.get("parentId").asText()).isEqualTo(question);
        assertThat(r.get("postedPeriod").asText()).isEqualTo("THIS_WEEK");
        List<String> fields = new ArrayList<>();
        r.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).doesNotContain("createdAt", "postedAt", "count", "likes");
    }

    @Test
    void writersDeleteTheirOwn_withTheRepliesUnderIt() throws Exception {
        String id = social.experience(asha, "EVERYONE");
        String been = social.commentId(chitra, id, "BEEN_HERE", "Me too.", null);
        social.commentId(devi, id, "REPLY", "When?", been);
        social.deleteComment(asha, been).andExpect(status().isNotFound());     // the post's author hides, not deletes
        social.deleteComment(chitra, been).andExpect(status().isNoContent());
        assertThat(social.ids(social.comments(asha, id))).isEmpty();
    }

    private String post(String audience, String commentPolicy) throws Exception {
        Map<String, Object> body = experienceBody(audience, "NONE");
        body.put("commentPolicy", commentPolicy);
        return social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private String ask(String audience) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "ASK", "body", "How do people get back at night?",
                "precision", "NONE", "audience", audience, "commentPolicy", "EVERYONE"));
        return social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode c : list) if (c.get("publicId").asText().equals(id)) return c;
        throw new AssertionError("not in the list: " + id);
    }
}
