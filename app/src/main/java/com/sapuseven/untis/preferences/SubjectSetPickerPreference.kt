package com.sapuseven.untis.preferences

import android.app.ProgressDialog
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.preference.MultiSelectListPreference
import com.sapuseven.untis.data.databases.UserDatabase
import com.sapuseven.untis.data.timetable.TimegridItem
import com.sapuseven.untis.helpers.config.PreferenceManager
import com.sapuseven.untis.helpers.config.PreferenceUtils
import com.sapuseven.untis.helpers.timetable.TimetableDatabaseInterface
import com.sapuseven.untis.helpers.timetable.TimetableLoader
import com.sapuseven.untis.interfaces.TimetableDisplay
import com.sapuseven.untis.models.untis.UntisDate
import kotlinx.coroutines.*
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.joda.time.LocalTime
import java.lang.ref.WeakReference


class SubjectSetPickerPreference(context: Context, attrs: AttributeSet) : MultiSelectListPreference(context, attrs) {
	private lateinit var profileUser: UserDatabase.User
	private lateinit var timetableDatabaseInterface: TimetableDatabaseInterface
	private lateinit var preferenceManager: PreferenceManager
	private var profileId: Long = -1

	override fun onAttached() {
		entries = emptyArray()
		entryValues = emptyArray()

		preferenceManager = PreferenceManager(context)
		profileId = preferenceManager.currentProfileId()
		loadDatabase()

		super.onAttached()
	}

	override fun onClick() {
		val dialog = ProgressDialog.show(context, "", "Loading. Please wait...", true)

		GlobalScope.launch(Dispatchers.Main) {
			loadTimetable(
					{ list ->
						val entryList =
								list
										.map { SubjectAttributes(it.startTime.toLocalTime(), it.endTime.toLocalTime(), it.startDateTime.dayOfWeek.toString(), it.title.toString()) }
										.distinct()
										.map { Pair(it.day + '\n' + it.startTime.toString() + '\n' + it.endTime.toString() + '\n' + it.name, it.day + '|' + it.startTime.toString() + '|' + it.endTime.toString() + '|' + it.name) }
						entries = entryList.map { it.first }.toTypedArray()
						entryValues = entryList.map { it.second }.toTypedArray()
						dialog.dismiss()
						super.onClick()
					},
					{ _, _, _ ->  }
			)
		}
	}

	private fun loadDatabase() {
		val userDatabase = UserDatabase.createInstance(context)
		userDatabase.getUser(profileId)?.let {
			profileUser = it
			timetableDatabaseInterface = TimetableDatabaseInterface(userDatabase, it.id ?: -1)
		}
	}

	private fun loadTimetable(onLoadingSuccess : (List<TimegridItem>) -> Unit, onLoadingError : (Int, Int?, String?) -> Unit) {
		Log.d("NotificationSetup", "loadTimetable for user ${profileUser.id}")

		val startDate = UntisDate.fromLocalDate(LocalDate.now())
		val endDate = UntisDate.fromLocalDate(LocalDate.now().plusDays(13))

		val targetTimetable = createPersonalTimetable()
		targetTimetable?.let {
			val target = TimetableLoader.TimetableLoaderTarget(startDate, endDate, it.second, it.first)
			val proxyHost = preferenceManager.defaultPrefs.getString("preference_connectivity_proxy_host", null)
			lateinit var timetableLoader: TimetableLoader
			timetableLoader = TimetableLoader(WeakReference(context), object : TimetableDisplay {
				override fun addTimetableItems(items: List<TimegridItem>, startDate: UntisDate, endDate: UntisDate, timestamp: Long) {
					onLoadingSuccess(items)
				}

				override fun onTimetableLoadingError(requestId: Int, code: Int?, message: String?) {
					when (code) {
						TimetableLoader.CODE_CACHE_MISSING -> timetableLoader.repeat(requestId, TimetableLoader.FLAG_LOAD_SERVER, proxyHost)
						else -> {
							onLoadingError(requestId, code, message)
						}
					}
				}
			}, profileUser, timetableDatabaseInterface)
			timetableLoader.load(target, TimetableLoader.FLAG_LOAD_SERVER, proxyHost)
		}
	}

	private fun createPersonalTimetable(): Pair<String, Int>? {
		@Suppress("RemoveRedundantQualifierName")
		val customType = TimetableDatabaseInterface.Type.valueOf(PreferenceUtils.getPrefString(
				preferenceManager,
				"preference_timetable_personal_timetable${ElementPickerPreference.KEY_SUFFIX_TYPE}",
				TimetableDatabaseInterface.Type.SUBJECT.toString()
		) ?: TimetableDatabaseInterface.Type.SUBJECT.toString())

		if (customType === TimetableDatabaseInterface.Type.SUBJECT) {
			profileUser.userData.elemType?.let { type ->
				return type to profileUser.userData.elemId
			} ?: run {
				return null
			}
		} else {
			val customId = preferenceManager.defaultPrefs.getInt("preference_timetable_personal_timetable${ElementPickerPreference.KEY_SUFFIX_ID}", -1)
			return customType.toString() to customId
		}
	}
}

data class SubjectAttributes(
		val startTime: LocalTime,
		val endTime: LocalTime,
		val day: String,
		val name: String
		)
