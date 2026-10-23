package com.safesphere.app.feature.social.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Language
import com.safesphere.app.feature.social.domain.ProfileInputPolicy

/** Phase 13A: the words for the closed lists and the form problems. The server's codes are never shown as text. */
object SocialLabels {

    @StringRes
    fun of(language: Language): Int = when (language) {
        Language.TA -> R.string.social_language_ta
        Language.EN -> R.string.social_language_en
        Language.HI -> R.string.social_language_hi
        Language.ML -> R.string.social_language_ml
        Language.TE -> R.string.social_language_te
        Language.KN -> R.string.social_language_kn
    }

    @StringRes
    fun of(interest: Interest): Int = when (interest) {
        Interest.FOOD_CAFES -> R.string.social_interest_food_cafes
        Interest.NATURE_OUTDOORS -> R.string.social_interest_nature_outdoors
        Interest.CULTURE_HERITAGE -> R.string.social_interest_culture_heritage
        Interest.MUSIC_NIGHTLIFE -> R.string.social_interest_music_nightlife
        Interest.STUDY_WORK -> R.string.social_interest_study_work
        Interest.DAY_TRIPS -> R.string.social_interest_day_trips
        Interest.ACTIVITIES_SPORT -> R.string.social_interest_activities_sport
        Interest.HOBBIES_MAKING -> R.string.social_interest_hobbies_making
        Interest.CITY_LIFE -> R.string.social_interest_city_life
        Interest.HIDDEN_GEMS -> R.string.social_interest_hidden_gems
    }

    @StringRes
    fun of(problem: ProfileInputPolicy.Problem): Int = when (problem) {
        ProfileInputPolicy.Problem.HANDLE_FORMAT -> R.string.social_problem_handle_format
        ProfileInputPolicy.Problem.HANDLE_UNAVAILABLE -> R.string.social_problem_handle_unavailable
        ProfileInputPolicy.Problem.NAME_REQUIRED -> R.string.social_problem_name_required
        ProfileInputPolicy.Problem.NAME_TOO_LONG -> R.string.social_problem_name_too_long
        ProfileInputPolicy.Problem.BIO_TOO_LONG -> R.string.social_problem_bio_too_long
        ProfileInputPolicy.Problem.CITY_INVALID -> R.string.social_problem_city
        ProfileInputPolicy.Problem.LINK -> R.string.social_problem_link
    }

    /** For date-of-birth errors shown outside a TextInputLayout. */
    @StringRes
    fun of(error: FieldError): Int = when (error) {
        FieldError.UNDER_AGE -> R.string.error_under_age
        FieldError.INVALID_DATE_OF_BIRTH -> R.string.error_invalid_date_of_birth
        else -> R.string.error_field_required
    }
}
