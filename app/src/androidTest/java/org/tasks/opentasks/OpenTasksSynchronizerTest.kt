package org.tasks.opentasks

import com.google.ical.values.RRule
import com.natpryce.makeiteasy.MakeItEasy.with
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.tasks.R
import org.tasks.TestUtilities.withTZ
import org.tasks.caldav.iCalendar.Companion.getParent
import org.tasks.data.CaldavAccount
import org.tasks.data.CaldavAccount.Companion.TYPE_OPENTASKS
import org.tasks.data.CaldavCalendar
import org.tasks.data.CaldavDao
import org.tasks.data.TaskDao
import org.tasks.injection.InjectingTestCase
import org.tasks.injection.ProductionModule
import org.tasks.makers.CaldavTaskMaker.CALENDAR
import org.tasks.makers.CaldavTaskMaker.REMOTE_ID
import org.tasks.makers.CaldavTaskMaker.REMOTE_PARENT
import org.tasks.makers.CaldavTaskMaker.TASK
import org.tasks.makers.CaldavTaskMaker.newCaldavTask
import org.tasks.makers.TaskMaker
import org.tasks.makers.TaskMaker.PARENT
import org.tasks.makers.TaskMaker.RRULE
import org.tasks.makers.TaskMaker.newTask
import org.tasks.preferences.Preferences
import org.tasks.time.DateTime
import java.util.*
import javax.inject.Inject

@UninstallModules(ProductionModule::class)
@HiltAndroidTest
class OpenTasksSynchronizerTest : InjectingTestCase() {
    @Inject lateinit var openTaskDao: TestOpenTaskDao
    @Inject lateinit var caldavDao: CaldavDao
    @Inject lateinit var synchronizer: OpenTasksSynchronizer
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var taskDao: TaskDao

    @Before
    override fun setUp() {
        super.setUp()

        openTaskDao.reset()
        preferences.setBoolean(R.string.p_debug_pro, true)
    }

    @Test
    fun createNewAccounts() = runBlocking {
        openTaskDao.insertList()

        synchronizer.sync()

        val accounts = caldavDao.getAccounts()
        assertEquals(1, accounts.size)
        with(accounts[0]) {
            assertEquals("bitfire.at.davdroid:test_account", uuid)
            assertEquals("test_account", name)
            assertEquals(TYPE_OPENTASKS, accountType)
        }
    }

    @Test
    fun cantSyncWithoutPro() = runBlocking {
        preferences.setBoolean(R.string.p_debug_pro, false)
        openTaskDao.insertList()

        synchronizer.sync()

        assertEquals(
                context.getString(R.string.requires_pro_subscription),
                caldavDao.getAccounts()[0].error
        )
    }

    @Test
    fun deleteRemovedAccounts() = runBlocking {
        caldavDao.insert(CaldavAccount().apply {
            uuid = "bitfire.at.davdroid:test_account"
            accountType = TYPE_OPENTASKS
        })

        synchronizer.sync()

        assertTrue(caldavDao.getAccounts().isEmpty())
    }

    @Test
    fun createNewLists() = runBlocking {
        openTaskDao.insertList()

        synchronizer.sync()

        val lists = caldavDao.getCalendarsByAccount("bitfire.at.davdroid:test_account")
        assertEquals(1, lists.size)
        with(lists[0]) {
            assertEquals(name, "default_list")
        }
    }

    @Test
    fun removeMissingLists() = runBlocking {
        val (_, list) = openTaskDao.insertList(url = "url1")
        caldavDao.insert(CaldavCalendar().apply {
            account = list.account
            url = "url2"
        })

        synchronizer.sync()

        assertEquals(listOf(list), caldavDao.getCalendars())
    }

