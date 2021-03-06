package org.stepic.droid.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.support.annotation.DrawableRes
import android.support.annotation.WorkerThread
import android.support.v4.app.NotificationCompat
import android.support.v4.app.TaskStackBuilder
import com.bumptech.glide.Glide
import org.stepic.droid.R
import org.stepic.droid.analytic.Analytic
import org.stepic.droid.configuration.Config
import org.stepic.droid.core.ScreenManager
import org.stepic.droid.model.Course
import org.stepic.droid.notifications.model.Notification
import org.stepic.droid.notifications.model.NotificationType
import org.stepic.droid.preferences.SharedPreferenceHelper
import org.stepic.droid.preferences.UserPreferences
import org.stepic.droid.store.operations.DatabaseFacade
import org.stepic.droid.store.operations.Table
import org.stepic.droid.ui.activities.MainFeedActivity
import org.stepic.droid.ui.activities.ProfileActivity
import org.stepic.droid.ui.activities.SectionActivity
import org.stepic.droid.ui.activities.StepsActivity
import org.stepic.droid.util.AppConstants
import org.stepic.droid.util.ColorUtil
import org.stepic.droid.util.HtmlHelper
import org.stepic.droid.util.StepikUtil
import org.stepic.droid.util.resolvers.text.TextResolver
import org.stepic.droid.web.Api
import timber.log.Timber
import java.util.*
import java.util.concurrent.ThreadPoolExecutor


