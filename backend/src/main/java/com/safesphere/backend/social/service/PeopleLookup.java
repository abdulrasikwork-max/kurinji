package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Phase N5: the people a viewer may see in the lists of a moment (who feels the same, who asks to join, who is in, who
 * wrote in a plan). Someone the viewer could not see on a profile (blocked either way, not active, not a member of the
 * viewer's community any more) is left out, in the same one rule (Visibility.canSeeProfile). Reads only.
 */
@Component
public class PeopleLookup {

    private final UserRepository users;
    private final ProfileRepository profiles;
    private final Relationships relationships;
    private final SocialAccess access;

    public PeopleLookup(UserRepository users, ProfileRepository profiles, Relationships relationships,
                        SocialAccess access) {
        this.users = users;
        this.profiles = profiles;
        this.relationships = relationships;
        this.access = access;
    }

    /** The ids among [ids] that [viewer] may see (themselves always), in the order given. */
    public List<Long> visible(Relationships.Viewer viewer, Collection<Long> ids) {
        Set<Long> unique = new LinkedHashSet<>(ids);
        if (unique.isEmpty()) return List.of();
        Set<Long> others = new HashSet<>(unique);
        others.remove(viewer.id());
        Set<Long> active = access.activeAmong(others);
        Set<Long> sameCommunity = access.sameCommunityAmong(viewer.id(), others);
        List<Long> out = new ArrayList<>();
        for (Long id : unique) {
            if (id == viewer.id() || (active.contains(id) && sameCommunity.contains(id)
                    && !viewer.blockedEitherWay().contains(id))) {
                out.add(id);
            }
        }
        return out;
    }

    /** The real names of [ids], in their order (ids without an account are left out). */
    public List<String> names(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(u -> u != null && u.getName() != null).map(User::getName).toList();
    }

    /**
     * People as lists show them, for [viewer]: [refs] maps a user id to what the author acts on (an ask's id), or is
     * empty. Callers pass only ids from [visible].
     */
    public List<Person> people(Relationships.Viewer viewer, List<Long> ids, Map<Long, String> refs) {
        if (ids.isEmpty()) return List.of();
        Map<Long, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Profile> profileOf = profiles.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        List<Person> out = new ArrayList<>();
        for (Long id : ids) {
            User user = byId.get(id);
            if (user == null) continue;
            Profile profile = profileOf.get(id);
            MembershipGate.Member member = access.memberOf(id).orElse(null);
            out.add(new Person(refs.get(id), user.getName(), profile == null ? null : profile.getPublicId(),
                    profile == null ? null : profile.getPhotoKey(), member == null ? null : member.programme(),
                    member == null ? null : member.graduationYear(), viewer.connected().contains(id)));
        }
        return out;
    }

    public Relationships.Viewer viewer(long viewerId) {
        return relationships.viewer(viewerId);
    }
}
