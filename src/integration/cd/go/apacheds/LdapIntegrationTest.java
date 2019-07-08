/*
 * Copyright 2019 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cd.go.apacheds;

import cd.go.authentication.ldap.BaseIntegrationTest;
import cd.go.authentication.ldap.exception.LdapException;
import cd.go.authentication.ldap.mapper.LdapMapperFactory;
import cd.go.authentication.ldap.mapper.UserMapper;
import cd.go.authentication.ldap.mapper.UsernameResolver;
import cd.go.authentication.ldap.model.LdapConfiguration;
import cd.go.authentication.ldap.model.User;
import org.apache.directory.ldap.client.template.exception.LdapRuntimeException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;

import java.util.List;

import static cd.go.authentication.ldap.LdapFactory.USE_JNDI_LDAP_CLIENT;
import static java.text.MessageFormat.format;
import static org.assertj.core.api.Assertions.*;

public class LdapIntegrationTest extends BaseIntegrationTest {
    @Rule
    public final ProvideSystemProperty systemProperty = new ProvideSystemProperty(USE_JNDI_LDAP_CLIENT, "false");
    private Ldap ldap;

    @Test
    public void authenticate_shouldAuthenticateUser() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=system"});

        ldap = new Ldap(ldapConfiguration);

        final User user = ldap.authenticate("bford", "bob", ldapConfiguration.getUserMapper(new UsernameResolver()));

        assertThat(user).isNotNull();
        assertThat(user).isEqualTo(new User("bford", "Bob Ford", "bford@example.com"));
    }

    @Test
    public void authenticate_shouldErrorOutIfFailToAuthenticateUser() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=system"});

        ldap = new Ldap(ldapConfiguration);

        assertThatCode(() -> ldap.authenticate("bford", "wrong-password", ldapConfiguration.getUserMapper(new UsernameResolver())))
                .isInstanceOf(LdapException.class)
                .hasMessageContaining("Failed to authenticate user `bford` with ldap server ldap://localhost");
    }

    @Test
    public void authenticate_shouldErrorOutUserIsNotExistInLdap() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=system"});

        ldap = new Ldap(ldapConfiguration);

        assertThatCode(() -> ldap.authenticate("foo", "bar", ldapConfiguration.getUserMapper(new UsernameResolver())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(format("User foo does not exist in {0}", ldapConfiguration.getLdapUrlAsString()));
    }

    @Test
    public void authenticate_shouldErrorOutIfMultipleUserDetectedInSearchBaseWhenUserLoginFilterHasWildCard() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=system"}, "(uid=*{0}*)");
        ldap = new Ldap(ldapConfiguration);

        assertThatCode(() -> ldap.authenticate("neil", "neil", new LdapMapperFactory().attributeOrEntryMapper()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Found multiple users in search base `[ou=system]` with username `neil`. It is not recommended to have wildcard(`*{0}*`, `{0}*` or `*{0}`) in `UserLoginFilter` field as it can match other users.");

    }

    @Test
    public void search_shouldSearchUser() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=Employees,ou=Enterprise,ou=Principal,ou=system"});

        ldap = new Ldap(ldapConfiguration);

        final List<User> users = ldap.search("(uid=*{0}*)", new String[]{"pbanks"}, ldapConfiguration.getUserMapper(new UsernameResolver()), 1);

        assertThat(users).hasSize(1);
        assertThat(users.get(0)).isEqualTo(new User("pbanks", "P.Banks", "pbanks@example.com"));
    }

    @Test
    public void search_shouldSearchUsersFromMultipleSearchBases() {
        LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=Employees,ou=Enterprise,ou=Principal,ou=system", "ou=Clients,ou=Enterprise,ou=Principal,ou=system"});

        ldap = new Ldap(ldapConfiguration);

        final List<User> users = ldap.search("(uid=*{0}*)", new String[]{"banks"}, ldapConfiguration.getUserMapper(new UsernameResolver()), 2);

        assertThat(users).hasSize(2);
        assertThat(users).contains(new User("pbanks", "P.Banks", "pbanks@example.com"), new User("sbanks", "S.Banks", "sbanks@example.com"));
    }


    @Test
    public void search_shouldStopSearchingWhenSpecifiedNumberOfUsersFoundInFirstSearchBase() {
        final LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=Employees,ou=Enterprise,ou=Principal,ou=system", "ou=Clients,ou=Enterprise,ou=Principal,ou=system"});
        final UserMapper userMapper = ldapConfiguration.getUserMapper(new UsernameResolver());

        ldap = new Ldap(ldapConfiguration);

        final List<User> allUsers = ldap.search("(uid=*{0}*)", new String[]{"a"}, userMapper, Integer.MAX_VALUE);

        assertThat(allUsers).hasSize(5);

        final List<User> userFoundFromFirstSearchBase = ldap.search("(uid=*{0}*)", new String[]{"a"}, userMapper, 3);

        assertThat(userFoundFromFirstSearchBase).hasSize(3);
    }

    @Test
    public void search_shouldSearchAcrossMultipleSearchBasesAndLimitTheSearchResult() {
        final LdapConfiguration ldapConfiguration = ldapConfiguration(new String[]{"ou=Employees,ou=Enterprise,ou=Principal,ou=system", "ou=Clients,ou=Enterprise,ou=Principal,ou=system"});
        final UserMapper userMapper = ldapConfiguration.getUserMapper(new UsernameResolver());

        ldap = new Ldap(ldapConfiguration);

        final List<User> allUsers = ldap.search("(uid=*{0}*)", new String[]{"a"}, userMapper, Integer.MAX_VALUE);

        assertThat(allUsers).hasSize(5);

        final List<User> users = ldap.search("(uid=*{0}*)", new String[]{"a"}, userMapper, 4);

        assertThat(users).hasSize(4);
    }

    @Test
    public void validate_shouldValidateManagerDnAndPassword() {
        LdapConfiguration ldapConfiguration = ldapConfiguration("uid=admin,ou=system", "secret", "ou=system");

        try {
            new Ldap(ldapConfiguration).validate();
        } catch (Exception e) {
            fail("Should not error out when valid credentials provided");
        }
    }

    @Test
    public void validate_shouldErrorOutWhenInvalidManagerDnAndPasswordProvided() {
        LdapConfiguration ldapConfiguration = ldapConfiguration("uid=admin,ou=system", "invalid-password", "ou=system");

        assertThatCode(() -> new Ldap(ldapConfiguration).validate())
                .isInstanceOf(LdapRuntimeException.class)
                .hasMessageContaining("Cannot authenticate user uid=admin,ou=system");
    }
}
