package com.safesphere.app.feature.moments.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.dispose
import coil.load
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemMomentCardBinding
import com.safesphere.app.databinding.ItemTodayMessageBinding
import com.safesphere.app.databinding.ItemTodayQuestionBinding
import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.People
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where a moment's pictures come from: the ONE Coil loader (memory cache only, sign-in header attached). */
class MomentImages(
    val loader: ImageLoader,
    val photoUrl: (momentId: String) -> String?,
    val personPhotoUrl: (profileId: String, key: String) -> String?
)

/** One row of the wall. */
sealed interface TodayRow {
    data class Question(val question: DailyQuestion) : TodayRow
    /** [open] = a covered moment the reader chose to read. */
    data class Card(val moment: Moment, val open: Boolean) : TodayRow
    data object Loading : TodayRow
    data class Empty(val people: People) : TodayRow
    /** The wall ends, and says so. */
    data class End(val people: People) : TodayRow
}

/** Builds the wall from the state: the question, the moments, then exactly one closing row. Pure, unit tested. */
object TodayWall {
    fun rows(state: TodayUiState, opened: Set<String>): List<TodayRow> {
        if (state.needsMembership) return emptyList()
        val rows = ArrayList<TodayRow>()
        state.question?.let { rows += TodayRow.Question(it) }
        state.moments.forEach { rows += TodayRow.Card(it, it.id in opened) }
        when {
            !state.loaded -> if (state.loading) rows += TodayRow.Loading
            state.loadingMore -> rows += TodayRow.Loading
            state.moments.isEmpty() -> rows += TodayRow.Empty(state.people)
            state.ended -> rows += TodayRow.End(state.people)
        }
        return rows
    }
}

/** Phase N4: the wall's list. Tapping a card opens the moment; tapping a covered one uncovers it first. */
class MomentCardAdapter(
    private val images: MomentImages,
    private val onOpen: (Moment) -> Unit,
    private val onUncover: (Moment) -> Unit,
    /** A2: "Answer" on today's question opens Create with it. */
    private val onAnswer: (DailyQuestion) -> Unit = {}
) : ListAdapter<TodayRow, RecyclerView.ViewHolder>(Diff) {

    private class CardHolder(val binding: ItemMomentCardBinding) : RecyclerView.ViewHolder(binding.root)
    private class QuestionHolder(val binding: ItemTodayQuestionBinding) : RecyclerView.ViewHolder(binding.root)
    private class MessageHolder(val binding: ItemTodayMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TodayRow.Card -> CARD
        is TodayRow.Question -> QUESTION
        else -> MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            CARD -> CardHolder(ItemMomentCardBinding.inflate(inflater, parent, false))
            QUESTION -> QuestionHolder(ItemTodayQuestionBinding.inflate(inflater, parent, false))
            else -> MessageHolder(ItemTodayMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is TodayRow.Card -> {
                val b = (holder as CardHolder).binding
                MomentCardBinder.bind(b, row.moment, row.open, images)
                b.content.setOnClickListener {
                    if (MomentCardBinder.covered(row.moment, row.open)) onUncover(row.moment) else onOpen(row.moment)
                }
            }
            is TodayRow.Question -> {
                val b = (holder as QuestionHolder).binding
                b.questionText.text = row.question.text
                b.answerButton.setOnClickListener { onAnswer(row.question) }
            }
            is TodayRow.Loading -> message(holder, loading = true, R.string.today_loading, null)
            is TodayRow.Empty -> message(holder, false, R.string.today_empty_title,
                if (row.people == People.MINE) R.string.today_empty_message_mine else R.string.today_empty_message)
            is TodayRow.End -> message(holder, false, R.string.today_end_title,
                if (row.people == People.MINE) R.string.today_end_message_mine else R.string.today_end_message)
        }
    }

    private fun message(holder: RecyclerView.ViewHolder, loading: Boolean, title: Int, message: Int?) {
        val b = (holder as MessageHolder).binding
        b.progress.isVisible = loading
        b.icon.isVisible = !loading
        b.title.setText(title)
        b.message.isVisible = message != null
        if (message != null) b.message.setText(message)
    }

    private object Diff : DiffUtil.ItemCallback<TodayRow>() {
        override fun areItemsTheSame(old: TodayRow, new: TodayRow) = when {
            old is TodayRow.Card && new is TodayRow.Card -> old.moment.id == new.moment.id
            old is TodayRow.Question && new is TodayRow.Question -> old.question.id == new.question.id
            else -> old::class == new::class
        }

        override fun areContentsTheSame(old: TodayRow, new: TodayRow) = old == new
    }

    private companion object {
        const val CARD = 0
        const val QUESTION = 1
        const val MESSAGE = 2
    }
}

