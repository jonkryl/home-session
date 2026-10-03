package com.jonkryl.homesession

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.jonkryl.homesession.ads.BannerController
import com.jonkryl.homesession.core.ExcludedTask
import com.jonkryl.homesession.core.ExclusionReason
import com.jonkryl.homesession.core.HomePlanner
import com.jonkryl.homesession.core.HomeRepository
import com.jonkryl.homesession.core.HomeTask
import com.jonkryl.homesession.core.PlanOutcome
import com.jonkryl.homesession.core.ReasonKind
import com.jonkryl.homesession.core.Room
import com.jonkryl.homesession.core.SessionItem
import com.jonkryl.homesession.core.TaskPriority
import com.jonkryl.homesession.core.TaskStatus
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A suggested plan stays read-only until Start; only individual done marks change history. */
class MainActivity : ComponentActivity() {
    private enum class Page { HOME, ROOMS, ROOM_EDITOR, TASKS, TASK_EDITOR, PLAN, SESSION, HISTORY }

    private lateinit var repository: HomeRepository
    private lateinit var banner: BannerController
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var page = Page.HOME
    private val navigationBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { navigateBack() }
    }
    private var editedRoomId: String? = null
    private var editedTaskId: String? = null
    private var budget = 15
    private var taskFilter: TaskStatus? = null
    private var chosenLastDone: LocalDate? = null
    private var restoredDraft: Bundle? = null
    private lateinit var roomNameInput: EditText
    private lateinit var taskTitleInput: EditText
    private lateinit var taskDurationInput: EditText
    private lateinit var taskIntervalInput: EditText
    private lateinit var taskRoomInput: Spinner
    private lateinit var taskPriorityInput: Spinner
    private lateinit var lastDoneButton: Button
    private lateinit var clearLastDoneButton: Button

    private val forest = Color.rgb(42, 70, 55)
    private val cream = Color.rgb(246, 242, 232)
    private val paper = Color.rgb(255, 254, 250)
    private val sage = Color.rgb(219, 229, 211)
    private val muted = Color.rgb(91, 102, 92)
    private val clay = Color.rgb(134, 94, 56)
    private val locale: Locale get() = resources.configuration.locales[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, navigationBack)
        try {
            repository = HomeRepository(this)
        } catch (_: Exception) {
            showLoadFailure()
            return
        }
        page = runCatching { Page.valueOf(savedInstanceState?.getString("page") ?: "HOME") }.getOrDefault(Page.HOME)
        editedRoomId = savedInstanceState?.getString("room_id")
        editedTaskId = savedInstanceState?.getString("task_id")
        budget = savedInstanceState?.getInt("budget", 15)?.takeIf { it in HomePlanner.BUDGETS } ?: 15
        taskFilter = savedInstanceState?.getString("filter")?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }
        restoredDraft = savedInstanceState?.getBundle("draft")
        banner = BannerController(this)
        val root = column().apply { setBackgroundColor(cream) }
        configureInsets(root)
        scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        content = column()
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // A separate labelled footer keeps advertising away from task controls and list content.
        root.addView(text(getString(R.string.advertisement), 11f, muted).apply {
            id = R.id.ad_label
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(4))
        }, matchWrap())
        val adHost = FrameLayout(this).apply {
            id = R.id.ad_container
            minimumHeight = dp(64)
            setBackgroundColor(cream)
        }
        root.addView(adHost, matchWrap())
        setContentView(root)
        render(true)
        banner.attach(adHost)
    }

    override fun onStart() {
        super.onStart()
        if (::banner.isInitialized) banner.onStart()
    }

    override fun onStop() {
        if (::banner.isInitialized) banner.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::banner.isInitialized) banner.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
        outState.putString("room_id", editedRoomId)
        outState.putString("task_id", editedTaskId)
        outState.putInt("budget", budget)
        outState.putString("filter", taskFilter?.name)
        if (page == Page.TASK_EDITOR && ::taskTitleInput.isInitialized) {
            outState.putBundle("draft", Bundle().apply {
                putString("title", taskTitleInput.text.toString())
                putString("duration", taskDurationInput.text.toString())
                putString("interval", taskIntervalInput.text.toString())
                putInt("room_index", taskRoomInput.selectedItemPosition)
                putInt("priority_index", taskPriorityInput.selectedItemPosition)
                putString("last_done", chosenLastDone?.toString())
            })
        } else if (page == Page.ROOM_EDITOR && ::roomNameInput.isInitialized) {
            outState.putBundle("draft", Bundle().apply { putString("room_name", roomNameInput.text.toString()) })
        }
    }

    private fun navigateBack() {
        navigate(when (page) {
            Page.ROOM_EDITOR -> Page.ROOMS
            Page.TASK_EDITOR -> Page.TASKS
            else -> Page.HOME
        })
    }

    @Suppress("DEPRECATION")
    private fun configureInsets(root: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun showLoadFailure() {
        val root = column().apply {
            setBackgroundColor(cream)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            addView(text(getString(R.string.load_failed), 20f, forest))
            addView(button(getString(R.string.retry)) { recreate() }, matchWrap(20))
        }
        setContentView(root)
    }

    private fun navigate(target: Page) {
        hideKeyboard()
        restoredDraft = null
        page = target
        render(true)
    }

    private fun render(resetScroll: Boolean = false) {
        val oldY = if (resetScroll) 0 else scroll.scrollY
        content.removeAllViews()
        if (!repository.state.onboardingComplete) {
            showWelcome()
        } else {
            if (page == Page.SESSION && repository.state.activeSession == null) page = Page.HOME
            when (page) {
                Page.HOME -> showHome()
                Page.ROOMS -> showRooms()
                Page.ROOM_EDITOR -> showRoomEditor()
                Page.TASKS -> showTasks()
                Page.TASK_EDITOR -> showTaskEditor()
                Page.PLAN -> showPlan()
                Page.SESSION -> showSession()
                Page.HISTORY -> showHistory()
            }
        }
        navigationBack.isEnabled = repository.state.onboardingComplete && page != Page.HOME
        scroll.post { scroll.scrollTo(0, oldY) }
    }

    private fun showWelcome() {
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
        title(getString(R.string.app_name), false)
        section(getString(R.string.welcome_title))
        body(getString(R.string.welcome_body))
        content.addView(button(getString(R.string.starter_templates), R.id.onboarding_templates, true) {
            commit({ repository.applyStarterTemplates(locale.language) }) { navigate(Page.HOME) }
        }, matchWrap(12))
        content.addView(button(getString(R.string.start_empty), R.id.onboarding_empty) {
            commit({ repository.initializeEmpty() }) { navigate(Page.HOME) }
        }, matchWrap(10))
        body(getString(R.string.offline_note))
    }

    private fun showHome() {
        title(getString(R.string.app_name), false)
        body(getString(R.string.tagline))
        val state = repository.state
        val due = state.tasks.count { HomePlanner.status(it, repository.today) == TaskStatus.OVERDUE }
        val soon = state.tasks.count { HomePlanner.status(it, repository.today) == TaskStatus.SOON }
        content.addView(text(getString(R.string.home_summary, due, soon), 17f, forest, true).apply {
            background = shape(sage)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }, matchWrap(8))
        state.activeSession?.let { session ->
            body(getString(R.string.resume_summary, session.completedCount, session.items.size, session.budgetMinutes))
            content.addView(button(getString(R.string.resume_session), R.id.home_resume, true) { navigate(Page.SESSION) }, matchWrap())
        }
        section(getString(R.string.choose_time))
        body(getString(R.string.choose_time_hint))
        listOf(5 to R.id.home_pick_5, 15 to R.id.home_pick_15, 30 to R.id.home_pick_30).forEach { (minutes, id) ->
            content.addView(button(getString(R.string.pick_minutes, minutes), id, minutes == 15) { chooseBudget(minutes) }, matchWrap(8))
        }
        if (state.tasks.isEmpty()) body(getString(R.string.home_empty))
        section(getString(R.string.my_tasks))
        content.addView(button(getString(R.string.my_tasks), R.id.home_tasks) { navigate(Page.TASKS) }, matchWrap(8))
        content.addView(button(getString(R.string.rooms), R.id.home_rooms) { navigate(Page.ROOMS) }, matchWrap(8))
        content.addView(button(getString(R.string.history), R.id.home_history) { navigate(Page.HISTORY) }, matchWrap(8))
        body(getString(R.string.offline_note))
        content.addView(button(getString(R.string.privacy_choice), R.id.privacy_choice) { banner.showPrivacyChoice() }, matchWrap(10))
        content.addView(button(getString(R.string.privacy_policy), R.id.privacy_policy) { banner.openPrivacyPolicy() }, matchWrap(8))
    }

    private fun showRooms() {
        title(getString(R.string.rooms))
        content.addView(button(getString(R.string.add_room), R.id.add_room, true) {
            editedRoomId = null
            navigate(Page.ROOM_EDITOR)
        }, matchWrap(12))
        val list = column().apply { id = R.id.rooms_list }
        content.addView(list, matchWrap(12))
        if (repository.state.rooms.isEmpty()) list.addView(text(getString(R.string.rooms_empty), 17f, muted))
        repository.state.rooms.forEach { room ->
            val card = card()
            card.addView(text(room.name, 22f, forest, true))
            card.addView(text(getString(R.string.room_task_count, repository.state.tasks.count { it.roomId == room.id }), 16f, muted), matchWrap(8))
            card.addView(button(getString(R.string.edit), R.id.room_edit).apply {
                contentDescription = getString(R.string.edit_room_accessibility, room.name)
                setOnClickListener { editedRoomId = room.id; navigate(Page.ROOM_EDITOR) }
            }, matchWrap(12))
            card.addView(button(getString(R.string.delete), R.id.room_delete).apply {
                contentDescription = getString(R.string.delete_room_accessibility, room.name)
                setOnClickListener {
                    confirm(R.string.delete_room_title, R.string.delete_room_body, R.string.delete) {
                        commit({ repository.deleteRoom(room.id) })
                    }
                }
            }, matchWrap(8))
            list.addView(card, matchWrap(12))
        }
    }

    private fun showRoomEditor() {
        val room = repository.state.rooms.firstOrNull { it.id == editedRoomId }
        title(getString(if (room == null) R.string.add_room else R.string.edit_room))
        label(getString(R.string.room_name), R.id.room_name)
        roomNameInput = input(R.id.room_name, restoredDraft?.getString("room_name") ?: room?.name.orEmpty(), 80)
        content.addView(roomNameInput, matchWrap(6))
        restoredDraft = null
        content.addView(button(getString(R.string.save_room), R.id.room_save, true) {
            val name = roomNameInput.text.toString().trim()
            if (name.isEmpty()) { roomNameInput.error = getString(R.string.required_name); return@button }
            commit({ repository.saveRoom(name, room?.id) }) { navigate(Page.ROOMS) }
        }, matchWrap(24))
    }

    private fun showTasks() {
        title(getString(R.string.my_tasks))
        content.addView(button(getString(R.string.add_task), R.id.add_task, true) {
            editedTaskId = null
            navigate(Page.TASK_EDITOR)
        }, matchWrap(12))
        val filters = listOf(
            Triple(R.string.filter_all, R.id.tasks_filter_all, null),
            Triple(R.string.filter_overdue, R.id.tasks_filter_overdue, TaskStatus.OVERDUE),
            Triple(R.string.filter_soon, R.id.tasks_filter_soon, TaskStatus.SOON),
            Triple(R.string.filter_fresh, R.id.tasks_filter_fresh, TaskStatus.FRESH),
        )
        // A vertical arrangement remains readable at 200% without clipped filter labels.
        filters.forEach { (stringId, viewId, filter) ->
            content.addView(button(getString(stringId), viewId, taskFilter == filter).apply {
                isSelected = taskFilter == filter
                setOnClickListener { taskFilter = filter; render(true) }
            }, matchWrap(8))
        }
        body(getString(R.string.filters_hint))
        val tasks = repository.state.tasks.filter { taskFilter == null || HomePlanner.status(it, repository.today) == taskFilter }
            .sortedWith(compareBy<HomeTask> { HomePlanner.status(it, repository.today).ordinal }.thenBy { it.title.lowercase(locale) })
        val list = column().apply { id = R.id.tasks_list }
        content.addView(list, matchWrap())
        if (tasks.isEmpty()) list.addView(text(getString(if (repository.state.tasks.isEmpty()) R.string.tasks_empty else R.string.filter_empty), 17f, muted))
        tasks.forEach { task ->
            val card = card()
            card.addView(text(task.title, 22f, forest, true))
            card.addView(text(roomName(task.roomId), 16f, muted), matchWrap(6))
            card.addView(text(getString(R.string.task_details, task.durationMinutes,
                resources.getQuantityString(R.plurals.repeat_interval, task.intervalDays, task.intervalDays)), 16f, muted), matchWrap(6))
            card.addView(text(statusText(task), 17f, if (HomePlanner.status(task, repository.today) == TaskStatus.OVERDUE) clay else forest, true), matchWrap(10))
            card.addView(text(getString(R.string.reason_priority, priorityName(task.priority)), 16f, muted), matchWrap(6))
            card.addView(text(getString(R.string.last_done_value, task.lastDone?.let(::dateText) ?: getString(R.string.never_done)), 16f, muted), matchWrap(6))
            card.addView(button(getString(R.string.edit), R.id.task_edit).apply {
                contentDescription = getString(R.string.edit_task_accessibility, task.title)
                setOnClickListener { editedTaskId = task.id; navigate(Page.TASK_EDITOR) }
            }, matchWrap(12))
            card.addView(button(getString(R.string.delete), R.id.task_delete).apply {
                contentDescription = getString(R.string.delete_task_accessibility, task.title)
                setOnClickListener {
                    confirm(R.string.delete_task_title, R.string.delete_task_body, R.string.delete) {
                        commit({ repository.deleteTask(task.id) })
                    }
                }
            }, matchWrap(8))
            list.addView(card, matchWrap(12))
        }
    }

    private fun showTaskEditor() {
        val task = repository.state.tasks.firstOrNull { it.id == editedTaskId }
        val draft = restoredDraft
        title(getString(if (task == null) R.string.add_task else R.string.edit_task))
        label(getString(R.string.task_title_label), R.id.task_title)
        taskTitleInput = input(R.id.task_title, draft?.getString("title") ?: task?.title.orEmpty(), 120)
        content.addView(taskTitleInput, matchWrap(6))
        label(getString(R.string.task_duration_label), R.id.task_duration)
        taskDurationInput = input(R.id.task_duration, draft?.getString("duration") ?: (task?.durationMinutes ?: 5).toString(), 3, true)
        content.addView(taskDurationInput, matchWrap(6))
        label(getString(R.string.task_interval_label), R.id.task_interval)
        taskIntervalInput = input(R.id.task_interval, draft?.getString("interval") ?: (task?.intervalDays ?: 7).toString(), 4, true)
        content.addView(taskIntervalInput, matchWrap(6))
        label(getString(R.string.task_room_label), R.id.task_room)
        val rooms = repository.state.rooms
        taskRoomInput = selector(R.id.task_room, listOf(getString(R.string.unassigned_room)) + rooms.map(Room::name))
        val roomIndex = draft?.getInt("room_index") ?: (rooms.indexOfFirst { it.id == task?.roomId } + 1)
        taskRoomInput.setSelection(roomIndex.coerceIn(0, rooms.size))
        content.addView(taskRoomInput, matchWrap(6))
        label(getString(R.string.task_priority_label), R.id.task_priority)
        taskPriorityInput = selector(R.id.task_priority, TaskPriority.values().map(::priorityName))
        taskPriorityInput.setSelection((draft?.getInt("priority_index") ?: (task?.priority ?: TaskPriority.NORMAL).ordinal).coerceIn(0, 2))
        content.addView(taskPriorityInput, matchWrap(6))
        label(getString(R.string.task_last_done_label), R.id.task_last_done)
        chosenLastDone = if (draft != null) draft.getString("last_done")?.let(LocalDate::parse) else task?.lastDone
        lastDoneButton = button("", R.id.task_last_done) { chooseCompletionDate() }
        clearLastDoneButton = button(getString(R.string.clear_last_done), R.id.task_clear_last_done) {
            chosenLastDone = null
            refreshDateButtons()
        }
        refreshDateButtons()
        content.addView(lastDoneButton, matchWrap(6))
        content.addView(clearLastDoneButton, matchWrap(8))
        body(getString(R.string.last_done_hint))
        if (repository.state.activeSession != null && task != null) body(getString(R.string.active_edit_hint))
        restoredDraft = null
        content.addView(button(getString(R.string.save_task), R.id.task_save, true) {
            val name = taskTitleInput.text.toString().trim()
            val duration = taskDurationInput.text.toString().toIntOrNull()
            val interval = taskIntervalInput.text.toString().toIntOrNull()
            when {
                name.isEmpty() -> { taskTitleInput.error = getString(R.string.required_name); taskTitleInput.requestFocus(); return@button }
                duration == null || duration !in 1..480 -> { taskDurationInput.error = getString(R.string.duration_error); taskDurationInput.requestFocus(); return@button }
                interval == null || interval !in 1..3650 -> { taskIntervalInput.error = getString(R.string.interval_error); taskIntervalInput.requestFocus(); return@button }
            }
            val roomId = rooms.getOrNull(taskRoomInput.selectedItemPosition - 1)?.id
            val priority = TaskPriority.values()[taskPriorityInput.selectedItemPosition]
            commit({
                if (task == null) repository.newTask(name, duration!!, interval!!, roomId, chosenLastDone, priority)
                else repository.saveTask(task.copy(title = name, durationMinutes = duration!!, intervalDays = interval!!,
                    roomId = roomId, lastDone = chosenLastDone, priority = priority))
            }) { navigate(Page.TASKS) }
        }, matchWrap(20))
    }

    private fun chooseCompletionDate() {
        val selected = chosenLastDone ?: repository.today
        val dialog = DatePickerDialog(this, { _, year, month, day ->
            chosenLastDone = LocalDate.of(year, month + 1, day)
            refreshDateButtons()
        }, selected.year, selected.monthValue - 1, selected.dayOfMonth)
        dialog.datePicker.minDate = Calendar.getInstance().apply {
            clear(); set(1900, Calendar.JANUARY, 1)
        }.timeInMillis
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun refreshDateButtons() {
        lastDoneButton.text = getString(R.string.choose_last_done, chosenLastDone?.let(::dateText) ?: getString(R.string.never_done))
        clearLastDoneButton.visibility = if (chosenLastDone == null) View.GONE else View.VISIBLE
    }

    private fun chooseBudget(minutes: Int) {
        if (repository.state.activeSession == null) {
            budget = minutes
            navigate(Page.PLAN)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.active_session_title)
                .setMessage(R.string.active_session_body)
                .setPositiveButton(R.string.resume_session) { _, _ -> navigate(Page.SESSION) }
                .setNeutralButton(R.string.close_and_pick) { _, _ ->
                    commit({ repository.cancelSession() }) { budget = minutes; navigate(Page.PLAN) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showPlan() {
        title(getString(R.string.session_preview))
        val plan = repository.planSession(budget)
        content.addView(text(getString(R.string.plan_total, plan.totalMinutes, plan.budgetMinutes), 22f, forest, true).apply {
            id = R.id.plan_total
            background = shape(sage)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }, matchWrap(12))
        body(getString(R.string.planner_explanation))
        if (plan.outcome == PlanOutcome.READY) {
            plan.items.forEach { item -> content.addView(sessionCard(item, false), matchWrap(12)) }
            body(getString(R.string.plan_read_only))
            content.addView(button(getString(R.string.start_session), R.id.plan_start, true) {
                commit({ repository.startSession(budget) }) {
                    navigate(if (repository.state.activeSession == null) Page.PLAN else Page.SESSION)
                }
            }, matchWrap(10))
        } else {
            val isNoDue = plan.outcome == PlanOutcome.NO_DUE_TASKS
            val empty = card().apply { id = R.id.plan_empty }
            empty.addView(text(getString(if (isNoDue) R.string.no_due_title else R.string.no_fit_title), 22f, forest, true))
            empty.addView(text(getString(if (isNoDue) R.string.no_due_body else R.string.no_fit_body), 17f, muted), matchWrap(10))
            content.addView(empty, matchWrap(12))
            content.addView(button(getString(R.string.my_tasks), R.id.home_tasks) { navigate(Page.TASKS) }, matchWrap(12))
            listOf(5 to R.id.home_pick_5, 15 to R.id.home_pick_15, 30 to R.id.home_pick_30).filter { it.first != budget }.forEach { (minutes, id) ->
                content.addView(button(getString(R.string.pick_minutes, minutes), id) { chooseBudget(minutes) }, matchWrap(8))
            }
        }
        showExclusions(plan.excluded)
    }

    private fun showSession() {
        val session = repository.state.activeSession ?: return
        title(getString(R.string.session_title))
        content.addView(text(getString(R.string.session_progress, session.completedCount, session.items.size), 23f, forest, true).apply {
            id = R.id.session_progress
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, matchWrap(12))
        body(getString(R.string.plan_total, session.totalMinutes, session.budgetMinutes))
        body(getString(R.string.session_hint))
        val list = column().apply { id = R.id.session_list }
        content.addView(list, matchWrap())
        if (session.items.isEmpty()) list.addView(text(getString(R.string.session_no_items), 18f, muted))
        session.items.forEach { item -> list.addView(sessionCard(item, true), matchWrap(12)) }
        body(getString(R.string.finish_hint))
        content.addView(button(getString(R.string.finish_session), R.id.session_finish, true) {
            commit({ repository.finishSession() }) { navigate(Page.HOME) }
        }, matchWrap(12))
        content.addView(button(getString(R.string.pause_session), R.id.session_pause) { navigate(Page.HOME) }, matchWrap(8))
        content.addView(button(getString(R.string.cancel_session), R.id.session_cancel) {
            confirm(R.string.cancel_session_title, R.string.cancel_session_body, R.string.close) {
                commit({ repository.cancelSession() }) { navigate(Page.HOME) }
            }
        }, matchWrap(8))
        showExclusions(session.excluded)
    }

    private fun sessionCard(item: SessionItem, interactive: Boolean): LinearLayout {
        val card = card(if (item.isCompleted) sage else paper)
        card.addView(text(item.title, 22f, forest, true))
        card.addView(text(item.roomName ?: getString(R.string.unassigned_room), 16f, muted), matchWrap(6))
        card.addView(text(getString(R.string.task_minutes, item.durationMinutes), 16f, muted), matchWrap(6))
        card.addView(text(when (item.reason.kind) {
            ReasonKind.NEVER_DONE -> getString(R.string.reason_never)
            ReasonKind.DUE_TODAY -> getString(R.string.reason_today)
            ReasonKind.OVERDUE -> resources.getQuantityString(R.plurals.reason_overdue,
                item.reason.daysOverdue.toInt(), item.reason.daysOverdue)
        }, 17f, forest), matchWrap(10))
        card.addView(text(getString(R.string.reason_priority, priorityName(item.priority)), 16f, muted), matchWrap(6))
        if (interactive) {
            if (item.isCompleted) {
                repository.state.history.firstOrNull { it.id == item.completionId }?.let { completed ->
                    card.addView(text(getString(R.string.completed_at, timestampText(completed.completedAt)), 16f, forest, true), matchWrap(10))
                }
                card.addView(button(getString(R.string.undo_done), R.id.session_undo).apply {
                    contentDescription = getString(R.string.undo_accessibility, item.title)
                    setOnClickListener { commit({ repository.undoDone(item.taskId) }) }
                }, matchWrap(12))
            } else {
                card.addView(button(getString(R.string.mark_done), R.id.session_complete, true).apply {
                    contentDescription = getString(R.string.complete_accessibility, item.title)
                    setOnClickListener { commit({ repository.markDone(item.taskId) }) }
                }, matchWrap(12))
            }
        }
        return card
    }

    private fun showExclusions(excluded: List<ExcludedTask>) {
        if (excluded.isEmpty()) return
        section(getString(R.string.excluded_title))
        val list = column().apply { id = R.id.plan_excluded }
        excluded.forEach { item ->
            val card = card()
            card.addView(text(item.title, 20f, forest, true))
            card.addView(text(getString(when (item.reason) {
                ExclusionReason.TOO_LONG -> R.string.excluded_too_long
                ExclusionReason.BUDGET -> R.string.excluded_budget
                ExclusionReason.TASK_UPDATED -> R.string.excluded_updated
            }, item.durationMinutes), 16f, muted), matchWrap(8))
            list.addView(card, matchWrap(10))
        }
        content.addView(list, matchWrap())
    }

    private fun showHistory() {
        title(getString(R.string.history))
        body(getString(R.string.history_hint))
        val list = column().apply { id = R.id.history_list }
        content.addView(list, matchWrap(8))
        if (repository.state.history.isEmpty()) list.addView(text(getString(R.string.history_empty), 17f, muted))
        repository.state.history.sortedByDescending { it.completedAt }.forEach { completion ->
            val card = card()
            card.addView(text(completion.taskTitle, 22f, forest, true))
            card.addView(text(completion.roomName ?: getString(R.string.unassigned_room), 16f, muted), matchWrap(6))
            card.addView(text(timestampText(completion.completedAt), 17f, forest), matchWrap(10))
            list.addView(card, matchWrap(12))
        }
    }

    private fun statusText(task: HomeTask): String {
        val days = HomePlanner.daysUntilDue(task, repository.today) ?: return getString(R.string.status_never)
        return when {
            days == 0L -> getString(R.string.status_today)
            days < 0 -> resources.getQuantityString(R.plurals.status_overdue, (-days).toInt(), -days)
            else -> resources.getQuantityString(R.plurals.status_in_days, days.toInt(), days)
        }
    }

    private fun roomName(roomId: String?): String = repository.state.rooms.firstOrNull { it.id == roomId }?.name ?: getString(R.string.unassigned_room)
    private fun priorityName(priority: TaskPriority): String = getString(when (priority) {
        TaskPriority.LOW -> R.string.priority_low
        TaskPriority.NORMAL -> R.string.priority_normal
        TaskPriority.HIGH -> R.string.priority_high
    })
    private fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    private fun timestampText(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis))

    private fun commit(action: () -> Unit, after: () -> Unit = { render() }) {
        try {
            action()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
            return
        }
        after()
    }

    private fun confirm(title: Int, body: Int, affirmative: Int, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body)
            .setPositiveButton(affirmative) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun title(value: String, back: Boolean = true) {
        if (back) content.addView(button(getString(R.string.back), R.id.toolbar_back) { onBackPressedDispatcher.onBackPressed() }, matchWrap())
        content.addView(text(value, 29f, forest, true).apply {
            id = R.id.screen_title
            accessibilityHeadingIfSupported()
        }, matchWrap(if (back) 18 else 8))
    }

    private fun View.accessibilityHeadingIfSupported() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun section(value: String) {
        content.addView(text(value, 22f, forest, true).apply { accessibilityHeadingIfSupported() }, matchWrap(24))
    }
    private fun body(value: String) { content.addView(text(value, 17f, muted), matchWrap(12)) }
    private fun label(value: String, target: Int) {
        content.addView(text(value, 17f, forest, true).apply { labelFor = target }, matchWrap(20))
    }
    private fun card(color: Int = paper): LinearLayout = column().apply {
        background = shape(color)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun button(value: String, viewId: Int = View.NO_ID, primary: Boolean = false, action: (() -> Unit)? = null) = Button(this).apply {
        id = viewId
        text = value
        textSize = 17f
        isAllCaps = false
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setTextColor(if (primary) paper else forest)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33ffffff else 0x222a4637), shape(if (primary) forest else paper), null)
        if (action != null) setOnClickListener { action() }
    }
    private fun input(viewId: Int, value: String, maxLength: Int, numeric: Boolean = false) = EditText(this).apply {
        id = viewId
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setText(value)
        textSize = 18f
        setTextColor(forest)
        setSingleLine(numeric)
        minHeight = dp(56)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
    }
    private fun selector(viewId: Int, labels: List<String>) = Spinner(this).apply {
        id = viewId
        minimumHeight = dp(56)
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply { formatSpinnerText(this) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).apply { formatSpinnerText(this) }
            private fun formatSpinnerText(view: View) {
                (view as? TextView)?.apply {
                    textSize = 18f
                    setTextColor(forest)
                    setSingleLine(false)
                    minHeight = dp(56)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
    private fun hideKeyboard() {
        currentFocus?.let { view -> (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0) }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