    @Test
    fun simplePushNewTask() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        val taskId = taskDao.createNew(newTask())
        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(REMOTE_ID, "1234"),
                with(TASK, taskId)
        ))

        synchronizer.sync()

        assertNotNull(openTaskDao.getTask(listId.toLong(), "1234"))
    }

    @Test
    fun sanitizeRecurrenceRule() = runBlocking {
        val (_, list) = openTaskDao.insertList()
        val taskId = taskDao.insert(newTask(with(RRULE, RRule("RRULE:FREQ=WEEKLY;COUNT=-1"))))
        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(TASK, taskId)
        ))

        synchronizer.sync()

        val task = openTaskDao.getTasks().first()
        assertEquals("FREQ=WEEKLY", task.rRule?.value)
    }

    @Test
    fun loadRemoteParentInfo() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        openTaskDao.insertTask(listId, SUBTASK)

        synchronizer.sync()

        val task = caldavDao.getTaskByRemoteId(list.uuid!!, "dfede1b0-435b-4bba-9708-2422e781747c")
        assertEquals("7daa4a5c-cc76-4ddf-b4f8-b9d3a9cb00e7", task?.remoteParent)
    }

    @Test
    fun pushParentInfo() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        val taskId = taskDao.createNew(newTask(with(PARENT, 594)))

        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(TASK, taskId),
                with(REMOTE_ID, "abcd"),
                with(REMOTE_PARENT, "1234")
        ))

        synchronizer.sync()

        assertEquals("1234", openTaskDao.getTask(listId.toLong(), "abcd")?.task?.getParent())
    }

    @Test
    fun readDueDatePositiveOffset() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        openTaskDao.insertTask(listId, ALL_DAY_DUE)

        withTZ(BERLIN) {
            synchronizer.sync()
        }

        val caldavTask = caldavDao.getTaskByRemoteId(list.uuid!!, "3863299529704302692")
        val task = taskDao.fetch(caldavTask!!.task)
        assertEquals(
                DateTime(2021, 2, 1, 12, 0, 0, 0, BERLIN).millis,
                task?.dueDate
        )
    }

    @Test
    fun writeDueDatePositiveOffset() = withTZ(BERLIN) {
        val (listId, list) = openTaskDao.insertList()
        val taskId = taskDao.createNew(newTask(
                with(TaskMaker.DUE_DATE, DateTime(2021, 2, 1))
        ))
        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(REMOTE_ID, "1234"),
                with(TASK, taskId)
        ))

        synchronizer.sync()

        assertEquals(
                1612137600000,
                openTaskDao.getTask(listId.toLong(), "1234")?.task?.due?.date?.time
        )
    }

    @Test
    fun readDueDateNoOffset() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        openTaskDao.insertTask(listId, ALL_DAY_DUE)

        withTZ(LONDON) {
            synchronizer.sync()
        }

        val caldavTask = caldavDao.getTaskByRemoteId(list.uuid!!, "3863299529704302692")
        val task = taskDao.fetch(caldavTask!!.task)
        assertEquals(
                DateTime(2021, 2, 1, 12, 0, 0, 0, LONDON).millis,
                task?.dueDate
        )
    }

    @Test
    fun writeDueDateNoOffset() = withTZ(LONDON) {
        val (listId, list) = openTaskDao.insertList()
        val taskId = taskDao.createNew(newTask(
                with(TaskMaker.DUE_DATE, DateTime(2021, 2, 1))
        ))
        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(REMOTE_ID, "1234"),
                with(TASK, taskId)
        ))

        synchronizer.sync()

        assertEquals(
                1612137600000,
                openTaskDao.getTask(listId.toLong(), "1234")?.task?.due?.date?.time
        )
    }

    @Test
    fun readDueDateNegativeOffset() = runBlocking {
        val (listId, list) = openTaskDao.insertList()
        openTaskDao.insertTask(listId, ALL_DAY_DUE)

        withTZ(NEW_YORK) {
            synchronizer.sync()
        }

        val caldavTask = caldavDao.getTaskByRemoteId(list.uuid!!, "3863299529704302692")
        val task = taskDao.fetch(caldavTask!!.task)
        assertEquals(
                DateTime(2021, 2, 1, 12, 0, 0, 0, NEW_YORK).millis,
                task?.dueDate
        )
    }

    @Test
    fun writeDueDateNegativeOffset() = withTZ(NEW_YORK) {
        val (listId, list) = openTaskDao.insertList()
        val taskId = taskDao.createNew(newTask(
                with(TaskMaker.DUE_DATE, DateTime(2021, 2, 1))
        ))
        caldavDao.insert(newCaldavTask(
                with(CALENDAR, list.uuid),
                with(REMOTE_ID, "1234"),
                with(TASK, taskId)
        ))

        synchronizer.sync()

        assertEquals(
                1612137600000,
                openTaskDao.getTask(listId.toLong(), "1234")?.task?.due?.date?.time
        )
    }

    companion object {
        val BERLIN = TimeZone.getTimeZone("Europe/Berlin")
        val LONDON = TimeZone.getTimeZone("Europe/London")
        val NEW_YORK = TimeZone.getTimeZone("America/New_York")
        val SUBTASK = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud Tasks v0.13.6
            BEGIN:VTODO
            UID:dfede1b0-435b-4bba-9708-2422e781747c
            CREATED:20210128T150333
            LAST-MODIFIED:20210128T150338
            DTSTAMP:20210128T150338
            SUMMARY:Child
            RELATED-TO:7daa4a5c-cc76-4ddf-b4f8-b9d3a9cb00e7
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val ALL_DAY_DUE = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:+//IDN tasks.org//android-110304//EN
            BEGIN:VTODO
            DTSTAMP:20210129T155402Z
            UID:3863299529704302692
            CREATED:20210129T155318Z
            LAST-MODIFIED:20210129T155329Z
            SUMMARY:Due date
            DUE;VALUE=DATE:20210201
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
    }
}