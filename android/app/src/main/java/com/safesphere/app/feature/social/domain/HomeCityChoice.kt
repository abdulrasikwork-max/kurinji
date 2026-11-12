package com.safesphere.app.feature.social.domain

import kotlinx.coroutines.flow.first

/**
 * IRL redesign (R6): the city the CURRENT account chose for its Home feed, remembered on this phone so Home does not ask
 * again at every start. It is the person's own choice (one code from the bundled city list, never typed words, never
 * anything about another person) and it is stored with the account that made it: someone else signing in on this phone
 * starts without it. Without a signed-in account nothing is stored.
 */
class HomeCityChoice(
    private val preferences: ViewerPreferences,
    private val currentUserId: () -> Long?
) {
    /** The remembered city of the signed-in account, or null (nothing chosen, another account's choice, or an unknown code). */
    suspend fun get(): City? {
        val me = currentUserId() ?: return null
        val saved = preferences.homeCity.first() ?: return null
        val code = saved.substringAfter(SEPARATOR, "")
        if (saved.substringBefore(SEPARATOR) != me.toString()) return null
        return Cities.listCity(code)
    }

    suspend fun set(city: City) {
        if (city.isOther) return
        val me = currentUserId() ?: return
        preferences.setHomeCity(me.toString() + SEPARATOR + city.code)
    }

    private companion object {
        const val SEPARATOR = ":"
    }
}