class NotificationManagerImpl(val sharedPreferenceHelper: SharedPreferenceHelper,
                              val api: Api,
                              val configs: Config,
                              val userPreferences: UserPreferences,
                              val databaseFacade: DatabaseFacade,
                              val analytic: Analytic,
                              val textResolver: TextResolver,
                              val screenManager: ScreenManager,
                              val threadPoolExecutor: ThreadPoolExecutor,
                              val context: Context,
                              val localReminder: LocalReminder) : INotificationManager {
    val notificationStreakId: Long = 3214L

    @WorkerThread
    override fun showLocalNotificationRemind() {
        Timber.d("Learn everyday, free courses")
        if (sharedPreferenceHelper.authResponseFromStore == null ||
                databaseFacade.getAllCourses(Table.enrolled).isNotEmpty() ||
                sharedPreferenceHelper.anyStepIsSolved() || sharedPreferenceHelper.isStreakNotificationEnabled) {
            analytic.reportEvent(Analytic.Notification.REMIND_HIDDEN)
            return
        }
        val dayType = if (!sharedPreferenceHelper.isNotificationWasShown(SharedPreferenceHelper.NotificationDay.DAY_ONE)) {
            SharedPreferenceHelper.NotificationDay.DAY_ONE
        } else if (!sharedPreferenceHelper.isNotificationWasShown(SharedPreferenceHelper.NotificationDay.DAY_SEVEN)) {
            SharedPreferenceHelper.NotificationDay.DAY_SEVEN
        } else {
            null
        }

        val deleteIntent = Intent(context, NotificationBroadcastReceiver::class.java)
        deleteIntent.action = AppConstants.NOTIFICATION_CANCELED_REMINDER
        val deletePendingIntent = PendingIntent.getBroadcast(context, 0, deleteIntent, PendingIntent.FLAG_CANCEL_CURRENT)

        //now we can show notification
        val intent = screenManager.getShowFindCoursesIntent(context)
        intent.action = AppConstants.OPEN_NOTIFICATION_FOR_ENROLL_REMINDER
        val analyticDayTypeName = dayType?.name ?: ""
        intent.putExtra(MainFeedActivity.REMINDER_KEY, analyticDayTypeName)
        val taskBuilder: TaskStackBuilder =
                TaskStackBuilder
                        .create(context)
                        .addNextIntent(intent)
        val title = context.resources.getString(R.string.stepik_free_courses_title)
        val remindMessage = context.resources.getString(R.string.local_remind_message)
        showSimpleNotification(id = 4,
                justText = remindMessage,
                taskBuilder = taskBuilder,
                title = title,
                deleteIntent = deletePendingIntent)

        if (!sharedPreferenceHelper.isNotificationWasShown(SharedPreferenceHelper.NotificationDay.DAY_ONE)) {
            afterLocalNotificationShown(SharedPreferenceHelper.NotificationDay.DAY_ONE)
        } else if (!sharedPreferenceHelper.isNotificationWasShown(SharedPreferenceHelper.NotificationDay.DAY_SEVEN)) {
            afterLocalNotificationShown(SharedPreferenceHelper.NotificationDay.DAY_SEVEN)
        }
        localReminder.remindAboutApp() // schedule for next time
    }

    @WorkerThread
    override fun showStreakRemind() {
        if (sharedPreferenceHelper.isStreakNotificationEnabled) {
            localReminder.userChangeStateOfNotification() //plan new alarm at next day

            val numberOfStreakNotifications = sharedPreferenceHelper.numberOfStreakNotifications
            if (numberOfStreakNotifications < AppConstants.MAX_NUMBER_OF_NOTIFICATION_STREAK) {
                try {
                    val pins: ArrayList<Long> = api.getUserActivities(sharedPreferenceHelper.profile?.id ?: throw Exception("User is not auth"))
                            .execute()
                            ?.body()
                            ?.userActivities
                            ?.firstOrNull()
                            ?.pins!!
                    val (currentStreak, isSolvedToday) = StepikUtil.getCurrentStreakExtended(pins)
                    if (currentStreak <= 0) {
                        analytic.reportEvent(Analytic.Streak.GET_ZERO_STREAK_NOTIFICATION)
                        showNotificationWithoutStreakInfo()
                    } else {
                        analytic.reportEvent(Analytic.Streak.GET_NON_ZERO_STREAK_NOTIFICATION)
                        if (isSolvedToday) {
                            showNotificationStreakImprovement(currentStreak)
                        } else {
                            showNotificationWithStreakCallToAction(currentStreak)
                        }
                    }
                } catch (exception: Exception) {
                    // no internet || cant get streaks -> show some notification without streak information.
                    analytic.reportEvent(Analytic.Streak.GET_NO_INTERNET_NOTIFICATION)
                    showNotificationWithoutStreakInfo()
                    return
                } finally {
                    sharedPreferenceHelper.incrementNumberOfNotifications()
                }
            } else {
                //too many ignored notifications about streaks
                streakNotificationNumberIsOverflow()
            }
        }
    }

    private fun streakNotificationNumberIsOverflow() {
        sharedPreferenceHelper.isStreakNotificationEnabled = false
        val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
        val profileIntent = screenManager.getProfileIntent(context)
        taskBuilder.addParentStack(ProfileActivity::class.java)
        taskBuilder.addNextIntent(profileIntent)
        val message = context.getString(R.string.streak_notification_not_working)
        showSimpleNotification(id = notificationStreakId,
                justText = message,
                taskBuilder = taskBuilder,
                title = context.getString(R.string.time_to_learn_notification_title))
    }

    private fun getDeleteIntentForStreaks(): PendingIntent {
        val deleteIntent = Intent(context, NotificationBroadcastReceiver::class.java)
        deleteIntent.action = AppConstants.NOTIFICATION_CANCELED_STREAK
        val deletePendingIntent = PendingIntent.getBroadcast(context, 0, deleteIntent, PendingIntent.FLAG_CANCEL_CURRENT)
        return deletePendingIntent
    }

    private fun showNotificationStreakImprovement(currentStreak: Int) {
        val message = context.resources.getString(R.string.streak_notification_message_improvement, currentStreak)
        showNotificationStreakBase(message)
    }

    private fun showNotificationWithStreakCallToAction(currentStreak: Int) {
        val message = context.resources.getQuantityString(R.plurals.streak_notification_message_call_to_action, currentStreak, currentStreak)
        showNotificationStreakBase(message)
    }

    private fun showNotificationWithoutStreakInfo() {
        val message = context.resources.getString(R.string.streak_notification_empty_number)
        showNotificationStreakBase(message)
    }

    private fun showNotificationStreakBase(message: String) {
        val taskBuilder: TaskStackBuilder = getStreakNotificationTaskBuilder()
        showSimpleNotification(id = notificationStreakId,
                justText = message,
                taskBuilder = taskBuilder,
                title = context.getString(R.string.time_to_learn_notification_title),
                deleteIntent = getDeleteIntentForStreaks())
    }

    private fun getStreakNotificationTaskBuilder(): TaskStackBuilder {
        val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
        val myCoursesIntent = screenManager.getMyCoursesIntent(context)
        myCoursesIntent.action = AppConstants.OPEN_NOTIFICATION_FROM_STREAK
        taskBuilder.addNextIntent(myCoursesIntent)
        return taskBuilder
    }

    private fun afterLocalNotificationShown(day: SharedPreferenceHelper.NotificationDay) {
        analytic.reportEvent(Analytic.Notification.REMIND_SHOWN, day.name)
        sharedPreferenceHelper.setNotificationShown(day)
    }


    override fun showNotification(notification: Notification) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw RuntimeException("Can't create notification on main thread")
        }

        if (!userPreferences.isNotificationEnabled(notification.type)) {
            analytic.reportEventWithName(Analytic.Notification.DISABLED_BY_USER, notification.type?.name)
        } else if (!sharedPreferenceHelper.isGcmTokenOk) {
            analytic.reportEvent(Analytic.Notification.GCM_TOKEN_NOT_OK)
        } else {
            resolveAndSendNotification(notification)
        }
    }

    private fun resolveAndSendNotification(notification: Notification) {
        val htmlText = notification.htmlText

        if (!NotificationHelper.isNotificationValidByAction(notification)) {
            analytic.reportEventWithIdName(Analytic.Notification.ACTION_NOT_SUPPORT, notification.id.toString(), notification.action ?: "")
            return
        } else if (htmlText == null || htmlText.isEmpty()) {
            analytic.reportEvent(Analytic.Notification.HTML_WAS_NULL, notification.id.toString())
            return
        } else if (notification.isMuted ?: false) {
            analytic.reportEvent(Analytic.Notification.WAS_MUTED, notification.id.toString())
            return
        } else {
            //resolve which notification we should show
            when (notification.type) {
                NotificationType.learn -> sendLearnNotification(notification, htmlText, notification.id ?: 0)
                NotificationType.comments -> sendCommentNotification(notification, htmlText, notification.id ?: 0)
                NotificationType.review -> sendReviewType(notification, htmlText, notification.id ?: 0)
                NotificationType.other -> sendDefaultNotification(notification, htmlText, notification.id ?: 0)
                NotificationType.teach -> sendTeachNotification(notification, htmlText, notification.id ?: 0)
                else -> analytic.reportEventWithIdName(Analytic.Notification.NOT_SUPPORT_TYPE, notification.id.toString(), notification.type.toString()) // it should never execute, because we handle it by action filter
            }
        }
    }

    private fun sendTeachNotification(stepikNotification: Notification, htmlText: String, id: Long) {
        val title = context.getString(R.string.teaching_title)
        val justText: String = textResolver.fromHtml(htmlText).toString()

        val intent = getTeachIntent(notification = stepikNotification)
        if (intent == null) {
            analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
            return
        }
        intent.action = AppConstants.OPEN_NOTIFICATION

        val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
        taskBuilder.addParentStack(SectionActivity::class.java)
        taskBuilder.addNextIntent(intent)

        analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
        showSimpleNotification(id, justText, taskBuilder, title)
    }

    private fun sendDefaultNotification(stepikNotification: Notification, htmlText: String, id: Long) {
        val action = stepikNotification.action
        if (action != null && action == NotificationHelper.ADDED_TO_GROUP) {
            val title = context.getString(R.string.added_to_group_title)
            val justText: String = textResolver.fromHtml(htmlText).toString()

            val intent = getDefaultIntent(notification = stepikNotification)
            if (intent == null) {
                analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
                return
            }
            intent.action = AppConstants.OPEN_NOTIFICATION

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addParentStack(SectionActivity::class.java)
            taskBuilder.addNextIntent(intent)

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
            showSimpleNotification(id, justText, taskBuilder, title)
        } else {
            analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
        }
    }

    private fun sendReviewType(stepikNotification: Notification, htmlText: String, id: Long) {
        // here is supportable action, but we need identify it
        val action = stepikNotification.action
        if (action != null && action == NotificationHelper.REVIEW_TAKEN) {
            val title = context.getString(R.string.received_review_title)
            val justText: String = textResolver.fromHtml(htmlText).toString()

            val intent = getReviewIntent(notification = stepikNotification)
            if (intent == null) {
                analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, stepikNotification.id.toString())
                return
            }

            intent.action = AppConstants.OPEN_NOTIFICATION

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addParentStack(StepsActivity::class.java)
            taskBuilder.addNextIntent(intent)

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
            showSimpleNotification(id, justText, taskBuilder, title)
        } else {
            analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
        }
    }

    private fun sendCommentNotification(stepikNotification: Notification, htmlText: String, id: Long) {
        val action = stepikNotification.action
        if (action != null && (action == NotificationHelper.REPLIED || action == NotificationHelper.COMMENTED)) {
            val title = context.getString(R.string.new_message_title)
            val justText: String = textResolver.fromHtml(htmlText).toString()

            val intent = getCommentIntent(stepikNotification)
            if (intent == null) {
                analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
                return
            }
            intent.action = AppConstants.OPEN_NOTIFICATION

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addParentStack(StepsActivity::class.java)
            taskBuilder.addNextIntent(intent)

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
            showSimpleNotification(id, justText, taskBuilder, title)
        } else {
            analytic.reportEvent(Analytic.Notification.CANT_PARSE_NOTIFICATION, id.toString())
        }
    }

    private fun sendLearnNotification(stepikNotification: Notification, rawMessageHtml: String, id: Long) {
        val action = stepikNotification.action
        if (action != null && action == NotificationHelper.ISSUED_CERTIFICATE) {
            val title = context.getString(R.string.get_certifcate_title)
            val justText: String = textResolver.fromHtml(rawMessageHtml).toString()

            val intent = screenManager.certificateIntent
            intent.action = AppConstants.OPEN_NOTIFICATION

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addParentStack(StepsActivity::class.java)
            taskBuilder.addNextIntent(intent)

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
            showSimpleNotification(id, justText, taskBuilder, title)
        } else if (action == NotificationHelper.ISSUED_LICENSE) {
            val title = context.getString(R.string.get_license_message)
            val justText: String = textResolver.fromHtml(rawMessageHtml).toString()

            val intent = getLicenseIntent(notification = stepikNotification)

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addNextIntent(intent)

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, id.toString(), stepikNotification.type?.name)
            showSimpleNotification(id, justText, taskBuilder, title)
        } else {


            val courseId: Long = HtmlHelper.parseCourseIdFromNotification(stepikNotification) ?: 0L
            if (courseId == 0L) {
                analytic.reportEvent(Analytic.Notification.CANT_PARSE_COURSE_ID, stepikNotification.id.toString())
                return
            }
            stepikNotification.course_id = courseId
            val notificationOfCourseList: MutableList<Notification?> = databaseFacade.getAllNotificationsOfCourse(courseId)
            val relatedCourse = getCourse(courseId)
            val isNeedAdd = notificationOfCourseList.none { it?.id == stepikNotification.id }

            if (isNeedAdd) {
                notificationOfCourseList.add(stepikNotification)
                databaseFacade.addNotification(stepikNotification)
            }

            val largeIcon = getPictureByCourse(relatedCourse)
            val colorArgb = ColorUtil.getColorArgb(R.color.stepic_brand_primary)

            val intent = Intent(context, SectionActivity::class.java)
            val bundle = Bundle()
            val modulePosition = HtmlHelper.parseModulePositionFromNotification(stepikNotification.htmlText)
            if (courseId >= 0 && modulePosition != null && modulePosition >= 0) {
                bundle.putLong(AppConstants.KEY_COURSE_LONG_ID, courseId)
                bundle.putInt(AppConstants.KEY_MODULE_POSITION, modulePosition)
            } else {
                bundle.putSerializable(AppConstants.KEY_COURSE_BUNDLE, relatedCourse)
            }
            intent.putExtras(bundle)
            intent.action = AppConstants.OPEN_NOTIFICATION_FOR_CHECK_COURSE
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

            val taskBuilder: TaskStackBuilder = TaskStackBuilder.create(context)
            taskBuilder.addParentStack(SectionActivity::class.java)
            taskBuilder.addNextIntent(intent)

            val pendingIntent = taskBuilder.getPendingIntent(courseId.toInt(), PendingIntent.FLAG_ONE_SHOT)

            val title = context.getString(R.string.app_name)
            val justText: String = textResolver.fromHtml(rawMessageHtml).toString()

            val notification = NotificationCompat.Builder(context)
                    .setLargeIcon(largeIcon)
                    .setSmallIcon(R.drawable.ic_notification_icon_1) // 1 is better
                    .setContentTitle(title)
                    .setContentText(justText)
                    .setColor(colorArgb)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDeleteIntent(getDeleteIntent(courseId))
            addVibrationIfNeed(notification)
            addSoundIfNeed(notification)

            val numberOfNotification = notificationOfCourseList.size
            val summaryText = context.resources.getQuantityString(R.plurals.notification_plural, numberOfNotification, numberOfNotification)
            if (notificationOfCourseList.size == 1) {
                notification.setStyle(NotificationCompat.BigTextStyle()
                        .bigText(justText))
                        .setContentText(justText)
                        .setNumber(1)
            } else {
                val inboxStyle = NotificationCompat.InboxStyle()
                for (notificationItem in notificationOfCourseList.reversed()) {
                    val line = textResolver.fromHtml(notificationItem?.htmlText ?: "").toString()
                    inboxStyle.addLine(line)
                }
                inboxStyle.setSummaryText(summaryText)
                notification.setStyle(inboxStyle)
                        .setNumber(numberOfNotification)
            }

            analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_SHOWN, stepikNotification.id?.toString() ?: "", stepikNotification.type?.name)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(courseId.toInt(), notification.build())
        }
    }

    private fun showSimpleNotification(id: Long, justText: String, taskBuilder: TaskStackBuilder, title: String?, deleteIntent: PendingIntent = getDeleteIntent()) {
        val pendingIntent = taskBuilder.getPendingIntent(id.toInt(), PendingIntent.FLAG_ONE_SHOT) //fixme if it will overlay courses id -> bug

        val colorArgb = ColorUtil.getColorArgb(R.color.stepic_brand_primary)
        val notification = NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_notification_icon_1)
                .setContentTitle(title)
                .setContentText(justText)
                .setColor(colorArgb)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDeleteIntent(deleteIntent)
        addVibrationIfNeed(notification)
        addSoundIfNeed(notification)

        notification.setStyle(NotificationCompat.BigTextStyle()
                .bigText(justText))
                .setContentText(justText)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id.toInt(), notification.build())
    }

    private fun getDeleteIntent(courseId: Long = -1): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = AppConstants.NOTIFICATION_CANCELED
        val bundle = Bundle()
        if (courseId > 0) {
            bundle.putSerializable(AppConstants.COURSE_ID_KEY, courseId)
        }
        intent.putExtras(bundle)
        //add course id for bundle
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT)
    }

    private fun getCourse(courseId: Long?): Course? {
        if (courseId == null) return null
        var course: Course? = databaseFacade.getCourseById(courseId, Table.enrolled)
        if (course == null) {
            course = api.getCourse(courseId).execute()?.body()?.courses?.get(0)
        }
        return course
    }

    private fun getPictureByCourse(course: Course?): Bitmap {
        val cover = course?.cover
        @DrawableRes val notificationPlaceholder = R.drawable.general_placeholder
        if (cover == null) {
            return getBitmap(R.drawable.general_placeholder)
        } else {
            return Glide.with(context)
                    .load(configs.baseUrl + cover)
                    .asBitmap()
                    .placeholder(notificationPlaceholder)
                    .into(200, 200)//pixels
                    .get()
        }
    }

    private fun getBitmap(@DrawableRes drawable: Int): Bitmap {
        return BitmapFactory.decodeResource(context.resources, drawable)
    }

    private fun addVibrationIfNeed(builder: NotificationCompat.Builder) {
        if (userPreferences.isVibrateNotificationEnabled) {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }
    }

    private fun addSoundIfNeed(builder: NotificationCompat.Builder) {
        if (userPreferences.isSoundNotificationEnabled) {
            val stepicSound = Uri.parse("android.resource://"
                    + context.packageName + "/" + R.raw.default_sound)
            builder.setSound(stepicSound)
        }
    }

    override fun discardAllNotifications(courseId: Long) {
        databaseFacade.removeAllNotificationsByCourseId(courseId)
    }

    override fun tryOpenNotificationInstantly(notification: Notification) {
        val isShown = when (notification.type) {
            NotificationType.learn -> openLearnNotification(notification)
            NotificationType.comments -> openCommentNotification(notification)
            NotificationType.review -> openReviewNotification(notification)
            NotificationType.teach -> openTeach(notification)
            NotificationType.other -> openDefault(notification)
            null -> false
        }

        if (!isShown) {
            analytic.reportEvent(Analytic.Notification.NOTIFICATION_NOT_OPENABLE, notification.action ?: "")
        }

    }

    private fun openTeach(notification: Notification): Boolean {
        val intent: Intent? = getTeachIntent(notification) ?: return false
        analytic.reportEvent(Analytic.Notification.OPEN_TEACH_CENTER)
        context.startActivity(intent)
        return true
    }

    private fun openDefault(notification: Notification): Boolean {
        if (notification.action != null && notification.action == NotificationHelper.ADDED_TO_GROUP) {
            val intent = getDefaultIntent(notification) ?: return false
            analytic.reportEvent(Analytic.Notification.OPEN_COMMENT_NOTIFICATION_LINK)
            context.startActivity(intent)
            return true
        } else {
            return false
        }
    }

    private fun getDefaultIntent(notification: Notification): Intent? {
        val data = HtmlHelper.parseNLinkInText(notification.htmlText ?: "", configs.baseUrl, 1) ?: return null
        val intent = Intent(context, SectionActivity::class.java)
        intent.data = Uri.parse(data)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private fun openReviewNotification(notification: Notification): Boolean {
        val intent = getReviewIntent(notification) ?: return false
        context.startActivity(intent)
        analytic.reportEvent(Analytic.Notification.OPEN_LESSON_NOTIFICATION_LINK)
        return true
    }

    private fun getReviewIntent(notification: Notification): Intent? {
        val data = HtmlHelper.parseNLinkInText(notification.htmlText ?: "", configs.baseUrl, 0) ?: return null
        val intent = Intent(context, StepsActivity::class.java)
        intent.data = Uri.parse(data)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private fun openCommentNotification(notification: Notification): Boolean {
        val intent: Intent = getCommentIntent(notification) ?: return false
        analytic.reportEvent(Analytic.Notification.OPEN_COMMENT_NOTIFICATION_LINK)
        context.startActivity(intent)
        return true
    }

    private fun getCommentIntent(notification: Notification): Intent? {
        val link: String
        val action = notification.action
        val htmlText = notification.htmlText ?: ""
        if (action == NotificationHelper.REPLIED) {
            link = HtmlHelper.parseNLinkInText(htmlText, configs.baseUrl, 1) ?: return null
        } else {
            link = HtmlHelper.parseNLinkInText(htmlText, configs.baseUrl, 3) ?: return null
        }
        val intent = Intent(context, StepsActivity::class.java)
        intent.data = Uri.parse(link)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private fun openLearnNotification(notification: Notification): Boolean {
        if (notification.action != null && notification.action == NotificationHelper.ISSUED_CERTIFICATE) {
            analytic.reportEvent(Analytic.Certificate.OPEN_CERTIFICATE_FROM_NOTIFICATION_CENTER)
            screenManager.showCertificates()
            return true
        } else if (notification.action == NotificationHelper.ISSUED_LICENSE) {
            val intent: Intent = getLicenseIntent(notification) ?: return false
            context.startActivity(intent)
            return true
        } else {
            val courseId = HtmlHelper.parseCourseIdFromNotification(notification)
            val modulePosition = HtmlHelper.parseModulePositionFromNotification(notification.htmlText)

            if (courseId != null && courseId >= 0 && modulePosition != null && modulePosition >= 0) {
                val intent: Intent = Intent(context, SectionActivity::class.java)
                val bundle = Bundle()
                bundle.putLong(AppConstants.KEY_COURSE_LONG_ID, courseId)
                bundle.putInt(AppConstants.KEY_MODULE_POSITION, modulePosition)
                intent.putExtras(bundle)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } else {
                return false
            }
        }
    }

    private fun getLicenseIntent(notification: Notification): Intent? {
        val link = HtmlHelper.parseNLinkInText(notification.htmlText ?: "", configs.baseUrl, 0) ?: return null
        val intent = screenManager.getOpenInWebIntent(link)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private fun getTeachIntent(notification: Notification): Intent? {
        val link = HtmlHelper.parseNLinkInText(notification.htmlText ?: "", configs.baseUrl, 0) ?: return null
        try {
            val url = Uri.parse(link)
            val identifier = url.pathSegments[0]
            val intent: Intent
            if (identifier == "course") {
                intent = Intent(context, SectionActivity::class.java)
            } else if (identifier == "lesson") {
                intent = Intent(context, StepsActivity::class.java)
            } else {
                return null
            }
            intent.data = url
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        } catch (exception: Exception) {
            return null
        }

    }
}