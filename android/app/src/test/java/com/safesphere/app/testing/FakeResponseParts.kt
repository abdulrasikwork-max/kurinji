package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.ResponseRepository

/**
 * N5: responses in memory. Every call is recorded as "name:momentId[:arg]"; a moment answer is [momentAnswer] (or a
 * queued one), a people answer [peopleAnswer].
 */
class FakeResponseRepository : ResponseRepository {

    val calls = ArrayList<String>()
    val momentAnswers = ArrayDeque<ApiResult<Moment>>()
    var momentAnswer: ApiResult<Moment> = ApiResult.Failure(ApiError.NOT_FOUND)
    var peopleAnswer: ApiResult<PlanPeople> = ApiResult.Success(PlanPeople(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
    var leaveAnswer: ApiResult<Unit> = ApiResult.Success(Unit)

    private fun moment(call: String): ApiResult<Moment> {
        calls += call
        return momentAnswers.removeFirstOrNull() ?: momentAnswer
    }

    private fun lists(call: String): ApiResult<PlanPeople> {
        calls += call
        return peopleAnswer
    }

    override suspend fun same(momentId: String, on: Boolean) = moment("same:$momentId:$on")
    override suspend fun sayMore(momentId: String, on: Boolean) = moment("sayMore:$momentId:$on")
    override suspend fun addMore(momentId: String, text: String) = moment("addMore:$momentId:$text")
    override suspend fun askIn(momentId: String) = moment("askIn:$momentId")
    override suspend fun leave(momentId: String): ApiResult<Unit> {
        calls += "leave:$momentId"
        return leaveAnswer
    }
    override suspend fun people(momentId: String) = lists("people:$momentId")
    override suspend fun add(momentId: String, ref: String) = lists("add:$momentId:$ref")
    override suspend fun setAside(momentId: String, ref: String, aside: Boolean) = lists("setAside:$momentId:$ref:$aside")
    override suspend fun remove(momentId: String, ref: String) = lists("remove:$momentId:$ref")
    override suspend fun callOff(momentId: String) = moment("callOff:$momentId")
}