/** Binds one moment card; shared by Today, the moment's own page and the profile lists. */
object MomentCardBinder {

    /** Covered = collapsed for the reader and not yet opened by them. The author always reads their own. */
    fun covered(moment: Moment, open: Boolean): Boolean = moment.collapsed && !moment.mine && !open

    fun bind(b: ItemMomentCardBinding, moment: Moment, open: Boolean, images: MomentImages) {
        val context = b.root.context
        val author = moment.author
        val photoUrl = if (author.profileId != null && author.photo != null)
            images.personPhotoUrl(author.profileId, author.photo) else null
        b.avatar.set(author.name, author.handle ?: author.name, photoUrl, images.loader)
        b.name.text = author.name
        b.meta.text = meta(context, moment)
        b.kindTag.isVisible = moment.kind == MomentKind.WHOS_IN

        b.question.isVisible = moment.question != null
        moment.question?.let { b.question.text = context.getString(R.string.moment_question_line, it.text) }

        val covered = covered(moment, open)
        b.cover.isVisible = covered
        b.body.isVisible = !covered

        b.text.isVisible = moment.text != null
        b.text.text = moment.text.orEmpty()

        val photo = moment.photo
        val url = if (photo != null && !covered) images.photoUrl(moment.id) else null
        b.photoFrame.isVisible = photo != null
        if (photo != null) b.photo.ratio = photo.ratio
        if (url != null) {
            b.photo.load(url, images.loader) { crossfade(true) }
        } else {
            b.photo.dispose()
            b.photo.setImageDrawable(null)
        }

        val plan = moment.plan
        b.plan.isVisible = plan != null
        if (plan != null) {
            b.planTime.text = plan.at?.let { planTime(context, it) } ?: context.getString(R.string.moment_plan_time_open)
            b.planWhere.isVisible = plan.where != null
            b.planWhere.text = plan.where.orEmpty()
        }

        val notice = moment.notice
        b.notice.isVisible = notice != null
        if (notice != null) b.notice.setMessage(noticeText(context, notice))
        b.keptLine.isVisible = moment.mine && moment.kept

        b.content.contentDescription = spoken(context, moment, covered)
    }

    /** The author's notice in words: the sentence, with the reason's words where it names one (no marked phrases). */
    fun noticeText(context: Context, notice: com.safesphere.app.feature.moments.domain.MomentNotice): String =
        context.getString(MomentLabels.notice(notice), context.getString(MomentLabels.reason(notice.reason)), "").trim()

    /** "This morning · My people"; the author's waiting moment says that others cannot see it yet. */
    fun meta(context: Context, moment: Moment): String {
        val time = moment.shared?.let { context.getString(MomentLabels.shared(it)) }
            ?: context.getString(R.string.moment_when_waiting)
        return if (moment.audience == MomentAudience.MY_PEOPLE)
            context.getString(R.string.moment_meta_two, time, context.getString(R.string.moment_audience_my_people))
        else time
    }

    /** The plan's exact time in the reader's own zone and clock style (12 or 24 hours). */
    fun planTime(context: Context, at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val plan = MomentLabels.planTime(at, now, zone)
        val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault())
            .format(plan.time)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(plan.date)
        return context.getString(MomentLabels.planDay(plan.day), clock, date)
    }

    /** One sentence for TalkBack: who, when, what kind, and the words (or that it is covered). */
    fun spoken(context: Context, moment: Moment, covered: Boolean): String {
        val parts = ArrayList<String>()
        if (moment.kind == MomentKind.WHOS_IN) parts += context.getString(R.string.moment_kind_whos_in)
        if (covered) {
            parts += context.getString(R.string.moment_cover)
        } else {
            moment.text?.let { parts += it }
            if (moment.photo != null) parts += context.getString(R.string.moment_photo)
            moment.plan?.let { plan ->
                plan.at?.let { parts += planTime(context, it) }
                plan.where?.let { parts += it }
            }
        }
        moment.notice?.let { parts += noticeText(context, it) }
        return context.getString(R.string.cd_moment_card, moment.author.name + ", " + meta(context, moment),
            parts.joinToString(". "))
    }
}
