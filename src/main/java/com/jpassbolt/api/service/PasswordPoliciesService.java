package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.PasswordPoliciesSettingsDto;
import org.springframework.stereotype.Service;

/**
 * CE password-policies settings, porting the PHP
 * {@code Passbolt\PasswordPolicies\Service\PasswordPoliciesGetSettingsService}.
 *
 * <p>
 * In Passbolt CE the settings ALWAYS come from the built-in defaults with
 * {@code source = "default"} (the {@code organization_settings} DB write path is
 * an EE-only feature). This service is therefore a pure defaults projection:
 * NO storage, NO admin gate, just the constant default DTO assembled from the
 * PHP {@code createFromDefault()} values:
 * </p>
 *
 * <ul>
 *   <li>{@code default_generator = "password"},
 *       {@code external_dictionary_check = true}, {@code source = "default"}
 *       (PHP {@code PasswordPoliciesSettingsDto::createFromDefault}).</li>
 *   <li>password generator: length 18; {@code mask_upper/lower/digit/parenthesis}
 *       and {@code mask_char1..5} and {@code exclude_look_alike_chars} all true;
 *       {@code mask_emoji} false
 *       (PHP {@code PasswordGeneratorSettingsDto::createFromDefault}).</li>
 *   <li>passphrase generator: words 9, separator " ", case "lowercase"
 *       (PHP {@code PassphraseGeneratorSettingsDto::createFromDefault}).</li>
 * </ul>
 *
 * <p>
 * The defaults live here (iron law 3: DTOs carry no business logic). No new
 * table/column is introduced (iron law 1): CE never persists these settings.
 * </p>
 */
@Service
public class PasswordPoliciesService {

    /** PHP {@code PasswordPoliciesSettingsDto::DEFAULT_PASSWORD_GENERATOR}. */
    public static final String DEFAULT_GENERATOR_PASSWORD = "password";

    /** PHP {@code PasswordPoliciesSettingsDto::SOURCE_DEFAULT}. */
    public static final String SOURCE_DEFAULT = "default";

    /** PHP {@code PassphraseGeneratorSettingsDto::PASSPHRASE_GENERATOR_WORDS_CASE_LOWER}. */
    public static final String PASSPHRASE_WORD_CASE_LOWER = "lowercase";

    /**
     * Returns the CE password-policies settings — always the built-in defaults
     * with {@code source = "default"} (PHP
     * {@code PasswordPoliciesGetSettingsService::get} in CE).
     *
     * @return the default settings DTO
     */
    public PasswordPoliciesSettingsDto getSettings() {
        PasswordPoliciesSettingsDto.PasswordGeneratorSettings password =
                PasswordPoliciesSettingsDto.PasswordGeneratorSettings.builder()
                        .length(18)
                        .maskUpper(true)
                        .maskLower(true)
                        .maskDigit(true)
                        .maskParenthesis(true)
                        .maskEmoji(false)
                        .maskChar1(true)
                        .maskChar2(true)
                        .maskChar3(true)
                        .maskChar4(true)
                        .maskChar5(true)
                        .excludeLookAlikeChars(true)
                        .build();

        PasswordPoliciesSettingsDto.PassphraseGeneratorSettings passphrase =
                PasswordPoliciesSettingsDto.PassphraseGeneratorSettings.builder()
                        .words(9)
                        .wordSeparator(" ")
                        .wordCase(PASSPHRASE_WORD_CASE_LOWER)
                        .build();

        return PasswordPoliciesSettingsDto.builder()
                .defaultGenerator(DEFAULT_GENERATOR_PASSWORD)
                .externalDictionaryCheck(true)
                .passwordGeneratorSettings(password)
                .passphraseGeneratorSettings(passphrase)
                .source(SOURCE_DEFAULT)
                .build();
    }
}
