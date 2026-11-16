package dev.kodakiko.app

import androidx.fragment.app.FragmentActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.lang.ref.WeakReference

@Inject
@SingleIn(AppScope::class)
class ActivityHolder {
    @Volatile
    private var reference: WeakReference<FragmentActivity>? = null

    val current: FragmentActivity? get() = reference?.get()

    fun attach(activity: FragmentActivity) {
        reference = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (reference?.get() === activity) reference = null
    }
}
