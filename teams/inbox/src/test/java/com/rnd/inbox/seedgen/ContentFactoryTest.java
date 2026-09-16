package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContentFactoryTest {

    private static final TenantKey TENANT = new TenantKey("Micro/Trial", 4);

    @Test
    void tenantSlug_containsNoSlashOrSpace() {
        // "Micro/Trial" has both a slash and (in other tiers) could have a space — slugs must be
        // URL/identifier-safe.
        String slug = ContentFactory.tenantSlug(TENANT);

        assertThat(slug).doesNotContain("/").doesNotContain(" ");
    }

    @Test
    void taskTitle_prettifiesUnderscoredSourceType() {
        assertThat(ContentFactory.taskTitle("github_issue", 0)).isEqualTo("Github issue task 1");
    }

    @Test
    void userRole_reflectsTeamLead() {
        assertThat(ContentFactory.userRole(true)).isEqualTo("team_lead");
        assertThat(ContentFactory.userRole(false)).isEqualTo("member");
    }

    @Test
    void sameInputs_alwaysProduceTheSameOutput() {
        assertThat(ContentFactory.tenantName(TENANT)).isEqualTo(ContentFactory.tenantName(TENANT));
        assertThat(ContentFactory.userEmail(TENANT, 2)).isEqualTo(ContentFactory.userEmail(TENANT, 2));
    }

    @Test
    void realDataset_everyTenantSlugIsUnique() {
        List<TenantSeed> tenants = SeedGenerator.generate().tenants();

        Set<String> slugs = new HashSet<>();
        for (TenantSeed tenant : tenants) {
            assertThat(slugs.add(ContentFactory.tenantSlug(tenant.tenantKey()))).isTrue();
        }
    }

    @Test
    void realDataset_everyUserEmailAndUsernameIsGloballyUnique() {
        List<UserSeed> users = SeedGenerator.generate().users();

        Set<String> emails = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        for (UserSeed user : users) {
            String email = ContentFactory.userEmail(user.tenantKey(), user.userIndexInTenant());
            String username = ContentFactory.userUsername(user.tenantKey(), user.userIndexInTenant());
            assertThat(emails.add(email)).as("duplicate email: %s", email).isTrue();
            assertThat(usernames.add(username)).as("duplicate username: %s", username).isTrue();
        }
    }
}
