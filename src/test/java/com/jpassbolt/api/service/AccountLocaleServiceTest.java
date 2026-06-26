package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountLocaleService}: the user → organization →
 * default resolution chain, locale validation, the upsert, and the
 * {@code toJavaLocale} mapping. Pure Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AccountLocaleServiceTest {

    private static final String USER_ID = "11111111-1111-4111-8111-111111111111";

    @Mock
    private AccountSettingRepository accountSettingRepository;

    @Mock
    private OrganizationSettingRepository organizationSettingRepository;

    @InjectMocks
    private AccountLocaleService service;

    private AccountSetting userSetting(String value) {
        AccountSetting setting = new AccountSetting();
        setting.setUserId(USER_ID);
        setting.setProperty("locale");
        setting.setPropertyId("pid");
        setting.setValue(value);
        return setting;
    }

    private OrganizationSetting orgSetting(String value) {
        OrganizationSetting setting = new OrganizationSetting();
        setting.setProperty("locale");
        setting.setPropertyId("pid");
        setting.setValue(value);
        return setting;
    }

    // ------------------------------------------------------------------
    // getUserLocale — fallback chain
    // ------------------------------------------------------------------

    @Test
    void getUserLocale_returnsUserSettingFirst() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.of(userSetting("zh-CN")));

        assertThat(service.getUserLocale(USER_ID)).isEqualTo("zh-CN");
        // org repo must not even be consulted when the user setting is present
        verify(organizationSettingRepository, never()).findByProperty(any());
    }

    @Test
    void getUserLocale_fallsBackToOrganizationLocale() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.empty());
        when(organizationSettingRepository.findByProperty("locale"))
                .thenReturn(Optional.of(orgSetting("fr-FR")));

        assertThat(service.getUserLocale(USER_ID)).isEqualTo("fr-FR");
    }

    @Test
    void getUserLocale_fallsBackToDefaultWhenNothingSet() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.empty());
        when(organizationSettingRepository.findByProperty("locale"))
                .thenReturn(Optional.empty());

        assertThat(service.getUserLocale(USER_ID))
                .isEqualTo(AccountLocaleService.DEFAULT_LOCALE)
                .isEqualTo("en-UK");
    }

    @Test
    void getUserLocale_blankUserValueFallsThroughToOrg() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.of(userSetting("   ")));
        when(organizationSettingRepository.findByProperty("locale"))
                .thenReturn(Optional.of(orgSetting("de-DE")));

        assertThat(service.getUserLocale(USER_ID)).isEqualTo("de-DE");
    }

    // ------------------------------------------------------------------
    // setUserLocale — validation + upsert
    // ------------------------------------------------------------------

    @Test
    void setUserLocale_createsRowWhenAbsent() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.empty());
        when(accountSettingRepository.save(any(AccountSetting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountSetting saved = service.setUserLocale(USER_ID, "zh-CN");

        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getProperty()).isEqualTo("locale");
        assertThat(saved.getValue()).isEqualTo("zh-CN");
        assertThat(saved.getPropertyId()).isNotBlank();
        verify(accountSettingRepository).save(any(AccountSetting.class));
    }

    @Test
    void setUserLocale_updatesExistingRow() {
        AccountSetting existing = userSetting("en-UK");
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.of(existing));
        when(accountSettingRepository.save(any(AccountSetting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountSetting saved = service.setUserLocale(USER_ID, "fr-FR");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getValue()).isEqualTo("fr-FR");
    }

    @Test
    void setUserLocale_acceptsUnderscoredInputAndDasherizes() {
        when(accountSettingRepository.findFirstByUserIdAndProperty(USER_ID, "locale"))
                .thenReturn(Optional.empty());
        when(accountSettingRepository.save(any(AccountSetting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountSetting saved = service.setUserLocale(USER_ID, "zh_CN");

        assertThat(saved.getValue()).isEqualTo("zh-CN");
    }

    @Test
    void setUserLocale_rejectsUnsupportedLocale() {
        assertThatThrownBy(() -> service.setUserLocale(USER_ID, "xx-XX"))
                .isInstanceOf(PassboltApiException.class)
                .hasMessage("This is not a valid locale.")
                .extracting(ex -> ((PassboltApiException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(accountSettingRepository, never()).save(any());
    }

    @Test
    void setUserLocale_rejectsNullAndBlank() {
        assertThatThrownBy(() -> service.setUserLocale(USER_ID, null))
                .isInstanceOf(PassboltApiException.class);
        assertThatThrownBy(() -> service.setUserLocale(USER_ID, ""))
                .isInstanceOf(PassboltApiException.class);

        verify(accountSettingRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // isValidLocale / toJavaLocale
    // ------------------------------------------------------------------

    @Test
    void isValidLocale_coversSupportedSet() {
        assertThat(service.isValidLocale("zh-CN")).isTrue();
        assertThat(service.isValidLocale("en-UK")).isTrue();
        assertThat(service.isValidLocale("zh_CN")).isTrue();
        assertThat(service.isValidLocale("ZH-cn")).isFalse(); // case-sensitive, like PHP
        assertThat(service.isValidLocale("xx-XX")).isFalse();
        assertThat(service.isValidLocale(null)).isFalse();
    }

    @Test
    void toJavaLocale_mapsCodes() {
        assertThat(service.toJavaLocale("zh-CN")).isEqualTo(new Locale("zh", "CN"));
        assertThat(service.toJavaLocale("fr-FR")).isEqualTo(new Locale("fr", "FR"));
        // en-UK: UK is not an ISO region, only the language survives
        assertThat(service.toJavaLocale("en-UK")).isEqualTo(new Locale("en"));
        // blank/null → default (en-UK → en)
        assertThat(service.toJavaLocale(null)).isEqualTo(new Locale("en"));
        assertThat(service.toJavaLocale("")).isEqualTo(new Locale("en"));
    }

    @Test
    void getOrganizationLocale_defaultsWhenAbsent() {
        // lenient: only this test path touches the org repo here
        lenient().when(organizationSettingRepository.findByProperty("locale"))
                .thenReturn(Optional.empty());

        assertThat(service.getOrganizationLocale()).isEqualTo("en-UK");
    }
}
